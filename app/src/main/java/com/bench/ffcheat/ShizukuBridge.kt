package com.bench.ffcheat

// ShizukuBridge.kt — v5
// Runs privileged shell commands via Shizuku without root.
// Shizuku must be running (started via ADB wireless pairing or USB ADB).
//
// What we use it for:
//   • Grant FF INTERNET permission (usually already granted; harmless)
//   • Clear proxy settings when done
//   • Confirm FF package is installed and runnable
//
// isAvailable() must return true before any other call.
// Never call runShell() without checking isAvailable() first —
// if Shizuku is not running, the call throws and crashes the service.

import rikka.shizuku.Shizuku

object ShizukuBridge {

    // Returns true if Shizuku is running AND we have permission
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    // Returns true if Shizuku is running but permission not yet granted
    fun needsPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(0) } catch (_: Exception) {}
    }

    // Called after VPN is established — confirm FF traffic will route through tunnel
    fun applyProxyToPackage(pkg: String): Boolean {
        if (!isAvailable()) return false
        return try {
            // Grant INTERNET if somehow missing
            runShell("pm grant $pkg android.permission.INTERNET")
            // Verify package exists
            val result = runShellOutput("pm list packages $pkg")
            result.contains(pkg)
        } catch (_: Exception) { false }
    }

    fun clearProxy() {
        if (!isAvailable()) return
        try { runShell("settings delete global http_proxy") } catch (_: Exception) {}
    }

    // Run a shell command via Shizuku (fire-and-forget)
    private fun runShell(cmd: String) {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        p.waitFor()
        p.destroy()
    }

    // Run a shell command and return stdout as a String
    private fun runShellOutput(cmd: String): String {
        val p   = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); p.destroy()
        return out
    }
}
