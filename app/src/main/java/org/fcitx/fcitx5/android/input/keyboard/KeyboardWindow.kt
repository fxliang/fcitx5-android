/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
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
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

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
    private var latchedLayerKey: String? = null
    private var oneShotLayerKey: String? = null
    private var companionHeightPercentOverride: Int? = null
    private var companionHeightPxOverride: Int? = null

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

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

    /**
     * Refresh all keyboard layouts.
     * Call this when split keyboard settings (gap, threshold, enabled) change.
     */
    fun refreshAllKeyboards() {
        keyboards.values.forEach { it.refreshStyle() }
    }

    /**
     * Refresh only the currently visible keyboard layout.
     * Lightweight alternative to [refreshAllKeyboards] used e.g. after toggling
     * floating mode to force alt-text layout recalculation.
     */
    fun refreshCurrentKeyboard() {
        currentKeyboard?.refreshStyle()
    }

    /**
     * Check and apply font refresh if needed.
     * Call this when keyboard is about to show.
     */
    fun checkAndApplyFontRefresh() {
        if (org.fcitx.fcitx5.android.input.font.FontProviders.checkAndClearRefreshFlag()) {
            refreshAllKeyboards()
        }
    }

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.LayoutSwitchAction -> switchLayout(it.act)
            is KeyAction.LayerSwitchAction -> handleLayerSwitchAction(it)
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
        // Make the current IME available before TextKeyboard's constructor builds its layout,
        // avoiding an initial default layout followed by an immediate custom-layout rebuild.
        TextKeyboard.ime = fcitx.runImmediately { inputMethodEntryCached }
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
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
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.setTextScale(currentTextScale)
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
            updateCompositionState()
        }
    }

    fun switchLayout(
        to: String,
        remember: Boolean = true,
        inheritTextHeight: Boolean = true
    ) {
        val target = to.ifEmpty { lastSymbolType }
        ContextCompat.getMainExecutor(service).execute {
            if (target == TextKeyboard.Name || target == NumberKeyboard.Name) {
                if (target == TextKeyboard.Name) {
                    clearCompanionKeyboardHeightOverride()
                } else if (inheritTextHeight) {
                    prepareCompanionKeyboardHeightPercentOverride()
                } else {
                    clearCompanionKeyboardHeightOverride()
                }
                if (remember && target != TextKeyboard.Name) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) {
                    if (target == TextKeyboard.Name) {
                        currentKeyboard?.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
                        updateCompositionState()
                    }
                    service.inputView?.onKeyboardHeightSourceChanged()
                    return@execute
                }
                detachCurrentLayout()
                attachLayout(target)
                service.inputView?.onKeyboardHeightSourceChanged()
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
        TextKeyboard.clearForcedLayoutKey()
    }

    private fun consumeOneShotLayerIfNeeded(action: KeyAction) {
        if (oneShotLayerKey == null) return
        if (action is KeyAction.LayoutSwitchAction || action is KeyAction.LayerSwitchAction) return
        if (action is MacroAction && !action.hasExecutableStep()) return
        oneShotLayerKey = null
        applyEffectiveTextLayer()
        switchLayout(TextKeyboard.Name, remember = false)
    }

    private fun handleLayerSwitchAction(action: KeyAction.LayerSwitchAction) {
        val resolved = TextKeyboard.resolveLayerTargetKey(action.target)
        if (resolved == null) {
            if (action.mode == KeyAction.LayerSwitchMode.TO) {
                clearAllLayerOverrides()
                service.inputView?.onKeyboardHeightSourceChanged()
            }
            return
        }
        when (action.mode) {
            KeyAction.LayerSwitchMode.TO -> {
                latchedLayerKey = resolved
                oneShotLayerKey = null
            }
            KeyAction.LayerSwitchMode.OSL -> {
                oneShotLayerKey = resolved
            }
        }
        applyEffectiveTextLayer()
        switchLayout(TextKeyboard.Name, remember = false)
    }

    fun switchLayer(mode: KeyAction.LayerSwitchMode, target: String) {
        handleLayerSwitchAction(KeyAction.LayerSwitchAction(mode, target))
    }

    fun consumeOneShotLayer() {
        consumeOneShotLayerIfNeeded(KeyAction.MacroConsumedAction)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        clearAllLayerOverrides()
        preeditEmpty = true
        candidateEmpty = true
        composingState = false
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false, inheritTextHeight = false)
        updateCompositionState()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        clearAllLayerOverrides()
        currentKeyboard?.onInputMethodUpdate(ime)
        service.inputView?.onKeyboardHeightSourceChanged()
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        preeditEmpty = empty
        updateCompositionState()
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        candidateEmpty = data.candidates.isEmpty()
        updateCompositionState()
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
        service.inputView?.requestBlurRefresh(retryFrames = 8)
    }

    override fun beforeAttached() {
        if (!usesCompanionKeyboardHeightOverride()) {
            clearCompanionKeyboardHeightOverride()
        }
    }

    override fun onDetached() {
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
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
            is MacroStep.LayerSwitch -> step.target.isNotBlank()
        }
    }
}
