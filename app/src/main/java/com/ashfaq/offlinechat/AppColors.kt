package com.ashfaq.offlinechat

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * The app's two palettes — the original light one and the dark one —
 * and the single place that decides which of them the screen uses.
 *
 * The layouts and the light colors in colors.xml are untouched: every
 * color or drawable whose look depends on the mode is resolved here,
 * from the "dark_mode" switch in the app's shared preferences (the
 * same file ChatManager keeps the display name in). MainActivity
 * paints the screen chrome from these functions in onCreate and the
 * message adapter paints every bubble the same way, so flipping the
 * switch and recreating the screen re-reads everything.
 *
 * With the switch off, every function returns exactly the resource
 * the app used before dark mode existed.
 */
object AppColors {

    private const val PREFS_NAME = "offline_chat_prefs"
    private const val KEY_DARK_MODE = "dark_mode"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)

    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, dark)
            .apply()
    }

    /** Resolves a light/dark pair of color resources to a color value. */
    fun color(context: Context, @ColorRes light: Int, @ColorRes dark: Int): Int =
        ContextCompat.getColor(context, if (isDark(context)) dark else light)

    // ---------- Screen chrome ----------

    fun screenBackground(context: Context): Int = color(
        context, R.color.screen_background, R.color.screen_background_dark_mode
    )

    fun headerBackground(context: Context): Int = color(
        context, R.color.primary, R.color.header_dark_mode
    )

    fun headerText(context: Context): Int = color(
        context, R.color.on_primary, R.color.text_primary_dark_mode
    )

    fun statusBar(context: Context): Int = color(
        context, R.color.primary_dark, R.color.header_dark_mode
    )

    fun textPrimary(context: Context): Int = color(
        context, R.color.text_primary, R.color.text_primary_dark_mode
    )

    fun textSecondary(context: Context): Int = color(
        context, R.color.text_secondary, R.color.text_secondary_dark_mode
    )

    fun accent(context: Context): Int = color(
        context, R.color.accent, R.color.accent_dark_mode
    )

    /**
     * Tint for the photo / file / mic buttons next to the input field:
     * navy in light mode (invisible on the dark input bar, so light
     * grey there).
     */
    fun iconTint(context: Context): Int = color(
        context, R.color.primary, R.color.text_secondary_dark_mode
    )

    fun inputBarBackground(context: Context): Int = color(
        context, R.color.input_bar_background, R.color.input_bar_background_dark_mode
    )

    /**
     * The status line colors. Callers pass one of the status_* light
     * resources, exactly as they always did; in dark mode its brighter
     * counterpart is used so the text stays readable on the dark
     * background.
     */
    fun statusColor(context: Context, @ColorRes lightRes: Int): Int {
        if (!isDark(context)) return ContextCompat.getColor(context, lightRes)
        val darkRes = when (lightRes) {
            R.color.status_connected -> R.color.status_connected_dark_mode
            R.color.status_error -> R.color.status_error_dark_mode
            else -> R.color.status_idle_dark_mode
        }
        return ContextCompat.getColor(context, darkRes)
    }

    // ---------- Message bubbles ----------

    fun bubbleReceivedText(context: Context): Int = color(
        context, R.color.bubble_received_text, R.color.bubble_received_text_dark_mode
    )

    fun videoPlaceholder(context: Context): Int = color(
        context, R.color.video_placeholder, R.color.video_placeholder_dark_mode
    )

    @DrawableRes
    fun bubbleSentDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_bubble_sent_dark
        else R.drawable.bg_bubble_sent

    @DrawableRes
    fun bubbleReceivedDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_bubble_received_dark
        else R.drawable.bg_bubble_received

    @DrawableRes
    fun quoteSentDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_quote_sent_dark
        else R.drawable.bg_quote_sent

    @DrawableRes
    fun quoteReceivedDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_quote_received_dark
        else R.drawable.bg_quote_received

    @DrawableRes
    fun inputFieldDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_input_field_dark
        else R.drawable.bg_input_field
}
