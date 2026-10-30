package com.peekguard.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service:
 *  - har 1 second me dekhta hai kaun sa app screen par hai (UsageStats)
 *  - agar wo app "protected" list me hai -> front camera ON + face detection
 *  - 2 ya zyada log screen ki taraf dekh rahe hon -> popup (Ignore / Friend)
 * Koi photo save nahi hoti, kuch internet par nahi jata.
 */
class GuardService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var detector: FaceDetector
    private val executor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var cameraOn = false
    private var lastCamTry = 0L
    private var lastAnalyze = 0L
    private var hits = 0
    private var snoozeUntil = 0L
    private var curFg: String? = null
    private var lastQuery = 0L
    private var overlay: View? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                loop()
            } catch (e: Exception) {
                // ignore, next tick retry
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("peek", Context.MODE_PRIVATE)
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, 1, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } catch (e: Exception) {
            stopSelf()
            return
        }
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.12f)
                .build()
        )
        scanEvents(System.currentTimeMillis() - 3_600_000L)
        prefs.edit().putBoolean("running", true).apply()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == "STOP") {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopCamera()
        removeOverlay()
        if (::detector.isInitialized) detector.close()
        executor.shutdown()
        getSharedPreferences("peek", Context.MODE_PRIVATE).edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    // ---------- main loop ----------
    private fun loop() {
        val now = System.currentTimeMillis()
        scanEvents(lastQuery)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val apps = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        val friend = now < prefs.getLong("friendUntil", 0L)
        val fg = curFg
        val want = pm.isInteractive && fg != null && apps.contains(fg) && !friend
        if (want) {
            if (!cameraOn && SystemClock.elapsedRealtime() - lastCamTry > 3000) startCamera()
        } else {
            if (cameraOn) stopCamera()
            hits = 0
            removeOverlay()
        }
    }

    @Suppress("DEPRECATION")
    private fun scanEvents(from: Long) {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val to = System.currentTimeMillis()
        val ev = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> curFg = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == curFg) curFg = null
            }
        }
        lastQuery = to - 1500
    }

    // ---------- camera ----------
    private fun startCamera() {
        lastCamTry = SystemClock.elapsedRealtime()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                cameraOn = true
            } catch (e: Exception) {
                cameraOn = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // ignore
        }
        cameraOn = false
    }

    private val analyzer = object : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(proxy: ImageProxy) {
            val now = SystemClock.elapsedRealtime()
            val media = proxy.image
            if (now - lastAnalyze < 600 || media == null) {
                proxy.close()
                return
            }
            lastAnalyze = now
            val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            detector.process(img)
                .addOnSuccessListener { faces -> onFaces(faces) }
                .addOnCompleteListener { proxy.close() }
        }
    }

    private fun onFaces(faces: List<Face>) {
        // sirf wo chehre gino jo screen ki taraf dekh rahe hain
        val looking = faces.count { abs(it.headEulerAngleY) < 45f && abs(it.headEulerAngleX) < 40f }
        hits = if (looking >= 2) hits + 1 else maxOf(0, hits - 1)
        if (hits >= 3 && overlay == null && System.currentTimeMillis() > snoozeUntil) {
            hits = 0
            showOverlay()
        }
    }

    // ---------- popup ----------
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(16), dp(18), dp(16))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2B3001B"))
        bg.cornerRadius = dp(18).toFloat()
        box.background = bg

        val title = TextView(this)
        title.text = "\u26A0 Third person detected!"
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        box.addView(title)

        val msg = TextView(this)
        msg.text = "Koi aur aapki screen dekh raha hai."
        msg.setTextColor(Color.WHITE)
        msg.textSize = 14f
        msg.setPadding(0, dp(6), 0, dp(12))
        box.addView(msg)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val ignore = Button(this)
        ignore.text = "Ignore"
        ignore.setOnClickListener {
            snoozeUntil = System.currentTimeMillis() + 2 * 60_000L
            removeOverlay()
        }
        val friend = Button(this)
        friend.text = "Friend"
        friend.setOnClickListener {
            prefs.edit().putLong("friendUntil", System.currentTimeMillis() + 30 * 60_000L).apply()
            Toast.makeText(applicationContext, "Friend mode ON: 30 minute", Toast.LENGTH_SHORT).show()
            removeOverlay()
        }
        val lpBtn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpBtn.setMargins(dp(4), 0, dp(4), 0)
        row.addView(ignore, lpBtn)
        row.addView(friend, lpBtn)
        box.addView(row)

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(70)

        try {
            wm.addView(box, lp)
            overlay = box
        } catch (e: Exception) {
            return
        }
        vibrate()
        handler.postDelayed({
            if (overlay === box) {
                snoozeUntil = System.currentTimeMillis() + 30_000L
                removeOverlay()
            }
        }, 15_000L)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // ignore
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- notification ----------
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("peek", "PeekGuard", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuardService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "peek")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("PeekGuard is ON")
            .setContentText("Protected apps me front camera se nigrani chal rahi hai")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }
}
