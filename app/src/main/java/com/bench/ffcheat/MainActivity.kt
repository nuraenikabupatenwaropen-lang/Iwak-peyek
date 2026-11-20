package com.bench.ffcheat

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnShizuku: Button
    private lateinit var btnInject: Button
    private lateinit var btnStart: Button

    companion object { const val VPN_REQ = 1001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus   = findViewById(R.id.tvStatus)
        btnShizuku = findViewById(R.id.btnShizuku)
        btnInject  = findViewById(R.id.btnInject)
        btnStart   = findViewById(R.id.btnStart)

        btnShizuku.setOnClickListener { connectShizuku() }
        btnInject.setOnClickListener  { inject() }
        btnStart.setOnClickListener   { startCheat() }

        Shizuku.addRequestPermissionResultListener { _, result ->
            val granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED
            updateStatus(if (granted) "✅ Shizuku ready" else "❌ Shizuku denied")
        }

        checkInitialState()
    }

    private fun checkInitialState() {
        when {
            ShizukuBridge.isAvailable() -> updateStatus("✅ Shizuku ready — tap Inject")
            ShizukuBridge.needsPermission() -> {
                updateStatus("⚠ Shizuku running — tap Connect")
            }
            else -> updateStatus("① Start Shizuku, then tap Connect Shizuku")
        }
    }

    private fun connectShizuku() {
        when {
            !Shizuku.pingBinder() -> {
                updateStatus("❌ Shizuku not running.\nStart it via ADB:\nadb shell sh /sdcard/shizuku/start.sh")
            }
            ShizukuBridge.isAvailable() -> {
                updateStatus("✅ Shizuku already connected")
            }
            else -> {
                updateStatus("⏳ Requesting Shizuku permission…")
                ShizukuBridge.requestPermission()
            }
        }
    }

    private fun inject() {
        if (!ShizukuBridge.isAvailable()) {
            updateStatus("❌ Connect Shizuku first"); return
        }
        val ok = ShizukuBridge.applyProxyToPackage("com.dts.freefireth")
        updateStatus(if (ok) "✅ Injected — tap Start" else "⚠ Injection done (FF may not be installed)")
        toast("Injection complete")
    }

    private fun startCheat() {
        // Step 1: overlay permission
        if (!android.provider.Settings.canDrawOverlays(this)) {
            updateStatus("⏳ Grant overlay permission…")
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
            return
        }
        // Step 2: VPN permission
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            updateStatus("⏳ Grant VPN permission…")
            startActivityForResult(vpnIntent, VPN_REQ)
            return
        }
        launch()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == VPN_REQ) {
            if (res == Activity.RESULT_OK) launch()
            else updateStatus("❌ VPN permission denied")
        }
    }

    private fun launch() {
        updateStatus("🚀 Launching…")
        startForegroundService(Intent(this, VpnProxyService::class.java))
        startForegroundService(Intent(this, OverlayService::class.java))
        // Open Free Fire
        packageManager.getLaunchIntentForPackage("com.dts.freefireth")
            ?.also { startActivity(it) }
            ?: toast("Free Fire not installed")
    }

    private fun updateStatus(msg: String) { tvStatus.text = msg }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
