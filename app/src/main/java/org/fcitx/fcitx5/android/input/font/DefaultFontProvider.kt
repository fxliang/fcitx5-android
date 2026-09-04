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
import java.util.concurrent.Executors

class DefaultFontProvider : FontProviderApi {
    private companion object {
        const val CONFIG_READ_RETRY_DELAY_MS = 1_000L
        const val MAX_CONFIG_READ_RETRIES = 5
        const val MAX_FONT_FILE_RETRIES = 3
    }

    private data class FontConfig(
        val paths: Map<String, List<String>>,
        val fontsDir: File
    )

    @Volatile
    private var cachedFontConfig: FontConfig? = null
    @Volatile
    private var fontConfigRead = false
    private var nextFontConfigReadAt = 0L

    // Typefaces currently served to the UI. This map is never emptied while a fontset
    // reload is in flight: the reload builds a fresh map on the worker thread and swaps
    // it in atomically once complete. Views created between clearCache() and the swap
    // therefore keep resolving to the previous custom fonts instead of falling back to
    // the system default (which used to bake "lost font" rows into the reusable rows
    // cache when a layout reload happened during the reload window).
    @Volatile
    private var servedFontTypefaceMap: MutableMap<String, Typeface?> = mutableMapOf()
    private val cachedTypefaceByPaths = mutableMapOf<List<String>, Typeface?>()
    private val retryableFontKeys = mutableMapOf<String, Int>()
    private var configReadRetriesLeft = MAX_CONFIG_READ_RETRIES
    @Volatile
    private var cachedFontSizeMap: MutableMap<String, Float>? = null
    @Volatile
    private var isLoading = false
    private var preloadPending = false
    private var cacheGeneration = 0L

