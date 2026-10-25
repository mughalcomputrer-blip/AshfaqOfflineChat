package com.ashfaq.offlinechat

import android.content.Context
import org.json.JSONObject

/**
 * The people this phone has connected with before — the "friends
 * list" behind the 👥 header button — and the only place that knows
 * how they are stored.
 *
 * A friend is a person's base display name (no endpoint-name suffix,
 * no host marker — ChatManager works that out before recording), so
 * the list survives the other phone picking a new random suffix on
 * every connection. What is kept, in the app's shared preferences,
 * is one small JSON object: base name -> the time (epoch millis) of
 * the last connection with that person. Only the [MAX_FRIENDS] most
 * recent are kept; connecting with someone again simply refreshes
 * their time.
 */
object FriendsStore {

    private const val PREFS_NAME = "offline_chat_prefs"
    private const val KEY_FRIENDS = "friends_json"
    private const val MAX_FRIENDS = 20

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Remembers [baseName] as connected with just now. Blank names —
     * and names that still carry the host marker, which a real base
     * name never does — are skipped: there is nobody sensible to
     * remember then.
     */
    fun record(context: Context, baseName: String) {
        val name = baseName.trim()
        if (name.isEmpty() || name.contains('\uFFFF')) return
        val friends = readAll(context)
        friends[name] = System.currentTimeMillis()
        writeAll(context, friends)
    }

    /**
     * All remembered friends as (base name, last connected millis),
     * most recent first. Empty when nobody has connected yet, or when
     * what was stored is damaged — a broken list must never stop the
     * app from opening.
     */
    fun list(context: Context): List<Pair<String, Long>> =
        readAll(context).entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    private fun readAll(context: Context): LinkedHashMap<String, Long> {
        val result = LinkedHashMap<String, Long>()
        val text = prefs(context).getString(KEY_FRIENDS, null) ?: return result
        try {
            val json = JSONObject(text)
            val names = json.keys()
            while (names.hasNext()) {
                val name = names.next()
                val time = json.optLong(name, 0L)
                if (name.isNotBlank() && time > 0L) result[name] = time
            }
        } catch (e: Exception) {
            // Damaged JSON: start over with an empty list.
            return LinkedHashMap()
        }
        return result
    }

    /** Saves at most the [MAX_FRIENDS] most recently connected friends. */
    private fun writeAll(context: Context, friends: Map<String, Long>) {
        val kept = friends.entries
            .sortedByDescending { it.value }
            .take(MAX_FRIENDS)
        val json = JSONObject()
        for ((name, time) in kept) {
            json.put(name, time)
        }
        prefs(context).edit().putString(KEY_FRIENDS, json.toString()).apply()
    }
}
