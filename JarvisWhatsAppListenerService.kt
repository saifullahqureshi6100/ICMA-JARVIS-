package com.icma.jarvis

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisWhatsAppListenerService : NotificationListenerService() {

    companion object {
        private const val PREFS_NAME = "jarvis_wa_cache"
        private const val KEY_MESSAGES = "messages_json"
        private const val MAX_MESSAGES = 80

        fun getMessagesJson(context: Context, groupFilter: String = ""): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_MESSAGES, "[]") ?: "[]"
            if (groupFilter.isBlank()) return raw

            val filterLower = groupFilter.lowercase(Locale.US).trim()
            val all = JSONArray(raw)
            val filtered = JSONArray()
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i) ?: continue
                val grp = item.optString("group", "").lowercase(Locale.US)
                val sender = item.optString("sender", "").lowercase(Locale.US)
                if (grp.contains(filterLower) || sender.contains(filterLower)) {
                    filtered.put(item)
                }
            }
            return filtered.toString()
        }

        fun clearMessages(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_MESSAGES)
                .apply()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val extras = sbn.notification?.extras ?: return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()

        val messageBody = if (bigText.length > rawText.length) bigText else rawText
        if (rawTitle.isEmpty() || messageBody.isEmpty()) return
        if (Regex("^\\d+ new messages?$").matches(messageBody.lowercase(Locale.US))) return

        // WhatsApp group notifications usually format title as "Group Name: Sender" or set EXTRA_CONVERSATION_TITLE
        var groupName = convTitle
        var senderName = rawTitle
        if (groupName.isEmpty() && rawTitle.contains(":")) {
            val parts = rawTitle.split(":", limit = 2)
            groupName = parts[0].trim()
            senderName = parts[1].trim()
        } else if (groupName.isEmpty()) {
            groupName = "WhatsApp Chat"
        }

        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(sbn.postTime))
        val entry = JSONObject().apply {
            put("group", groupName)
            put("sender", senderName)
            put("text", messageBody)
            put("time", timeStr)
            put("timestamp", sbn.postTime)
        }

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = try {
            JSONArray(prefs.getString(KEY_MESSAGES, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

        val updated = JSONArray()
        updated.put(entry)
        val limit = minOf(existing.length(), MAX_MESSAGES - 1)
        for (i in 0 until limit) {
            updated.put(existing.get(i))
        }

        prefs.edit().putString(KEY_MESSAGES, updated.toString()).apply()
    }
}
