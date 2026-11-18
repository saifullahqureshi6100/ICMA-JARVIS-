package com.icma.jarvis

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class JarvisWhatsAppListenerService : NotificationListenerService() {

    companion object {
        private const val PREFS_NAME = "jarvis_social_cache"
        private const val KEY_MESSAGES = "messages_json"
        private const val MAX_MESSAGES = 120

        private val supportedApps = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "org.telegram.messenger" to "Telegram",
            "com.linkedin.android" to "LinkedIn",
            "com.twitter.android" to "X"
        )

        private val replyActionMap = ConcurrentHashMap<String, Pair<Notification.Action, RemoteInput>>()
        private val senderReplyMap = ConcurrentHashMap<String, Pair<Notification.Action, RemoteInput>>()

        fun getMessagesJson(context: Context, filterText: String = ""): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_MESSAGES, "[]") ?: "[]"
            if (filterText.isBlank()) return raw

            val filterLower = filterText.lowercase(Locale.US).trim()
            val all = JSONArray(raw)
            val filtered = JSONArray()
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i) ?: continue
                val app = item.optString("app", "").lowercase(Locale.US)
                val grp = item.optString("group", "").lowercase(Locale.US)
                val sender = item.optString("sender", "").lowercase(Locale.US)
                if (app.contains(filterLower) || grp.contains(filterLower) || sender.contains(filterLower)) {
                    filtered.put(item)
                }
            }
            return filtered.toString()
        }

        fun sendDirectReply(context: Context, notifId: String, replyText: String): Boolean {
            val pair = replyActionMap[notifId] ?: return false
            return dispatchRemoteInput(context, pair.first, pair.second, replyText)
        }

        fun sendDirectReplyBySender(context: Context, senderQuery: String, replyText: String): Boolean {
            val q = senderQuery.lowercase(Locale.US).trim()
            val match = senderReplyMap.entries.firstOrNull { it.key.contains(q) } ?: return false
            return dispatchRemoteInput(context, match.value.first, match.value.second, replyText)
        }

        private fun dispatchRemoteInput(
            context: Context,
            action: Notification.Action,
            remoteInput: RemoteInput,
            replyText: String
        ): Boolean {
            return try {
                val intent = Intent()
                val bundle = Bundle().apply { putCharSequence(remoteInput.resultKey, replyText) }
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                action.actionIntent.send(context, 0, intent)
                true
            } catch (_: Exception) {
                false
            }
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
        val appName = supportedApps[pkg] ?: return

        val extras = sbn.notification?.extras ?: return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()

        val messageBody = if (bigText.length > rawText.length) bigText else rawText
        if (rawTitle.isEmpty() || messageBody.isEmpty()) return
        if (Regex("^\\d+ new messages?$").matches(messageBody.lowercase(Locale.US))) return

        var groupName = convTitle
        var senderName = rawTitle
        if (groupName.isEmpty() && rawTitle.contains(":")) {
            val parts = rawTitle.split(":", limit = 2)
            groupName = parts[0].trim()
            senderName = parts[1].trim()
        } else if (groupName.isEmpty()) {
            groupName = "$appName Direct"
        }

        val notifId = "${sbn.key}|${sbn.postTime}"
        var canDirectReply = false
        sbn.notification.actions?.forEach { action ->
            val inputs = action.remoteInputs
            if (inputs != null && inputs.isNotEmpty()) {
                val pair = Pair(action, inputs[0])
                replyActionMap[notifId] = pair
                senderReplyMap[senderName.lowercase(Locale.US)] = pair
                canDirectReply = true
            }
        }

        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(sbn.postTime))
        val entry = JSONObject().apply {
            put("id", notifId)
            put("app", appName)
            put("package", pkg)
            put("group", groupName)
            put("sender", senderName)
            put("text", messageBody)
            put("time", timeStr)
            put("timestamp", sbn.postTime)
            put("canDirectReply", canDirectReply)
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
