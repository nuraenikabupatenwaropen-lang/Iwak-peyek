// PacketEngine.kt — v5
// Language: Kotlin | Module: com.bench.ffcheat | Runtime: Android API 28+
//
// ── WIRE FORMAT ───────────────────────────────────────────────────────────────
//  IPv4 → UDP → Garena frame:
//    [0..1]  0xFFFF  magic
//    [2..3]  u16 BE  sequence
//    [4]     u8      type: 0x01=handshake  0x02=input(C→S)  0x03=state(S→C)
//    [5]     u8      sub:  input 0x01=aim+move
//    [6..7]  u16 BE  encrypted payload length
//    [8+]            XOR-encrypted payload
//
// ── HANDSHAKE payload (plain) ─────────────────────────────────────────────────
//    [0..3]  u32 LE  token
//    [4..11] u8[8]   session XOR key  ← capture
//    [12+]           version/platform
//
// ── STATE payload (decrypted) ─────────────────────────────────────────────────
//    [0..3]  u32 LE  tick
//    [4]     u8      N entities
//    for i in 0..N-1  (28 bytes each):
//      +0   f32 LE  foot_x
//      +4   f32 LE  foot_y
//      +8   f32 LE  foot_z
//      +12  f32 LE  head_x  ← head-bone world position
//      +16  f32 LE  head_y
//      +20  f32 LE  head_z
//      +24  u16 LE  hp
//      +26  u8      team_id
//      +27  u8      flags: bit0=alive  bit1=visible  bit2=enemy
//    Entity index 0 is ALWAYS the local player.
//
// ── INPUT/AIM payload (decrypted) ────────────────────────────────────────────
//    [0..3]  u32 LE  tick
//    [4..7]  f32 LE  yaw    (−π … +π)   ← HEAD LOCK writes here
//    [8..11] f32 LE  pitch  (−π/2…+π/2) ← HEAD LOCK writes here
//    [12+]           movement+fire (pass through)
//
// ── HEAD LOCK ─────────────────────────────────────────────────────────────────
//  Direction = (target.head) − (local.head)  in world space.
//  yaw   = atan2(dx, dz)
//  pitch = atan2(−dy, sqrt(dx²+dz²))
//  Written into decrypted aim payload, re-encrypted, spliced back into IP pkt.
//  Sequence number and all other fields untouched — server accepts normally.

package com.bench.ffcheat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

object PacketEngine {

    // ── toggles ───────────────────────────────────────────────────────────────
    @Volatile var headLockEnabled = false
    @Volatile var espEnabled      = false

    // ── session state ─────────────────────────────────────────────────────────
    @Volatile private var key: ByteArray? = null
    @Volatile private var localTeam = -1

    // Local player head position — updated every state frame from entity[0]
    @Volatile private var localHX = 0f
    @Volatile private var localHY = 0f
    @Volatile private var localHZ = 0f

    // ── entity cache ──────────────────────────────────────────────────────────
    data class Entity(
        val fx: Float, val fy: Float, val fz: Float,  // foot world pos
        val hx: Float, val hy: Float, val hz: Float,  // head bone world pos
        val hp: Int, val team: Int,
        val sx: Float, val sy: Float                  // screen pos (crude W2S)
    )
    val entities = CopyOnWriteArrayList<Entity>()

    // ── constants ─────────────────────────────────────────────────────────────
    private const val MAGIC_0  = 0xFF.toByte()
    private const val MAGIC_1  = 0xFF.toByte()
    private const val T_SHAKE  = 0x01
    private const val T_INPUT  = 0x02
    private const val T_STATE  = 0x03
    private const val SUB_AIM  = 0x01
    private const val GDR_HDR  = 8
    private const val ENT_SZ   = 28
    private const val F_ALIVE  = 0x01
    private const val F_ENEMY  = 0x04
    private const val SW       = 1080f
    private const val SH       = 1920f

    // ── entry ─────────────────────────────────────────────────────────────────
    // Receives a complete IPv4 packet (fresh ByteArray copy from VpnProxyService).
    // Returns original ref (unchanged) or new ByteArray (modified).
    fun process(raw: ByteArray): ByteArray {
        if (raw.size < 28) return raw
        val ihl   = (raw[0].toInt() and 0x0F) shl 2
        if (raw[9].toInt() and 0xFF != 17) return raw   // UDP only
        val uOff  = ihl
        if (raw.size < uOff + 8) return raw
        val uTotalLen = (raw[uOff+4].u shl 8) or raw[uOff+5].u
        val pOff  = uOff + 8
        val pLen  = uTotalLen - 8
        if (pLen < GDR_HDR || pOff + pLen > raw.size) return raw
        if (raw[pOff] != MAGIC_0 || raw[pOff+1] != MAGIC_1) return raw

        val fType  = raw[pOff+4].u
        val fSub   = raw[pOff+5].u
        val encLen = (raw[pOff+6].u shl 8) or raw[pOff+7].u
        val eOff   = pOff + GDR_HDR
        if (eOff + encLen > raw.size) return raw

        return when (fType) {

            T_SHAKE -> {
                // Unencrypted — capture session key from bytes [eOff+4 .. eOff+12)
                if (key == null && encLen >= 12)
                    key = raw.copyOfRange(eOff + 4, eOff + 12)
                raw
            }

            T_STATE -> {
                // Server→client: decrypt, parse entities (ESP + local pos), forward unchanged
                val k = key ?: return raw
                val plain = xorR(raw, eOff, encLen, k)
                parseState(plain)
                raw
            }

            T_INPUT -> {
                // Client→server: rewrite aim if head-lock enabled
                if (!headLockEnabled || fSub != SUB_AIM) return raw
                val k     = key ?: return raw
                val plain = xorR(raw, eOff, encLen, k)
                val rw    = rewriteAim(plain) ?: return raw
                val enc   = xorB(rw, k)
                val out   = raw.copyOf()
                enc.copyInto(out, eOff)
                out[uOff+6] = 0; out[uOff+7] = 0   // zero UDP checksum
                out
            }

            else -> raw
        }
    }

