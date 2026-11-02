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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
 *    (protected app screen par hone tak camera hamesha chalu rehti hai)
 *  - 2 ya zyada log screen ki taraf dekh rahe hon -> screen dim + popup
 *    (Friend / Ignore), fir timer list (30s, 1, 2, 5, 10 minute)
 *  - jab tak user in dono me se koi time nahi chunta, popup khud band nahi
 *    hota aur scanning chalti rehti hai
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

    // overlay state
    private var dimView: View? = null
    private var cardView: LinearLayout? = null
    private var pendingWho: String? = null // null = "ask" step abhi dikh raha hai, warna "Friend"/"Ignore"

    private val TIMER_OPTIONS = listOf(
        "30 second" to 30_000L,
        "1 minute" to 60_000L,
        "2 minute" to 120_000L,
        "5 minute" to 300_000L,
        "10 minute" to 600_000L
    )

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
        scanEvents(lastQuery)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val apps = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        val fg = curFg
        // Protected app screen par hai to camera hamesha ON rahegi — snooze sirf
        // naya popup dikhne se rokta hai, camera/scanning ko band nahi karta.
        val want = pm.isInteractive && fg != null && apps.contains(fg)
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
        if (hits >= 3 && cardView == null && System.currentTimeMillis() > snoozeUntil) {
            hits = 0
            showAskOverlay()
        }
    }

    // ---------- popup: step 1 (Friend / Ignore) ----------
    private fun showAskOverlay() {
        pendingWho = null
        showDim()
        renderCard {
            title("\u26A0 Third person detected!")
            message("Screen dim kar di gayi hai. Ye kaun hai?")
            twoButtons(
                "Ignore", { showTimerOverlay("Ignore") },
                "Friend", { showTimerOverlay("Friend") }
            )
        }
        vibrate()
    }

    // ---------- popup: step 2 (timer list) ----------
    private fun showTimerOverlay(who: String) {
        pendingWho = who
        renderCard {
            title("$who \u2014 kitni der ke liye?")
            for ((label, ms) in TIMER_OPTIONS) {
                oneButton(label) { onTimerPicked(who, label, ms) }
            }
            oneButton("\u2190 Wapas") { showAskOverlay() }
        }
    }

    private fun onTimerPicked(who: String, label: String, millis: Long) {
        snoozeUntil = System.currentTimeMillis() + millis
        removeOverlay()
        Toast.makeText(applicationContext, "$who: $label tak alert band", Toast.LENGTH_SHORT).show()
    }

    // ---------- overlay building blocks ----------

    /** Poori screen ke peeche dim/blur layer — ye touch intercept nahi karta,
     *  sirf neeche ka content chhupata hai. */
    private fun showDim() {
        if (dimView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled
        val v = View(this)
        v.setBackgroundColor(if (blurOk) Color.argb(90, 0, 0, 0) else Color.argb(235, 10, 10, 10))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (blurOk) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = 90
        }
        try {
            wm.addView(v, lp)
            dimView = v
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun removeDim() {
        val v = dimView ?: return
        dimView = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Card ko dobara banata hai (ya pehli baar banata hai) aur builder lambda
     *  se andar ke views bharta hai. */
    private fun renderCard(builder: CardBuilder.() -> Unit) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var box = cardView
        if (box == null) {
            box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            val d = resources.displayMetrics.density
            box.setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt())
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#F2B3001B"))
            bg.cornerRadius = (18 * d)
            box.background = bg

            val lp = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (70 * d).toInt()
            try {
                wm.addView(box, lp)
                cardView = box
            } catch (e: Exception) {
                return
            }
        }
        box.removeAllViews()
        CardBuilder(this, box).apply(builder)
    }

    private fun removeOverlay() {
        pendingWho = null
        removeDim()
        val v = cardView ?: return
        cardView = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Chhota helper jo card ke andar text aur buttons add karta hai. */
    private class CardBuilder(private val ctx: Context, private val root: LinearLayout) {
        private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        fun title(text: String) {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextColor(Color.WHITE)
            tv.textSize = 20f
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            root.addView(tv)
        }

        fun message(text: String) {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            tv.setPadding(0, dp(6), 0, dp(12))
            root.addView(tv)
        }

        fun twoButtons(label1: String, onClick1: () -> Unit, label2: String, onClick2: () -> Unit) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            val lpBtn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lpBtn.setMargins(dp(4), 0, dp(4), 0)
            val b1 = Button(ctx).apply { text = label1; setOnClickListener { onClick1() } }
            val b2 = Button(ctx).apply { text = label2; setOnClickListener { onClick2() } }
            row.addView(b1, lpBtn)
            row.addView(b2, lpBtn)
            root.addView(row)
        }

        fun oneButton(label: String, onClick: () -> Unit) {
            val b = Button(ctx)
            b.text = label
            b.setOnClickListener { onClick() }
            root.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) })
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
