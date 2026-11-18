package com.icma.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var phoneController: JarvisPhoneController
    private var tts: TextToSpeech? = null

    private val commandCenterUrl = "https://ais-pre-tgcxqe53ouzy622wo2qp27-120229870269.asia-northeast1.run.app"
    private val speechReqCode = 202

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phoneController = JarvisPhoneController(this)
        tts = TextToSpeech(this, this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        requestRuntimePermissions()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(JarvisBridge(), "JarvisAndroid")
            loadUrl(commandCenterUrl)
        }
        setContentView(webView)

        if (intent.getBooleanExtra("wake_auto_listen", false)) {
            webView.postDelayed({ startNativeVoiceCapture() }, 700)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("wake_auto_listen", false) == true) {
            startNativeVoiceCapture()
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    fun startNativeVoiceCapture() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Jarvis sun raha hai...")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, speechReqCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechReqCode && resultCode == RESULT_OK) {
            val spoken = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                dispatchVoiceCommandToCloud(spoken)
            }
        }
    }

    private fun dispatchVoiceCommandToCloud(command: String) {
        thread {
            try {
                val url = URL("$commandCenterUrl/api/jarvis/mobile-command")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val payload = JSONObject().put("command", command).toString()
                OutputStreamWriter(conn.outputStream).use { it.write(payload) }

                val responseText = conn.inputStream.bufferedReader().readText()
                runOnUiThread {
                    val reply = phoneController.executeCloudJson(responseText)
                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
                    Toast.makeText(this, reply, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Jarvis error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    inner class JarvisBridge {
        @JavascriptInterface
        fun executeCommand(jsonPayload: String): String {
            var reply = "Executed"
            runOnUiThread {
                reply = phoneController.executeCloudJson(jsonPayload)
                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis-bridge")
            }
            return reply
        }

        @JavascriptInterface
        fun openVoice() {
            runOnUiThread { startNativeVoiceCapture() }
        }

        @JavascriptInterface
        fun getWhatsAppMessages(groupFilter: String): String {
            return JarvisWhatsAppListenerService.getMessagesJson(applicationContext, groupFilter)
        }

        @JavascriptInterface
        fun getSocialMessages(platformFilter: String): String {
            return JarvisWhatsAppListenerService.getMessagesJson(applicationContext, platformFilter)
        }

        @JavascriptInterface
        fun sendDirectNotificationReply(notifId: String, replyText: String): Boolean {
            return JarvisWhatsAppListenerService.sendDirectReply(applicationContext, notifId, replyText)
        }

        @JavascriptInterface
        fun setWakeWordEnabled(enabled: Boolean): String {
            val svcIntent = Intent(applicationContext, JarvisWakeWordService::class.java)
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(svcIntent)
                } else {
                    applicationContext.startService(svcIntent)
                }
                return "Wake word 'Wake up Jarvis' background service started."
            } else {
                applicationContext.stopService(svcIntent)
                return "Wake word background service stopped."
            }
        }

        @JavascriptInterface
        fun isWakeWordRunning(): Boolean {
            return JarvisWakeWordService.isRunning
        }

        @JavascriptInterface
        fun clearWhatsAppMessages() {
            JarvisWhatsAppListenerService.clearMessages(applicationContext)
        }

        @JavascriptInterface
        fun openNotificationAccessSettings() {
            runOnUiThread {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            }
        }

        @JavascriptInterface
        fun scheduleStudyCall(hour: Int, minute: Int, delayMinutes: Int, topic: String): String {
            if (delayMinutes > 0) {
                phoneController.scheduleStudyCallDelay(delayMinutes, topic)
                return "Study call scheduled in $delayMinutes minutes."
            }
            phoneController.scheduleStudyCallAtTime(hour, minute, topic)
            return "Daily study call set for %02d:%02d.".format(hour, minute)
        }

        @JavascriptInterface
        fun triggerStudyCallNow(topic: String) {
            runOnUiThread { phoneController.triggerStudyCallNow(topic) }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            tts?.setPitch(0.82f)
            tts?.setSpeechRate(0.98f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    val maleVoice = tts?.voices?.firstOrNull { v ->
                        val n = v.name.lowercase(Locale.US)
                        v.locale.language == "en" &&
                            (n.contains("male") || n.contains("-m-") || n.contains("iom") || n.contains("rjs") || n.contains("gba")) &&
                            !n.contains("female")
                    }
                    if (maleVoice != null) tts?.voice = maleVoice
                } catch (_: Exception) {}
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
