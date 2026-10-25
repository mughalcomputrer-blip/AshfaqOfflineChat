package com.ashfaq.offlinechat

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * The optional 4-digit PIN that locks the app at start, and the only
 * place that knows how it is stored.
 *
 * The PIN itself is never saved: a fresh random salt is made when
 * the PIN is set, and what goes into the shared preferences is the
 * SHA-256 hash of salt + PIN alongside the salt, so checking an
 * entry means redoing that hash.
 *
 * Be clear about what this is: a casual lock that keeps a picked-up
 * phone out of the chat. It is NOT encryption — the saved chat on
 * the phone is untouched, and anyone determined enough to read the
 * app's private files could get at it without the PIN.
 */
object PinLock {

    private const val PREFS_NAME = "offline_chat_prefs"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether a PIN is currently set on this phone. */
    fun isSet(context: Context): Boolean {
        val p = prefs(context)
        return p.getString(KEY_PIN_SALT, null) != null &&
            p.getString(KEY_PIN_HASH, null) != null
    }

    /** Stores [pin] as a salted hash, replacing any PIN already set. */
    fun setPin(context: Context, pin: String) {
        val salt = UUID.randomUUID().toString()
        prefs(context).edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash(salt, pin))
            .apply()
    }

    /** True when [pin] matches the stored one. False when none is set. */
    fun verify(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        // MessageDigest.isEqual compares without an early exit, so
        // the check does not leak how much of the hash matched.
        return MessageDigest.isEqual(
            hash(salt, pin).toByteArray(Charsets.UTF_8),
            stored.toByteArray(Charsets.UTF_8)
        )
    }

    /** Drops the stored PIN; the app starts unlocked again. */
    fun remove(context: Context) {
        prefs(context).edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .apply()
    }

    private fun hash(salt: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
