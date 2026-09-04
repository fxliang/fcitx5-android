/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.content.Context
import android.graphics.Typeface
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import kotlin.math.roundToInt

interface FontProviderApi {
    fun clearCache()
    val fontTypefaceMap: MutableMap<String, Typeface?>
    val fontSizeMap: MutableMap<String, Float>

    /**
     * Monotonic revision of the served custom-font data. It changes only when the
     * resolved fonts actually change (not on every reload attempt), so callers can
     * rebuild font-dependent views exactly once per real change.
     */
    val fontDataVersion: Long
        get() = 0L

    fun resolveTypeface(key: String, current: Typeface? = null): Typeface {
        return fontTypefaceMap[key]
            ?: fontTypefaceMap["font"]
            ?: current
            ?: Typeface.DEFAULT
    }
}

object FontProviders {
    /**
     * Font key for the candidate comment (e.g. Zhuyin / shape-code breakdown).
     * Comments are usually longer than the candidate text itself, so they get
     * their own typeface and a smaller size (see [DEFAULT_COMMENT_FONT_SIZE]).
     */
    const val KEY_COMMENT_FONT = "comment_font"

    /**
     * Default comment font size in sp. Smaller than the candidate font size
     * so long comments stay readable without dominating the candidate bar.
     */
    const val DEFAULT_COMMENT_FONT_SIZE = 14f

    @Volatile
    var provider: FontProviderApi = DefaultFontProvider()
        set(value) {
            field = value
            synchronized(fontSizeResultCache) {
                fontSizeResultCache.clear()
            }
        }

    private val refreshLock = Any()
    @Volatile
    private var needsRefresh = false
    private val fontSizeResultCache = HashMap<String, Float>()

    init {
        ensureListenerRegistered()
    }

    private fun ensureListenerRegistered() {
        ConfigProviders.addFontsetListener {
            handleFontsetChanged()
        }
    }

    private fun handleFontsetChanged() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
        }
        synchronized(refreshLock) {
            needsRefresh = true
        }
    }

    /**
     * Mark refresh needed after saving fontset in settings.
     * Keyboard will refresh on next show via checkAndClearRefreshFlag().
     */
    fun markNeedsRefresh() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
        }
        synchronized(refreshLock) {
            needsRefresh = true
        }
    }

    /**
     * Check and clear refresh flag. Call when keyboard loads.
     * @return true if font changed and keyboard needs refresh
     */
    fun checkAndClearRefreshFlag(): Boolean = synchronized(refreshLock) {
        val result = needsRefresh
        needsRefresh = false
        result
    }

    fun clearCache() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
        }
        synchronized(refreshLock) {
            needsRefresh = true
        }
    }

    val fontTypefaceMap: MutableMap<String, Typeface?>
        get() = provider.fontTypefaceMap

    val fontSizeMap: MutableMap<String, Float>
        get() = provider.fontSizeMap

    /**
     * Revision of the currently served custom-font data; see [FontProviderApi.fontDataVersion].
     */
    val fontGeneration: Long
        get() = provider.fontDataVersion

    /**
     * Get font size for a specific key with fallback logic.
     * @param key The font key (e.g., "key_main_font", "cand_font")
     * @param default Default size if not configured
     * @return Font size in sp
     */
    fun getFontSize(key: String, default: Float): Float {
        val cacheKey = "$key|$default"
        synchronized(fontSizeResultCache) {
            fontSizeResultCache[cacheKey]?.let { return it }
        }

        val sizeMap = provider.fontSizeMap

        // First try to get specific font size for this key (e.g., "key_main_font_size"),
        // then fallback to key itself (backward compatibility), finally default.
        // Note: We intentionally do NOT fallback to "font_size" to avoid
        // overriding specific defaults (e.g., key_main_font=23sp, key_alt_font=10.67sp)
        val specificSize = sizeMap["${key}_size"]
        val size = sizeMap[key]
        val resolved = when {
            specificSize != null && specificSize in 8f..72f -> specificSize
            size != null && size in 8f..72f -> size
            else -> default
        }
        synchronized(fontSizeResultCache) {
            // The default provider loads font sizes asynchronously. Do not retain a
            // fallback calculated from its temporary empty map, or it can mask the
            // configured value after preload completes.
            if (sizeMap.isNotEmpty()) {
                fontSizeResultCache[cacheKey] = resolved
            }
        }
        return resolved
    }

    /**
     * Resolve typeface with fallback chain:
     * specific key -> global "font" -> current view typeface (if provided) -> system default.
     */
    fun resolveTypeface(key: String, current: Typeface? = null): Typeface {
        return provider.resolveTypeface(key, current)
    }

    /**
     * Resolve the candidate comment typeface with the same fallback chain as
     * [resolveTypeface] but starting from the dedicated comment font key:
     * comment_font -> cand_font -> current -> system default.
     */
    fun resolveCommentTypeface(current: Typeface? = null): Typeface =
        resolveTypeface(KEY_COMMENT_FONT, resolveTypeface("cand_font", current))

    /**
     * Candidate comment font size in pixels (sp * scaledDensity).
     * See [DEFAULT_COMMENT_FONT_SIZE] for the default size in sp.
     */
    fun commentFontSizePx(context: Context): Int =
        (getFontSize(KEY_COMMENT_FONT, DEFAULT_COMMENT_FONT_SIZE) *
            context.resources.displayMetrics.scaledDensity).roundToInt()

    /**
     * Returns true if current refresh flag is set, without consuming it.
     */
    fun needsRefresh(): Boolean = needsRefresh

    /**
     * Preload fonts asynchronously. Call this before keyboard is shown.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        val completion: (MutableMap<String, Typeface?>) -> Unit = { fonts ->
            synchronized(fontSizeResultCache) {
                fontSizeResultCache.clear()
            }
            onComplete?.invoke(fonts)
        }
        if (provider is DefaultFontProvider) {
            (provider as DefaultFontProvider).preloadFontsAsync(completion)
        } else {
            completion(fontTypefaceMap)
        }
    }

    /**
     * Check if user has custom fonts configured in fontset.json.
     * @return true if any custom font is configured, false if using system default fonts
     */
    fun hasCustomFonts(): Boolean {
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot().getOrNull() ?: return false
        // Check if any font path is non-empty
        return snapshot.value.values.flatten().any { it.trim().isNotEmpty() }
    }

    /**
     * Check if user has custom font sizes configured in fontset.json.
     * @return true if any custom font size is configured
     */
    fun hasCustomFontSizes(): Boolean {
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot().getOrNull() ?: return false
        // Check if any font size key exists with valid value
        val sizeKeys = snapshot.value.keys.filter { it.endsWith("_size") }
        return sizeKeys.any { key ->
            snapshot.value[key]?.firstOrNull()?.toFloatOrNull() != null
        }
    }
}
