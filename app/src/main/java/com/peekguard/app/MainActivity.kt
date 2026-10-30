package com.peekguard.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var friendText: TextView
    private lateinit var friendBtn: Button
    private val permRows = ArrayList<Triple<TextView, String, () -> Boolean>>()
    private val selected = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("peek", Context.MODE_PRIVATE)
        selected.addAll(prefs.getStringSet("apps", emptySet()) ?: emptySet())

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B0F1A"))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(36), dp(18), dp(30))
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("PeekGuard", 28f, "#00E5A8", true))
        root.addView(text("Aapke chune hue apps use karte time front camera se nigrani rakhta hai. " +
                "Agar koi aur aapki screen dekhe to popup aata hai.", 14f, "#9AA3B8", false))

        status = text("", 15f, "#FFFFFF", true)
        status.setPadding(0, dp(14), 0, dp(6))
        root.addView(status)

        root.addView(text("Step 1: Permissions (sab ✅ hone chahiye)", 16f, "#FFFFFF", true).also {
            it.setPadding(0, dp(10), 0, dp(6))
        })
        addPerm(root, "Camera", { checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        addPerm(root, "Notifications", {
            Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        addPerm(root, "Usage access (kaun sa app khula hai)", { usageOk() }) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addPerm(root, "Display over other apps (popup ke liye)", { Settings.canDrawOverlays(this) }) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addPerm(root, "Battery: Unrestricted (recommended)", {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        toggle = Button(this)
        toggle.setOnClickListener { onToggle() }
        root.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(14) })

        friendText = text("", 14f, "#FFFFFF", false)
        friendText.setPadding(0, dp(14), 0, dp(4))
        root.addView(friendText)
        friendBtn = Button(this)
        friendBtn.text = "Friend mode band karo"
        friendBtn.setOnClickListener {
            prefs.edit().putLong("friendUntil", 0L).apply()
            refresh()
        }
        root.addView(friendBtn)

        root.addView(text("Step 2: Apps chuno jinhe protect karna hai", 16f, "#FFFFFF", true).also {
            it.setPadding(0, dp(18), 0, dp(6))
        })
        loadApps(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- UI helpers ----------
    private fun text(t: String, size: Float, color: String, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(Color.parseColor(color))
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun addPerm(root: LinearLayout, label: String, check: () -> Boolean, action: () -> Unit) {
        val tv = TextView(this)
        tv.textSize = 15f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#161B2B"))
        bg.cornerRadius = dp(12).toFloat()
        tv.background = bg
        tv.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        root.addView(tv, lp)
        permRows.add(Triple(tv, label, check))
    }

    private fun loadApps(root: LinearLayout) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.loadLabel(pm).toString().lowercase() }
        for ((pkg, info) in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(4), 0, dp(4))
            val icon = ImageView(this)
            icon.setImageDrawable(info.loadIcon(pm))
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            val cb = CheckBox(this)
            cb.text = info.loadLabel(pm).toString()
            cb.setTextColor(Color.WHITE)
            cb.isChecked = selected.contains(pkg)
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(pkg) else selected.remove(pkg)
                prefs.edit().putStringSet("apps", HashSet(selected)).apply()
            }
            row.addView(cb)
            root.addView(row)
        }
    }

    @Suppress("DEPRECATION")
    private fun usageOk(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val uid = android.os.Process.myUid()
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requiredOk(): Boolean {
        // Battery wali row (last) optional hai
        return permRows.dropLast(1).all { it.third() }
    }

    private fun refresh() {
        for ((tv, label, check) in permRows) {
            tv.text = if (check()) "✅  $label" else "❌  $label  (tap karke allow karo)"
        }
        val running = prefs.getBoolean("running", false)
        status.text = if (running) "● Protection ON" else "○ Protection OFF"
        status.setTextColor(Color.parseColor(if (running) "#00E5A8" else "#FF6B81"))
        toggle.text = if (running) "STOP Protection" else "START Protection"
        val left = prefs.getLong("friendUntil", 0L) - System.currentTimeMillis()
        if (left > 0) {
            friendText.text = "Friend mode ON: ${left / 60000 + 1} minute baaki (is dauran popup nahi aayega)"
            friendBtn.visibility = View.VISIBLE
        } else {
            friendText.text = "Friend mode OFF"
            friendBtn.visibility = View.GONE
        }
    }

    private fun onToggle() {
        val running = prefs.getBoolean("running", false)
        if (running) {
            stopService(Intent(this, GuardService::class.java))
            prefs.edit().putBoolean("running", false).apply()
            refresh()
            return
        }
        if (!requiredOk()) {
            Toast.makeText(this, "Pehle upar ki saari permissions allow karo (✅)", Toast.LENGTH_LONG).show()
            return
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Kam se kam ek app chuno", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, GuardService::class.java))
        ui.postDelayed({ refresh() }, 800)
    }
}
