package com.icma.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

class JarvisWakeWordService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var isRunning = false
        private const val CHANNEL_ID = "jarvis_wake_service"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        tts = TextToSpeech(this, this)
        startAsForeground()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IcmaJarvis::WakeCpuLock").apply {
            acquire(10 * 60 * 1000L)
        }

        mainHandler.postDelayed({ startListeningCycle() }, 600)
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Always-On Wake Word",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val tapIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            301,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. Standby Online")
            .setContentText("Say 'Wake up Jarvis' anytime to wake screen & give orders")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        startForeground(301, notification)
    }

    private fun startListeningCycle() {
        if (shuttingDown || !SpeechRecognizer.isRecognitionAvailable(this)) return
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {
                            checkMatches(partialResults)
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onResults(results: Bundle?) {
                            val triggered = checkMatches(results)
                            if (!shuttingDown && !triggered) {
                                mainHandler.postDelayed({ startListeningCycle() }, 350)
                            }
                        }
                        override fun onError(error: Int) {
                            if (!shuttingDown) {
                                mainHandler.postDelayed({ startListeningCycle() }, 900)
                            }
                        }
                    })
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                }
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {
                if (!shuttingDown) mainHandler.postDelayed({ startListeningCycle() }, 1500)
            }
        }
    }

    private fun checkMatches(bundle: Bundle?): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val hit = matches.any { line ->
            val lower = line.lowercase(Locale.US)
            lower.contains("wake up jarvis") ||
                lower.contains("wakeup jarvis") ||
                lower.contains("hey jarvis") ||
                lower.contains("jarvis utho")
        }
        if (hit) {
            wakePhoneAndReply()
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun wakePhoneAndReply() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "IcmaJarvis::ScreenWakeLock"
            )
            screenLock.acquire(8000L)
        } catch (_: Exception) {}

        tts?.speak(
            "Yes Saif, I am awake. What are your orders?",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis-wake"
        )

        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("wake_auto_listen", true)
        }
        startActivity(launch)

        // Resume standby detection after command window
        mainHandler.postDelayed({ startListeningCycle() }, 9000)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            tts?.setPitch(0.82f)
            tts?.setSpeechRate(0.98f)
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
