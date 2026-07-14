package de.lobianco.saftssh.remotedesktop

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Minimal launcher activity so the APK is launchable/Play-Store-acceptable — the plugin works
 *  entirely as a bound service, same pattern as the Linux Plugin's InfoActivity. */
class InfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply { setColor(Color.rgb(18, 20, 28)) }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 39, 52))
                cornerRadius = 32f
            }
        }

        val title = TextView(this).apply {
            text = "LobiShell\nRemote Desktop Plugin"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "VNC / RDP / SPICE / noVNC client engine"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(170, 180, 200))
            setPadding(0, 0, 0, 32)
        }

        val description = TextView(this).apply {
            text = """
                This plugin provides VNC, RDP, and SPICE remote-desktop
                connectivity for the LobiShell app, including Proxmox VE
                consoles (SPICE, with an automatic noVNC fallback when
                a VM's Display isn't set to SPICE).

                It works together with LobiShell over a background
                service — no configuration is required here.

                This plugin only works together with LobiShell.
                It is not a standalone application and cannot
                be used without the main LobiShell app.
            """.trimIndent()
            textSize = 15f
            setTextColor(Color.rgb(220, 225, 235))
            setLineSpacing(8f, 1f)
        }

        // Opening this Activity is also the fix for a real Android platform restriction: a
        // freshly installed/updated app starts in a "stopped" state in which NO other app may
        // bindService() into it (ApplicationInfo.FLAG_STOPPED) — LobiShell's own bind attempt then
        // fails with a raw "Not allowed to bind to service" SecurityException until this Activity
        // is opened at least once. Same button as the Linux Plugin's InfoActivity, for the same
        // reason.
        val openAppButton = Button(this).apply {
            text = "Open LobiShell"
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage("de.lobianco.saftssh")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this@InfoActivity, "LobiShell isn't installed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val badge = TextView(this).apply {
            text = "PLUGIN COMPONENT"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(120, 200, 255))
            setPadding(0, 32, 0, 0)
        }

        card.addView(title)
        card.addView(subtitle)
        card.addView(description)
        card.addView(openAppButton)
        card.addView(badge)

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }
}
