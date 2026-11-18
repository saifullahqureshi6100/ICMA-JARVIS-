package com.icma.jarvis

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Calendar

class JarvisPhoneController(private val context: Context) {

    private val appPackages = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "telegram" to "org.telegram.messenger",
        "linkedin" to "com.linkedin.android",
        "x" to "com.twitter.android",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "calculator" to "com.google.android.calculator"
    )

    fun executeCloudJson(rawJson: String): String {
        val obj = JSONObject(rawJson)
        val action = obj.optString("action", "say")
        val target = obj.optString("target", "")
        val hasHour = obj.has("hour") && !obj.isNull("hour")
        val hour = if (hasHour) obj.optInt("hour", 19) else 19
        val minute = if (obj.has("minute") && !obj.isNull("minute")) obj.optInt("minute", 0) else 0
        val hasDelay = obj.has("minutes") && !obj.isNull("minutes")
        val minutes = if (hasDelay) obj.optInt("minutes", 25) else 25
        val textArg = obj.optString("textArg", "")
        val spoken = obj.optString("spokenReply", "Done.")

        when (action) {
            "open_app" -> openApp(target)
            "youtube_search" -> searchYouTube(target)
            "web_search" -> searchWeb(target)
            "set_alarm" -> setAlarm(hour, minute, target.ifEmpty { "ICMA Study" })
            "set_timer" -> setTimer(minutes, target.ifEmpty { "Study Session" })
            "schedule_study_call" -> {
                val topic = target.ifEmpty { "ICMA Study Session" }
                if (hasDelay && !hasHour) {
                    scheduleStudyCallDelay(minutes, topic)
                } else {
                    scheduleStudyCallAtTime(hour, minute, topic)
                }
            }
            "trigger_study_call" -> triggerStudyCallNow(target.ifEmpty { "ICMA Study Session" })
            "toggle_flashlight" -> toggleFlashlight(target.equals("on", ignoreCase = true))
            "set_dnd" -> setDoNotDisturb(target.equals("on", ignoreCase = true))
            "set_volume" -> setVolumeLevel(target)
            "media_control" -> controlMediaPlayback(target)
            "wifi_settings" -> launchIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
            "bluetooth_settings" -> launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "phone_call" -> dialPhone(target)
            "send_whatsapp" -> openWhatsApp(target, textArg)
            "reply_social" -> {
                val sentDirectly = JarvisWhatsAppListenerService.sendDirectReplyBySender(context, target, textArg)
                if (!sentDirectly) openWhatsApp("", textArg)
            }
            "nav_action" -> performAccessibilityNav(target)
        }
        return spoken
    }

    fun triggerStudyCallNow(topic: String) {
        val intent = Intent(context, StudyAlarmActivity::class.java).apply {
            putExtra("topic", topic)
        }
        launchIntent(intent)
    }

    fun scheduleStudyCallDelay(delayMinutes: Int, topic: String) {
        val triggerAt = System.currentTimeMillis() + delayMinutes.coerceIn(1, 720) * 60_000L
        scheduleStudyReceiver(triggerAt, topic)
    }

    fun scheduleStudyCallAtTime(hour: Int, minute: Int, topic: String) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        scheduleStudyReceiver(cal.timeInMillis, topic)
    }

    private fun scheduleStudyReceiver(triggerAtMillis: Long, topic: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, StudyReceiver::class.java).apply {
            putExtra("topic", topic)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            902,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun openApp(name: String) {
        val key = name.lowercase().trim()
        if (key == "settings") {
            launchIntent(Intent(Settings.ACTION_SETTINGS))
            return
        }
        if (key == "clock") {
            launchIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            return
        }
        if (key == "camera") {
            launchIntent(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
            return
        }
        if (key == "phone") {
            launchIntent(Intent(Intent.ACTION_DIAL))
            return
        }
        val pkg = appPackages[key] ?: return
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }

    private fun controlMediaPlayback(cmd: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (cmd.lowercase()) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun searchYouTube(query: String) {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        launchIntent(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun searchWeb(query: String) {
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        launchIntent(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun setAlarm(hour: Int, minute: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        launchIntent(intent)
    }

    private fun setTimer(minutes: Int, label: String) {
        val seconds = minutes.coerceIn(1, 180) * 60
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        launchIntent(intent)
    }

    private fun toggleFlashlight(turnOn: Boolean) {
        try {
            val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cam.cameraIdList.firstOrNull() ?: return
            cam.setTorchMode(id, turnOn)
        } catch (_: Exception) {
        }
    }

    private fun setDoNotDisturb(enable: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            launchIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        val filter = if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
        else NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(filter)
    }

    private fun setVolumeLevel(level: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = when (level.lowercase()) {
            "mute" -> 0
            "low" -> (max * 0.25).toInt()
            "medium" -> (max * 0.6).toInt()
            else -> max
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
    }

    private fun dialPhone(number: String) {
        val clean = number.replace(Regex("[^0-9+]"), "")
        if (clean.isNotEmpty()) {
            launchIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
        } else {
            launchIntent(Intent(Intent.ACTION_DIAL))
        }
    }

    private fun openWhatsApp(phone: String, message: String) {
        val clean = phone.replace(Regex("[^0-9]"), "")
        val url = if (clean.isNotEmpty()) {
            "https://wa.me/$clean?text=" + Uri.encode(message)
        } else {
            "https://wa.me/?text=" + Uri.encode(message)
        }
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun performAccessibilityNav(actionName: String) {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            launchIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        when (actionName.lowercase()) {
            "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
        }
    }

    private fun launchIntent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