    // Revision of the served font data. It only changes when the served content actually
    // changes (a publish with different content, e.g. after a fontset edit or when a
    // previously missing font file becomes available), so consumers can refresh exactly
    // once per real change instead of on every preload retry tick.
    @Volatile
    private var fontGeneration = 0L
    private val preloadCallbacks = mutableListOf<(MutableMap<String, Typeface?>) -> Unit>()
    // One background worker reused across preloads; fontset edits and retry ticks can
    // otherwise spawn several short-lived threads in a row.
    private val preloadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FcitxFontPreload").apply { isDaemon = true }
    }

    override val fontDataVersion: Long
        get() = fontGeneration

    override fun clearCache() {
        synchronized(this) {
            cacheGeneration++
            if (isLoading) preloadPending = true
            cachedFontConfig = null
            fontConfigRead = false
            nextFontConfigReadAt = 0L
            cachedTypefaceByPaths.clear()
            retryableFontKeys.clear()
            configReadRetriesLeft = MAX_CONFIG_READ_RETRIES
            // servedFontTypefaceMap and cachedFontSizeMap are intentionally kept: they
            // keep serving the previous custom fonts until the reload publishes their
            // replacement, so a fontset change never renders the system default font as
            // an intermediate state. The in-flight worker observes the generation change
            // before publishing.
        }
    }

    /**
     * Preload fonts asynchronously to avoid blocking UI thread.
     * Call this when keyboard is about to show.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        var settledSnapshot: MutableMap<String, Typeface?>? = null
        val generationOrNull: Long? = synchronized(this) {
            if (isLoading) {
                onComplete?.let(preloadCallbacks::add)
                preloadPending = true
                null
            } else if (SystemClock.elapsedRealtime() < nextFontConfigReadAt) {
                null
            } else if (fontConfigRead && retryableFontKeys.isEmpty()) {
                // Already settled: hand the current map to the caller synchronously so a
                // refresh requester never waits on a load that will never start. The
                // callback is invoked outside the lock below.
                settledSnapshot = servedFontTypefaceMap.toMutableMap()
                null
            } else {
                onComplete?.let(preloadCallbacks::add)
                isLoading = true
                cacheGeneration
            }
        }
        val generation = generationOrNull ?: run {
            settledSnapshot?.let { snapshot -> onComplete?.invoke(snapshot) }
            return
        }

        preloadExecutor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val (activeGeneration, config) = fontConfigSnapshot()
                val keys = config?.paths?.keys?.filterNot { it.endsWith("_size") }.orEmpty()
                if (activeGeneration != generation) return@execute
                cacheFontSizes(config, activeGeneration)
                // Build the replacement map in isolation; the currently served map stays
                // untouched so concurrent view creation keeps seeing the old fonts.
                val fresh = mutableMapOf<String, Typeface?>()
                config?.let { fontConfig ->
                    keys.forEach { key ->
                        // clearCache() during a load must stop the remaining file I/O:
                        // the publish step below would discard the result anyway.
                        if (cacheGeneration != activeGeneration) return@execute
                        loadTypeface(key, fontConfig, activeGeneration, fresh)
                    }
                }
                val published: Pair<List<(MutableMap<String, Typeface?>) -> Unit>, MutableMap<String, Typeface?>>? =
                    synchronized(this) {
                        if (cacheGeneration != generation) {
                            null
                        } else {
                            // A failed/absent config read must never wipe already served
                            // fonts: keep publishing the previous data (and revision) so
                            // the UI never flashes back to the system default. Only
                            // publish when a config was resolved, or when nothing has
                            // ever been served yet (initial empty state).
                            if (config != null || servedFontTypefaceMap.isEmpty()) {
                                if (servedFontTypefaceMap != fresh) {
                                    // Swap before bumping the revision: a reader that sees the
                                    // new revision must never be able to rebuild rows from the
                                    // previous map and cache them under the new revision.
                                    servedFontTypefaceMap = fresh
                                    fontGeneration++
                                }
                            }
                            preloadCallbacks.toList().also { preloadCallbacks.clear() } to
                                servedFontTypefaceMap.toMutableMap()
                        }
                    }
                if (published != null) {
                    val (callbacks, fonts) = published
                    Log.i(
                        "FcitxColdStart",
                        "font preload keys=${keys.size} duration=${SystemClock.elapsedRealtime() - startedAt}ms"
                    )
                    callbacks.forEach { it(fonts) }
                }
            } finally {
                val retry = synchronized(this) {
                    val shouldRetry = preloadPending && cacheGeneration != generation
                    preloadPending = false
                    isLoading = false
                    shouldRetry
                }
                if (retry) preloadFontsAsync()
            }
        }
    }

    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            requestPreloadIfNeeded()
            return synchronized(this) { servedFontTypefaceMap.toMutableMap() }
        }

    override fun resolveTypeface(key: String, current: Typeface?): Typeface {
        val resolved = synchronized(this) {
            servedFontTypefaceMap[key]
                ?: servedFontTypefaceMap["font"]
        }
        if (resolved != null) return resolved
        requestPreloadIfNeeded()
        return current ?: Typeface.DEFAULT
    }

    private fun requestPreloadIfNeeded() {
        val needsPreload = synchronized(this) {
            (!fontConfigRead || retryableFontKeys.isNotEmpty()) &&
                !isLoading && SystemClock.elapsedRealtime() >= nextFontConfigReadAt
        }
        if (needsPreload) preloadFontsAsync()
    }

    private fun fontConfigSnapshot(): Pair<Long, FontConfig?> {
        val generation = synchronized(this) {
            cachedFontConfig?.let { return cacheGeneration to it }
            cacheGeneration
        }
        val loaded = readFontConfig()
        return synchronized(this) {
            if (cacheGeneration != generation) {
                cacheGeneration to null
            } else {
                val config = cachedFontConfig ?: loaded
                if (config != null) {
                    cachedFontConfig = config
                    fontConfigRead = true
                    nextFontConfigReadAt = 0L
                } else if (fontsetConfigAbsent()) {
                    // No fontset file yet (fresh install): settle on the empty config
                    // instead of polling forever. Saving the fontset in settings calls
                    // clearCache() which re-reads the now-existing file.
                    fontConfigRead = true
                    nextFontConfigReadAt = 0L
                } else if (configReadRetriesLeft > 0) {
                    // The file exists but could not be read (transient I/O or decode
                    // failure): retry a bounded number of times.
                    configReadRetriesLeft--
                    fontConfigRead = false
                    nextFontConfigReadAt = SystemClock.elapsedRealtime() + CONFIG_READ_RETRY_DELAY_MS
                } else {
                    // Give up until the next clearCache() instead of retrying forever.
                    fontConfigRead = true
                    nextFontConfigReadAt = 0L
                }
                generation to config
            }
        }
    }

    private fun readFontConfig(): FontConfig? {
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot().getOrNull() ?: return null
        val fontsDir = snapshot.file?.parentFile ?: return null
        return FontConfig(snapshot.value, fontsDir)
    }

    /**
     * True when no fontset file exists at all (fresh install). A missing file is a
     * settled empty configuration, not a transient failure worth retrying.
     */
    private fun fontsetConfigAbsent(): Boolean =
        ConfigProviders.provider.fontsetFile()?.exists() != true

    private fun loadTypeface(
        key: String,
        config: FontConfig,
        generation: Long,
        target: MutableMap<String, Typeface?>
    ): Typeface? {
        val configuredPaths = config.paths[key].orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val validPaths = configuredPaths
            .map { File(config.fontsDir, it) }
            .filter(File::exists)
            .map(File::getAbsolutePath)

        if (configuredPaths.isNotEmpty() && validPaths.isEmpty()) {
            synchronized(this) {
                if (cacheGeneration == generation) {
                    val retriesLeft = retryableFontKeys[key] ?: MAX_FONT_FILE_RETRIES
                    if (retriesLeft > 0) {
                        retryableFontKeys[key] = retriesLeft - 1
                        nextFontConfigReadAt = SystemClock.elapsedRealtime() + CONFIG_READ_RETRY_DELAY_MS
                    } else {
                        // Give up on this key until the next clearCache(); a missing
                        // custom font file is not transient enough to poll forever.
                        retryableFontKeys.remove(key)
                    }
                }
            }
            return null
        }

        synchronized(this) {
            if (cacheGeneration != generation) return null
            if (target.containsKey(key)) return target[key]
            if (cachedTypefaceByPaths.containsKey(validPaths)) {
                val cached = cachedTypefaceByPaths[validPaths]
                target[key] = cached
                return cached
            }
        }

        val startedAt = SystemClock.elapsedRealtime()
        val loaded = runCatching {
            when {
                validPaths.isEmpty() -> null
                validPaths.size == 1 || android.os.Build.VERSION.SDK_INT < 29 ->
                    Typeface.createFromFile(validPaths.first())
                else -> buildCustomFallbackTypeface(validPaths)
            }
        }.getOrNull()

        synchronized(this) {
            if (cacheGeneration != generation) return null
            cachedTypefaceByPaths[validPaths] = loaded
            target[key] = loaded
            retryableFontKeys.remove(key)
            if (retryableFontKeys.isEmpty()) nextFontConfigReadAt = 0L
        }
        if (validPaths.isNotEmpty()) {
            Log.i(
                "FcitxColdStart",
                "font key=$key files=${validPaths.size} loaded=${loaded != null} " +
                    "duration=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }
        return loaded
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
            cachedFontSizeMap?.let { return it }
            requestPreloadIfNeeded()
            return mutableMapOf()
        }

    private fun cacheFontSizes(config: FontConfig?, generation: Long) {
        val sizes = config?.paths
            ?.filterKeys { it.endsWith("_size") }
            ?.mapValues { (_, values) ->
                values.firstOrNull()?.trim()?.toFloatOrNull()?.coerceIn(8f, 72f)
            }
            ?.filterValues { it != null }
            ?.mapValues { it.value!! }
            ?.toMutableMap()
            ?: mutableMapOf()
        synchronized(this) {
            // Keep the previous size map when the config could not be resolved; an
            // empty map would make every size fall back to its default until the
            // next successful reload.
            if (cacheGeneration == generation && config != null) cachedFontSizeMap = sizes
        }
    }
}
