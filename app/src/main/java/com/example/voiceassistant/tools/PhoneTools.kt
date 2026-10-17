package com.example.voiceassistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import com.example.voiceassistant.notify.MessageNotificationListener
import androidx.core.content.ContextCompat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneTools(private val context: Context) {

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Looks up numbers in the system contacts provider — the same database
     * the user's default Contacts app reads and writes.
     */
    fun lookupContact(name: String): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return "Error: READ_CONTACTS permission not granted"
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.Contacts.STARRED
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val sort = "${ContactsContract.Contacts.STARRED} DESC, " +
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        val matches = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            arrayOf("%$name%"),
            sort
        )?.use { cursor ->
            val seen = HashSet<String>()
            while (cursor.moveToNext() && matches.size < 5) {
                val number = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: name
                val key = "$displayName|$number"
                if (!seen.add(key)) continue
                val starred = cursor.getInt(2) == 1
                val mark = if (starred) " (starred)" else ""
                matches.add("$displayName$mark: $number")
            }
        }
        return if (matches.isEmpty()) {
            "No contact found matching '$name' in the default contacts database"
        } else {
            "Contacts (same store as your Contacts app):\n" + matches.joinToString("\n")
        }
    }

    /** Opens the match in the default Contacts app. */
    fun openContact(name: String): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return "Error: READ_CONTACTS permission not granted"
        }
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.Contacts.STARRED} DESC"
        )
        cursor?.use {
            if (!it.moveToFirst()) return "No contact found matching '$name'"
            val id = it.getLong(0)
            val lookup = it.getString(1)
            val display = it.getString(2)
            val uri = ContactsContract.Contacts.getLookupUri(id, lookup)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return "Opened $display in the default Contacts app"
        }
        return "No contact found matching '$name'"
    }

    fun makeCall(number: String): String {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return "Error: CALL_PHONE permission not granted. Use dial instead or grant permission."
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${cleanNumber(number)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Calling $number"
        } catch (e: Exception) {
            "Failed to call: ${e.message}"
        }
    }

    fun dial(number: String): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${cleanNumber(number)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opened dialer for $number"
        } catch (e: Exception) {
            "Failed to open dialer: ${e.message}"
        }
    }

    /**
     * Hands the message to Google Messages when it is installed, otherwise the
     * user's default SMS app. Google Messages sends RCS when that chat supports
     * it, and falls back to SMS/MMS itself. A third-party app cannot silently
     * inject an RCS message, so the draft opens ready to send.
     */
    fun sendSms(number: String, message: String): String {
        return sendMessage(number, null, message, false)
    }

    /**
     * Resolves a contact name if needed, optionally appends a Google Maps link
     * for the current location, then opens the draft in Google Messages.
     */
    fun sendMessage(
        number: String?,
        name: String?,
        message: String,
        includeLocation: Boolean
    ): String {
        val resolved = when {
            !number.isNullOrBlank() -> cleanNumber(number)
            !name.isNullOrBlank() -> firstNumberFor(name)
                ?: return "No phone number found for '$name' in contacts"
            else -> return "Need a contact name or a phone number"
        }
        val body = if (includeLocation) {
            val link = locationLink()
            if (link.startsWith("http")) {
                message.trim().trimEnd('.') + ". My location: $link"
            } else {
                return link
            }
        } else {
            message
        }
        return openDraft(resolved, body)
    }

    private fun openDraft(cleaned: String, message: String): String {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$cleaned")
            putExtra("sms_body", message)
            putExtra("address", cleaned)
            putExtra(Intent.EXTRA_TEXT, message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val messagesPackage = "com.google.android.apps.messaging"
        val target = when {
            isInstalled(messagesPackage) -> messagesPackage
            else -> Telephony.Sms.getDefaultSmsPackage(context)
        }
        if (target != null) intent.setPackage(target)
        return try {
            context.startActivity(intent)
            val via = target ?: "the default messaging app"
            "Opened $via with a draft to $cleaned. Tap send there — Google Messages will use RCS if this chat supports it, otherwise SMS."
        } catch (e: Exception) {
            try {
                intent.setPackage(null)
                context.startActivity(intent)
                "Opened the default messaging app with a draft to $cleaned. Tap send."
            } catch (e2: Exception) {
                "Failed to open messaging app: ${e2.message}"
            }
        }
    }

    private fun firstNumberFor(name: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.Contacts.STARRED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cleanNumber(cursor.getString(0) ?: return null)
            }
        }
        return null
    }

    private fun locationLink(): String {
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            return "Error: location permission not granted"
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        var best: Location? = null
        for (provider in providers) {
            val enabled = try {
                manager.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
            if (!enabled && provider != LocationManager.FUSED_PROVIDER) continue
            val loc = try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        }
        val freshEnough = best != null && System.currentTimeMillis() - best.time < 120_000
        val location = if (freshEnough) best else requestFresh(manager, providers) ?: best
        if (location == null) {
            return "Error: could not read location. Turn on location and try again."
        }
        return "https://maps.google.com/?q=${location.latitude},${location.longitude}"
    }

    private fun requestFresh(manager: LocationManager, providers: List<String>): Location? {
        val provider = providers.firstOrNull { name ->
            try {
                manager.isProviderEnabled(name)
            } catch (_: Exception) {
                false
            }
        } ?: return null
        val latch = CountDownLatch(1)
        var found: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                found = location
                manager.removeUpdates(this)
                latch.countDown()
            }
        }
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            latch.await(12, TimeUnit.SECONDS)
        } catch (_: SecurityException) {
            return null
        } finally {
            try {
                manager.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        return found
    }

    /** SMS inbox only. RCS history lives inside Google Messages and is not readable here. */
    fun readRecentMessages(limit: Int = 5): String {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return "Error: READ_SMS permission not granted. RCS chats cannot be read by this app; only the SMS inbox is available."
        }
        val results = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"),
            null, null, "date DESC"
        )
        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                val address = it.getString(0) ?: "?"
                val body = it.getString(1) ?: ""
                results.add("From $address: $body")
                count++
            }
        }
        val header = "SMS inbox only. For RCS, use message notifications:"
        val sms = if (results.isEmpty()) "$header none" else "$header\n" + results.joinToString("\n")
        val notes = MessageNotificationListener.recentText(limit)
        return "$sms\n\nMessage notifications (RCS and SMS as shown by the messaging app):\n$notes"
    }

    private fun isInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun cleanNumber(number: String): String {
        return number.trim().replace(" ", "").replace("-", "")
    }
}
