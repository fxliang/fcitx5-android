/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import org.fcitx.fcitx5.android.input.config.ConfigProviders

interface FontProviderApi {
    fun clearCache()
    val fontTypefaceMap: MutableMap<String, Typeface?>
    val fontSizeMap: MutableMap<String, Float>

    fun resolveTypeface(key: String, current: Typeface? = null): Typeface {
        return fontTypefaceMap[key]
            ?: fontTypefaceMap["font"]
            ?: current
            ?: Typeface.DEFAULT
    }
}

object FontProviders {
    @Volatile
    var provider: FontProviderApi = DefaultFontProvider()
        set(value) {
            field = value
            synchronized(fontSizeResultCache) {
                fontSizeResultCache.clear()
            }
        }

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
        needsRefresh = true
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
        needsRefresh = true
    }

    /**
     * Check and clear refresh flag. Call when keyboard loads.
     * @return true if font changed and keyboard needs refresh
     */
    fun checkAndClearRefreshFlag(): Boolean {
        if (!needsRefresh) return false
        needsRefresh = false
        return true
    }

    fun clearCache() {
        provider.clearCache()
        synchronized(fontSizeResultCache) {
            fontSizeResultCache.clear()
        }
        needsRefresh = true
    }

    val fontTypefaceMap: MutableMap<String, Typeface?>
        get() = provider.fontTypefaceMap

    val fontSizeMap: MutableMap<String, Float>
        get() = provider.fontSizeMap

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
            fontSizeResultCache[cacheKey] = resolved
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
     * Returns true if current refresh flag is set, without consuming it.
     */
    fun needsRefresh(): Boolean = needsRefresh

    /**
     * Preload fonts asynchronously. Call this before keyboard is shown.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        if (provider is DefaultFontProvider) {
            (provider as DefaultFontProvider).preloadFontsAsync(onComplete)
        } else {
            // For other providers, just return cached map immediately
            onComplete?.invoke(fontTypefaceMap)
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
