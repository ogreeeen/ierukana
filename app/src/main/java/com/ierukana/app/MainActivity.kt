package com.ierukana.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** クイズ画面（assets/index.html）を表示し、音声認識・読み上げ・ごほうびタイマーをつなぐ */
class MainActivity : Activity() {

    companion object {
        @Volatile var visible = false
    }

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null
    private var leavingOnPurpose = false   // 設定画面を開く・おうちのひと終了など

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.JAPAN)
                tts?.setPitch(1.3f)
                tts?.setSpeechRate(0.9f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { js("window.onNativeSaid&&onNativeSaid(${q(id ?: "")})") }
                    @Deprecated("") override fun onError(id: String?) { onDone(id) }
                })
                ttsReady = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        leavingOnPurpose = false
        hideSystemUi()
        RewardService.send(this, RewardService.ACTION_FOREGROUND)
        js("window.onAppResume&&onAppResume()")
    }

    override fun onPause() {
        visible = false
        sr?.cancel()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!leavingOnPurpose && !isFinishing) RewardService.send(this, RewardService.ACTION_LOCK)
    }

    override fun onDestroy() {
        tts?.shutdown()
        sr?.destroy()
        super.onDestroy()
    }

    @Deprecated("")
    override fun onBackPressed() { /* 戻るボタンでは抜けられない */ }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun q(s: String) = JSONObject.quote(s)
    private fun js(code: String) = ui.post { web.evaluateJavascript(code, null) }
    private fun speech(type: String, data: String = "") =
        js("window.onNativeSpeech&&onNativeSpeech(${q(type)},${q(data)})")

    // ---------- 音声認識 ----------
    private fun startRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speech("error", "permission")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { speech("error", "unavailable"); return }

        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        var soundSent = false
        sr!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { speech("ready") }
            override fun onBeginningOfSpeech() { if (!soundSent) { soundSent = true; speech("sound") } }
            override fun onRmsChanged(rms: Float) {
                // ことばにならない声でも「何か言えた」として拾う
                if (rms > 7f && !soundSent) { soundSent = true; speech("sound") }
            }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) speech("error", "permission")
                speech("end", error.toString())
            }
            override fun onResults(b: Bundle?) { send(b, "final"); speech("end") }
            override fun onPartialResults(b: Bundle?) { send(b, "partial") }
            override fun onEvent(t: Int, b: Bundle?) {}
            private fun send(b: Bundle?, type: String) {
                val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                if (list.isEmpty()) return
                speech(type, JSONArray(list).toString())
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        sr!!.startListening(intent)
    }

    // ---------- HTML から呼べる関数 ----------
    inner class Bridge {
        private val ctx = this@MainActivity

        @JavascriptInterface fun say(text: String, id: String) = ui.post {
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            else js("window.onNativeSaid&&onNativeSaid(${q(id)})")
        }
        @JavascriptInterface fun listen() = ui.post { startRecognition() }
        @JavascriptInterface fun stopListen() = ui.post { sr?.stopListening() }
        @JavascriptInterface fun cancelListen() = ui.post { sr?.cancel() }

        @JavascriptInterface fun hasOverlay(): Boolean = Settings.canDrawOverlays(ctx)
        @JavascriptInterface fun openOverlaySettings() = ui.post {
            leavingOnPurpose = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface fun setLock(on: Boolean) = Prefs.setLock(ctx, on)

        @JavascriptInterface fun startReward(sec: Int): String {
            val end = System.currentTimeMillis() + sec * 1000L
            Prefs.setRewardEnd(ctx, end)
            RewardService.send(ctx, RewardService.ACTION_REWARD)
            return end.toString()
        }
        @JavascriptInterface fun rewardEndAt(): String = Prefs.rewardEnd(ctx).toString()
        @JavascriptInterface fun clearReward() = Prefs.setRewardEnd(ctx, 0L)
        @JavascriptInterface fun goHome() = ui.post { moveTaskToBack(true) }

        @JavascriptInterface fun parentExit() = ui.post {
            leavingOnPurpose = true
            Prefs.setLock(ctx, false)
            Prefs.setRewardEnd(ctx, 0L)
            RewardService.send(ctx, RewardService.ACTION_STOP)
            finishAndRemoveTask()
        }
    }
}
