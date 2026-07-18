/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import java.io.File

class DefaultFontProvider : FontProviderApi {
    private data class FontConfig(
        val paths: Map<String, List<String>>,
        val fontsDir: File
    )

    @Volatile
    private var cachedFontConfig: FontConfig? = null
    private val cachedFontTypefaceMap = mutableMapOf<String, Typeface?>()
    private val cachedTypefaceByPaths = mutableMapOf<List<String>, Typeface?>()
    @Volatile
    private var cachedFontSizeMap: MutableMap<String, Float>? = null
    @Volatile
    private var isLoading = false

    override fun clearCache() {
        synchronized(this) {
            cachedFontConfig = null
            cachedFontTypefaceMap.clear()
            cachedTypefaceByPaths.clear()
            cachedFontSizeMap = null
            isLoading = false
        }
    }

    /**
     * Preload fonts asynchronously to avoid blocking UI thread.
     * Call this when keyboard is about to show.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        synchronized(this) {
            if (isLoading) return
            isLoading = true
        }

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = SystemClock.elapsedRealtime()
            val keys = synchronized(this) {
                loadFontConfigLocked()?.paths?.keys
                    ?.filterNot { it.endsWith("_size") }
                    .orEmpty()
            }
            keys.forEach { key ->
                synchronized(this) {
                    loadFontConfigLocked()?.let { loadTypefaceLocked(key, it) }
                }
            }
            val fonts = synchronized(this) {
                isLoading = false
                cachedFontTypefaceMap.toMutableMap()
            }
            Log.i(
                "FcitxColdStart",
                "font preload keys=${keys.size} duration=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            onComplete?.invoke(fonts)
        }, "FcitxFontPreload").start()
    }

    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            return synchronized(this) {
                val config = loadFontConfigLocked() ?: return@synchronized mutableMapOf()
                config.paths.keys
                    .filterNot { it.endsWith("_size") }
                    .forEach { loadTypefaceLocked(it, config) }
                cachedFontTypefaceMap.toMutableMap()
            }
        }

    override fun resolveTypeface(key: String, current: Typeface?): Typeface = synchronized(this) {
        val config = loadFontConfigLocked()
        val resolved = config?.let { loadTypefaceLocked(key, it) }
            ?: config?.let { loadTypefaceLocked("font", it) }
        resolved ?: current ?: Typeface.DEFAULT
    }

    private fun loadFontConfigLocked(): FontConfig? {
        cachedFontConfig?.let { return it }
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot().getOrNull() ?: return null
        val fontsDir = snapshot.file?.parentFile ?: return null
        return FontConfig(snapshot.value, fontsDir).also { cachedFontConfig = it }
    }

    private fun loadTypefaceLocked(key: String, config: FontConfig): Typeface? {
        if (cachedFontTypefaceMap.containsKey(key)) return cachedFontTypefaceMap[key]

        val validPaths = config.paths[key].orEmpty()
            .map { it.trim() }
            .map { File(config.fontsDir, it) }
            .filter(File::exists)
            .map(File::getAbsolutePath)
        val typeface = if (cachedTypefaceByPaths.containsKey(validPaths)) {
            cachedTypefaceByPaths[validPaths]
        } else {
            val startedAt = SystemClock.elapsedRealtime()
            val loaded = runCatching {
                when {
                    validPaths.isEmpty() -> null
                    validPaths.size == 1 || android.os.Build.VERSION.SDK_INT < 29 ->
                        Typeface.createFromFile(validPaths.first())
                    else -> buildCustomFallbackTypeface(validPaths)
                }
            }.getOrNull()
            cachedTypefaceByPaths[validPaths] = loaded
            if (validPaths.isNotEmpty()) {
                Log.i(
                    "FcitxColdStart",
                    "font key=$key files=${validPaths.size} loaded=${loaded != null} " +
                        "duration=${SystemClock.elapsedRealtime() - startedAt}ms"
                )
            }
            loaded
        }
        cachedFontTypefaceMap[key] = typeface
        return typeface
    }

    @androidx.annotation.RequiresApi(29)
    private fun buildCustomFallbackTypeface(
        validPaths: List<String>
    ): Typeface {
        val firstFamily = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(File(validPaths[0])).build()
        ).build()
        val builder = android.graphics.Typeface.CustomFallbackBuilder(firstFamily)
        for (i in 1 until validPaths.size) {
            val family = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(File(validPaths[i])).build()
            ).build()
            builder.addCustomFallback(family)
        }
        return builder.build()
    }

    override val fontSizeMap: MutableMap<String, Float>
        get() {
            // Fast path: lock-free volatile read.
            val cached = cachedFontSizeMap
            if (cached != null) return cached
            return synchronized(this) { loadSizeMapLocked() }
        }

    private fun loadSizeMapLocked(): MutableMap<String, Float> {
        val recheck = cachedFontSizeMap
        if (recheck != null) return recheck

        val snapshot = ConfigProviders
            .readFontsetPathMapSnapshot()
            .getOrNull() ?: run {
            cachedFontSizeMap = null
            return mutableMapOf()
        }
        cachedFontSizeMap = runCatching {
            snapshot.value
                .filterKeys { it.endsWith("_size") }
                .mapValues { (_, values) ->
                    runCatching {
                        values.firstOrNull()?.trim()
                            ?.toFloatOrNull()?.coerceIn(8f, 72f)
                    }.getOrNull()
                }
                .filterValues { it != null }
                .mapValues { it.value!! }
                .toMutableMap()
        }.getOrElse { mutableMapOf() }
        return cachedFontSizeMap ?: mutableMapOf()
    }
}
