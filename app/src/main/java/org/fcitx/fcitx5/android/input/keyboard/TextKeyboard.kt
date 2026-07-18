/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import java.lang.ref.WeakReference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, ::getLayout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"
        private const val LAYOUT_META_KEY = "__meta__"
        private const val LAYOUT_META_HEIGHT_PERCENT_KEY = "keyboard_height_percent"
        private const val LAYOUT_META_HEIGHT_PERCENT_LANDSCAPE_KEY = "keyboard_height_percent_landscape"
        private var lastModified = 0L
        var ime: InputMethodEntry? = null
        private var listenerRegistered = false
        private var resolvedLayoutHeightPercentOverride: Int? = null
        private val attachedKeyboards = mutableListOf<WeakReference<TextKeyboard>>()

        @Synchronized
        private fun ensureListenerRegistered() {
            if (listenerRegistered) return
            org.fcitx.fcitx5.android.input.config.ConfigProviders.addTextKeyboardLayoutListener {
                onTextLayoutFileChanged()
            }
            listenerRegistered = true
        }

        @Synchronized
        private fun registerKeyboard(keyboard: TextKeyboard) {
            attachedKeyboards.removeAll { it.get() == null || it.get() === keyboard }
            attachedKeyboards.add(WeakReference(keyboard))
            ensureListenerRegistered()
        }

        @Synchronized
        private fun unregisterKeyboard(keyboard: TextKeyboard) {
            attachedKeyboards.removeAll { it.get() == null || it.get() === keyboard }
        }

        @Synchronized
        private fun onTextLayoutFileChanged() {
            cachedRawLayoutJson = null
            lastRawModified = 0L
            lastRawLayoutFile = null
            val living = attachedKeyboards.mapNotNull { it.get() }
            attachedKeyboards.removeAll { it.get() == null }
            living.forEach { keyboard ->
                keyboard.refreshStyle()
                ime?.let { keyboard.updateSpaceLabel(it) }
            }
        }

        @Synchronized
        fun refreshCapsPresentationOnAll() {
            val living = attachedKeyboards.mapNotNull { it.get() }
            attachedKeyboards.removeAll { it.get() == null }
            living.forEach { keyboard ->
                keyboard.refreshCapsPresentation()
            }
        }

        @Synchronized
        fun clearCapsStateOnAll() {
            val living = attachedKeyboards.mapNotNull { it.get() }
            attachedKeyboards.removeAll { it.get() == null }
            living.forEach { keyboard ->
                keyboard.clearLocalCapsState()
            }
        }

        // Cache for raw JSON layout (preserves submode structure)
        internal var cachedRawLayoutJson: JsonObject? = null
        private var lastRawModified = 0L
        private var lastRawLayoutFile: String? = null

        // Compatibility alias for cachedRawLayoutJson (used by SplitKeyboardCalibrationActivity)
        @JvmStatic
        var cachedLayoutJsonMap: JsonObject?
            get() = cachedRawLayoutJson
            set(value) {
                cachedRawLayoutJson = value
                if (value == null) {
                    lastRawLayoutFile = null
                }
            }

        // Cache for parsed KeyDef layouts to avoid recreating them on every reloadLayout()
        private val cachedKeyDefLayouts = mutableMapOf<String, List<List<KeyDef>>>()
        private var lastLayoutCacheInvalidated = 0L
        private var forcedLayoutKey: String? = null

        /**
         * Clear KeyDef layout cache. Call this after saving layout changes.
         */
        fun clearCachedKeyDefLayouts() {
            cachedKeyDefLayouts.clear()
            lastLayoutCacheInvalidated = 0L
        }

        @Synchronized
        fun setForcedLayoutKey(layoutKey: String?) {
            val normalized = layoutKey?.trim()?.takeIf { it.isNotEmpty() }
            if (forcedLayoutKey == normalized) return
            forcedLayoutKey = normalized
            cachedKeyDefLayouts.clear()
            val living = attachedKeyboards.mapNotNull { it.get() }
            attachedKeyboards.removeAll { it.get() == null }
            living.forEach { keyboard ->
                keyboard.refreshStyle()
                ime?.let { keyboard.updateSpaceLabel(it) }
            }
        }

        @Synchronized
        fun clearForcedLayoutKey() = setForcedLayoutKey(null)

        @Synchronized
        fun currentLayoutHeightPercentOverride(): Int? {
            resolvedLayoutHeightPercentOverride = resolveCurrentLayoutHeightPercentOverride()
            return resolvedLayoutHeightPercentOverride
        }

        private fun resolveCurrentLayoutHeightPercentOverride(): Int? {
            val currentIme = ime ?: return null
            val json = textLayoutJson ?: return null

            forcedLayoutKey?.let { forced ->
                val forcedLayout = findLayoutElementByKey(json, forced)
                if (forcedLayout != null) {
                    val baseName = forced.substringBefore(':')
                    val forcedSub = forced.substringAfter(':', "")
                    return if (forcedSub.isNotEmpty()) {
                        parseLayoutHeightPercentOverride((json[baseName] as? JsonObject)?.get(forcedSub))
                            ?: parseLayoutHeightPercentOverride(json[baseName])
                    } else {
                        parseLayoutHeightPercentOverride(json[baseName])
                    }
                }
            }

            val imeLayoutElement = json[currentIme.uniqueName] ?: json[currentIme.displayName]
            if (imeLayoutElement != null) {
                val subModeLabel = currentIme.subMode.label
                val subModeLayoutElement = if (imeLayoutElement is JsonObject) {
                    imeLayoutElement[subModeLabel]
                        ?: imeLayoutElement["default"]
                        ?: imeLayoutElement[""]
                } else {
                    imeLayoutElement
                }
                if (parseLayoutArray(subModeLayoutElement) != null) {
                    return parseLayoutHeightPercentOverride(subModeLayoutElement)
                        ?: parseLayoutHeightPercentOverride(imeLayoutElement)
                }
            }

            val defaultLayoutElement = json["default"]
            if (parseLayoutArray(defaultLayoutElement) != null) {
                return parseLayoutHeightPercentOverride(defaultLayoutElement)
            }
            return null
        }

        @Synchronized
        fun currentBaseLayoutKey(): String? {
            val currentIme = ime ?: return null
            val json = textLayoutJson
            return when {
                json?.containsKey(currentIme.uniqueName) == true -> currentIme.uniqueName
                json?.containsKey(currentIme.displayName) == true -> currentIme.displayName
                else -> "default"
            }
        }

        @Synchronized
        private fun currentHeightOverrideTargetLayoutKey(): String? {
            val json = textLayoutJson
            forcedLayoutKey?.let { forced ->
                if (json != null && containsLayoutKey(json, forced)) return forced
            }
            val base = currentBaseLayoutKey() ?: return null
            val subModeLabel = ime?.subMode?.label?.takeIf { it.isNotEmpty() }
            val subModeKey = subModeLabel?.let { "$base:$it" }
            return if (json != null && subModeKey != null && containsLayoutKey(json, subModeKey)) {
                subModeKey
            } else {
                base
            }
        }

        @Synchronized
        fun resolveLayerTargetKey(target: String): String? {
            val normalized = target.trim()
            if (normalized.isEmpty()) return null
            val json = textLayoutJson ?: return null
            val base = currentBaseLayoutKey() ?: return null
            if (normalized.contains(':')) {
                val subModeFromTarget = normalized.substringAfter(':', "")
                if (subModeFromTarget.isNotEmpty()) {
                    val baseScoped = "$base:$subModeFromTarget"
                    if (containsLayoutKey(json, baseScoped)) return baseScoped
                    val normalizedSubMode = if (LayoutJsonUtils.isLayerSubModeLabel(subModeFromTarget)) {
                        subModeFromTarget
                    } else {
                        LayoutJsonUtils.toLayerSubModeLabel(subModeFromTarget)
                    }
                    val baseScopedLayer = "$base:$normalizedSubMode"
                    if (containsLayoutKey(json, baseScopedLayer)) return baseScopedLayer
                }
            }
            if (containsLayoutKey(json, normalized)) return normalized
            val subModeLabel = if (LayoutJsonUtils.isLayerSubModeLabel(normalized)) {
                normalized
            } else {
                LayoutJsonUtils.toLayerSubModeLabel(normalized)
            }
            val candidate = "$base:$subModeLabel"
            return candidate.takeIf { containsLayoutKey(json, it) }
        }

        private fun containsLayoutKey(json: JsonObject, layoutKey: String): Boolean {
            val base = layoutKey.substringBefore(':')
            val sub = layoutKey.substringAfter(':', "")
            val element = json[base] ?: return false
            if (sub.isEmpty()) {
                return when (element) {
                    is JsonArray -> true
                    is JsonObject -> parseLayoutArray(element["default"]) != null || parseLayoutArray(element[""]) != null
                    else -> false
                }
            }
            val subElement = (element as? JsonObject)?.get(sub) ?: return false
            return parseLayoutArray(subElement) != null
        }

        private fun findLayoutElementByKey(json: JsonObject, layoutKey: String): JsonArray? {
            val base = layoutKey.substringBefore(':')
            val sub = layoutKey.substringAfter(':', "")
            val element = json[base] ?: return null
            return if (sub.isEmpty()) {
                when (element) {
                    is JsonArray -> element
                    is JsonObject -> parseLayoutArray(element["default"]) ?: parseLayoutArray(element[""])
                    else -> null
                }
            } else {
                parseLayoutArray((element as? JsonObject)?.get(sub))
            }
        }

        private fun parseLayoutArray(layoutElement: JsonElement?): JsonArray? {
            return when (layoutElement) {
                is JsonArray -> layoutElement
                is JsonObject -> (layoutElement["default"] as? JsonArray) ?: (layoutElement[""] as? JsonArray)
                else -> null
            }
        }

        private fun isLandscapeNow(): Boolean =
            android.content.res.Resources.getSystem().configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE

        private fun parseLayoutHeightPercentOverride(layoutElement: JsonElement?): Int? {
            val objectElement = layoutElement as? JsonObject ?: return null
            val meta = objectElement[LAYOUT_META_KEY] as? JsonObject ?: return null
            fun readKey(key: String): Int? {
                val raw = (meta[key] as? JsonPrimitive)?.intOrNull
                    ?: (meta[key] as? JsonPrimitive)?.content?.toIntOrNull()
                return raw?.takeIf { it in 10..90 }
            }
            return if (isLandscapeNow()) {
                readKey(LAYOUT_META_HEIGHT_PERCENT_LANDSCAPE_KEY) ?: readKey(LAYOUT_META_HEIGHT_PERCENT_KEY)
            } else {
                readKey(LAYOUT_META_HEIGHT_PERCENT_KEY)
            }
        }

        @Synchronized
        fun setCurrentLayoutHeightPercentOverride(percent: Int): Boolean {
            val layoutKey = currentHeightOverrideTargetLayoutKey() ?: return false
            return setLayoutHeightPercentOverride(layoutKey, percent)
        }

        @Synchronized
        private fun setLayoutHeightPercentOverride(layoutKey: String, percent: Int): Boolean {
            if (percent !in 10..90) return false
            val metaKey = if (isLandscapeNow()) {
                LAYOUT_META_HEIGHT_PERCENT_LANDSCAPE_KEY
            } else {
                LAYOUT_META_HEIGHT_PERCENT_KEY
            }
            val snapshot = org.fcitx.fcitx5.android.input.config.ConfigProviders
                .readTextKeyboardLayout<JsonObject>() ?: return false
            val root = snapshot.value.toMutableMap()
            val base = layoutKey.substringBefore(':')
            val sub = layoutKey.substringAfter(':', "")
            val existingBase = root[base] ?: return false

            if (sub.isEmpty()) {
                val updatedLayoutElement = when (existingBase) {
                    is JsonArray -> {
                        JsonObject(
                            mapOf(
                                LAYOUT_META_KEY to JsonObject(
                                    mapOf(metaKey to JsonPrimitive(percent))
                                ),
                                "default" to existingBase
                            )
                        )
                    }
                    is JsonObject -> {
                        val mutable = existingBase.toMutableMap()
                        val meta = (mutable[LAYOUT_META_KEY] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                        meta[metaKey] = JsonPrimitive(percent)
                        mutable[LAYOUT_META_KEY] = JsonObject(meta)
                        JsonObject(mutable)
                    }
                    else -> return false
                }
                root[base] = updatedLayoutElement
            } else {
                val baseObject = when (existingBase) {
                    is JsonObject -> mutableMapOf<String, JsonElement>().apply { putAll(existingBase) }
                    is JsonArray -> mutableMapOf<String, JsonElement>("default" to existingBase)
                    else -> return false
                }
                val existingSub = baseObject[sub] ?: return false
                val updatedSubElement = when (existingSub) {
                    is JsonArray -> {
                        JsonObject(
                            mapOf(
                                LAYOUT_META_KEY to JsonObject(
                                    mapOf(metaKey to JsonPrimitive(percent))
                                ),
                                "default" to existingSub
                            )
                        )
                    }
                    is JsonObject -> {
                        val mutable = existingSub.toMutableMap()
                        val meta = (mutable[LAYOUT_META_KEY] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                        meta[metaKey] = JsonPrimitive(percent)
                        mutable[LAYOUT_META_KEY] = JsonObject(meta)
                        JsonObject(mutable)
                    }
                    else -> return false
                }
                baseObject[sub] = updatedSubElement
                root[base] = JsonObject(baseObject)
            }
            val targetFile = snapshot.file
                ?: org.fcitx.fcitx5.android.input.config.ConfigProviders.provider.textKeyboardLayoutFile()
                ?: return false
            return runCatching {
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(JsonObject(root).toString() + "\n")
                resolvedLayoutHeightPercentOverride = percent
                onTextLayoutFileChanged()
            }.isSuccess
        }

        val textLayoutJson: JsonObject?
            @Synchronized
            get() {
                val providers = org.fcitx.fcitx5.android.input.config.ConfigProviders
                val provider = providers.provider
                val memoryJson = provider.textKeyboardLayoutJson()
                if (memoryJson != null) {
                    providers.ensureWatching()
                    if (cachedRawLayoutJson !== memoryJson || lastRawLayoutFile != null) {
                        cachedRawLayoutJson = memoryJson
                        lastRawLayoutFile = null
                        // Keep an in-memory snapshot distinct from a missing default file,
                        // which also has a null path and zero last-modified timestamp.
                        lastRawModified = Long.MIN_VALUE
                        lastLayoutCacheInvalidated = 0L
                        cachedKeyDefLayouts.clear()
                    }
                    return cachedRawLayoutJson
                }

                val file = provider.textKeyboardLayoutFile()
                val currentFile = file?.absolutePath
                val currentModified = file?.takeIf { it.exists() }?.lastModified() ?: 0L
                if (cachedRawLayoutJson != null &&
                    currentFile == lastRawLayoutFile &&
                    currentModified == lastRawModified
                ) {
                    providers.ensureWatching()
                    return cachedRawLayoutJson
                }

                val snapshot = providers.readTextKeyboardLayout<JsonObject>() ?: run {
                    cachedRawLayoutJson = null
                    lastRawLayoutFile = null
                    lastRawModified = 0L
                    return null
                }
                if (cachedRawLayoutJson == null ||
                    currentFile != lastRawLayoutFile ||
                    snapshot.lastModified != lastRawModified
                ) {
                    lastRawModified = snapshot.lastModified
                    lastRawLayoutFile = currentFile
                    cachedRawLayoutJson = snapshot.value
                    // Invalidate KeyDef cache when JSON changes
                    lastLayoutCacheInvalidated = snapshot.lastModified
                    cachedKeyDefLayouts.clear()
                }
                return cachedRawLayoutJson
            }

        private fun getTextLayoutJsonForIme(displayName: String): JsonArray? {
            val json = textLayoutJson ?: return null
            return json[displayName]?.jsonArray
        }

        fun getLayout(): List<List<KeyDef>> {
            val imeName = ime?.uniqueName
            val subModeLabel = ime?.subMode?.label ?: ""
            val showLangSwitch = AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
            val json = textLayoutJson

            forcedLayoutKey?.let { forced ->
                if (json != null) {
                    val forcedLayout = findLayoutElementByKey(json, forced)
                    if (forcedLayout != null) {
                        val cacheKey = "forced:$forced:$showLangSwitch"
                        val baseName = forced.substringBefore(':')
                        val forcedSub = forced.substringAfter(':', "")
                        resolvedLayoutHeightPercentOverride = if (forcedSub.isNotEmpty()) {
                            parseLayoutHeightPercentOverride((json[baseName] as? JsonObject)?.get(forcedSub))
                                ?: parseLayoutHeightPercentOverride(json[baseName])
                        } else {
                            parseLayoutHeightPercentOverride(json[baseName])
                        }
                        return cachedKeyDefLayouts.getOrPut(cacheKey) {
                            forcedLayout.map { rowElement ->
                                LayoutJsonUtils.parseKeyJsonArray(rowElement.jsonArray, showLangSwitch)
                                    .map { LayoutJsonUtils.createKeyDef(it, subModeLabel, ime?.subMode?.name ?: "") }
                            }
                        }
                    }
                }
            }

            if (imeName != null) {
                if (json != null) {
                    // Try uniqueName first, then displayName
                    val layoutKey = imeName
                    val imeLayoutElement = json[layoutKey]
                        ?: json[ime?.displayName]

                    if (imeLayoutElement != null) {
                        // Check if this is a submode structure (JsonObject) or direct layout (JsonArray)
                        val subModeLayoutElement = if (imeLayoutElement is JsonObject) {
                            // Submode structure: try submode label, then "default", then empty string
                            imeLayoutElement[subModeLabel]
                                ?: imeLayoutElement["default"]
                                ?: imeLayoutElement[""]
                        } else {
                            // Direct layout array, use as-is
                            imeLayoutElement
                        }

                        val layoutArray = parseLayoutArray(subModeLayoutElement)
                        if (layoutArray != null) {
                            resolvedLayoutHeightPercentOverride =
                                parseLayoutHeightPercentOverride(subModeLayoutElement)
                                    ?: parseLayoutHeightPercentOverride(imeLayoutElement)
                            // Use a cache key that includes submode and showLangSwitch for proper caching
                            // Include showLangSwitch in cache key so layout is re-created when setting changes
                            val cacheKey = "$layoutKey:$subModeLabel:$showLangSwitch"
                            return cachedKeyDefLayouts.getOrPut(cacheKey) {
                                layoutArray.map { rowElement ->
                                    LayoutJsonUtils.parseKeyJsonArray(rowElement.jsonArray, showLangSwitch)
                                        .map { LayoutJsonUtils.createKeyDef(it, subModeLabel, ime?.subMode?.name ?: "") }
                                }
                            }
                        }
                    }

                    // Fallback to global "default" layout
                    json["default"]?.let { layoutElement ->
                        resolvedLayoutHeightPercentOverride = parseLayoutHeightPercentOverride(layoutElement)
                        val layoutArray = parseLayoutArray(layoutElement)
                        if (layoutArray != null) {
                            return cachedKeyDefLayouts.getOrPut("default:$showLangSwitch:$lastRawModified") {
                                layoutArray.map { rowElement ->
                                    LayoutJsonUtils.parseKeyJsonArray(rowElement.jsonArray, showLangSwitch)
                                        .map { LayoutJsonUtils.createKeyDef(it) }
                                }
                            }
                        }
                    }
                }
            }
            resolvedLayoutHeightPercentOverride = null
            return getDefaultLayout(showLangSwitch)
        }

        fun getDefaultLayout(showLangSwitch: Boolean = true): List<List<KeyDef>> {
            return listOf(
                listOf(
                    AlphabetKey("Q", "1"),
                    AlphabetKey("W", "2"),
                    AlphabetKey("E", "3"),
                    AlphabetKey("R", "4"),
                    AlphabetKey("T", "5"),
                    AlphabetKey("Y", "6"),
                    AlphabetKey("U", "7"),
                    AlphabetKey("I", "8"),
                    AlphabetKey("O", "9"),
                    AlphabetKey("P", "0")
                ),
                listOf(
                    AlphabetKey("A", "@"),
                    AlphabetKey("S", "*"),
                    AlphabetKey("D", "+"),
                    AlphabetKey("F", "-"),
                    AlphabetKey("G", "="),
                    AlphabetKey("H", "/"),
                    AlphabetKey("J", "#"),
                    AlphabetKey("K", "("),
                    AlphabetKey("L", ")")
                ),
                listOf(
                    CapsKey(),
                    AlphabetKey("Z", "'"),
                    AlphabetKey("X", ":"),
                    AlphabetKey("C", "\""),
                    AlphabetKey("V", "?"),
                    AlphabetKey("B", "!"),
                    AlphabetKey("N", "~"),
                    AlphabetKey("M", "\\"),
                    BackspaceKey()
                ),
                listOf(
                    LayoutSwitchKey("?123", ""),
                    CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative),
                    *if (showLangSwitch) arrayOf(LanguageKey()) else emptyArray(),
                    SpaceKey(),
                    SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                    ReturnKey()
                )
            )
        }
    }

    private var specialKeyViews: SpecialKeyViews = SpecialKeyViews(
        caps = emptyList(),
        backspace = emptyList(),
        quickphrase = emptyList(),
        space = emptyList(),
        `return` = emptyList()
    )

    data class SpecialKeyViews(
        val caps: List<ImageKeyView>,
        val backspace: List<ImageKeyView>,
        val quickphrase: List<ImageKeyView>,
        val space: List<TextKeyView>,
        val `return`: List<ImageKeyView>
    )

    private fun findAllSpecialKeyViews(): SpecialKeyViews {
        val caps = mutableListOf<ImageKeyView>()
        val backspace = mutableListOf<ImageKeyView>()
        val quickphrase = mutableListOf<ImageKeyView>()
        val space = mutableListOf<TextKeyView>()
        val returnKeys = mutableListOf<ImageKeyView>()

        allViews.forEach { view ->
            when (view.tag) {
                R.id.button_caps -> (view as? ImageKeyView)?.let(caps::add)
                R.id.button_backspace -> (view as? ImageKeyView)?.let(backspace::add)
                R.id.button_quickphrase -> (view as? ImageKeyView)?.let(quickphrase::add)
                R.id.button_space -> (view as? TextKeyView)?.let(space::add)
                R.id.button_return -> (view as? ImageKeyView)?.let(returnKeys::add)
            }
        }

        return SpecialKeyViews(
            caps = caps,
            backspace = backspace,
            quickphrase = quickphrase,
            space = space,
            `return` = returnKeys
        )
    }
    
    private fun ensureSpecialKeyViewsInitialized() {
        specialKeyViews = findAllSpecialKeyViews()
    }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey
    private val spaceKeyLabelMode = AppPrefs.getInstance().keyboard.spaceKeyLabelMode

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        // Clear cache when showLangSwitch setting changes
        cachedKeyDefLayouts.clear()
        // Reload layout to show/hide LanguageKey
        reloadLayout()
    }

    @Keep
    private val spaceKeyLabelModeListener = ManagedPreference.OnChangeListener<SpaceKeyLabelMode> { _, _ ->
        updateSpaceLabel(TextKeyboard.ime)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
        // BaseKeyboard has already built the initial layout. If the current IME was supplied
        // before construction, remember its signature so onInputMethodUpdate does not rebuild
        // the exact same custom layout immediately afterwards.
        ime?.let { lastLayoutSignature = layoutSignature(it) }
    }

    private val textKeys: List<TextKeyView>
        get() = allViews.filterIsInstance(TextKeyView::class.java).toList()

    private var capsState: CapsState = CapsState.None

    private fun isDisplayCapsOn(): Boolean {
        return capsState != CapsState.None || isSimulatedCapsLockOn()
    }

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private var lastLayoutSignature: String? = null
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    private fun layoutSignature(ime: InputMethodEntry): String {
        val json = textLayoutJson
        val layoutSource = when {
            json?.containsKey(ime.uniqueName) == true -> "u:${ime.uniqueName}"
            json?.containsKey(ime.displayName) == true -> "d:${ime.displayName}"
            else -> "default"
        }
        val subModeLabel = ime.subMode.run { label.ifEmpty { name.ifEmpty { "" } } }
        val forced = forcedLayoutKey ?: ""
        return "$layoutSource|$subModeLabel|$forced|$lastRawModified"
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = if (isSimulatedCapsLockOn()) {
                                action.copy(
                                    act = action.act.uppercase(),
                                    states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                                )
                            } else {
                                action.copy(act = action.act.lowercase())
                            }
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> {
                if (!action.lock && source == KeyActionListener.Source.Keyboard && tryConsumeMacroCapsLock()) {
                    // MacroKey tap Caps_Lock opened lock state: single tap on CapsKey should send Caps_Lock again.
                } else {
                    switchCapsState(action.lock)
                }
            }
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun preprocessMacroAction(
        action: MacroAction,
        source: KeyActionListener.Source
    ): MacroAction {
        val allowConsumeCapsOnce = source == KeyActionListener.Source.Keyboard
        var consumeCapsOnce = false
        val simulatedCapsOn = isSimulatedCapsLockOn()
        val pendingUppercaseDown = mutableMapOf<String, Int>()

        fun isLetter(code: String): Boolean = code.length == 1 && code[0].isLetter()

        fun consumeUppercaseDecision(): Boolean {
            return when (capsState) {
                CapsState.None -> simulatedCapsOn
                CapsState.Once -> {
                    if (allowConsumeCapsOnce && !consumeCapsOnce) {
                        consumeCapsOnce = true
                        true
                    } else {
                        simulatedCapsOn
                    }
                }
                CapsState.Lock -> true
            }
        }

        fun nonConsumingUppercaseDecision(): Boolean {
            return when (capsState) {
                CapsState.None -> simulatedCapsOn
                CapsState.Once -> simulatedCapsOn
                CapsState.Lock -> true
            }
        }

        fun transformTapLetter(code: String): String {
            if (!isLetter(code)) return code
            val lower = code.lowercase()
            return if (consumeUppercaseDecision()) lower.uppercase() else lower
        }

        fun transformDownLetter(code: String): String {
            if (!isLetter(code)) return code
            val lower = code.lowercase()
            val transformed = if (consumeUppercaseDecision()) {
                pendingUppercaseDown[lower] = (pendingUppercaseDown[lower] ?: 0) + 1
                lower.uppercase()
            } else {
                lower
            }
            return transformed
        }

        fun transformUpLetter(code: String): String {
            if (!isLetter(code)) return code
            val lower = code.lowercase()
            val pending = pendingUppercaseDown[lower] ?: 0
            return if (pending > 0) {
                if (pending == 1) pendingUppercaseDown.remove(lower) else pendingUppercaseDown[lower] = pending - 1
                lower.uppercase()
            } else {
                if (nonConsumingUppercaseDecision()) lower.uppercase() else lower
            }
        }

        fun transformShortcutKey(code: String): String {
            if (code.length != 1 || !code[0].isLetter()) return code
            val lower = code.lowercase()
            return if (consumeUppercaseDecision()) lower.uppercase() else lower
        }

        fun transformKeyRef(keyRef: KeyRef, step: MacroStep): KeyRef {
            return when (keyRef) {
                is KeyRef.Fcitx -> keyRef.copy(
                    code = when (step) {
                        is MacroStep.Down -> transformDownLetter(keyRef.code)
                        is MacroStep.Up -> transformUpLetter(keyRef.code)
                        is MacroStep.Tap -> transformTapLetter(keyRef.code)
                        else -> keyRef.code
                    }
                )
                is KeyRef.Android -> keyRef
            }
        }

        val transformedSteps = action.steps.map { step ->
            when (step) {
                is MacroStep.Down -> step.copy(keys = step.keys.map { transformKeyRef(it, step) })
                is MacroStep.Up -> step.copy(keys = step.keys.map { transformKeyRef(it, step) })
                is MacroStep.Tap -> step.copy(keys = step.keys.map { transformKeyRef(it, step) })
                is MacroStep.Shortcut -> step.copy(
                    modifiers = step.modifiers,
                    key = when (step.key) {
                        is KeyRef.Fcitx -> step.key.copy(code = transformShortcutKey(step.key.code))
                        is KeyRef.Android -> step.key
                    }
                )
                is MacroStep.Text,
                is MacroStep.Edit,
                is MacroStep.AppAction,
                is MacroStep.LayerSwitch -> step
            }
        }

        if (consumeCapsOnce) {
            switchCapsState()
        }

        return action.copy(steps = transformedSteps)
    }

    private fun tryConsumeMacroCapsLock(): Boolean {
        val service = getService() ?: return false
        if (!service.isSimulatedCapsLockOnByMacroTap()) return false
        service.sendSimulatedCapsLockTapFromMacro()
        return true
    }

    private fun getService(): FcitxInputMethodService? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is FcitxInputMethodService) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return context as? FcitxInputMethodService
    }

    override fun onAttach() {
        ensureSpecialKeyViewsInitialized()
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    protected override fun defaultRowHeightPercent(rowCount: Int): Float =
        super.defaultRowHeightPercent(rowCount)

    override fun preferredKeyboardHeightPercentOverride(): Int? =
        TextKeyboard.currentLayoutHeightPercentOverride()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerKeyboard(this)
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        spaceKeyLabelMode.registerOnChangeListener(spaceKeyLabelModeListener)
    }

    override fun onDetachedFromWindow() {
        unregisterKeyboard(this)
        showLangSwitchKey.unregisterOnChangeListener(showLangSwitchKeyListener)
        spaceKeyLabelMode.unregisterOnChangeListener(spaceKeyLabelModeListener)
        super.onDetachedFromWindow()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        specialKeyViews.`return`.forEach { returnKey ->
            returnKey.img.imageResource = returnDrawable
        }
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    private fun updateSpaceLabel(ime: InputMethodEntry?) {
        if (ime == null) return
        val subModeText = ime.subMode.run { label.ifEmpty { name.ifEmpty { "" } } }
        val newText = when (spaceKeyLabelMode.getValue()) {
            SpaceKeyLabelMode.Default -> {
                buildString {
                    append(ime.displayName)
                    if (subModeText.isNotEmpty()) append(" ($subModeText)")
                }
            }
            SpaceKeyLabelMode.CompactWhenSubMode -> {
                val imeText = if (subModeText.isNotEmpty()) ime.label.ifEmpty { ime.displayName } else ime.displayName
                val combined = if (subModeText.isNotEmpty()) "$imeText ($subModeText)" else imeText
                if (subModeText.isNotEmpty() && combined.length > 10) subModeText else combined
            }
            SpaceKeyLabelMode.SubModeOnly -> {
                if (subModeText.isNotEmpty()) subModeText else ime.displayName
            }
        }
        ensureSpecialKeyViewsInitialized()
        specialKeyViews.space.forEach { spaceKey ->
            spaceKey.mainText.text = newText
        }
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        // update ime of companion object ime
        TextKeyboard.ime = ime
        val signature = layoutSignature(ime)
        if (signature != lastLayoutSignature) {
            reloadLayout()
            lastLayoutSignature = signature
        }
        // Re-find special key views after layout reload (or ensure initialized on first call)
        ensureSpecialKeyViewsInitialized()
        updateAlphabetKeys()
        updateSpaceLabel(ime)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    override fun onStyleRefreshFinished() {
        ensureSpecialKeyViewsInitialized()
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
        updateSpaceLabel(TextKeyboard.ime)
    }

    override fun onThemeUpdate(newTheme: Theme) {
        ensureSpecialKeyViewsInitialized()
        updateCapsButtonIcon()
        // Note: returnDrawable is managed by KeyboardWindow
    }

    override fun onCompositionStateChanged(composing: Boolean) {
        super.onCompositionStateChanged(composing)
        ensureSpecialKeyViewsInitialized()
        // Compose-state switches may recreate key views; re-apply caps presentation immediately.
        updateAlphabetKeys()
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        val oldCapsState = capsState
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        val oldLocked = oldCapsState == CapsState.Lock
        val newLocked = capsState == CapsState.Lock
        if (oldLocked != newLocked) {
            getService()?.setVirtualCapsLockState(newLocked)
        }
        refreshCapsPresentation()
    }

    private fun refreshCapsPresentation() {
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun clearLocalCapsState() {
        if (capsState == CapsState.None) return
        capsState = CapsState.None
        refreshCapsPresentation()
    }

    private fun updateCapsButtonIcon() {
        val displayLock = isDisplayCapsOn()
        specialKeyViews.caps.forEach { cap ->
            cap.img.apply {
                imageResource = when (capsState) {
                    CapsState.None -> if (displayLock) R.drawable.ic_capslock_lock else R.drawable.ic_capslock_none
                    CapsState.Once -> R.drawable.ic_capslock_once
                    CapsState.Lock -> R.drawable.ic_capslock_lock
                }
            }
        }
    }

    private fun updateAlphabetKeys() {
        val displayUppercase = isDisplayCapsOn()
        textKeys.forEach {
            val keyDef = it.def
            if (keyDef is KeyDef.Appearance.AltText) {
                val renderedText = it.mainText.text.toString()
                val sourceFromDef = renderedText.isEmpty() || renderedText == keyDef.displayText
                val displayText = if (sourceFromDef) keyDef.displayText else renderedText
                val character = keyDef.character
                val displayIsSingleLetter = displayText.length == 1 && displayText[0].isLetter()
                val characterIsSingleLetter = sourceFromDef && character.length == 1 && character[0].isLetter()

                it.mainText.text = when {
                    keepLettersUppercase && displayIsSingleLetter -> displayText.uppercase()
                    keepLettersUppercase -> displayText
                    !displayUppercase && displayIsSingleLetter -> displayText.lowercase()
                    !displayUppercase -> displayText
                    displayIsSingleLetter -> displayText.uppercase()
                    characterIsSingleLetter -> character.uppercase()
                    else -> displayText
                }
            } else if (keyDef is KeyDef.Appearance.Text) {
                val renderedText = it.mainText.text.toString()
                val str = if (renderedText.isEmpty() || renderedText == keyDef.displayText) {
                    keyDef.displayText
                } else {
                    renderedText
                }
                if (str.length == 1 && str[0].isLetter()) {
                     it.mainText.text = if (keepLettersUppercase) {
                        str.uppercase()
                    } else {
                        if (displayUppercase) str.uppercase() else str.lowercase()
                    }
                }
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    val first = str.firstOrNull() ?: return@forEach
                    if (first.run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}
