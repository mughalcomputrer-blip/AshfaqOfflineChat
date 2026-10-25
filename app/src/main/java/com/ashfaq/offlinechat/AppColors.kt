package com.ashfaq.offlinechat

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/**
 * The app's palettes — the original light one and the dark one — and
 * the single place that decides which of them the screen uses.
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
 *
 * On top of the light/dark mode sit the color themes (update #12):
 * four skins that re-color only the app's identity colors — the
 * header, the status bar, the accent, our own message bubbles and
 * the quote strip inside them. Everything else (the screen
 * background, received bubbles, all text colors, the input bar, the
 * status colors) is the same in every theme. "Ocean" is the original
 * navy + teal look, and its palette entries below are exactly the
 * colors the app has always used, so with Ocean chosen — the
 * default — every screen renders precisely as before themes existed.
 */
object AppColors {

    private const val PREFS_NAME = "offline_chat_prefs"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_THEME_ID = "theme_id"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)

    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, dark)
            .apply()
    }

    // ---------- Color themes ----------

    const val THEME_OCEAN = "ocean"
    const val THEME_FOREST = "forest"
    const val THEME_SUNSET = "sunset"
    const val THEME_ROYAL = "royal"

    /** The themes in the order the picker dialog lists them. */
    val THEME_IDS: List<String> =
        listOf(THEME_OCEAN, THEME_FOREST, THEME_SUNSET, THEME_ROYAL)

    /** The chosen theme's id; an unknown stored value means Ocean. */
    fun themeId(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, THEME_OCEAN)
        return if (stored != null && PALETTES.containsKey(stored)) {
            stored
        } else {
            THEME_OCEAN
        }
    }

    fun setThemeId(context: Context, themeId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_THEME_ID,
                if (PALETTES.containsKey(themeId)) themeId else THEME_OCEAN
            )
            .apply()
    }

    @StringRes
    fun themeNameRes(themeId: String): Int = when (themeId) {
        THEME_FOREST -> R.string.theme_forest
        THEME_SUNSET -> R.string.theme_sunset
        THEME_ROYAL -> R.string.theme_royal
        else -> R.string.theme_ocean
    }

    /**
     * One theme in one mode: the five identity colors it re-skins.
     * [quoteSentBg] follows Ocean's relationship to the bubble —
     * a shade darker than [bubbleSent] in light mode, a shade
     * lighter in dark mode, where the dark quote resources already
     * sat lighter than the dark bubble.
     */
    private data class Palette(
        @ColorInt val header: Int,
        @ColorInt val statusBar: Int,
        @ColorInt val accent: Int,
        @ColorInt val bubbleSent: Int,
        @ColorInt val quoteSentBg: Int
    )

    /** Theme id → (light palette, dark palette). */
    private val PALETTES: Map<String, Pair<Palette, Palette>> = mapOf(
        // Ocean — the original look. Every value here is exactly the
        // color resource the app used before themes: light header =
        // @color/primary, light status bar = @color/primary_dark,
        // light accent/bubble = @color/accent / @color/bubble_sent,
        // light quote = @color/quote_sent_bg; dark header/status bar
        // = @color/header_dark_mode, dark accent =
        // @color/accent_dark_mode, dark bubble =
        // @color/bubble_sent_dark_mode, dark quote =
        // @color/quote_sent_bg_dark_mode.
        THEME_OCEAN to Pair(
            Palette(
                header = 0xFF0B2B5B.toInt(),
                statusBar = 0xFF071F42.toInt(),
                accent = 0xFF12B5A5.toInt(),
                bubbleSent = 0xFF12B5A5.toInt(),
                quoteSentBg = 0xFF0D9E8B.toInt()
            ),
            Palette(
                header = 0xFF12222E.toInt(),
                statusBar = 0xFF12222E.toInt(),
                accent = 0xFF1CC8B4.toInt(),
                bubbleSent = 0xFF0B7563.toInt(),
                quoteSentBg = 0xFF0E8570.toInt()
            )
        ),
        // Forest — deep green header, vivid green bubbles.
        THEME_FOREST to Pair(
            Palette(
                header = 0xFF14532D.toInt(),
                statusBar = 0xFF0C3B1F.toInt(),
                accent = 0xFF1FA855.toInt(),
                bubbleSent = 0xFF1FA855.toInt(),
                quoteSentBg = 0xFF178544.toInt()
            ),
            Palette(
                header = 0xFF0D2818.toInt(),
                statusBar = 0xFF0D2818.toInt(),
                accent = 0xFF3ED97E.toInt(),
                bubbleSent = 0xFF0E6B45.toInt(),
                quoteSentBg = 0xFF128257.toInt()
            )
        ),
        // Sunset — deep maroon header, warm orange bubbles.
        THEME_SUNSET to Pair(
            Palette(
                header = 0xFF6D1A2D.toInt(),
                statusBar = 0xFF520F20.toInt(),
                accent = 0xFFE8641C.toInt(),
                bubbleSent = 0xFFE8641C.toInt(),
                quoteSentBg = 0xFFC95312.toInt()
            ),
            Palette(
                header = 0xFF33101C.toInt(),
                statusBar = 0xFF33101C.toInt(),
                accent = 0xFFFF9A3D.toInt(),
                bubbleSent = 0xFF9E4E14.toInt(),
                quoteSentBg = 0xFFB25F1E.toInt()
            )
        ),
        // Royal — deep purple header, violet bubbles.
        THEME_ROYAL to Pair(
            Palette(
                header = 0xFF3B1D6E.toInt(),
                statusBar = 0xFF2A1252.toInt(),
                accent = 0xFF7C53E8.toInt(),
                bubbleSent = 0xFF7C53E8.toInt(),
                quoteSentBg = 0xFF6842C9.toInt()
            ),
            Palette(
                header = 0xFF1E1440.toInt(),
                statusBar = 0xFF1E1440.toInt(),
                accent = 0xFFA78BFA.toInt(),
                bubbleSent = 0xFF5B3FA8.toInt(),
                quoteSentBg = 0xFF6D4FC0.toInt()
            )
        )
    )

    /** The palette for the chosen theme in the current mode. */
    private fun palette(context: Context): Palette {
        val (light, dark) = PALETTES.getValue(themeId(context))
        return if (isDark(context)) dark else light
    }

    /** Resolves a light/dark pair of color resources to a color value. */
    fun color(context: Context, @ColorRes light: Int, @ColorRes dark: Int): Int =
        ContextCompat.getColor(context, if (isDark(context)) dark else light)

    // ---------- Screen chrome ----------

    fun screenBackground(context: Context): Int = color(
        context, R.color.screen_background, R.color.screen_background_dark_mode
    )

    /** A theme color: the chosen theme's header, in the current mode. */
    fun headerBackground(context: Context): Int = palette(context).header

    fun headerText(context: Context): Int = color(
        context, R.color.on_primary, R.color.text_primary_dark_mode
    )

    /** A theme color: the chosen theme's status bar, in the current mode. */
    fun statusBar(context: Context): Int = palette(context).statusBar

    fun textPrimary(context: Context): Int = color(
        context, R.color.text_primary, R.color.text_primary_dark_mode
    )

    fun textSecondary(context: Context): Int = color(
        context, R.color.text_secondary, R.color.text_secondary_dark_mode
    )

    /** A theme color: the chosen theme's accent, in the current mode. */
    fun accent(context: Context): Int = palette(context).accent

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
    private fun bubbleSentDrawableRes(context: Context): Int =
        if (isDark(context)) R.drawable.bg_bubble_sent_dark
        else R.drawable.bg_bubble_sent

    @DrawableRes
    private fun quoteSentDrawableRes(context: Context): Int =
        if (isDark(context)) R.drawable.bg_quote_sent_dark
        else R.drawable.bg_quote_sent

    /**
     * The background of one of our own bubbles. With the Ocean theme
     * this is the app's original shape drawable, untouched; the other
     * themes get the same shape built in code (same corners — the
     * bubble's asymmetric tail included) with the palette's fill.
     */
    fun bubbleSentBackground(context: Context): Drawable =
        if (themeId(context) == THEME_OCEAN) {
            ContextCompat.getDrawable(context, bubbleSentDrawableRes(context))!!
        } else {
            shapedBackground(
                context,
                palette(context).bubbleSent,
                // bg_bubble_sent.xml's corners, in GradientDrawable's
                // order (top-left, top-right, bottom-right,
                // bottom-left): 18dp everywhere except the 6dp tail
                // corner at the bottom right.
                floatArrayOf(18f, 18f, 18f, 18f, 6f, 6f, 18f, 18f)
            )
        }

    /**
     * The quote strip inside one of our own bubbles, the same deal
     * as [bubbleSentBackground]: Ocean uses the original drawable,
     * the other themes a code-built copy of its 8dp rounded shape.
     */
    fun quoteSentBackground(context: Context): Drawable =
        if (themeId(context) == THEME_OCEAN) {
            ContextCompat.getDrawable(context, quoteSentDrawableRes(context))!!
        } else {
            shapedBackground(
                context,
                palette(context).quoteSentBg,
                // bg_quote_sent.xml: one 8dp radius on every corner.
                floatArrayOf(8f, 8f, 8f, 8f, 8f, 8f, 8f, 8f)
            )
        }

    /**
     * A plain rounded-rectangle shape drawable filled with [fill].
     * [cornerRadiiDp] holds the four corners' radii in dp, as
     * (x, y) pairs in GradientDrawable's order: top-left, top-right,
     * bottom-right, bottom-left.
     */
    private fun shapedBackground(
        context: Context,
        @ColorInt fill: Int,
        cornerRadiiDp: FloatArray
    ): Drawable {
        val radii = FloatArray(cornerRadiiDp.size) { i ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                cornerRadiiDp[i],
                context.resources.displayMetrics
            )
        }
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(fill)
        drawable.cornerRadii = radii
        return drawable
    }

    @DrawableRes
    fun bubbleReceivedDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_bubble_received_dark
        else R.drawable.bg_bubble_received

    @DrawableRes
    fun quoteReceivedDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_quote_received_dark
        else R.drawable.bg_quote_received

    @DrawableRes
    fun inputFieldDrawable(context: Context): Int =
        if (isDark(context)) R.drawable.bg_input_field_dark
        else R.drawable.bg_input_field
}
