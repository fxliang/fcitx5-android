/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.AuxBarAction
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver, NumericLayoutFallbackListener {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val iconThemeListener = IconThemeManager.OnIconThemeChangeListener {
        returnKeyDrawable.onIconThemeChanged()
        refreshCurrentKeyboard()
    }

    init {
        TextKeyboard.setNumericLayoutFallbackTarget(this)
    }

    override fun onNumericLayoutOverrideInvalidated() {
        if (currentKeyboardName == TextKeyboard.Name) {
            switchLayout(NumberKeyboard.Name, remember = false, inheritTextHeight = false)
        }
    }

    companion object : EssentialWindow.Key {
        private const val MAX_LAYER_HISTORY = 8
    }

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout
    private var currentTextScale = 1.0f

    private val keyboards = hashMapOf<String, BaseKeyboard>()

    private fun getOrCreateKeyboard(name: String): BaseKeyboard? {
        keyboards[name]?.let { return it }
        val keyboard = when (name) {
            TextKeyboard.Name -> TextKeyboard(context, theme)
            NumberKeyboard.Name -> NumberKeyboard(context, theme)
            else -> return null
        }
        keyboards[name] = keyboard
        return keyboard
    }
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout
    private var preeditEmpty = true
    private var candidateEmpty = true
    private var composingState = false
    private var lastAuxActions = emptyList<AuxBarAction>()
    private var latchedLayerKey: String? = null
    private var oneShotLayerKey: String? = null
    private val layerHistory = ArrayDeque<String>()
    private var noConfigAuxBarFallbackActive = false
    private var companionHeightPercentOverride: Int? = null
    private var companionHeightPxOverride: Int? = null

    internal val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private fun clearCompanionKeyboardHeightOverride() {
        companionHeightPercentOverride = null
        companionHeightPxOverride = null
    }

    fun usesCompanionKeyboardHeightOverride(): Boolean {
        return currentKeyboardName != TextKeyboard.Name
    }

    private fun updateCompositionState() {
        val composing = !preeditEmpty || !candidateEmpty
        if (composingState == composing) return
        composingState = composing
        currentKeyboard?.onCompositionStateChanged(composing)
        service.inputView?.requestBlurRefresh(retryFrames = 2, hierarchyChanged = true)
    }

    private fun reapplyReturnKeyDrawable() {
        currentKeyboard?.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
        currentKeyboard?.onReturnDrawableOverride(returnKeyDrawable.iconThemeDrawable)
    }

    /**
     * Refresh all keyboard layouts.
     * Call this when split keyboard settings (gap, threshold, enabled) change.
     */
    fun refreshAllKeyboards() {
        // A layout profile switch can invalidate the numeric-input override resolved at the
        // last onStartInput. Drop it first so the refresh below does not render the stale
        // (or now-different) layout, and fall back to the built-in number keyboard.
        TextKeyboard.handleLayoutSourceChanged()
        keyboards.values.forEach { it.refreshStyle() }
        reapplyReturnKeyDrawable()
    }

    /**
     * Refresh only the currently visible keyboard layout.
     * Lightweight alternative to [refreshAllKeyboards] used e.g. after toggling
     * floating mode to force alt-text layout recalculation.
     */
    fun refreshCurrentKeyboard() {
        currentKeyboard?.refreshStyle()
        reapplyReturnKeyDrawable()
    }

    /**
     * Check and apply font refresh if needed.
     * Call this when keyboard is about to show.
     */
    fun checkAndApplyFontRefresh() {
        if (org.fcitx.fcitx5.android.input.font.FontProviders.checkAndClearRefreshFlag()) {
            // The refresh flag is consumed above, so the rows cache would otherwise
            // reuse rows built with the previous font set. Clear it first so
            // refreshAllKeyboards() rebuilds rows and re-applies configured fonts.
            keyboards.values.forEach { it.clearReusableRowsCache() }
            refreshAllKeyboards()
        }
    }

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.LayoutSwitchAction -> switchLayout(it.act, fromUserKey = true)
            is KeyAction.LayerSwitchAction -> handleLayerSwitchAction(it)
            is KeyAction.AuxBarTrigger -> {
                val actionId = it.edgeId
                fcitx.launchOnReady { fcitxApi: FcitxAPI -> fcitxApi.triggerCandidateListTabAction(actionId) }
            }
            KeyAction.MacroConsumedAction -> consumeOneShotLayerIfNeeded(it)
            else -> {
                commonKeyActionListener.listener.onKeyAction(it, source)
                consumeOneShotLayerIfNeeded(it)
            }
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        val startedAt = SystemClock.elapsedRealtime()
        // Make the current IME available before TextKeyboard's constructor builds its layout,
        // avoiding an initial default layout followed by an immediate custom-layout rebuild.
        TextKeyboard.ime = fcitx.runImmediately { inputMethodEntryCached }
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        Log.i(
            "FcitxColdStart",
            "KeyboardWindow.onCreateView duration=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        getOrCreateKeyboard(target)?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.auxBarListener = { scrollable, pinned ->
                service.inputView?.updateAuxBar(
                    (scrollable + pinned).filter { a -> !a.isSeparator }, keyActionListener
                )
            }
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.setTextScale(currentTextScale)
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onReturnDrawableOverride(returnKeyDrawable.iconThemeDrawable)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
            applyAuxActions(lastAuxActions)
            updateCompositionState()
        }
    }

    fun switchLayout(
        to: String,
        remember: Boolean = true,
        inheritTextHeight: Boolean = true,
        notifyHeightChange: Boolean = true,
        fromUserKey: Boolean = false
    ) {
        val requestedTarget = to.ifEmpty { lastSymbolType }
        var target = requestedTarget
        // The built-in NumberKeyboard is short-circuited by the configured numeric layout
        // no matter how it is reached: a numeric editor via onStartInput, a manual
        // LayoutSwitchKey, the symbol picker numpad button, or a preset/macro key targeting
        // the Number keyboard. In a numeric editor session the override is sticky; a manual
        // switch from a text session is remembered so switching back to Text restores the
        // normal text keyboard.
        if (target == NumberKeyboard.Name) {
            val override = TextKeyboard.resolveNumericLayoutKey()
            if (override != null) {
                target = TextKeyboard.Name
                TextKeyboard.activateManualNumericLayout(override)
            }
        } else if (target == TextKeyboard.Name && fromUserKey && TextKeyboard.isNumericLayoutActive()) {
            // An explicit key in the numeric layout targets the text keyboard (e.g. an
            // "ABC"-style LayoutSwitchKey in the custom numeric layout). Release the
            // override for the rest of the session so the normal text keyboard shows.
            TextKeyboard.dismissNumericLayoutOverride()
        } else if (target == TextKeyboard.Name) {
            TextKeyboard.releaseManualNumericLayout()
        }
        ContextCompat.getMainExecutor(service).execute {
            if (target == TextKeyboard.Name || target == NumberKeyboard.Name) {
                if (target == TextKeyboard.Name) {
                    clearCompanionKeyboardHeightOverride()
                } else if (inheritTextHeight) {
                    prepareCompanionKeyboardHeightPercentOverride()
                } else {
                    clearCompanionKeyboardHeightOverride()
                }
                if (target != TextKeyboard.Name) {
                    noConfigAuxBarFallbackActive = false
                }
                // A request for the built-in Number keyboard may be redirected to the text
                // keyboard by the numeric layout override. Remember the requested target so
                // the "?123" key still returns to the same (overridden) number keyboard.
                val rememberTarget = if (target == TextKeyboard.Name) requestedTarget else target
                if (remember && rememberTarget != TextKeyboard.Name) {
                    lastSymbolType = rememberTarget
                }
                if (target == currentKeyboardName) {
                    if (target == TextKeyboard.Name) {
                        currentKeyboard?.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
                        updateCompositionState()
                    }
                    applyAuxActions(lastAuxActions)
                    if (notifyHeightChange) {
                        service.inputView?.onKeyboardHeightSourceChanged()
                    }
                    return@execute
                }
                detachCurrentLayout()
                attachLayout(target)
                if (notifyHeightChange) {
                    service.inputView?.onKeyboardHeightSourceChanged()
                }
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                if (inheritTextHeight) {
                    prepareCompanionKeyboardHeightPercentOverride()
                } else {
                    clearCompanionKeyboardHeightOverride()
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    private fun applyEffectiveTextLayer() {
        val effective = oneShotLayerKey ?: latchedLayerKey
        TextKeyboard.setForcedLayoutKey(effective)
    }

    private fun clearAllLayerOverrides() {
        latchedLayerKey = null
        oneShotLayerKey = null
        layerHistory.clear()
        noConfigAuxBarFallbackActive = false
        TextKeyboard.clearForcedLayoutKey()
    }

    private fun parseAuxActions(panel: FcitxEvent.InputPanelEvent.Data): List<AuxBarAction> {
        return if (panel.auxBarActions.isNotEmpty()) {
            panel.auxBarActions.toList()
        } else {
            panel.tabs.map { AuxBarAction(it.id, it.text, it.isSeparator) }
        }
    }

    private fun applyAuxActions(actions: List<AuxBarAction>) {
        currentKeyboard?.updateAuxBarActions(actions)
        val current = currentKeyboard
        val fallbackToPreedit = noConfigAuxBarFallbackActive &&
            currentKeyboardName == TextKeyboard.Name &&
            current?.auxBarPosition() == null
        if (fallbackToPreedit) {
            service.inputView?.updateAuxBar(actions.filter { a -> !a.isSeparator }, keyActionListener)
            return
        }
        if (current?.auxBarPosition() == null) {
            service.inputView?.clearAuxBar()
        }
    }

    private fun refreshNoConfigAuxBarFallback(previousHadAuxBarConfig: Boolean) {
        val currentHasAuxBarConfig = currentKeyboardName == TextKeyboard.Name &&
            TextKeyboard.getAuxBarConfig() != null
        noConfigAuxBarFallbackActive = previousHadAuxBarConfig && !currentHasAuxBarConfig
    }

    private fun consumeOneShotLayerIfNeeded(action: KeyAction) {
        if (oneShotLayerKey == null) return
        if (action is KeyAction.LayoutSwitchAction || action is KeyAction.LayerSwitchAction) return
        if (action is MacroAction && !action.hasExecutableStep()) return
        val hadAuxBarConfig = TextKeyboard.getAuxBarConfig() != null
        oneShotLayerKey = null
        applyEffectiveTextLayer()
        refreshNoConfigAuxBarFallback(hadAuxBarConfig)
        switchLayout(TextKeyboard.Name, remember = false)
    }

    private fun handleLayerSwitchAction(action: KeyAction.LayerSwitchAction) {
        val hadAuxBarConfig = TextKeyboard.getAuxBarConfig() != null
        val heightBefore = TextKeyboard.currentLayoutHeightPercentOverride()
        if (action.mode == KeyAction.LayerSwitchMode.BACK) {
            latchedLayerKey = layerHistory.removeLastOrNull()
            oneShotLayerKey = null
            applyLayerOverridesAndRelayout(hadAuxBarConfig, heightBefore)
            return
        }
        val resolved = TextKeyboard.resolveLayerTargetKey(action.target)
        if (resolved == null) {
            if (action.mode == KeyAction.LayerSwitchMode.TO) {
                clearAllLayerOverrides()
                val heightAfter = TextKeyboard.currentLayoutHeightPercentOverride()
                if (heightBefore != heightAfter) {
                    service.inputView?.onKeyboardHeightSourceChanged()
                }
            }
            return
        }
        when (action.mode) {
            KeyAction.LayerSwitchMode.TO -> {
                val oldEffective = oneShotLayerKey ?: latchedLayerKey
                if (oldEffective != resolved) {
                    // remember the previous layer so BACK can undo this switch
                    oldEffective?.let { layerHistory.addLast(it) }
                    if (layerHistory.size > MAX_LAYER_HISTORY) {
                        layerHistory.removeFirst()
                    }
                }
                latchedLayerKey = resolved
                oneShotLayerKey = null
            }
            KeyAction.LayerSwitchMode.OSL -> {
                oneShotLayerKey = resolved
            }
        }
        applyLayerOverridesAndRelayout(hadAuxBarConfig, heightBefore)
    }

    private fun applyLayerOverridesAndRelayout(hadAuxBarConfig: Boolean, heightBefore: Int?) {
        applyEffectiveTextLayer()
        refreshNoConfigAuxBarFallback(hadAuxBarConfig)
        val heightAfter = TextKeyboard.currentLayoutHeightPercentOverride()
        switchLayout(
            TextKeyboard.Name,
            remember = false,
            notifyHeightChange = heightBefore != heightAfter
        )
    }

    fun switchLayer(mode: KeyAction.LayerSwitchMode, target: String) {
        handleLayerSwitchAction(KeyAction.LayerSwitchAction(mode, target))
    }

    fun consumeOneShotLayer() {
        consumeOneShotLayerIfNeeded(KeyAction.MacroConsumedAction)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        // Clear latched/one-shot layer state and the BACK layer history; the forced layout
        // slot is updated in one pass by TextKeyboard.setNumericLayoutKey below.
        latchedLayerKey = null
        oneShotLayerKey = null
        layerHistory.clear()
        noConfigAuxBarFallbackActive = false
        preeditEmpty = true
        candidateEmpty = true
        composingState = false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val isNumericClass = inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE
        // Numeric editors short-circuit the built-in number keyboard when the app
        // preference "numeric_layout_override" names a resolvable custom layout. PIN-style
        // numeric password fields are included; their candidate bar stays empty via the
        // Password capability flag, mirroring text password fields.
        val numericLayoutKey = if (isNumericClass) TextKeyboard.resolveNumericLayoutKey() else null
        TextKeyboard.setNumericLayoutKey(numericLayoutKey)
        val targetLayout = when {
            numericLayoutKey != null -> TextKeyboard.Name
            isNumericClass -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false, inheritTextHeight = false)
        updateCompositionState()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        val heightBefore = TextKeyboard.currentLayoutHeightPercentOverride()
        clearAllLayerOverrides()
        currentKeyboard?.onInputMethodUpdate(ime)
        val heightAfter = TextKeyboard.currentLayoutHeightPercentOverride()
        // Avoid the IME-window height update path when the resolved keyboard height did not
        // actually change. This runs on every input method/sub-mode update (e.g. pressing
        // shift to toggle language) and used to be an unconditional, expensive window relayout.
        if (heightBefore != heightAfter) {
            service.inputView?.onKeyboardHeightSourceChanged()
        }
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
        currentKeyboard?.onReturnDrawableOverride(returnKeyDrawable.iconThemeDrawable)
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        preeditEmpty = empty
        updateCompositionState()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        val auxActions = parseAuxActions(data)
        if (auxActions == lastAuxActions) return
        lastAuxActions = auxActions
        applyAuxActions(auxActions)
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        candidateEmpty = data.candidates.isEmpty()
        updateCompositionState()
        val panelData = fcitx.runImmediately { inputPanelCached }
        val auxActions = parseAuxActions(panelData)
        if (auxActions != lastAuxActions) {
            lastAuxActions = auxActions
            applyAuxActions(auxActions)
        }
    }

    override fun onAttached() {
        IconThemeManager.addOnChangedListener(iconThemeListener)
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.auxBarListener = { scrollable, pinned ->
                service.inputView?.updateAuxBar(
                    (scrollable + pinned).filter { a -> !a.isSeparator }, keyActionListener
                )
            }
            it.onAttach()
        }
        applyAuxActions(lastAuxActions)
        notifyBarLayoutChanged()
        service.inputView?.requestBlurRefresh(retryFrames = 8)
    }

    override fun beforeAttached() {
        if (!usesCompanionKeyboardHeightOverride()) {
            clearCompanionKeyboardHeightOverride()
        }
    }

    override fun onDetached() {
        IconThemeManager.removeOnChangedListener(iconThemeListener)
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
            it.auxBarListener = null
        }
        service.inputView?.clearAuxBar()
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }

    fun updateBounds() {
        currentKeyboard?.updateBounds()
    }

    fun currentKeyBoundsInKeyboard(): List<android.graphics.Rect> {
        return currentKeyboard?.keyBoundsInKeyboard() ?: emptyList()
    }

    fun setTextScale(scale: Float) {
        currentTextScale = scale
        keyboards.values.forEach { it.setTextScale(scale) }
    }

    fun setHorizontalGapScale(scale: Float) {
        val target = scale.coerceIn(0.5f, 1f)
        currentKeyboard?.setHorizontalGapScale(target)
    }

    fun currentKeyboardHeightScaleFactor(): Float {
        return currentKeyboard?.keyboardHeightScaleFactor() ?: 1f
    }

    fun currentKeyboardHeightPercentOverride(): Int? {
        return currentKeyboard?.preferredKeyboardHeightPercentOverride()
            ?: companionHeightPercentOverride.takeIf { usesCompanionKeyboardHeightOverride() }
    }

    fun companionKeyboardHeightPercentOverride(): Int? = companionHeightPercentOverride

    fun companionKeyboardHeightPxOverride(): Int? = companionHeightPxOverride

    fun prepareCompanionKeyboardHeightPercentOverride() {
        service.inputView?.captureCurrentKeyboardHeightPxForCompanion()?.let {
            companionHeightPxOverride = it
        }
        companionHeightPercentOverride = service.inputView?.captureCurrentKeyboardHeightPercentForCompanion()
            ?: TextKeyboard.currentLayoutHeightPercentOverride()
            ?: currentKeyboard?.preferredKeyboardHeightPercentOverride()
            ?: companionHeightPercentOverride.takeIf { usesCompanionKeyboardHeightOverride() }
    }

    fun updateCurrentKeyboardHeightPercentOverride(percent: Int): Boolean {
        return if (currentKeyboard is TextKeyboard) {
            TextKeyboard.setCurrentLayoutHeightPercentOverride(percent)
        } else {
            false
        }
    }
}

private fun MacroAction.hasExecutableStep(): Boolean {
    return steps.any { step ->
        when (step) {
            is MacroStep.Down -> step.keys.isNotEmpty()
            is MacroStep.Up -> step.keys.isNotEmpty()
            is MacroStep.Tap -> step.keys.isNotEmpty()
            is MacroStep.Text -> step.text.isNotEmpty()
            is MacroStep.Edit -> step.action.isNotBlank()
            is MacroStep.AppAction -> step.id.isNotBlank()
            is MacroStep.Shortcut -> true
            is MacroStep.LayerSwitch ->
                step.mode == KeyAction.LayerSwitchMode.BACK || step.target.isNotBlank()
        }
    }
}
