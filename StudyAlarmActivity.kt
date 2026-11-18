package com.icma.jarvis

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class StudyAlarmActivity : Activity(), TextToSpeech.OnInitListener {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var studyTopic: String = "ICMA Study Session"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        studyTopic = intent.getStringExtra("topic")?.takeIf { it.isNotBlank() } ?: "ICMA Study Session"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#040C18"))
            setPadding(64, 96, 64, 96)
        }

        val badge = TextView(this).apply {
            text = "INCOMING JARVIS STUDY CALL"
            textSize = 13f
            setTextColor(Color.parseColor("#4FD3FF"))
            gravity = Gravity.CENTER
        }

        val caller = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 12)
        }

        val subtitle = TextView(this).apply {
            text = "Saif, waqt ho gaya hai: $studyTopic"
            textSize = 18f
            setTextColor(Color.parseColor("#CFE8FF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        val btnAnswer = Button(this).apply {
            text = "ANSWER & START STUDY"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.parseColor("#04121C"))
            setPadding(40, 32, 40, 32)
            setOnClickListener {
                stopRinging()
                val main = Intent(this@StudyAlarmActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("study_topic", studyTopic)
                }
                startActivity(main)
                finish()
            }
        }

        val btnSnooze = Button(this).apply {
            text = "SNOOZE 10 MIN"
            setBackgroundColor(Color.parseColor("#F59E0B"))
            setTextColor(Color.parseColor("#04121C"))
            setPadding(40, 28, 40, 28)
            setOnClickListener {
                stopRinging()
                JarvisPhoneController(this@StudyAlarmActivity)
                    .scheduleStudyCallDelay(10, studyTopic)
                finish()
            }
        }

        val btnDecline = Button(this).apply {
            text = "DECLINE"
            setBackgroundColor(Color.parseColor("#EF4444"))
            setTextColor(Color.WHITE)
            setPadding(40, 28, 40, 28)
            setOnClickListener {
                stopRinging()
                finish()
            }
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 18, 0, 0) }

        root.addView(badge)
        root.addView(caller)
        root.addView(subtitle)
        root.addView(btnAnswer, lp)
        root.addView(btnSnooze, lp)
        root.addView(btnDecline, lp)
        setContentView(root)

        startRinging()
        tts = TextToSpeech(this, this)
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) {}

        try {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 600, 400, 600, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.speak(
                "Incoming study call from Jarvis. Saif, it is time for $studyTopic.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "jarvis-call"
            )
        }
    }

    override fun onDestroy() {
        stopRinging()
        tts?.shutdown()
        super.onDestroy()
    }
}
