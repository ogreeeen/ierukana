package com.ierukana.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ごほうび時間を数え、時間になったらクイズ画面を強制的に前面へ戻すサービス。
 * - 3分後：全画面の「おしまい！」を他アプリの上にかぶせ、同時にクイズ画面を起動
 * - ロック中：ごほうび時間以外にホームなどで抜けたら、すぐ引き戻す
 */
class RewardService : Service() {

    companion object {
        const val ACTION_FOREGROUND = "foreground"   // クイズ画面が前面に来た
        const val ACTION_REWARD = "reward"           // ごほうび開始
        const val ACTION_LOCK = "lock"               // クイズ画面から抜けた
        const val ACTION_STOP = "stop"
        private const val CH = "ierukana"
        private const val NID = 1
        @Volatile var running = false

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RewardService::class.java).setAction(action)
            try {
                if (running) ctx.startService(i) else ctx.startForegroundService(i)
            } catch (e: Exception) {
                try { ctx.startService(i) } catch (_: Exception) {}
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "いえるかな？", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(statusText())
        when (intent?.action) {
            ACTION_FOREGROUND -> {
                handler.removeCallbacks(lockCheck)
                removeOverlay()
                startTicking()
            }
            ACTION_REWARD -> startTicking()
            ACTION_LOCK -> {
                handler.removeCallbacks(lockCheck)
                handler.postDelayed(lockCheck, 700)
            }
            ACTION_STOP -> {
                handler.removeCallbacksAndMessages(null)
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startTicking()   // OSによる再起動時など
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    // ---------- タイマー ----------
    private val tick = object : Runnable {
        override fun run() {
            val end = Prefs.rewardEnd(this@RewardService)
            if (end == 0L) { updateNotification("みまもりちゅう"); return }
            val left = end - System.currentTimeMillis()
            if (left <= 0) {
                updateNotification("じかんです！")
                // 終了時刻はクイズ画面側で「おしまい」処理をしてから消す
                vibrate()
                bringBack()
                return
            }
            updateNotification(statusText())
            handler.postDelayed(this, 1000)
        }
    }

    private fun startTicking() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private val lockCheck = Runnable {
        val c = this
        if (!MainActivity.visible && Prefs.lock(c) && !Prefs.rewardRunning(c)) bringBack()
    }

    // ---------- 引き戻し ----------
    private fun bringBack() {
        if (MainActivity.visible) return
        showOverlay()
        launchQuiz()
    }

    private fun launchQuiz() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (_: Exception) { }
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#FFF7E8")) }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "⏰"; textSize = 110f; gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "おしまい！\nタッチして もどろう"
                textSize = 32f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#4A3B2A"))
            })
        }
        root.addView(center, FrameLayout.LayoutParams(-1, -1))

        // おうちのひと用：右下を長押しでロック解除
        val parent = TextView(this).apply {
            text = "おうちのひと（ながおし）"
            textSize = 12f
            setTextColor(Color.parseColor("#998877"))
            setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
            setOnLongClickListener {
                Prefs.setLock(this@RewardService, false)
                Prefs.setRewardEnd(this@RewardService, 0L)
                removeOverlay(); true
            }
        }
        root.addView(parent, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        root.setOnClickListener { launchQuiz() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        try { wm.addView(root, lp); overlay = root } catch (_: Exception) { }
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        } catch (_: Exception) {}
    }

    // ---------- 通知（フォアグラウンドサービス用） ----------
    private fun statusText(): String {
        val end = Prefs.rewardEnd(this)
        val left = end - System.currentTimeMillis()
        if (end == 0L || left <= 0) return "みまもりちゅう"
        val s = ((left + 999) / 1000).toInt()
        return "スマホじかん のこり %d:%02d".format(s / 60, s % 60)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("いえるかな？")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NID, buildNotification(text))
    }
}
