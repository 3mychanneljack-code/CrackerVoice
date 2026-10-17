package com.example.voiceassistant.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Captures message notifications, including Google Messages RCS alerts.
 * The user must enable this app under Notification access. History is only
 * what has been posted since the listener connected, plus whatever is still
 * in the shade.
 */
class MessageNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        activeNotifications?.forEach { record(it) }
    }

    override fun onListenerDisconnected() {
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record(sbn)
    }

    private fun record(sbn: StatusBarNotification) {
        if (!isMessageNotification(sbn)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val lines = messagingLines(extras)
        val fallback = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val body = when {
            lines.isNotEmpty() -> lines.joinToString(" | ")
            !fallback.isNullOrBlank() -> fallback.trim()
            else -> return
        }
        val who = title.ifBlank { sbn.packageName }
        synchronized(lock) {
            recent.add(0, Entry(System.currentTimeMillis(), who, body, sbn.packageName))
            while (recent.size > MAX) recent.removeAt(recent.lastIndex)
        }
    }

    private fun isMessageNotification(sbn: StatusBarNotification): Boolean {
        val category = sbn.notification.category
        if (category == Notification.CATEGORY_MESSAGE) return true
        val pkg = sbn.packageName
        return pkg == "com.google.android.apps.messaging" ||
            pkg == "com.samsung.android.messaging" ||
            pkg.endsWith(".messaging") ||
            pkg.contains("mms") ||
            pkg.contains("sms")
    }

    private fun messagingLines(extras: Bundle): List<String> {
        val parcels = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return parcels.mapNotNull { item ->
            val bundle = item as? Bundle ?: return@mapNotNull null
            val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val sender = bundle.getCharSequence("sender")?.toString()?.trim().orEmpty()
            if (sender.isEmpty()) text else "$sender: $text"
        }
    }

    data class Entry(
        val timeMillis: Long,
        val title: String,
        val body: String,
        val packageName: String
    )

    companion object {
        private const val MAX = 40
        private val lock = Any()
        private val recent = ArrayDeque<Entry>()
        @Volatile
        private var instance: MessageNotificationListener? = null

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val component = ComponentName(context, MessageNotificationListener::class.java)
            return flat.contains(component.flattenToString()) || flat.contains(context.packageName)
        }

        fun recentText(limit: Int): String {
            val snapshot = synchronized(lock) { recent.take(limit) }
            if (snapshot.isEmpty()) {
                return if (instance == null) {
                    "No message notifications yet. Turn on notification access for this app, then ask again after a message arrives."
                } else {
                    "Notification access is on, but no RCS or message notifications have arrived since it was enabled."
                }
            }
            return snapshot.joinToString("\n") { entry ->
                "${entry.title}: ${entry.body}"
            }
        }
    }
}
