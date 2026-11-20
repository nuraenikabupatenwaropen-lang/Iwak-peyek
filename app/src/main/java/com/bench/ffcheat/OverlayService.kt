package com.bench.ffcheat

// OverlayService.kt — v4
// Hosts two views on TYPE_APPLICATION_OVERLAY:
//   1. EspView  — full-screen transparent canvas, redraws at ~60 fps from entity cache
//   2. FloatingMenu — small always-on-top square, expandable control panel

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.WindowManager.LayoutParams.*

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var esp: EspView
    private lateinit var menu: FloatingMenu

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(3, buildNotif())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        esp = EspView(this)
        wm.addView(esp, lp(MATCH_PARENT, MATCH_PARENT, touch = false))

        menu = FloatingMenu(this) { tearDown() }
        wm.addView(menu, lp(170, WRAP_CONTENT, touch = true).apply {
            gravity = Gravity.TOP or Gravity.START; x = 12; y = 260
        })
        return START_STICKY
    }

    private fun tearDown() {
        PacketEngine.resetSession()
        PacketEngine.headLockEnabled = false
        PacketEngine.espEnabled = false
        runCatching { wm.removeView(esp) }
        runCatching { wm.removeView(menu) }
        stopSelf()
        // Also stop VPN
        startService(Intent(this, VpnProxyService::class.java)
            .setAction(VpnProxyService.ACTION_STOP))
    }

    override fun onDestroy() {
        runCatching { wm.removeView(esp) }
        runCatching { wm.removeView(menu) }
        super.onDestroy()
    }

    private fun lp(w: Int, h: Int, touch: Boolean) = WindowManager.LayoutParams(
        w, h, TYPE_APPLICATION_OVERLAY,
        (if (!touch) FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE else FLAG_NOT_FOCUSABLE)
            or FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun buildNotif(): Notification {
        val ch = NotificationChannel("ffov", "FFBench Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        return Notification.Builder(this, "ffov")
            .setContentTitle("FFBench")
            .setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
    }
}

// ── ESP canvas ────────────────────────────────────────────────────────────────
class EspView(ctx: Context) : View(ctx) {

    private val pBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val pSnap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 220, 0); strokeWidth = 1.5f
    }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; textSize = 24f; typeface = Typeface.MONOSPACE
    }
    private val pShadow = Paint(pTxt).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3.5f
    }

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); h.postDelayed(this, 16) }
    }
    init { h.post(tick) }

    override fun onDraw(c: Canvas) {
        if (!PacketEngine.espEnabled) return
        val snap = PacketEngine.entities.toList()
        if (snap.isEmpty()) return
        val cx = width * 0.5f; val cy = height.toFloat()
        for (e in snap) {
            val sx = e.sx * width  / 1080f
            val sy = e.sy * height / 1920f
            val bh = (height * 0.13f).coerceIn(55f, 280f)
            val bw = bh * 0.40f
            val l = sx - bw/2; val r = sx + bw/2
            val t = sy - bh;   val b = sy
            c.drawRect(l, t, r, b, pBox)
            c.drawLine(cx, cy, sx, b, pSnap)
            val lbl = "HP ${e.hp}"
            c.drawText(lbl, l, t - 5f, pShadow)
            c.drawText(lbl, l, t - 5f, pTxt)
        }
    }

    override fun onDetachedFromWindow() { h.removeCallbacks(tick); super.onDetachedFromWindow() }
}
