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
import org.fcitx.fcitx5.android.input.font.FontProviders
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
            // Seed the instance with the current input method so its first layout pass already
            // resolves the custom layout instead of building the default one twice.
            TextKeyboard.Name -> TextKeyboard(context, theme, TextKeyboard.ime)
            NumberKeyboard.Name -> NumberKeyboard(context, theme)
            else -> return null
        }
        keyboards[name] = keyboard
        return keyboard
    }

    /**
     * Mirror the real keyboard's input method into [TextKeyboard.ime]. Each keyboard resolves
     * its own layout from its instance state, so this companion field exists only for
     * window-level state that must follow the real input method (keyboard height overrides,
     * layer targets, the base entry of preview IMEs).
     */
    private fun syncCurrentInputMethod(ime: InputMethodEntry) {
        TextKeyboard.ime = ime
    }

    /** Whether the text keyboard's own last layout pass resolved an aux bar config. */
    private fun textKeyboardHasAuxBarConfig(): Boolean =
        (keyboards[TextKeyboard.Name] as? TextKeyboard)?.resolvedAuxBarConfig != null

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
    private var fontRefreshPending = false
    private var lastRefreshedFontGeneration = -1L
    private var companionHeightPercentOverride: Int? = null
    private var companionHeightPxOverride: Int? = null
    private val inputLifecycleTracker = KeyboardInputLifecycleTracker()

    /**
     * Keyboard layout state captured when a voice input session starts, restored when it
     * ends. Voice input commits text straight through the InputConnection, bypassing
     * fcitx's preedit/candidate events, and some editors react to that commit by
     * re-running an input restart that can leave the keyboard on a stale numeric
     * layer/override. Restoring the pre-voice layout gives the same reset a chat-window
     * switch would otherwise trigger.
     */
    private var voiceLayoutSnapshot: VoiceLayoutSnapshot? = null

    private data class VoiceLayoutSnapshot(
        val currentKeyboardName: String,
        val latchedLayerKey: String?,
        val oneShotLayerKey: String?,
        val layerHistory: List<String>,
        val numericOverride: NumericLayoutOverrideController.Snapshot
    )

    internal val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    /**
     * Whether a numeric layout is on screen: either the built-in number keyboard, or the text
     * keyboard rendering a numeric override (session or manual) resolved from the
     * "numeric_layout_override" preference. Callers must have an attached layout, since an empty
     * [currentKeyboardName] cannot tell the two apart.
     */
    private fun numericLayoutShowing(): Boolean =
        currentKeyboardName == NumberKeyboard.Name || TextKeyboard.isNumericLayoutShowing()

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
        if (FontProviders.checkAndClearRefreshFlag()) {
            // Keep the visible keyboard intact until the new font cache is ready. Rebuilding
            // immediately would force cache-miss lookups onto the first input frame.
            keyboards.values.forEach { it.clearReusableRowsCache() }
            preloadFontsForKeyboard()
        }
    }

    private fun preloadFontsForKeyboard() {
        FontProviders.preloadFontsAsync {
            ContextCompat.getMainExecutor(service).execute {
                // The completion may also fire for reloads that produced identical font
                // data (e.g. retry ticks while a configured font file is still missing).
                // Gate on the served-data revision so rows are rebuilt exactly once per
                // real font change; a stale callback can no longer drop the refresh (the
                // old token was invalidated by onStartInput running right after
                // checkAndApplyFontRefresh, which silently killed this path).
                val generation = FontProviders.fontGeneration
                if (generation == lastRefreshedFontGeneration) return@execute
                lastRefreshedFontGeneration = generation
                fontRefreshPending = true
                applyPendingFontRefresh()
            }
        }
    }

    private fun applyPendingFontRefresh() {
        if (!fontRefreshPending || !::keyboardView.isInitialized || !keyboardView.isAttachedToWindow) return
        fontRefreshPending = false
        refreshCurrentKeyboard()
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
        val currentIme = fcitx.runImmediately { inputMethodEntryCached }
        inputLifecycleTracker.resetInputMethod(currentIme)
        syncCurrentInputMethod(currentIme)
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        preloadFontsForKeyboard()
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
        // Publish the input method before creating/seeding the keyboard: getOrCreateKeyboard
        // may construct a fresh TextKeyboard whose first layout pass must already see the
        // freshest input method instead of a stale or absent companion mirror.
        val attachIme = fcitx.runImmediately { inputMethodEntryCached }
        syncCurrentInputMethod(attachIme)
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
            it.onInputMethodUpdate(attachIme)
            applyAuxActions(lastAuxActions)
            updateCompositionState()
        }
    }

    fun switchLayout(
        to: String,
        remember: Boolean = true,
        inheritTextHeight: Boolean = true,
        notifyHeightChange: Boolean = true,
        fromUserKey: Boolean = false,
        restartKeepingLayout: Boolean = false
    ) {
        val requestedTarget = to.ifEmpty { lastSymbolType }
        var target = requestedTarget
        // A restart re-affirming the layout the user is already looking at must not run either
        // branch below: activateManualNumericLayout would newly latch a manual override the user
        // never asked for, and the "ABC"-key path would release the one they did ask for.
        if (!restartKeepingLayout) {
            // The built-in NumberKeyboard is short-circuited by the configured numeric layout
            // no matter how it is reached: a numeric editor via onStartInput, a manual
            // LayoutSwitchKey, the symbol picker numpad button, or a preset/macro key targeting
            // the Number keyboard. In a numeric editor session the override is sticky; a manual
            // switch from a text session is remembered so switching back to Text restores the
            // normal text keyboard.
            if (target == NumberKeyboard.Name) {
                val override = TextKeyboard.resolveNumericLayoutKey()
                if (override != null && TextKeyboard.activateManualNumericLayout(override)) {
                    target = TextKeyboard.Name
                }
            } else if (target == TextKeyboard.Name && fromUserKey) {
                // An explicit user key targeting the text keyboard (e.g. an "ABC"-style
                // LayoutSwitchKey) resets latched/one-shot layers so the normal text keyboard
                // shows again even when a MacroKey layer switch is currently latched, and
                // releases any numeric override for the rest of the session.
                latchedLayerKey = null
                oneShotLayerKey = null
                layerHistory.clear()
                noConfigAuxBarFallbackActive = false
                applyEffectiveTextLayer()
                if (TextKeyboard.isNumericLayoutActive()) {
                    TextKeyboard.dismissNumericLayoutOverride()
                } else {
                    TextKeyboard.releaseManualNumericLayout()
                }
            }
        }
        // Note: an internal Text -> Text switch (layer relayout, one-shot consumption,
        // onStartInput) must NOT release the manual numeric layout here: the layer latch
        // was applied moments before via applyEffectiveTextLayer and releasing would
        // clobber it back to the session fallback.
        ContextCompat.getMainExecutor(service).execute {
            if (target == TextKeyboard.Name || target == NumberKeyboard.Name) {
                // A same-editor restart must not touch the companion height captured when the
                // number pad was opened; re-capturing or clearing it would resize the visible
                // keyboard on every committed character.
                if (!restartKeepingLayout) {
                    if (target == TextKeyboard.Name) {
                        clearCompanionKeyboardHeightOverride()
                    } else if (inheritTextHeight) {
                        prepareCompanionKeyboardHeightPercentOverride()
                    } else {
                        clearCompanionKeyboardHeightOverride()
                    }
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
                        val reaffirmedIme = fcitx.runImmediately { inputMethodEntryCached }
                        syncCurrentInputMethod(reaffirmedIme)
                        currentKeyboard?.onInputMethodUpdate(reaffirmedIme)
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
            textKeyboardHasAuxBarConfig()
        noConfigAuxBarFallbackActive = previousHadAuxBarConfig && !currentHasAuxBarConfig
    }

    private fun consumeOneShotLayerIfNeeded(action: KeyAction) {
        if (oneShotLayerKey == null) return
        if (action is KeyAction.LayoutSwitchAction || action is KeyAction.LayerSwitchAction) return
        if (action is MacroAction && !action.hasExecutableStep()) return
        val hadAuxBarConfig = textKeyboardHasAuxBarConfig()
        oneShotLayerKey = null
        applyEffectiveTextLayer()
        refreshNoConfigAuxBarFallback(hadAuxBarConfig)
        switchLayout(TextKeyboard.Name, remember = false)
    }

    private fun handleLayerSwitchAction(action: KeyAction.LayerSwitchAction) {
        val hadAuxBarConfig = textKeyboardHasAuxBarConfig()
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

    /**
     * Remember the keyboard layout the user is looking at when a voice session begins.
     * No-op while a voice session is already active (e.g. provider auto-restart).
     */
    fun onVoiceInputStarted() {
        if (voiceLayoutSnapshot != null) return
        android.util.Log.i(
            "FcitxVoiceKbd",
            "voice started snapshot name=$currentKeyboardName latched=$latchedLayerKey " +
                "oneShot=$oneShotLayerKey numeric=" + TextKeyboard.snapshotNumericLayoutOverride()
        )
        voiceLayoutSnapshot = VoiceLayoutSnapshot(
            currentKeyboardName = currentKeyboardName,
            latchedLayerKey = latchedLayerKey,
            oneShotLayerKey = oneShotLayerKey,
            layerHistory = layerHistory.toList(),
            numericOverride = TextKeyboard.snapshotNumericLayoutOverride()
        )
    }

    /**
     * Restore the layout captured by [onVoiceInputStarted] once the voice session ends.
     * Re-applies the same latched/one-shot layers and numeric override so the commit
     * cannot leave a text editor stuck on a numeric layout.
     */
    fun onVoiceInputFinished() {
        val snapshot = voiceLayoutSnapshot ?: return
        voiceLayoutSnapshot = null
        android.util.Log.i(
            "FcitxVoiceKbd",
            "voice finished restore name=${snapshot.currentKeyboardName} " +
                "latched=${snapshot.latchedLayerKey} oneShot=${snapshot.oneShotLayerKey} " +
                "numeric=${snapshot.numericOverride} currentName=$currentKeyboardName " +
                "currentLatched=$latchedLayerKey currentOneShot=$oneShotLayerKey " +
                "currentNumeric=" + TextKeyboard.snapshotNumericLayoutOverride()
        )
        latchedLayerKey = snapshot.latchedLayerKey
        oneShotLayerKey = snapshot.oneShotLayerKey
        layerHistory.clear()
        layerHistory.addAll(snapshot.layerHistory)
        TextKeyboard.restoreNumericLayoutOverride(snapshot.numericOverride)
        applyEffectiveTextLayer()
        if (currentKeyboardName != snapshot.currentKeyboardName) {
            switchLayout(snapshot.currentKeyboardName, remember = false, restartKeepingLayout = true)
        } else {
            currentKeyboard?.refreshStyle()
            notifyBarLayoutChanged()
        }
        android.util.Log.i(
            "FcitxVoiceKbd",
            "voice finished afterRestore name=$currentKeyboardName latched=$latchedLayerKey " +
                "oneShot=$oneShotLayerKey numeric=" + TextKeyboard.snapshotNumericLayoutOverride()
        )
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean) {
        // Clear latched/one-shot layer state and the BACK layer history. The forced layout slot
        // is updated in one pass further down, by setNumericLayoutKey when EditorInfo is applied
        // or by clearForcedLayoutKey when a hand-picked layout is kept across a restart.
        latchedLayerKey = null
        oneShotLayerKey = null
        layerHistory.clear()
        noConfigAuxBarFallbackActive = false
        preeditEmpty = true
        candidateEmpty = true
        // Let updateCompositionState notify the active keyboard as well. Resetting
        // composingState directly can leave compose-aware keys in their old view.
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val isNumericClass = inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE
        val inputClassChanged = inputLifecycleTracker.isInputClassChanged(inputClass)
        inputLifecycleTracker.recordInputClass(inputClass)
        // A genuinely different editor supersedes a pending voice layout restore; a same-editor
        // restart does not, because the restart may have preserved a corrupted numeric layout
        // that the voice-session end still needs to roll back.
        if (voiceLayoutSnapshot != null && inputClassChanged) {
            voiceLayoutSnapshot = null
        }
        // Some editors call restartInput after every committed character (Alipay's bank-card
        // field) while keeping TYPE_CLASS_TEXT. Re-applying EditorInfo there would undo a
        // hand-picked keyboard on every key press, so a selection that deviates from the
        // input class is kept until the editor or its input class actually changes.
        val keepLayoutRequested = currentKeyboardName.isNotEmpty() &&
            shouldKeepCurrentLayoutOnStartInput(
                restarting = restarting,
                inputClassChanged = inputClassChanged,
                numericLayoutShowing = numericLayoutShowing(),
                numericLayoutExpected = isNumericClass
            )
        // A restart keeping the same editor may still arrive after the layout profile or the
        // "numeric_layout_override" preference changed. Re-resolve the override before honouring
        // the request: once it no longer resolves, EditorInfo must be re-applied so the editor
        // falls back to the built-in number keyboard instead of a stale custom layout.
        val overrideInvalidated = keepLayoutRequested && TextKeyboard.isNumericLayoutShowing() &&
            TextKeyboard.revalidateNumericLayoutOverride()
        val keepCurrentLayout = keepLayoutRequested && !overrideInvalidated
        // Read before clearForcedLayoutKey below, which can change the resolved height.
        val heightBefore =
            if (keepCurrentLayout) TextKeyboard.currentLayoutHeightPercentOverride() else null
        val targetLayout = if (keepCurrentLayout) {
            // Only the temporary layer is dropped: clearForcedLayoutKey falls back to the
            // remembered manual/session numeric layout, whereas setNumericLayoutKey below
            // would wipe a manual custom number pad and re-arm a dismissed override.
            TextKeyboard.clearForcedLayoutKey()
            currentKeyboardName
        } else {
            // Numeric editors short-circuit the built-in number keyboard when the app
            // preference "numeric_layout_override" names a resolvable custom layout. PIN-style
            // numeric password fields are included; their candidate bar stays empty via the
            // Password capability flag, mirroring text password fields.
            val numericLayoutKey =
                if (isNumericClass) TextKeyboard.resolveNumericLayoutKey() else null
            TextKeyboard.setNumericLayoutKey(numericLayoutKey)
            when {
                numericLayoutKey != null -> TextKeyboard.Name
                isNumericClass -> NumberKeyboard.Name
                else -> TextKeyboard.Name
            }
        }
        // Both paths converge on switchLayout so a same-layout restart still refreshes the
        // aux bar. restartKeepingLayout tells it to leave the numeric override and the
        // companion height alone; the normal path would clear the height and resize the
        // visible keyboard on every committed character. The height-source notification is a
        // window relayout, so on a keep-layout restart it only fires when the resolved height
        // actually changed.
        val notifyHeightChange = !keepCurrentLayout ||
            heightBefore != TextKeyboard.currentLayoutHeightPercentOverride()
        switchLayout(
            targetLayout,
            remember = false,
            inheritTextHeight = false,
            notifyHeightChange = notifyHeightChange,
            restartKeepingLayout = keepCurrentLayout
        )
        updateCompositionState()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        // Focus restarts deactivate/reactivate the same fcitx input method and can emit one
        // or more duplicate IMChangeEvents. Only a real input-method/sub-mode change is an
        // explicit user move away from a manually selected numeric layout. Releasing it for
        // a duplicate event would undo the restart preservation above on every key press.
        if (inputLifecycleTracker.onInputMethodUpdate(ime)) {
            TextKeyboard.releaseManualNumericLayoutOnImeUpdate()
        }
        val heightBefore = TextKeyboard.currentLayoutHeightPercentOverride()
        clearAllLayerOverrides()
        // Publish the new input method before resolving the height again: the comparison below
        // relies on currentLayoutHeightPercentOverride() seeing the updated entry.
        syncCurrentInputMethod(ime)
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
        applyPendingFontRefresh()
        keyboardView.post { applyPendingFontRefresh() }
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
        bar.onKeyboardLayoutSwitched(numericLayoutShowing())
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