    // ── XOR decrypt region raw[off .. off+len) ────────────────────────────────
    private fun xorR(src: ByteArray, off: Int, len: Int, k: ByteArray): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len)
            out[i] = (src[off+i].u xor k[i % k.size].u).toByte()
        return out
    }

    // ── XOR re-encrypt full ByteArray ────────────────────────────────────────
    private fun xorB(src: ByteArray, k: ByteArray): ByteArray {
        val out = src.copyOf()
        for (i in out.indices) out[i] = (out[i].u xor k[i % k.size].u).toByte()
        return out
    }

    // ── parse state frame ─────────────────────────────────────────────────────
    private fun parseState(plain: ByteArray) {
        if (plain.size < 5) return
        val n = plain[4].u
        if (plain.size < 5 + n * ENT_SZ) return

        // Entity 0 = local player: capture team + head position
        if (n > 0) {
            val b0 = ByteBuffer.wrap(plain, 5, ENT_SZ).order(ByteOrder.LITTLE_ENDIAN)
            b0.float; b0.float; b0.float          // skip foot pos
            val h0x = b0.float; val h0y = b0.float; val h0z = b0.float
            localHX = h0x; localHY = h0y; localHZ = h0z
            if (localTeam == -1) localTeam = plain[5 + 26].u
        }

        if (!espEnabled && !headLockEnabled) return   // skip full parse if neither feature on

        val fresh = ArrayList<Entity>(n)
        for (i in 0 until n) {
            if (i == 0) continue   // skip local player
            val base = 5 + i * ENT_SZ
            val bb   = ByteBuffer.wrap(plain, base, ENT_SZ).order(ByteOrder.LITTLE_ENDIAN)
            val fx = bb.float; val fy = bb.float; val fz = bb.float
            val hx = bb.float; val hy = bb.float; val hz = bb.float
            val hp    = bb.short.toInt() and 0xFFFF
            val team  = bb.get().u
            val flags = bb.get().u
            if ((flags and F_ALIVE) == 0 || hp <= 0) continue
            val enemy = (flags and F_ENEMY) != 0 || (localTeam != -1 && team != localTeam)
            if (!enemy) continue

            // Crude orthographic W2S from head-bone
            // For accurate W2S: dump VP matrix from libil2cpp.so and apply here
            val sx = (hx / 500f + 0.5f) * SW
            val sy = (1f - hz / 500f) * SH
            fresh.add(Entity(fx, fy, fz, hx, hy, hz, hp, team, sx, sy))
        }
        entities.clear()
        entities.addAll(fresh)
    }

    // ── rewrite aim toward nearest enemy head bone ────────────────────────────
    // Direction vector = target.head − local.head  (world space delta)
    private fun rewriteAim(plain: ByteArray): ByteArray? {
        if (plain.size < 12) return null
        val snap = entities.toList()
        if (snap.isEmpty()) return null

        // Pick enemy closest to screen centre (= crosshair)
        val t = snap.minByOrNull {
            hypot((it.sx - SW * 0.5f).toDouble(), (it.sy - SH * 0.5f).toDouble())
        } ?: return null

        // Direction from local head → enemy head (world space)
        val dx = (t.hx - localHX).toDouble()
        val dy = (t.hy - localHY).toDouble()
        val dz = (t.hz - localHZ).toDouble()
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) return null

        val yaw   = atan2(dx, dz).toFloat()
        val pitch = atan2(-dy, sqrt(dx*dx + dz*dz)).toFloat()

        val out = plain.copyOf()
        // tick [0..3] unchanged
        ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(yaw)
        ByteBuffer.wrap(out, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(pitch)
        // [12+] movement + fire unchanged
        return out
    }

    // ── diagnostics ───────────────────────────────────────────────────────────
    fun hasKey()       = key != null
    fun teamId()       = localTeam
    fun entityCount()  = entities.size
    fun localHead()    = Triple(localHX, localHY, localHZ)
    fun resetSession() { key = null; localTeam = -1; localHX = 0f; localHY = 0f; localHZ = 0f; entities.clear() }

    private val Byte.u get() = this.toInt() and 0xFF
}
