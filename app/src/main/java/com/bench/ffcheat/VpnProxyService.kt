package com.bench.ffcheat

// VpnProxyService.kt — v5
//
// ── HOW ANDROID VPN FORWARDING ACTUALLY WORKS ────────────────────────────────
//
//  The Android VpnService creates a TUN interface. When an allowed app (Free Fire)
//  sends a packet:
//    1. The kernel routes it INTO the TUN device instead of the real interface.
//    2. We read it from the TUN fd (FileInputStream).
//    3. We inspect / modify it via PacketEngine.
//    4. We write the (possibly modified) packet to a REAL UDP socket that we
//       have called protect() on.  protect() exempts the socket from the VPN
//       so its traffic goes straight out the real interface — no loop.
//    5. The game server replies to our protected socket.
//    6. We read the reply, run it through PacketEngine (ESP parse), then
//       write it BACK into the TUN fd so the kernel delivers it to Free Fire
//       as if it came from the server directly.
//
//  This is a true man-in-the-middle UDP proxy — the only approach that
//  actually forwards packets.  Simply writing back to TUN without step 4
//  loops the packet back to the same VPN — it never reaches the server.
//
// ── SHIZUKU USAGE ────────────────────────────────────────────────────────────
//  ShizukuBridge.applyProxyToPackage() is called once after the VPN is
//  established to confirm that FF's traffic is routed through the tunnel.
//  It runs `cmd connectivity start-vpn-profile` for the package if available,
//  which forces FF traffic through our VPN without needing root.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class VpnProxyService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    // Map of (src_ip:src_port → dst_ip:dst_port) → protected outbound socket
    // One socket per FF UDP session (FF typically uses 1–3 UDP flows).
    private val sockets = ConcurrentHashMap<Long, DatagramSocket>()

    companion object {
        const val ACTION_STOP = "com.bench.ffcheat.STOP_VPN"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, buildNotif())
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        startTun()
        return START_STICKY
    }

    private fun startTun() {
        tun = Builder()
            .addAddress("10.233.0.1", 24)
            .addRoute("0.0.0.0", 0)          // capture all IPv4
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .setSession("FFBench")
            .addAllowedApplication("com.dts.freefireth")
            .establish() ?: return

        running = true

        // ── outbound thread: TUN → real network ───────────────────────────────
        Thread({
            val inp = FileInputStream(tun!!.fileDescriptor)
            val buf = ByteArray(32_768)
            while (running) {
                val n = try { inp.read(buf) } catch (_: Exception) { break }
                if (n <= 0) continue
                val pkt = buf.copyOf(n)
                handleOutbound(pkt)
            }
        }, "ff-tun-out").apply { isDaemon = true; start() }
    }

    // ── outbound: read from TUN, forward to real server via protected socket ──
    private fun handleOutbound(raw: ByteArray) {
        if (raw.size < 28) return
        val ihl   = (raw[0].toInt() and 0x0F) shl 2
        val proto = raw[9].toInt() and 0xFF
        if (proto != 17) return   // UDP only

        // Parse destination from IPv4 + UDP headers
        val dstIp   = InetAddress.getByAddress(raw.copyOfRange(16, 20))
        val uOff    = ihl
        val dstPort = (raw[uOff+2].u shl 8) or raw[uOff+3].u
        val srcPort = (raw[uOff+0].u shl 8) or raw[uOff+1].u
        val payOff  = uOff + 8
        val payLen  = raw.size - payOff
        if (payLen <= 0) return

        // Rewrite packet (head-lock) before forwarding
        val modified = PacketEngine.process(raw)
        val fwPayOff = ihl + 8
        val fwPayLen = modified.size - fwPayOff

        // Get or create a protected socket for this src port
        val sockKey = srcPort.toLong()
        val sock = sockets.getOrPut(sockKey) {
            DatagramSocket().also { s ->
                protect(s)   // ← exempts socket from VPN; traffic goes to real interface
                // Start inbound thread for this socket
                startInboundThread(s, ihl, uOff, srcPort)
            }
        }

        try {
            val udpPayload = modified.copyOfRange(fwPayOff, fwPayOff + fwPayLen)
            sock.send(DatagramPacket(udpPayload, udpPayload.size, dstIp, dstPort))
        } catch (e: Exception) {
            sockets.remove(sockKey)
            e.printStackTrace()
        }
    }

    // ── inbound: real server reply → TUN (delivered to Free Fire) ────────────
    private fun startInboundThread(sock: DatagramSocket, ihl: Int, uOff: Int, srcPort: Int) {
        Thread({
            val tunOut = FileOutputStream(tun!!.fileDescriptor)
            val buf    = ByteArray(32_768)
            val dp     = DatagramPacket(buf, buf.size)
            while (running && !sock.isClosed) {
                try {
                    sock.receive(dp)
                    val payload   = buf.copyOf(dp.length)
                    // Wrap in fake IPv4+UDP header addressed to Free Fire's local socket
                    val ipPkt     = wrapUdp(
                        srcIp   = dp.address,
                        srcPort = dp.port,
                        dstIp   = InetAddress.getByName("10.233.0.1"),
                        dstPort = srcPort,
                        payload = payload
                    )
                    // Parse ESP from inbound packet BEFORE writing to TUN
                    PacketEngine.process(ipPkt)   // state frames parsed here
                    tunOut.write(ipPkt)           // deliver to Free Fire
                } catch (_: Exception) { break }
            }
        }, "ff-tun-in-$srcPort").apply { isDaemon = true; start() }
    }

    // ── build a minimal IPv4+UDP packet around a UDP payload ─────────────────
    private fun wrapUdp(
        srcIp: InetAddress, srcPort: Int,
        dstIp: InetAddress, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen  = 8 + payload.size
        val ipLen   = 20 + udpLen
        val bb      = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)
        // IPv4 header
        bb.put(0x45.toByte())              // version=4, IHL=5
        bb.put(0)                           // DSCP/ECN
        bb.putShort(ipLen.toShort())
        bb.putShort(0)                      // ID
        bb.putShort(0x4000.toShort())       // DF flag
        bb.put(64)                          // TTL
        bb.put(17)                          // protocol = UDP
        bb.putShort(0)                      // checksum (0 = ignored by tun)
        bb.put(srcIp.address)
        bb.put(dstIp.address)
        // UDP header
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort(udpLen.toShort())
        bb.putShort(0)                      // UDP checksum (optional in IPv4)
        bb.put(payload)
        return bb.array()
    }

    private fun stop() {
        running = false
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { tun?.close() }
        PacketEngine.resetSession()
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }

    private fun buildNotif(): Notification {
        val ch = NotificationChannel("ffvpn", "FFBench VPN", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        return Notification.Builder(this, "ffvpn")
            .setContentTitle("FFBench")
            .setContentText("VPN proxy active")
            .setSmallIcon(android.R.drawable.ic_lock_lock).build()
    }

    private val Byte.u get() = this.toInt() and 0xFF
}
