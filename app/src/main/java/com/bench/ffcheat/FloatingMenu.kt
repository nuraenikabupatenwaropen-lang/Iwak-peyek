package com.bench.ffcheat

// FloatingMenu.kt — v4
// Always-on-top floating panel (TYPE_APPLICATION_OVERLAY).
// Collapsed: shows a small ⚙ square button.
// Expanded: shows Inject toggle, Head Lock switch, ESP Box switch,
//           live diagnostics (key status, enemy count), and Stop button.

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*

class FloatingMenu(ctx: Context, private val onStop: () -> Unit) : LinearLayout(ctx) {

    private var expanded = false

    private val inner = LinearLayout(ctx).apply {
        orientation = VERTICAL
        visibility  = GONE
    }

    private val tvDiag = TextView(ctx).apply {
        setTextColor(Color.parseColor("#AAFFAA"))
        textSize = 11f
        setPadding(6, 4, 6, 4)
        typeface = Typeface.MONOSPACE
    }

    private val h = Handler(Looper.getMainLooper())
    private val diagTick = object : Runnable {
        override fun run() {
            tvDiag.text =
                "key:${if (PacketEngine.hasKey()) "✓" else "✗"}  " +
                "team:${PacketEngine.teamId()}  " +
                "ents:${PacketEngine.entityCount()}"
            h.postDelayed(this, 400)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(220, 10, 10, 10))
        setPadding(10, 10, 10, 10)

        // ── toggle ⚙ ─────────────────────────────────────────────────────────
        addView(Button(ctx).apply {
            text = "⚙"; textSize = 17f
            setBackgroundColor(Color.argb(180, 50, 50, 50))
            setTextColor(Color.WHITE)
            setOnClickListener {
                expanded = !expanded
                inner.visibility = if (expanded) VISIBLE else GONE
            }
        })

        // ── inner panel ───────────────────────────────────────────────────────
        inner.addView(tvDiag)

        inner.addView(divider(ctx))

        // Inject button — activates both features at once
        inner.addView(Button(ctx).apply {
            text = "INJECT"
            setBackgroundColor(Color.parseColor("#1A6B1A"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                PacketEngine.headLockEnabled = true
                PacketEngine.espEnabled      = true
                (parent as? ViewGroup)?.let { vg ->
                    // update switch states visually
                    for (i in 0 until vg.childCount) {
                        val v = vg.getChildAt(i)
                        if (v is Switch) v.isChecked = true
                    }
                }
            }
        })

        inner.addView(divider(ctx))

        // Head Lock switch
        inner.addView(Switch(ctx).apply {
            text = "Head Lock"
            isChecked = PacketEngine.headLockEnabled
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, v -> PacketEngine.headLockEnabled = v }
        })

        // ESP Box switch
        inner.addView(Switch(ctx).apply {
            text = "ESP Box"
            isChecked = PacketEngine.espEnabled
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, v -> PacketEngine.espEnabled = v }
        })

        inner.addView(divider(ctx))

        // Stop button
        inner.addView(Button(ctx).apply {
            text = "STOP"
            setBackgroundColor(Color.parseColor("#7A1515"))
            setTextColor(Color.WHITE)
            setOnClickListener { onStop() }
        })

        addView(inner)
        h.post(diagTick)
    }

    override fun onDetachedFromWindow() {
        h.removeCallbacks(diagTick)
        super.onDetachedFromWindow()
    }

    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.argb(80, 255, 255, 255))
        layoutParams = LayoutParams(MATCH_PARENT, 1).apply {
            setMargins(0, 6, 0, 6)
        }
    }
}
