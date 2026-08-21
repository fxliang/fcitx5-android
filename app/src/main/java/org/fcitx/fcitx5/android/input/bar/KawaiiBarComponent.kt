/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Color
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.action.executeMacroSteps
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.bar.ui.TitleUi
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.loadThumbnailBitmap
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

private const val VOICE_INPUT_TAG = "FcitxVoiceInput"

class KawaiiBarComponent : UniqueViewComponent<KawaiiBarComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val windowManager: InputWindowManager by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()

    var onFloatingToggleListener: (() -> Unit)? = null
    var onFloatingLongPressListener: (() -> Unit)? = null

    fun setFloatingState(isFloating: Boolean) {
        idleUi.buttonsUi.setFloatingState(isFloating)
    }

    fun setOneHandKeyboardState(isOneHanded: Boolean) {
        idleUi.buttonsUi.setOneHandKeyboardState(isOneHanded)
    }

    fun refreshButtonsLayout() {
        idleUi.buttonsUi.refreshLayout()
    }

    /**
     * Macro-simulated key events can move focus out of touch mode. Clear any
     * stale focus from toolbar descendants before Android renders a focus highlight.
     */
    fun clearFocusState() {
        view.clearFocus()
        idleUi.root.clearFocus()
        idleUi.buttonsUi.root.clearFocus()
    }

    private val prefs = AppPrefs.getInstance()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandedCandidateStyle by prefs.keyboard.expandedCandidateStyle
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword
    private val showVoiceInputButton by prefs.keyboard.showVoiceInputButton
    private val preferredVoiceInput by prefs.keyboard.preferredVoiceInput

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false

    private enum class NumberRowState { Auto, ForceShow, ForceHide }

    private var numberRowState = NumberRowState.Auto

    @Keep
    private val onClipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener {
            if (!clipboardSuggestion.getValue()) return@OnClipboardUpdateListener
            service.lifecycleScope.launch {
                if (it.text.isEmpty()) {
                    isClipboardFresh = false
                } else {
                    val isImage = it.type.startsWith("image/")
                    if (isImage) {
                        idleUi.clipboardUi.text.visibility = View.GONE
                        idleUi.clipboardUi.preview.visibility = View.VISIBLE
                        val bitmap = it.loadThumbnailBitmap(context)
                        if (bitmap != null) {
                            idleUi.clipboardUi.preview.setImageBitmap(bitmap)
                        } else {
                            context.drawable(R.drawable.ic_baseline_image_24)?.apply {
                                setTint(theme.altKeyTextColor)
                            }?.let {
                                idleUi.clipboardUi.preview.setImageDrawable(it)
                            }
                        }
                    } else {
                        idleUi.clipboardUi.preview.visibility = View.GONE
                        idleUi.clipboardUi.text.visibility = View.VISIBLE
                        idleUi.clipboardUi.text.text = if (it.sensitive && clipboardMaskSensitive) {
                            ClipboardEntry.BULLET.repeat(min(42, it.text.length))
                        } else {
                            it.text.take(42)
                        }
                    }
                    isClipboardFresh = true
                    launchClipboardTimeoutJob()
                }
                evalIdleUiState()
            }
        }

    @Keep
    private val onClipboardSuggestionUpdateListener =
        ManagedPreference.OnChangeListener<Boolean> { _, it ->
            if (!it) {
                isClipboardFresh = false
                evalIdleUiState()
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
            }
        }

    @Keep
    private val onClipboardTimeoutUpdateListener =
        ManagedPreference.OnChangeListener<Int> { _, _ ->
            when (idleUi.currentState) {
                IdleUi.State.Clipboard -> {
                    // renew timeout when clipboard suggestion is present
                    launchClipboardTimeoutJob()
                }
                else -> {}
            }
        }

    @Keep
    private val onIconThemeChangeListener =
        IconThemeManager.OnIconThemeChangeListener { _idleUi?.refreshAllIcons() }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardItemTimeout.getValue() * 1000L
        // never transition to ClipboardTimedOut state when timeout is disabled (<= 0)
        if (timeout <= 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalIdleUiState()
        }
    }

    private fun evalIdleUiState(fromUser: Boolean = false) {
        val newState = when {
            numberRowState == NumberRowState.ForceShow -> IdleUi.State.NumberRow
            isClipboardFresh -> IdleUi.State.Clipboard
            isInlineSuggestionPresent -> IdleUi.State.InlineSuggestion
            isCapabilityFlagsPassword && !isKeyboardLayoutNumber && numberRowState != NumberRowState.ForceHide -> IdleUi.State.NumberRow
            /**
             * state matrix:
             *                               expandToolbarByDefault
             *                          |   \   |    true |   false
             * toolbarManuallyToggled   |  true |   Empty | Toolbar
             *                          | false | Toolbar |   Empty
             */
            expandToolbarByDefault == prefs.keyboard.toolbarManuallyToggled.getValue() -> IdleUi.State.Empty
            else -> IdleUi.State.Toolbar
        }
        if (newState == idleUi.currentState) return
        idleUi.updateState(newState, fromUser)
    }

    private fun hideKeyboardAndExitAdjustingMode() {
        service.inputView?.exitAdjustingMode()
        service.requestHideSelf(0)
    }

    private val hideKeyboardCallback = View.OnClickListener {
        hideKeyboardAndExitAdjustingMode()
    }

    private val swipeDownExpandCallback = CustomGestureView.OnGestureListener { _, e ->
        if (e.type == CustomGestureView.GestureType.Up && e.totalY > 0) {
            hideKeyboardAndExitAdjustingMode()
            true
        } else false
    }

    // Combined gesture: determine primary direction by comparing totalX and totalY.
    // - If horizontal is dominant and left, show number row (when allowed).
    // - If vertical is dominant and down, hide keyboard.
    private val swipeHideKeyboardCallback = CustomGestureView.OnGestureListener { v, e ->
        require(v is ToolButton)
        val numberRowAvailable = isCapabilityFlagsPassword && !isKeyboardLayoutNumber
        if (numberRowAvailable) {
            val dir = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1 else -1
            // `e.x` and `e.y` are relative to the view's top-left corner
            val centerX = e.x - v.width / 2f
            val centerY = e.y - v.height / 2f

            val distance = hypot(centerX, centerY)
            // the button is ↓, so apply -90 degrees offset
            var angle = atan2(-centerX, centerY) * (180f / PI.toFloat())

            when (e.type) {
                CustomGestureView.GestureType.Move -> {
                    angle = if (angle in -45f..45f) {
                        angle.coerceIn(-10f, 10f)
                    } else abs(angle).coerceIn(90f - 10f, 90f + 10f) * dir
                    v.iconRotation = angle
                }
                CustomGestureView.GestureType.Up -> {
                    val handled = when (angle) {
                        in -45f..45f if distance > v.swipeThresholdY -> {
                            hideKeyboardAndExitAdjustingMode()
                            true
                        }
                        !in -45f..45f if distance > v.swipeThresholdX -> {
                            v.iconRotation = 90f * dir
                            numberRowState = NumberRowState.ForceShow
                            evalIdleUiState(fromUser = true)
                            true
                        }
                        else -> false
                    }
                    v.iconRotation = 0f
                    return@OnGestureListener handled
                }
                else -> {}
            }
        }

        if (e.type == CustomGestureView.GestureType.Up && abs(e.totalY) > abs(e.totalX) && e.totalY > 0) {
            hideKeyboardAndExitAdjustingMode()
            true
        } else false
    }

    private var voiceInputSubtype: Pair<String, InputMethodSubtype>? = null

    private val switchToVoiceInputCallback = View.OnClickListener {
        Log.i(
            VOICE_INPUT_TAG,
            "toolbar voice click preferred=$preferredVoiceInput " +
                "isProvider=${VoiceInputProviderManager.isProviderId(preferredVoiceInput)} " +
                "subtype=${voiceInputSubtype != null}"
        )
        if (VoiceInputProviderManager.isProviderId(preferredVoiceInput)) {
            if (VoiceInputProviderManager.isActive()) {
                idleUi.showVoiceStatus(service.getString(R.string.voice_status_recognizing))
            }
            VoiceInputProviderManager.toggle(
                service = service,
                id = preferredVoiceInput,
                onReady = { idleUi.showVoiceStatus(service.getString(R.string.voice_status_listening)) },
                onPartialResult = { /* recognized text goes to the input field, not the bar */ },
                onLevel = { rms -> idleUi.updateVoiceLevel(rms) },
                onStatus = { status -> idleUi.showVoiceStatus(status) },
                onFinished = { idleUi.hideVoiceStatus() },
                onError = { msg ->
                    idleUi.hideVoiceStatus()
                    Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
                },
            )
            return@OnClickListener
        }
        val (id, subtype) = voiceInputSubtype ?: return@OnClickListener
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    // Load buttons config from file or use default
    // Note: 'more' button is always added automatically at the end if not present in config
    private fun loadButtonsConfig(): Triple<List<ConfigurableButton>, ConfigurableButton, ConfigurableButton> {
        val snapshot = ConfigProviders.readButtonsLayoutConfig<ButtonsLayoutConfig>()
        val config = snapshot?.value ?: ButtonsLayoutConfig.default()

        // Filter out 'more' button from config (it's always added automatically)
        val filteredButtons = config.kawaiiBarButtons.filter { it.id != "more" }

        // Always add 'more' button at the end
        val buttons = filteredButtons + ConfigurableButton("more")

        // Update watched icon filenames for hot-reload
        val allButtons = buttons + listOf(config.toolbarToggleButton, config.hideKeyboardButton)
        val iconFileNames = allButtons
            .mapNotNull { it.icon }
            .filter { it.startsWith(ButtonIconFile.PREFIX) }
            .map { it.removePrefix(ButtonIconFile.PREFIX).substringAfterLast('/') }
            .toSet()
        ConfigProviders.setWatchedIconFileNames(iconFileNames)

        return Triple(buttons, config.toolbarToggleButton, config.hideKeyboardButton)
    }

    private var _idleUi: IdleUi? = null
    private var currentButtonsConfig: List<ConfigurableButton> = emptyList()
    private var currentToolbarToggleConfig: ConfigurableButton = ConfigurableButton("toolbar_toggle")
    private var currentHideKeyboardConfig: ConfigurableButton = ConfigurableButton("hide_keyboard")
    
    private val idleUi: IdleUi
        get() {
            if (_idleUi == null) {
                val (buttons, toolbarToggle, hideKeyboard) = loadButtonsConfig()
                currentButtonsConfig = buttons
                currentToolbarToggleConfig = toolbarToggle
                currentHideKeyboardConfig = hideKeyboard
                _idleUi = IdleUi(context, theme, popup, commonKeyActionListener, currentButtonsConfig,
                    currentToolbarToggleConfig, currentHideKeyboardConfig)
                setupIdleUiCallbacks(_idleUi!!)
            }
            return _idleUi!!
        }

    private fun setupIdleUiCallbacks(ui: IdleUi) {
        fun restoreVirtualKeyboardMode() {
            service.restoreVirtualKeyboardForKawaiiBarAction()
        }

        ui.menuButton.setOnClickListener {
            restoreVirtualKeyboardMode()
            if (service.inputView?.isButtonsAdjustingOverlayVisible == true) {
                service.inputView?.hideButtonsAdjustingOverlay()
                return@setOnClickListener
            }
            when (ui.currentState) {
                IdleUi.State.Empty -> {
                    prefs.keyboard.toolbarManuallyToggled.setValue(!expandToolbarByDefault)
                    evalIdleUiState(fromUser = true)
                }
                IdleUi.State.Toolbar -> {
                    prefs.keyboard.toolbarManuallyToggled.setValue(expandToolbarByDefault)
                    evalIdleUiState(fromUser = true)
                }
                else -> {
                    prefs.keyboard.toolbarManuallyToggled.setValue(!expandToolbarByDefault)
                    ui.updateState(IdleUi.State.Toolbar, fromUser = true)
                }
            }
            // reset timeout timer (if present) when user switch layout
            if (clipboardTimeoutJob != null) {
                launchClipboardTimeoutJob()
            }
        }
        ui.menuButton.setOnLongClickListener {
            restoreVirtualKeyboardMode()
            if (service.inputView?.isButtonsAdjustingOverlayVisible == true) {
                service.inputView?.hideButtonsAdjustingOverlay()
            } else {
                service.inputView?.showButtonsAdjustingOverlay()
            }
            true
        }
        ui.hideKeyboardButton.apply {
            setOnClickListener {
                restoreVirtualKeyboardMode()
                hideKeyboardCallback.onClick(it)
            }
            swipeEnabled = true
            swipeThresholdY = dp(HEIGHT.toFloat())
            onGestureListener = swipeHideKeyboardCallback
        }
        ui.buttonsUi.apply {
            // Setup click listeners using ButtonAction
            ButtonAction.allConfigurableActions.forEach { action ->
                setOnClickListener(action.id) {
                    restoreVirtualKeyboardMode()
                    action.execute(
                        context = context,
                        service = service,
                        fcitx = fcitx,
                        windowManager = windowManager,
                        view = null,
                        onActionComplete = {
                            // Refresh UI state after action completion
                            when (action.id) {
                                "floating_toggle" -> evalIdleUiState()
                                "one_handed_keyboard" -> updateButtonsState()
                            }
                        }
                    )
                }
            }
            
            // Special handling for 'more' button
            setOnClickListener("more") {
                restoreVirtualKeyboardMode()
                windowManager.attachWindow(StatusAreaWindow())
            }

            setOnClickListener("floating_toggle") {
                restoreVirtualKeyboardMode()
                val action = ButtonAction.fromId("floating_toggle")
                if (onFloatingToggleListener != null) {
                    onFloatingToggleListener?.invoke()
                    updateButtonsState()
                } else {
                    action?.execute(
                        context = context,
                        service = service,
                        fcitx = fcitx,
                        windowManager = windowManager,
                        view = null,
                        onActionComplete = { updateButtonsState() }
                    )
                }
            }

            setOnLongClickListener("floating_toggle") {
                restoreVirtualKeyboardMode()
                if (onFloatingLongPressListener != null) {
                    onFloatingLongPressListener?.invoke()
                } else {
                    ButtonAction.fromId("floating_toggle")?.onLongPress(
                        context = context,
                        service = service,
                        fcitx = fcitx,
                        windowManager = windowManager,
                        view = ui.buttonsUi.root
                    )
                }
                true
            }

            // Keep language switch long-press behavior aligned with keyboard globe key.
            setOnLongClickListener("language_switch") {
                restoreVirtualKeyboardMode()
                ButtonAction.fromId("language_switch")?.onLongPress(
                    context = context,
                    service = service,
                    fcitx = fcitx,
                    windowManager = windowManager,
                    view = ui.buttonsUi.root
                )
                true
            }

            // Setup click listeners for custom action buttons
            setupCustomActionListeners(ui)
        }
        ui.numberRow.onCollapseListener = {
            numberRowState = NumberRowState.ForceHide
            evalIdleUiState(fromUser = true)
        }
        ui.clipboardUi.suggestionView.apply {
            setOnClickListener {
                ClipboardManager.lastEntry?.let {
                    if (it.isUriEntry()) {
                        windowManager.attachWindow(ClipboardWindow(ClipboardCategory.Media))
                    } else {
                        service.commitText(it.text)
                    }
                }
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
                isClipboardFresh = false
                evalIdleUiState()
            }
            setOnLongClickListener {
                ClipboardManager.lastEntry?.let {
                    AppUtil.launchClipboardEdit(context, it.id, true)
                }
                true
            }
        }
    }

    // Reload Kawaii Bar buttons config (called when config file changes)
    fun reloadButtonsConfig() {
        val (newButtons, newToolbarToggle, newHideKeyboard) = loadButtonsConfig()
        val buttonsChanged = newButtons != currentButtonsConfig
        val systemButtonsChanged = newToolbarToggle != currentToolbarToggleConfig ||
            newHideKeyboard != currentHideKeyboardConfig
        if (buttonsChanged || systemButtonsChanged) {
            cleanupOrphanedIconFiles(
                currentButtonsConfig, newButtons,
                currentToolbarToggleConfig, newToolbarToggle,
                currentHideKeyboardConfig, newHideKeyboard
            )
        }
        if (buttonsChanged) {
            currentButtonsConfig = newButtons
            _idleUi?.buttonsUi?.updateConfig(newButtons)
            _idleUi?.let { setupCustomActionListeners(it) }
            updateButtonsState()
        }
        if (systemButtonsChanged) {
            currentToolbarToggleConfig = newToolbarToggle
            currentHideKeyboardConfig = newHideKeyboard
            _idleUi?.updateSystemButtonConfigs(newToolbarToggle, newHideKeyboard)
        }
    }

    // Reload button icons from disk (called when icon files change on disk)
    fun reloadButtonIcons() {
        _idleUi?.buttonsUi?.reloadIcons()
        _idleUi?.reloadSystemButtonIcons()
    }

    private fun extractIconFileNames(buttons: List<ConfigurableButton>): Set<String> {
        return buttons
            .mapNotNull { it.icon }
            .filter { it.startsWith(ButtonIconFile.PREFIX) }
            .map { it.removePrefix(ButtonIconFile.PREFIX).substringAfterLast('/') }
            .toSet()
    }

    private fun getAllReferencedIconFileNames(): Set<String> {
        val snapshot = ConfigProviders.readButtonsLayoutConfig<ButtonsLayoutConfig>()
        if (snapshot == null) return emptySet()
        val config = snapshot.value
        val allButtons = config.kawaiiBarButtons + config.statusAreaButtons +
            listOf(config.toolbarToggleButton, config.hideKeyboardButton)
        return extractIconFileNames(allButtons)
    }

    private fun cleanupOrphanedIconFiles(
        oldKawaiiButtons: List<ConfigurableButton>,
        newKawaiiButtons: List<ConfigurableButton>,
        oldToolbarToggle: ConfigurableButton = ConfigurableButton("toolbar_toggle"),
        newToolbarToggle: ConfigurableButton = ConfigurableButton("toolbar_toggle"),
        oldHideKeyboard: ConfigurableButton = ConfigurableButton("hide_keyboard"),
        newHideKeyboard: ConfigurableButton = ConfigurableButton("hide_keyboard")
    ) {
        val oldIcons = extractIconFileNames(oldKawaiiButtons) +
            extractIconFileNames(listOf(oldToolbarToggle, oldHideKeyboard))
        val newLocalIcons = extractIconFileNames(newKawaiiButtons) +
            extractIconFileNames(listOf(newToolbarToggle, newHideKeyboard))
        // Icons that were in the old config but not in the new local config
        val removed = oldIcons - newLocalIcons
        if (removed.isEmpty()) return
        // But don't delete if still referenced by any button in the full config
        val allNewIcons = getAllReferencedIconFileNames()
        val orphaned = removed - allNewIcons
        if (orphaned.isEmpty()) return
        val extDir = context.getExternalFilesDir(null) ?: return
        val iconDir = File(extDir, ButtonIconFile.DIR)
        orphaned.forEach { filename ->
            val file = File(iconDir, filename)
            if (file.exists() && file.isFile) {
                try { file.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun setupCustomActionListeners(ui: IdleUi) {
        fun restoreVirtualKeyboardMode() {
            service.restoreVirtualKeyboardForKawaiiBarAction()
        }
        currentButtonsConfig.forEach { button ->
            val steps = button.macroSteps ?: return@forEach
            if (steps.isEmpty()) return@forEach
            ui.buttonsUi.setOnClickListener(button.id) {
                restoreVirtualKeyboardMode()
                executeMacroSteps(steps, service, context)
            }
        }
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, horizontalCandidate.view).apply {
            expandButton.apply {
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                onGestureListener = swipeDownExpandCallback
            }
        }
    }

    private val titleUi by lazy {
        TitleUi(context, theme)
    }

    private val barStateMachine = KawaiiBarStateMachine.new {
        switchUiByState(it)
    }

    val expandButtonStateMachine = ExpandButtonStateMachine.new {
        when (it) {
            ClickToAttachWindow -> {
                setExpandButtonToAttach()
                setExpandButtonEnabled(true)
            }
            ClickToDetachWindow -> {
                setExpandButtonToDetach()
                setExpandButtonEnabled(true)
            }
            Hidden -> {
                setExpandButtonEnabled(false)
            }
        }
    }

    // set expand candidate button to create expand candidate
    private fun setExpandButtonToAttach() {
        candidateUi.expandButton.setOnClickListener {
            service.restoreVirtualKeyboardForKawaiiBarAction()
            windowManager.attachWindow(
                when (expandedCandidateStyle) {
                    ExpandedCandidateStyle.Grid -> GridExpandedCandidateWindow()
                    ExpandedCandidateStyle.Flexbox -> FlexboxExpandedCandidateWindow()
                }
            )
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_more_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.expand_candidates_list)
    }

    // set expand candidate button to close expand candidate
    private fun setExpandButtonToDetach() {
        candidateUi.expandButton.setOnClickListener {
            service.restoreVirtualKeyboardForKawaiiBarAction()
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_less_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.hide_candidates_list)
    }

    // should be used with setExpandButtonToAttach or setExpandButtonToDetach
    private fun setExpandButtonEnabled(enabled: Boolean) {
        candidateUi.expandButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun switchUiByState(state: KawaiiBarStateMachine.State) {
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != titleUi.root) {
            titleUi.setReturnButtonOnClickListener { }
            titleUi.setTitle("")
            titleUi.removeExtension()
        }
        view.displayedChild = index
    }

    override val view by lazy {
        ViewAnimator(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            backgroundColor =
                if (ThemeManager.prefs.keyBorder.getValue()) Color.TRANSPARENT
                else theme.barColor
            add(idleUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(titleUi.root, lParams(matchParent, matchParent))
        }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        ClipboardManager.lastEntry?.let {
            val now = System.currentTimeMillis()
            val clipboardTimeout = clipboardItemTimeout.getValue() * 1000L
            if (now - it.timestamp < clipboardTimeout) {
                onClipboardUpdateListener.onUpdate(it)
            }
        }
        ClipboardManager.addOnUpdateListener(onClipboardUpdateListener)
        clipboardSuggestion.registerOnChangeListener(onClipboardSuggestionUpdateListener)
        clipboardItemTimeout.registerOnChangeListener(onClipboardTimeoutUpdateListener)
        VoiceInputProviderManager.floatingCommitListener = { text ->
            Log.i(VOICE_INPUT_TAG, "floating commit reflected in kawaii bar len=${text.length}")
            idleUi.showVoiceStatus(service.getString(R.string.voice_status_committed))
            idleUi.hideVoiceStatus()
        }
        // Shared callbacks for both kawaii bar button and space long-press.
        VoiceInputProviderManager.voiceStatusCallback = { s -> idleUi.showVoiceStatus(s) }
        VoiceInputProviderManager.voiceReadyCallback = { idleUi.showVoiceStatus(service.getString(R.string.voice_status_listening)) }
        VoiceInputProviderManager.voiceLevelCallback = { rms -> idleUi.updateVoiceLevel(rms) }
        VoiceInputProviderManager.voiceFinishedCallback = { idleUi.hideVoiceStatus() }
        VoiceInputProviderManager.voiceErrorCallback = { msg ->
            idleUi.hideVoiceStatus()
            android.widget.Toast.makeText(service, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
        IconThemeManager.addOnChangedListener(onIconThemeChangeListener)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            idleUi.privateMode(info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        }
        isCapabilityFlagsPassword = toolbarNumRowOnPassword && capFlags.has(CapabilityFlag.Password)
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            idleUi.inlineSuggestionsBar.clear()
        }
        refreshHideKeyboardVoiceButton()
        /*
                "password=${capFlags.has(CapabilityFlag.Password)} shouldShow=$shouldShowVoiceInput"
        )
        */
        evalIdleUiState()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        refreshHideKeyboardVoiceButton()
    }

    private fun refreshHideKeyboardVoiceButton() {
        voiceInputSubtype = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
        val hasPluginProvider = VoiceInputProviderManager.isProviderId(preferredVoiceInput) &&
            VoiceInputProviderManager.hasProvider(preferredVoiceInput, service)
        val shouldShowVoiceInput =
            showVoiceInputButton &&
                (voiceInputSubtype != null || hasPluginProvider) &&
                !isCapabilityFlagsPassword
        Log.i(
            VOICE_INPUT_TAG,
            "refreshVoiceButton showVoice=$showVoiceInputButton preferred=$preferredVoiceInput " +
                "subtype=${voiceInputSubtype != null} plugin=$hasPluginProvider " +
                ""
        )
        val hideKeyboardOrVoiceCallback = if (shouldShowVoiceInput) {
            switchToVoiceInputCallback
        } else {
            hideKeyboardCallback
        }
        idleUi.setHideKeyboardIsVoiceInput(
            shouldShowVoiceInput,
            View.OnClickListener { view ->
                service.restoreVirtualKeyboardForKawaiiBarAction()
                hideKeyboardOrVoiceCallback.onClick(view)
            }
        )
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        // When using "Always" floating mode, don't show candidates in Kawaii Bar
        val floatingMode = AppPrefs.getInstance().candidates.mode.getValue()
        val useFloatingAlways =
            floatingMode == FloatingCandidatesMode.Always && !service.inputDeviceManager.isPhysicalCandidateBarMode

        if (useFloatingAlways) {
            // Force stay in Idle state when using floating candidates
            barStateMachine.push(CandidatesUpdated, CandidateEmpty to true)
        } else {
            barStateMachine.push(CandidatesUpdated, CandidateEmpty to data.candidates.isEmpty())
        }
    }

    override fun onWindowAttached(window: InputWindow) {
        when (window) {
            is InputWindow.ExtendedInputWindow<*> -> {
                titleUi.setTitle(window.title)
                window.onCreateBarExtension()?.let { titleUi.addExtension(it, window.showTitle) }
                titleUi.setReturnButtonOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
                barStateMachine.push(ExtendedWindowAttached)
            }
            else -> {}
        }
        service.inputView?.requestBlurRefresh()
    }

    override fun onWindowDetached(window: InputWindow) {
        barStateMachine.push(WindowDetached)
        service.inputView?.requestBlurRefresh()
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(HEIGHT))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            evalIdleUiState()
            idleUi.inlineSuggestionsBar.clear()
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            idleUi.inlineSuggestionsBar.setPinnedView(
                pinned?.let { inflateInlineContentView(it) }
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            idleUi.inlineSuggestionsBar.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalIdleUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? {
        return suspendCancellableCoroutine { c ->
            // callback view might be null
            suggestion.inflate(context, suggestionSize, directExecutor) { v ->
                c.resume(v)
            }
        }
    }

    companion object {
        const val HEIGHT = 40
    }

    private fun updateButtonsState() {
        _idleUi?.buttonsUi?.updateButtonsState(service)
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        evalIdleUiState()
    }

}
