package com.icma.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class StudyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val topic = intent.getStringExtra("topic") ?: "ICMA Study Session"

        val callIntent = Intent(context, StudyAlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("topic", topic)
        }

        val fullScreenPending = PendingIntent.getActivity(
            context,
            901,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "jarvis_study_calls"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Jarvis Study Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen study call reminders from ICMA Jarvis"
            }
            nm.createNotificationChannel(ch)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Jarvis Study Call")
            .setContentText("Time for $topic — tap to answer")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPending, true)
            .build()

        nm.notify(901, notification)
        try {
            context.startActivity(callIntent)
        } catch (_: Exception) {}
    }
}
