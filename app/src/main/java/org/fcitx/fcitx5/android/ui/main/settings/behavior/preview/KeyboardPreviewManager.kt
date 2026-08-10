/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.AuxBarAction
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import splitties.dimensions.dp
import java.io.File

/**
 * Keyboard preview manager, responsible for previewing keyboard layouts.
 *
 * Main functions:
 * - [updatePreview] - Update keyboard preview
 * - [clear] - Clear preview keyboard
 *
 * How it works:
 * 1. Build in-memory JSON to store current layout
 * 2. Temporarily replace ConfigProvider with PreviewConfigProvider (provides in-memory JSON)
 * 3. Load TextKeyboard for preview (reads from in-memory JSON, no disk I/O)
 * 4. Restore original ConfigProvider
 *
 * Usage example:
 * ```kotlin
 * val previewManager = KeyboardPreviewManager(context, container, entries)
 * previewManager.updatePreview(layoutName, subModeLabel, fcitxConnection)
 * ```
 */
class KeyboardPreviewManager(
    private val context: Context,
    private val previewContainer: ViewGroup,
    private val entries: Map<String, List<List<Map<String, Any?>>>>,
    private val layoutHeightPercentOverrideProvider: (String) -> Int? = { null },
    private val layoutAuxBarConfigProvider: (String) -> AuxBarConfig? = { null },
    private val layoutAuxBarKeysProvider: (String) -> List<Map<String, Any?>> = { emptyList() },
    private val subModeNameToIdProvider: () -> Map<String, String> = { emptyMap() }
) {
    private var previewKeyboard: TextKeyboard? = null
    private val previewBlurMask by lazy { PreviewKeyBlurMaskView(context) }

    /**
     * Update keyboard preview.
     *
     * @param layoutName Layout name
     * @param previewSubModeLabel Submode label, null for default
     * @param fcitxConnection Fcitx connection for getting current input method
     */
    fun updatePreview(
        layoutName: String,
        previewSubModeLabel: String?,
        fcitxConnection: FcitxConnection
    ) {
        previewContainer.removeAllViews()

        // Try to load submode-specific layout first
        val effectiveSubModeKey = resolveEffectiveSubModeKey(layoutName, previewSubModeLabel)
        val effectiveLayoutKey = effectiveSubModeKey ?: layoutName
        val rows = entries[effectiveLayoutKey] ?: return

        val theme = ThemeManager.activeTheme
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        previewContainer.background = theme.backgroundDrawable(keyBorder)
        previewBlurMask.bindKeyboard(null)

        // Remove old keyboard view
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }

        // Build submode map with all available submodes for this layout
        val subModeMap = buildSubModeMap(layoutName, effectiveSubModeKey, rows, previewSubModeLabel)

        val tempJson = JsonObject(mapOf(layoutName to JsonObject(subModeMap)))

        // Temporarily replace the layout file and reload
        val provider = ConfigProviders.provider
        val tempProvider = PreviewConfigProvider(tempJson, provider)

        ConfigProviders.provider = tempProvider
        TextKeyboard.clearCachedKeyDefLayouts()

        // Save the original IME state to restore later.
        // Keep this purely in-process to avoid blocking Binder calls on UI thread.
        val originalIme = TextKeyboard.ime
        var appliedPreviewIme: org.fcitx.fcitx5.android.core.InputMethodEntry? = null

        try {
            appliedPreviewIme = createKeyboardPreview(layoutName, previewSubModeLabel, effectiveSubModeKey, fcitxConnection)
        } catch (e: Exception) {
            android.util.Log.e("KeyboardPreview", "Failed to create keyboard preview for layout: $layoutName, submode: $previewSubModeLabel", e)
            showError(e.message ?: "Unknown error")
        } finally {
            // Restore original provider and IME state
            ConfigProviders.provider = provider
            TextKeyboard.clearCachedKeyDefLayouts()
            // Avoid clobbering real IME updates that may happen while preview is rendering.
            if (appliedPreviewIme != null && TextKeyboard.ime === appliedPreviewIme) {
                TextKeyboard.ime = originalIme
            }
        }
    }

    /**
     * Build submode map for temporary JSON file.
     */
    private fun buildSubModeMap(
        layoutName: String,
        subModeKey: String?,
        currentRows: List<List<Map<String, Any?>>>,
        previewSubModeLabel: String?
    ): MutableMap<String, JsonElement> {
        val subModeMap = mutableMapOf<String, JsonElement>()

        val currentRowsArray = JsonArray(currentRows.map { row ->
            JsonArray(row.map { key ->
                JsonObject(key.entries.associate { (k, v) ->
                    k to LayoutJsonUtils.convertToJsonProperty(v)
                })
            })
        })

        val effectiveKey = subModeKey ?: layoutName
        val auxBarConfig = if (layoutAuxBarConfigProvider(effectiveKey)?.position == org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.AbovePreedit) null
            else layoutAuxBarConfigProvider(effectiveKey)
        val auxBarKeys = layoutAuxBarKeysProvider(effectiveKey)
        val meta = if (auxBarConfig != null) {
            val posStr = when (auxBarConfig.position) {
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Top -> "top"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Bottom -> "bottom"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Left -> "left"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.Right -> "right"
                org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition.AbovePreedit -> "top"
            }
            val auxBarObj = JsonObject(
                mutableMapOf<String, JsonElement>(
                    "position" to JsonPrimitive(posStr),
                    "size_percent" to JsonPrimitive(auxBarConfig.sizePercent)
                ).apply {
                    if (auxBarKeys.isNotEmpty()) {
                        this["keys"] = JsonArray(auxBarKeys.map { keyMap ->
                            JsonObject(keyMap.entries.associate { (k, v) ->
                                k to LayoutJsonUtils.convertToJsonProperty(v)
                            })
                        })
                    }
                }
            )
            JsonObject(mapOf("aux_bar" to auxBarObj))
        } else if (auxBarKeys.isNotEmpty()) {
            JsonObject(mapOf(
                "aux_bar" to JsonObject(mapOf(
                    "keys" to JsonArray(auxBarKeys.map { keyMap ->
                        JsonObject(keyMap.entries.associate { (k, v) ->
                            k to LayoutJsonUtils.convertToJsonProperty(v)
                        })
                    })
                ))
            ))
        } else null

        if (subModeKey != null && entries.containsKey(subModeKey)) {
            // Editing a submode layout - add it with its label
            val subModeEntry: JsonElement = if (meta != null) {
                JsonObject(mapOf("__meta__" to meta, "default" to currentRowsArray))
            } else {
                currentRowsArray
            }
            subModeMap[previewSubModeLabel ?: "default"] = subModeEntry
            // Also add default layout if it exists (for fallback)
            val defaultRows = entries[layoutName]
            if (defaultRows != null) {
                val defaultRowsArray = JsonArray(defaultRows.map { row ->
                    JsonArray(row.map { key ->
                        JsonObject(key.entries.associate { (k, v) ->
                            k to LayoutJsonUtils.convertToJsonProperty(v)
                        })
                    })
                })
                subModeMap["default"] = defaultRowsArray
            }
        } else {
            // Editing default layout
            subModeMap["default"] = if (meta != null) {
                JsonObject(mapOf("__meta__" to meta, "default" to currentRowsArray))
            } else {
                currentRowsArray
            }
        }

        return subModeMap
    }

    /**
     * Create keyboard preview view.
     */
    private fun createKeyboardPreview(
        layoutName: String,
        previewSubModeLabel: String?,
        effectiveSubModeKey: String?,
        fcitxConnection: FcitxConnection
    ): org.fcitx.fcitx5.android.core.InputMethodEntry {
        val theme = ThemeManager.activeTheme

        previewKeyboard = TextKeyboard(context, theme).apply {
            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels

            val effectiveLayoutKey = effectiveSubModeKey ?: layoutName
            val rows = entries[effectiveLayoutKey].orEmpty()

            // Get keyboard height percentage from layout override or preferences
            val keyboardPrefs = AppPrefs.getInstance().keyboard
            val isLandscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val globalPercent = if (isLandscape) {
                keyboardPrefs.keyboardHeightPercentLandscape.getValue()
            } else {
                keyboardPrefs.keyboardHeightPercent.getValue()
            }
            val basePercent = layoutHeightPercentOverrideProvider(effectiveLayoutKey)
                ?: globalPercent
            val rowScale = computeRowHeightScale(rows)
            val effectivePercent = (basePercent * rowScale).coerceIn(10f, 90f)
            val keyboardHeight = (screenHeight * effectivePercent / 100f).toInt()

            // Get keyboard side and bottom padding from preferences
            val sidePadding = keyboardPrefs.keyboardSidePadding.getValue()
            val bottomPadding = keyboardPrefs.keyboardBottomPadding.getValue()
            val sidePaddingPx = (sidePadding * displayMetrics.density).toInt()
            val bottomPaddingPx = (bottomPadding * displayMetrics.density).toInt()

            val layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeight
            )
            previewContainer.addView(
                previewBlurMask,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    keyboardHeight
                )
            )
            previewContainer.addView(this, layoutParams)

            onAttach()

            // Create preview IME from the currently cached in-process IME state.
            // Avoid runImmediately() here because updatePreview is called very frequently
            // during editing and can ANR when host IME and editor contend for the same IPC path.
            val currentIme = TextKeyboard.ime
            val previewIme = PreviewInputMethodEntry.create(
                layoutName = layoutName,
                subModeLabel = previewSubModeLabel,
                base = currentIme
            )

            onInputMethodUpdate(previewIme)
            setTextScale(1.0f)
            refreshStyle()
            updateAuxBarActions(emptyList<AuxBarAction>())
            previewBlurMask.applyTheme(theme, ThemeManager.prefs.keyBorder.getValue())
            previewBlurMask.bindKeyboard(this)
            post { previewBlurMask.refreshMask(hierarchyChanged = true) }
            requestLayout()
            invalidate()

            return previewIme
        }
    }

    private fun computeRowHeightScale(rows: List<List<Map<String, Any?>>>): Float {
        if (rows.isEmpty()) return 1f

        val parsedPercents = rows.map { row ->
            row.mapNotNull { key ->
                (key["rowHeightPercent"] as? Number)?.toFloat()
                    ?: (key["rowHeightPercent"] as? String)?.trim()?.toFloatOrNull()
            }.maxOrNull()?.takeIf { it in 1f..100f }
        }

        val definedSum = parsedPercents.filterNotNull().sum()
        val undefinedCount = parsedPercents.count { it == null }

        val distributed = if (undefinedCount == 0) {
            parsedPercents.map { it ?: 0f }
        } else {
            val remaining = (100f - definedSum).coerceAtLeast(0f)
            val avg = remaining / undefinedCount
            parsedPercents.map { it ?: avg }
        }

        val sum = distributed.sum()
        if (sum <= 0f) return 1f
        val normalized = distributed.map { it * 100f / sum }
        return (normalized.sum() / 100f).coerceAtLeast(0.1f)
    }

    private fun resolveEffectiveSubModeKey(
        layoutName: String,
        previewSubModeLabel: String?
    ): String? {
        val label = previewSubModeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val keyByLabel = "$layoutName:$label"
        if (entries.containsKey(keyByLabel)) return keyByLabel
        val keyById = subModeNameToIdProvider()[label]?.let { "$layoutName:$it" }
        if (keyById != null && entries.containsKey(keyById)) return keyById
        return null
    }

    /**
     * Show error message in preview container.
     */
    private fun showError(message: String) {
        previewContainer.removeAllViews()
        val errorText = TextView(context).apply {
            text = context.getString(R.string.text_keyboard_layout_preview_error, message)
            textSize = 12f
            setTextColor(Color.RED)
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
        }
        previewContainer.addView(errorText)
    }

    /**
     * Clear preview keyboard.
     */
    fun clear() {
        previewBlurMask.bindKeyboard(null)
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }
    }

    /**
     * Get preview keyboard as bitmap.
     * @return Bitmap of the preview keyboard, or null if no preview is available
     */
    fun getPreviewBitmap(): Bitmap? {
        val keyboard = previewKeyboard
        val targetView: View = if (previewContainer.width > 0 && previewContainer.height > 0) {
            previewContainer
        } else if (keyboard != null) {
            keyboard
        } else if (previewContainer.childCount > 0) {
            previewContainer
        } else {
            return null
        }
        val width = if (targetView.width > 0) targetView.width else targetView.measuredWidth
        val height = if (targetView.height > 0) targetView.height else targetView.measuredHeight
        if (width <= 0 || height <= 0) return null

        // Directly render the current view tree into bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        targetView.draw(canvas)

        return bitmap
    }

    /**
     * Temporary config provider for preview using in-memory JSON.
     */
    private class PreviewConfigProvider(
        private val tempJson: JsonObject,
        private val delegate: ConfigProvider
    ) : ConfigProvider {
        override fun textKeyboardLayoutFile(): File? = null
        override fun textKeyboardLayoutJson(): JsonObject = tempJson
        override fun popupPresetFile(): File? = delegate.popupPresetFile()
        override fun fontsetFile(): File? = delegate.fontsetFile()
        override fun buttonsLayoutConfigFile(): File? = delegate.buttonsLayoutConfigFile()
        override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
            delegate.writeFontsetPathMap(pathMap)
    }
}

/**
 * Extension function to convert dp to pixels.
 */
private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
