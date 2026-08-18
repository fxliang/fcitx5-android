/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.SparseIntArray
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.InputDevice
import android.graphics.Rect
import android.graphics.Region
import android.inputmethodservice.InputMethodService.Insets
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.clipboardsync.MainService
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.ClipboardUriStore
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.userManager
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import kotlin.math.max

/**
 * All numpad key codes, from [KeyEvent.KEYCODE_NUMPAD_0] to [KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN].
 * For these keys the simulated key events always carry [KeyEvent.META_NUM_LOCK_ON] so that
 * Android KeyCharacterMap resolves the digit/symbol characters instead of navigation keys.
 */
private val NUMPAD_KEYCODE_RANGE = KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN

class FcitxInputMethodService : LifecycleInputMethodService() {

    internal lateinit var fcitx: FcitxConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)
    private var inputBindingGeneration = 0
    private var inputSessionGeneration = 0
    private var pendingCandidatePagingMode: Int? = null
    private var appliedCandidatePagingMode: Int? = null
    private var candidatePagingModeJob: Job? = null

    /**
     * Marks if we're in a critical input lifecycle phase to delay theme changes and avoid race conditions.
     */
    private var isInInputLifecycleCriticalPhase = false

    /**
     * Keep latest theme change while input lifecycle is unstable, and apply once it's safe.
     */
    private var pendingThemeUpdate: Theme? = null

    private val cachedKeyEvents = LruCache<Int, KeyEvent>(78)
    private val cachedScancodes = SparseIntArray(64)
    private var cachedKeyEventIndex = 0

    /**
     * Saves MetaState produced by hardware keyboard with "sticky" modifier keys, to clear them in order.
     * See also [InputConnection#clearMetaKeyStates(int)](https://developer.android.com/reference/android/view/inputmethod/InputConnection#clearMetaKeyStates(int))
     */
    private var lastMetaState: Int = 0

    private lateinit var pkgNameCache: PackageNameCache

    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    internal var inputView: InputView? = null
    internal var candidatesView: CandidatesView? = null

    private val navbarMgr = NavigationBarManager()
    internal val inputDeviceManager = InputDeviceManager(
        onChange = { isVirtualKeyboard ->
            requestCandidatePagingMode(1)
            currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard)
            updateStatusIcon()
            window.window?.let {
                navbarMgr.evaluate(it, isVirtualKeyboard)
            }
            // Update candidates position when virtual keyboard visibility changes
            if (AppPrefs.getInstance().candidates.mode.getValue() == FloatingCandidatesMode.Always) {
                updateCandidatesViewPagingAndBounds()
            }
        },
        floatingModeProvider = {
            AppPrefs.getInstance().candidates.mode.getValue()
        }
    )

    internal fun restoreVirtualKeyboardForKawaiiBarAction() {
        if (!inputDeviceManager.isPhysicalCandidateBarMode) return
        inputDeviceManager.forceVirtualKeyboardForKawaiiBarAction()
    }

    /**
     * Listener for floating candidates mode changes
     * Reset state when switching away from "Always" mode
     */
    @Keep
    private val onFloatingModeChangeListener = ManagedPreference.OnChangeListener<FloatingCandidatesMode> { _, newMode ->
        // Reset state when switching away from "Always" mode
        if (newMode != FloatingCandidatesMode.Always) {
            // Keep paged candidate mode for consistent highlight + page behavior.
            requestCandidatePagingMode(1)
            // Disable cursor anchor monitoring
            currentInputConnection?.monitorCursorAnchor(false)
        } else {
            // Switching to "Always" mode: enable paging mode for digit key selection
            requestCandidatePagingMode(1)
            // Enable cursor anchor monitoring for floating candidates positioning
            currentInputConnection?.monitorCursorAnchor(true)
            // Update candidates view position
            updateCandidatesViewPagingAndBounds()
        }
        // Re-configure InputView and CandidatesView based on the new mode
        inputDeviceManager.onFloatingModeChanged()
    }

    /**
     * Update paging mode and cursor anchor for "Always" floating mode
     */
    internal fun updateCandidatesViewPagingAndBounds() {
        val floatingMode = AppPrefs.getInstance().candidates.mode.getValue()
        if (floatingMode != FloatingCandidatesMode.Always) return

        // Enable candidate paging mode for "Always" floating mode
        requestCandidatePagingMode(1)
        
        // Enable cursor anchor monitoring to get actual cursor position from app
        // This will trigger onUpdateCursorAnchorInfo callback
        currentInputConnection?.monitorCursorAnchor(true)
        
        // Also update position immediately with current anchor info
        updateCursorAnchorForFloatingCandidates()
    }
    
    /**
     * Update cursor anchor position for floating candidates in "Always" mode
     */
    private fun updateCursorAnchorForFloatingCandidates() {
        val cv = candidatesView ?: return
        val iv = inputView ?: return

        val parentWidth = contentView.width.toFloat()
        val parentHeight = contentView.height.toFloat()

        // Use the anchor position from system cursor anchor info
        // This is updated by onUpdateCursorAnchorInfo with the actual cursor position
        // anchorPosition[1] = cursor bottom, anchorPosition[3] = cursor top
        var cursorBottom = anchorPosition[1]
        var cursorTop = anchorPosition[3]

        // Get keyboard top position for reference
        // This is used to position candidates near keyboard when cursor is far away
        val keyboardTop = iv.getInputFieldTopY()

        // Check if virtual keyboard is visible
        val isKeyboardVisible = inputDeviceManager.isVirtualKeyboard

        // Fallback: if system doesn't provide valid cursor position,
        // use keyboard top as reference. Only apply when cursor position
        // is truly unset (both are 0), not when cursor is above the IME
        // window (which produces negative values after coordinate transform).
        if (cursorTop == 0f && cursorBottom == 0f) {
            // System didn't provide any cursor position, assume near keyboard top
            cursorBottom = keyboardTop
            cursorTop = keyboardTop
        }

        // Pass keyboard top as additional reference for positioning
        cv.updateCursorAnchorForFloating(
            floatArrayOf(anchorPosition[0], cursorBottom, cursorTop),
            floatArrayOf(parentWidth, parentHeight),
            keyboardTop,
            isKeyboardVisible
        )
    }
    private var capabilityFlags = CapabilityFlags.DefaultFlags

    private val selection = CursorTracker()

    val currentInputSelection: CursorRange
        get() = selection.latest

    private val composing = CursorRange()
    private var composingText = FormattedText.Empty

    private fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    private fun updateStatusIcon(entry: InputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }) {
        if (inputDeviceManager.isVirtualKeyboard) {
            hideStatusIcon()
            return
        }

        val iconRes = StatusIconMapping.fromEntry(entry)
        // Skip placeholder icon when IM info has not been initialized yet.
        if (entry.uniqueName.isEmpty() && iconRes == R.drawable.ic_baseline_keyboard_24) {
            hideStatusIcon()
            return
        }
        showStatusIcon(iconRes)
    }

    private var cursorUpdateIndex: Int = 0

    private var highlightColor: Int = DefaultHighlightColor

    private val prefs = AppPrefs.getInstance()
    private val inlineSuggestions by prefs.keyboard.inlineSuggestions
    private val ignoreSystemCursor by prefs.advanced.ignoreSystemCursor

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.advanced.disableAnimation,
        prefs.advanced.ignoreSystemWindowInsets,
    )

    private val voiceInputCommitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BuildConfig.APPLICATION_ID + VoiceInputIpc.PARTIAL_ACTION_SUFFIX -> {
                    val text = intent.getStringExtra(VoiceInputIpc.EXTRA_PARTIAL_TEXT).orEmpty()
                    android.util.Log.i("FcitxVoiceInput", "floating partial received len=${text.length}: $text")
                    if (text.isNotBlank()) {
                        lifecycleScope.launch { setVoiceComposingText(text) }
                    }
                }
                BuildConfig.APPLICATION_ID + VoiceInputIpc.COMMIT_ACTION_SUFFIX -> {
                    val text = intent.getStringExtra(VoiceInputIpc.EXTRA_COMMIT_TEXT).orEmpty()
                    android.util.Log.i("FcitxVoiceInput", "floating commit received len=${text.length}: $text")
                    if (text.isNotBlank()) {
                        lifecycleScope.launch { commitVoiceText(text) }
                        VoiceInputProviderManager.floatingCommitListener?.invoke(text)
                    }
                }
            }
        }
    }

    private fun replaceInputView(theme: Theme): InputView {
        val startedAt = SystemClock.elapsedRealtime()
        val newInputView = InputView(this, fcitx, theme)
        setInputView(newInputView)
        inputDeviceManager.setInputView(newInputView)
        inputView = newInputView
        android.util.Log.i(
            "FcitxColdStart",
            "replaceInputView duration=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        inputDeviceManager.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        candidatesView?.onPositionChanged = { inputView?.updateAuxBarPosition() }
        return newCandidatesView
    }

    private fun refreshViewsForFontChange() {
        val theme = ThemeManager.activeTheme
        inputView?.let {
            replaceInputView(theme)
        }
        candidatesView?.let {
            replaceCandidateView(theme)
        }
    }

    private fun replaceInputViews(theme: Theme) {
        window.window?.let {
            navbarMgr.evaluate(it, inputDeviceManager.isVirtualKeyboard)
        }
        replaceInputView(theme)
        replaceCandidateView(theme)
    }

    /**
     * Theme-driven InputView replacement should only happen when IME window is stable.
     * Applying it too early (eg. during config/input lifecycle transitions) may race with
     * framework initViews/reset flow and leave virtual keyboard in a bad state.
     */
    private fun canApplyPendingThemeNow(): Boolean {
        if (!this::contentView.isInitialized) return false
        if (isInInputLifecycleCriticalPhase) return false
        if (currentInputBinding == null) return false
        if (!isInputViewShown) return false
        return window.window?.decorView?.isAttachedToWindow == true
    }

    private fun applyPendingThemeIfPossible() {
        if (!canApplyPendingThemeNow()) return
        contentView.post {
            if (!canApplyPendingThemeNow()) return@post
            val theme = pendingThemeUpdate ?: return@post
            pendingThemeUpdate = null
            replaceInputViews(theme)
            inputView?.syncImeFromCache()
        }
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        replaceInputView(ThemeManager.activeTheme)
    }

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        replaceCandidateView(ThemeManager.activeTheme)
    }

    /**
     * Theme change listener that delays InputView replacement during critical lifecycle phases.
     */
    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener { theme ->
        if (!this::contentView.isInitialized) return@OnThemeChangeListener
        pendingThemeUpdate = theme
        applyPendingThemeIfPossible()
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit): Job {
        val job = fcitx.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        jobs.trySend(job)
        return job
    }

    private suspend fun FcitxAPI.applyCandidatePagingModeIfNeeded(mode: Int) {
        if (appliedCandidatePagingMode == mode) return
        setCandidatePagingMode(mode)
        appliedCandidatePagingMode = mode
    }

    private fun resetCandidatePagingModeCache() {
        pendingCandidatePagingMode = null
        appliedCandidatePagingMode = null
    }

    private fun requestCandidatePagingMode(mode: Int) {
        pendingCandidatePagingMode = mode
        if (candidatePagingModeJob?.isActive == true) return
        val job = postFcitxJob {
            while (true) {
                val nextMode = pendingCandidatePagingMode ?: break
                pendingCandidatePagingMode = null
                applyCandidatePagingModeIfNeeded(nextMode)
            }
        }
        candidatePagingModeJob = job
        job.invokeOnCompletion {
            if (candidatePagingModeJob === job) {
                candidatePagingModeJob = null
            }
            pendingCandidatePagingMode?.let {
                requestCandidatePagingMode(it)
            }
        }
    }

    private fun postFcitxBindingJob(
        expectedGeneration: Int,
        block: suspend FcitxAPI.() -> Unit
    ): Job = postFcitxJob {
        if (expectedGeneration != inputBindingGeneration) return@postFcitxJob
        block()
    }

    private fun postFcitxSessionJob(
        expectedGeneration: Int,
        block: suspend FcitxAPI.() -> Unit
    ): Job = postFcitxJob {
        if (expectedGeneration != inputSessionGeneration) return@postFcitxJob
        block()
    }

    /**
     * Re-apply the current Android input binding/session state to fcitx.
     *
     * This is needed after fcitx process restart (e.g. plugin package replaced) because
     * Android may keep IME UI visible without re-triggering onBindInput/onStartInput callbacks.
     */
    private fun rebindCurrentInputStateToFcitx() {
        val binding = currentInputBinding
        if (binding != null) {
            val uid = binding.uid
            val pkgName = pkgNameCache.forUid(uid)
            val bindingGeneration = inputBindingGeneration
            postFcitxBindingJob(bindingGeneration) {
                activate(uid, pkgName)
            }
        }
        val editorInfo = currentInputEditorInfo ?: return
        val shouldFocus = !editorInfo.isTypeNull()
        val flags = capabilityFlags
        val inputSessionGeneration = this.inputSessionGeneration
        postFcitxSessionJob(inputSessionGeneration) {
            setCapFlags(flags)
            focus(shouldFocus)
        }
    }

    override fun onCreate() {
        val startedAt = SystemClock.elapsedRealtime()
        android.util.Log.i("FcitxColdStart", "service.onCreate begin")
        fcitx = FcitxDaemon.connect(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
        pkgNameCache = PackageNameCache(this)
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        // Register listener for floating mode changes to reset state when switching away from "Always" mode
        prefs.candidates.mode.registerOnChangeListener(onFloatingModeChangeListener)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        val voiceIntentFilter = IntentFilter().apply {
            addAction(BuildConfig.APPLICATION_ID + VoiceInputIpc.COMMIT_ACTION_SUFFIX)
            addAction(BuildConfig.APPLICATION_ID + VoiceInputIpc.PARTIAL_ACTION_SUFFIX)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                voiceInputCommitReceiver,
                voiceIntentFilter,
                BuildConfig.APPLICATION_ID + ".permission.PLUGIN",
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                voiceInputCommitReceiver,
                voiceIntentFilter,
                BuildConfig.APPLICATION_ID + ".permission.PLUGIN",
                null,
            )
        }
        android.util.Log.i("FcitxVoiceInput", "registered floating voice receiver actions=$voiceIntentFilter")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = resources.configuration
        android.util.Log.i(
            "FcitxColdStart",
            "service.onCreate duration=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.ReadyEvent -> {
                resetCandidatePagingModeCache()
                rebindCurrentInputStateToFcitx()
            }
            is FcitxEvent.CommitStringEvent -> {
                commitText(event.data.text, event.data.cursor)
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    when (it.sym.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace -> handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return -> handleReturnKey()
                        FcitxKeyMapping.FcitxKey_Left -> handleArrowKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        FcitxKeyMapping.FcitxKey_Right -> handleArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        FcitxKeyMapping.FcitxKey_Up -> handleArrowKey(KeyEvent.KEYCODE_DPAD_UP)
                        FcitxKeyMapping.FcitxKey_Down -> handleArrowKey(KeyEvent.KEYCODE_DPAD_DOWN)
                        else -> if (it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    // use cached event if available
                    cachedKeyEvents.remove(it.timestamp)?.let { keyEvent ->
                        /**
                         * intercept the KeyEvent which would cause the default [android.text.method.QwertyKeyListener]
                         * to show a Gingerbread-style CharacterPickerDialog
                         */
                        if (keyEvent.unicodeChar == KeyCharacterMap.PICKER_DIALOG_INPUT.code) {
                            currentInputConnection?.sendKeyEvent(
                                KeyEvent(
                                    keyEvent.downTime, keyEvent.eventTime,
                                    keyEvent.action, keyEvent.keyCode,
                                    keyEvent.repeatCount, keyEvent.metaState, -1,
                                    keyEvent.scanCode, keyEvent.flags, keyEvent.source
                                )
                            )
                            return@event
                        }
                        currentInputConnection?.sendKeyEvent(keyEvent)
                        if (KeyEvent.isModifierKey(keyEvent.keyCode)) {
                            when (keyEvent.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    // save current metaState when modifier key down
                                    lastMetaState = keyEvent.metaState
                                }
                                KeyEvent.ACTION_UP -> {
                                    // only clear metaState that would be missing when this modifier key up
                                    currentInputConnection?.clearMetaKeyStates(lastMetaState xor keyEvent.metaState)
                                    lastMetaState = keyEvent.metaState
                                }
                            }
                        }
                        return@event
                    }
                    // simulate key event
                    val keyCode = it.sym.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        // recognized keyCode
                        val eventTime = SystemClock.uptimeMillis()
                        if (it.up) {
                            sendUpKeyEvent(eventTime, keyCode, it.states.metaState)
                        } else {
                            sendDownKeyEvent(eventTime, keyCode, it.states.metaState)
                        }
                    } else {
                        // no matching keyCode, commit character once on key down
                        if (!it.up && it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Fcitx KeyEvent: $it")
                        }
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                updateComposingText(event.data)
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                handleDeleteSurrounding(before, after)
            }
            is FcitxEvent.IMChangeEvent -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val im = event.data.uniqueName
                    val subtype = SubtypeManager.subtypeOf(im) ?: return
                    skipNextSubtypeChange = im
                    // [^1]: notify system that input method subtype has changed
                    switchInputMethod(InputMethodUtil.componentName, subtype)
                }
                // Update space key label in "Always" floating mode
                inputView?.updateSpaceLabelOnFloatingMode()
                if (inputDeviceManager.evaluateOnInputMethodActivate()) {
                    updateStatusIcon(event.data)
                }
            }
            is FcitxEvent.SwitchInputMethodEvent -> {
                val (reason) = event.data
                if (reason != FcitxEvent.SwitchInputMethodEvent.Reason.CapabilityChanged &&
                    reason != FcitxEvent.SwitchInputMethodEvent.Reason.Other
                ) {
                    if (inputDeviceManager.evaluateOnInputMethodSwitch()) {
                        // show inputView for [CandidatesView] when input method switched by user
                        forceShowSelf()
                    }
                }
            }
            else -> {}
        }
    }

    private fun handleDeleteSurrounding(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        if (before > 0) {
            selection.predictOffset(-before)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(before, after)
        } else {
            ic.deleteSurroundingText(before, after)
        }
    }

    private fun handleBackspaceKey() {
        handleBackspaceDirectly()
    }

    fun handleBackspaceDirectly() {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo
        val isTypeNull = editorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        val lastSelection = selection.latest
        val hasSelection = lastSelection.isNotEmpty()
        if (hasSelection) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            selection.predictOffset(-1)
        }
        // In practice nobody (apart from ourselves) would set `privateImeOptions` to our
        // `DeleteSurroundingFlag`, leading to a behavior of simulating backspace key pressing
        // in almost every EditText.
        if (editorInfo.privateImeOptions != DeleteSurroundingFlag || isTypeNull) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (!hasSelection) {
            if (lastSelection.start <= 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        } else {
            ic.commitText("", 0)
        }
    }

    private fun handleReturnKey() {
        val ic = currentInputConnection ?: run {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            return
        }
        val editorInfo = currentInputEditorInfo
        val inputType = editorInfo.inputType
        val imeOptions = editorInfo.imeOptions
        if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
            imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            return
        }
        val actionId = editorInfo.actionId
        if (editorInfo.actionLabel?.isNotEmpty() == true && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
            return
        }
        when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_UNSPECIFIED,
            EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            else -> ic.performEditorAction(action)
        }
    }

    private fun handleArrowKey(keyCode: Int) {
        val ic = currentInputConnection ?: run {
            sendDownUpKeyEvents(keyCode)
            return
        }
        val editorInfo = currentInputEditorInfo
        val type = editorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (type == InputType.TYPE_NULL ||
            type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        ) {
            sendDownUpKeyEvents(keyCode)
            return
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendDownUpKeyEvents(keyCode)
            }
        }
    }

    fun commitText(text: String, cursor: Int = -1) {
        val ic = currentInputConnection ?: return
        // when composing text equals commit content, finish composing text as-is
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            ic.withBatchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    ic.setSelection(target, target)
                }
                ic.finishComposingText()
            }
            return
        }
        // committed text should replace composing (if any), replace selected range (if any),
        // or simply prepend before cursor
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        if (cursor == -1) {
            selection.predict(start + text.length)
            ic.commitText(text, 1)
        } else {
            val target = start + cursor
            selection.predict(target)
            ic.withBatchEdit {
                commitText(text, 1)
                setSelection(target, target)
            }
        }
    }

    fun setVoiceComposingText(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isBlank()) return
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        val formatted = FormattedText(
            arrayOf(text),
            intArrayOf(TextFormatFlag.Underline.flag),
            text.length,
        )
        composing.update(start, start + text.length)
        composingText = formatted
        selection.predict(composing.end)
        ic.setComposingText(formatted.toSpannedString(highlightColor), 1)
        android.util.Log.i(
            "FcitxVoiceInput",
            "voice composing set len=${text.length} range=$composing",
        )
    }

    fun clearVoiceComposingText() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        resetComposingState()
        ic.setComposingText("", 1)
    }

    fun commitVoiceText(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isBlank()) return
        android.util.Log.i(
            "FcitxVoiceInput",
            "voice commit len=${text.length} composing=${composing.isNotEmpty()} same=${composingText.toString() == text}",
        )
        if (composing.isNotEmpty() && composingText.toString() == text) {
            selection.predict(composing.start + text.length)
            resetComposingState()
            ic.finishComposingText()
        } else {
            clearVoiceComposingText()
            commitText(text)
        }
    }

    fun commitClipboardEntry(text: String): Boolean {
        return commitUriContent(text) || run {
            commitText(text)
            false
        }
    }

    private fun commitUriContent(text: String): Boolean {
        val staged = ClipboardUriStore.stageForCommit(this, text) ?: return false
        val editorInfo = currentInputEditorInfo ?: return false
        val inputConnection = currentInputConnection ?: return false
        val packageName = editorInfo.packageName
        if (!packageName.isNullOrEmpty()) {
            grantUriPermission(packageName, staged.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val description = ClipDescription(staged.uri.lastPathSegment ?: "clipboard", arrayOf(staged.mimeType))
        return InputConnectionCompat.commitContent(
            inputConnection,
            editorInfo,
            InputContentInfoCompat(staged.uri, description, null),
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null
        )
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        val ic = currentInputConnection ?: return
        val scanCode = getCachedScancode(keyEventCode)
        ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                scanCode,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        val ic = currentInputConnection ?: return
        val scanCode = getCachedScancode(keyEventCode)
        ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                scanCode,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun getCachedScancode(keyCode: Int): Int {
        val uncached = Int.MIN_VALUE
        val cached = cachedScancodes.get(keyCode, uncached)
        if (cached != uncached) return cached
        return ScancodeMapping.keyCodeToScancode(keyCode).also {
            cachedScancodes.put(keyCode, it)
        }
    }

    fun deleteSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        currentInputConnection?.commitText("", 1)
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        val lastSelection = selection.latest
        currentInputConnection?.also {
            val start = max(lastSelection.start + offsetStart, 0)
            val end = max(lastSelection.end + offsetEnd, 0)
            if (start > end) return
            selection.predict(start, end)
            it.setSelection(start, end)
        }
    }

    fun cancelSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        currentInputConnection?.setSelection(end, end)
    }

    private lateinit var lastKnownConfig: Configuration

    override fun onConfigurationChanged(newConfig: Configuration) {
        /**
         * skip keyboard|keyboardHidden changes, because we have [inputDeviceManager]
         * skip uiMode (system light/dark mode) changes, because we have [onThemeChangeListener]
         * to replace InputView(s) when needed
         * [android.inputmethodservice.InputMethodService.onConfigurationChanged] would call
         * resetStateForNewConfiguration() which calls initViews() causes InputView(s) to be replaced again
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1984
         */
        val f = ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        val hasMeaningfulDiff = diff and f != diff
        if (hasMeaningfulDiff) {
            postFcitxJob { reset() }
        } else {
            Timber.d("onConfigurationChanged: skip reset for keyboard/uiMode-only diff=$diff")
        }
        /**
         * perform `super.onConfigurationChanged` only when `newConfig` diff fall outside "skipped" flags
         * we have to calculate the mask ourselves because nobody knows how `handledConfigChanges` works
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1876
         */
        if (hasMeaningfulDiff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig = newConfig
    }

    override fun onWindowShown() {
        super.onWindowShown()
        highlightColor =
            styledColorOrDefault(android.R.attr.colorAccent, DefaultHighlightColor).alpha(0.4f)
        InputFeedbacks.syncSystemPrefs()
        applyPendingThemeIfPossible()
    }

    override fun onCreateInputView(): View? {
        val startedAt = SystemClock.elapsedRealtime()
        android.util.Log.i("FcitxColdStart", "service.onCreateInputView begin")
        replaceInputViews(ThemeManager.activeTheme)
        android.util.Log.i(
            "FcitxColdStart",
            "service.onCreateInputView duration=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // input method layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        // expand inputArea to fullscreen
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        /**
         * expand InputView to fullscreen, since [android.inputmethodservice.InputMethodService.setInputView]
         * would set InputView's height to [ViewGroup.LayoutParams.WRAP_CONTENT]
         */
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var inputViewLocation = intArrayOf(0, 0)

    override fun onEvaluateFullscreenMode(): Boolean {
        // Always return false to prevent "Extract View" mode which hides the app.
        // We handle full screen window size manually via onConfigureWindow.
        return false 
    }

    override fun onComputeInsets(outInsets: Insets) {
        val inputView = this.inputView
        if (inputView?.isFloating == true && !inputView.isPhysicalCandidateBarMode) {
            // Floating mode: allow app to be full screen (insets.contentTopInsets = screen height)
            // But we need to handle touches.
             outInsets.contentTopInsets = inputView.height
             outInsets.visibleTopInsets = inputView.height
             
             outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
             inputView.getFloatingKeyboardRegion(outInsets.touchableRegion)
             return
        }

        if (inputView?.isPhysicalCandidateBarMode == true) {
            val location = IntArray(2)
            inputView.keyboardView.getLocationInWindow(location)
            val top = location[1]

            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            inputView.getDockedKeyboardRegion(outInsets.touchableRegion)

            if (top > 0) {
                outInsets.contentTopInsets = top
                outInsets.visibleTopInsets = top
            } else {
                val h = decorView.height
                outInsets.contentTopInsets = h
                outInsets.visibleTopInsets = h
            }
            return
        }

        if (inputDeviceManager.isVirtualKeyboard) {
            val location = IntArray(2)
            inputView?.keyboardView?.getLocationInWindow(location)
            val top = location[1]
            
            // In fixed mode, use TOUCHABLE_INSETS_REGION to explicitly define touchable area
            // to avoid any ambiguity about full screen blocking.
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
              inputView?.getDockedKeyboardRegion(outInsets.touchableRegion)
            
            // Also set contentTopInsets to where the keyboard starts
            if (top > 0) {
                 outInsets.contentTopInsets = top
                 outInsets.visibleTopInsets = top
            } else {
                 // Fallback
                 val h = decorView.height
                 outInsets.contentTopInsets = h
                 outInsets.visibleTopInsets = h
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    fun toggleOneHandKeyboard() {
        inputView?.toggleOneHandMode()
    }

    fun isOneHandKeyboardEnabled(): Boolean {
        return inputView?.isOneHanded == true
    }

    // override fun onEvaluateFullscreenMode() = false // Removed duplicate

    private fun forwardKeyEvent(event: KeyEvent, preserveModifierState: Boolean = false): Boolean {
        // reason to use a self increment index rather than timestamp:
        // KeyUp and KeyDown events actually can happen on the same time
        val timestamp = cachedKeyEventIndex++
        val forwardedEvent = if (simulatedCapsLockOn && !event.isCapsLockOn) {
            KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                event.keyCode,
                event.repeatCount,
                event.metaState or KeyEvent.META_CAPS_LOCK_ON,
                event.deviceId,
                event.scanCode,
                event.flags,
                event.source
            )
        } else {
            event
        }
        cachedKeyEvents.put(timestamp, forwardedEvent)
        val sym = KeySym.fromKeyEvent(forwardedEvent)
        Timber.v("forwardKeyEvent: keyCode=%d scanCode=%d action=%d unicodeChar=%d sym=%s",
            forwardedEvent.keyCode, forwardedEvent.scanCode, forwardedEvent.action, forwardedEvent.unicodeChar, sym)
        if (sym != null) {
            var states = if (preserveModifierState) {
                keyStatesFromEventRaw(forwardedEvent)
            } else {
                KeyStates.fromKeyEvent(forwardedEvent)
            }
            if (simulatedCapsLockOn && !states.has(KeyState.CapsLock)) {
                states = KeyStates(states.states or KeyState.CapsLock.state)
            }
            val adjustedSym = adjustAlphabetSymForCaps(sym, states)
            val up = forwardedEvent.action == KeyEvent.ACTION_UP
            postFcitxJob {
                sendKey(adjustedSym, states, forwardedEvent.scanCode, up, timestamp)
            }
            return true
        }
        Timber.v("Skipped KeyEvent: $forwardedEvent")
        return false
    }

    private fun keyStatesFromEventRaw(event: KeyEvent): KeyStates {
        var states = KeyState.NoState.state
        if (event.isAltPressed) states = states or KeyState.Alt.state
        if (event.isCtrlPressed) states = states or KeyState.Ctrl.state
        if (event.isShiftPressed) states = states or KeyState.Shift.state
        if (event.isCapsLockOn) states = states or KeyState.CapsLock.state
        if (event.isNumLockOn) states = states or KeyState.NumLock.state
        if (event.isMetaPressed) states = states or KeyState.Meta.state
        return KeyStates(states)
    }

    private fun adjustAlphabetSymForCaps(sym: KeySym, states: KeyStates): KeySym {
        val code = sym.sym
        if (code !in 'a'.code..'z'.code && code !in 'A'.code..'Z'.code) return sym
        val shouldUpper = states.capsLock.xor(states.shift)
        val adjusted = if (shouldUpper) {
            code.toChar().uppercaseChar().code
        } else {
            code.toChar().lowercaseChar().code
        }
        return KeySym(adjusted)
    }

    /**
     * Send simulated KeyEvent (for macros)
     * Route through the physical-keyboard path so Rime can recognize it correctly
     */
    private var simulatedCapsLockOn = false
    private var simulatedNumLockOn = false
    private var simulatedCapsLockPressed = false
    private var simulatedNumLockPressed = false
    private var simulatedShiftPressedCount = 0
    private var simulatedCtrlPressedCount = 0
    private var simulatedAltPressedCount = 0
    private var simulatedMetaPressedCount = 0
    private var simulatedFunctionPressedCount = 0
    private var simulatedAltRightPressedCount = 0
    private var simulatedCapsLockPressedFromMacro = false
    private var simulatedCapsLockOnByMacroTap = false
    private var virtualShiftLockOn = false

    public fun isSimulatedCapsLockOn(): Boolean = simulatedCapsLockOn
    public fun isSimulatedCapsLockOnByMacroTap(): Boolean = simulatedCapsLockOnByMacroTap
    public fun isVirtualShiftLockOn(): Boolean = virtualShiftLockOn

    public fun setVirtualCapsLockState(enabled: Boolean) {
        if (simulatedCapsLockOn == enabled) return
        simulatedCapsLockOn = enabled
        simulatedCapsLockPressed = false
        simulatedCapsLockPressedFromMacro = false
        simulatedCapsLockOnByMacroTap = false
        TextKeyboard.refreshCapsPresentationOnAll()
    }

    public fun setVirtualShiftLockState(enabled: Boolean) {
        if (virtualShiftLockOn == enabled) return
        virtualShiftLockOn = enabled
        TextKeyboard.refreshCapsPresentationOnAll()
    }

    private fun onHardwareTypingModeEnter(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) return
        if (event.unicodeChar == 0) return
        TextKeyboard.clearCapsStateOnAll()
        setVirtualShiftLockState(false)
        setVirtualCapsLockState(event.isCapsLockOn)
    }

    /**
     * Track modifier hold state for simulated key events (shared by the keyboard macro path
     * [sendSimulatedKeyEvent] and the custom-button path [sendSimulatedKeyEventOrFallback]) so
     * key taps/shortcuts carry the correct metaState (e.g. Ctrl+A is not just a plain "a").
     */
    private fun updateSimulatedModifierCount(keyCode: Int, action: Int) {
        when (action) {
            KeyEvent.ACTION_DOWN -> when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> simulatedShiftPressedCount += 1
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> simulatedCtrlPressedCount += 1
                KeyEvent.KEYCODE_ALT_LEFT -> simulatedAltPressedCount += 1
                KeyEvent.KEYCODE_ALT_RIGHT -> {
                    simulatedAltPressedCount += 1
                    simulatedAltRightPressedCount += 1
                }
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> simulatedMetaPressedCount += 1
                KeyEvent.KEYCODE_FUNCTION -> simulatedFunctionPressedCount += 1
            }
            KeyEvent.ACTION_UP -> when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                    simulatedShiftPressedCount = (simulatedShiftPressedCount - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT ->
                    simulatedCtrlPressedCount = (simulatedCtrlPressedCount - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_ALT_LEFT ->
                    simulatedAltPressedCount = (simulatedAltPressedCount - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_ALT_RIGHT -> {
                    simulatedAltPressedCount = (simulatedAltPressedCount - 1).coerceAtLeast(0)
                    simulatedAltRightPressedCount = (simulatedAltRightPressedCount - 1).coerceAtLeast(0)
                }
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT ->
                    simulatedMetaPressedCount = (simulatedMetaPressedCount - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_FUNCTION ->
                    simulatedFunctionPressedCount = (simulatedFunctionPressedCount - 1).coerceAtLeast(0)
            }
        }
    }

    private fun simulatedMetaState(keyCode: Int): Int {
        var metaState = 0
        if (simulatedCapsLockOn) metaState = metaState or KeyEvent.META_CAPS_LOCK_ON
        // Num lock is always treated as ON for numpad keys from the simulated keyboard,
        // so that Android KeyCharacterMap produces the correct digit/symbol unicode
        // characters instead of converting them to navigation keys.
        if (simulatedNumLockOn || keyCode in NUMPAD_KEYCODE_RANGE) {
            metaState = metaState or KeyEvent.META_NUM_LOCK_ON
        }
        if (simulatedShiftPressedCount > 0) metaState = metaState or KeyEvent.META_SHIFT_ON
        if (simulatedCtrlPressedCount > 0) metaState = metaState or KeyEvent.META_CTRL_ON
        if (simulatedAltPressedCount > 0) metaState = metaState or KeyEvent.META_ALT_ON
        if (simulatedMetaPressedCount > 0) metaState = metaState or KeyEvent.META_META_ON
        if (simulatedFunctionPressedCount > 0) metaState = metaState or KeyEvent.META_FUNCTION_ON
        if (simulatedAltRightPressedCount > 0) metaState = metaState or KeyEvent.META_ALT_RIGHT_ON
        return metaState
    }

    /**
     * Send a simulated key event directly to the current input connection (custom buttons on the
     * Kawaii bar / status area). Like [sendSimulatedKeyEvent], it tracks modifier hold state so
     * shortcuts and modifier-composed macros carry the correct metaState to the app.
     */
    public fun sendSimulatedKeyEventOrFallback(keyCode: Int, isDown: Boolean) {
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        updateSimulatedModifierCount(keyCode, action)
        val eventTime = SystemClock.uptimeMillis()
        val scanCode = getCachedScancode(keyCode)
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                action,
                keyCode,
                0,
                simulatedMetaState(keyCode),
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                scanCode,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    public fun sendSimulatedKeyEvent(keyCode: Int, scanCode: Int, action: Int, fromMacro: Boolean = false) {
        val eventTime = SystemClock.uptimeMillis()
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_CAPS_LOCK -> {
                    simulatedCapsLockPressed = true
                    simulatedCapsLockPressedFromMacro = fromMacro
                }
                KeyEvent.KEYCODE_NUM_LOCK -> simulatedNumLockPressed = true
            }
            updateSimulatedModifierCount(keyCode, action)
        } else if (action == KeyEvent.ACTION_UP) {
            updateSimulatedModifierCount(keyCode, action)
        }
        val metaState = simulatedMetaState(keyCode)
        // Use InputDevice.SOURCE_KEYBOARD so the system uses the physical keyboard KeyCharacterMap
        // This makes function keys (F1-F12) return unicodeChar = 0 and follow the keyCodeToSym path
        val event = KeyEvent(
            eventTime, // downTime
            eventTime, // eventTime
            action,
            keyCode,
            0, // repeatCount
            metaState,
            0, // deviceId
            scanCode,
            KeyEvent.FLAG_FROM_SYSTEM, // flags: mark as coming from system hardware
            InputDevice.SOURCE_KEYBOARD // source: physical keyboard
        )
        Timber.v("sendSimulatedKeyEvent: keyCode=%d scanCode=%d action=%d unicodeChar=%d",
            keyCode, scanCode, action, event.unicodeChar)
        forwardKeyEvent(event, preserveModifierState = true)

        if (action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_CAPS_LOCK -> {
                    val old = simulatedCapsLockOn
                    if (simulatedCapsLockPressed) {
                        simulatedCapsLockOn = !simulatedCapsLockOn
                        simulatedCapsLockOnByMacroTap =
                            simulatedCapsLockOn && simulatedCapsLockPressedFromMacro
                    }
                    simulatedCapsLockPressed = false
                    simulatedCapsLockPressedFromMacro = false
                    if (old != simulatedCapsLockOn) {
                        TextKeyboard.refreshCapsPresentationOnAll()
                    }
                }
                KeyEvent.KEYCODE_NUM_LOCK -> {
                    if (simulatedNumLockPressed) {
                        simulatedNumLockOn = !simulatedNumLockOn
                    }
                    simulatedNumLockPressed = false
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            simulatedCapsLockPressed = true
            simulatedCapsLockPressedFromMacro = false
        }
        onHardwareTypingModeEnter(event)
        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceManager.evaluateOnKeyDown(event)) {
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            val old = simulatedCapsLockOn
            simulatedCapsLockOn = event.isCapsLockOn
            simulatedCapsLockPressed = false
            simulatedCapsLockPressedFromMacro = false
            simulatedCapsLockOnByMacroTap = false
            if (old != simulatedCapsLockOn) {
                TextKeyboard.refreshCapsPresentationOnAll()
            }
        }
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    public fun sendSimulatedCapsLockTapFromMacro() {
        val keyCode = KeyEvent.KEYCODE_CAPS_LOCK
        val scanCode = run {
            try {
                getCachedScancode(keyCode).takeIf { it != 0 } ?: 0
            } catch (e: Exception) {
                Timber.w("keyCodeToScancode failed for keyCode=$keyCode: ${e.message}")
                0
            }
        }
        sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
        sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP, fromMacro = true)
    }

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceManager.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    private var firstBindInput = true

    override fun onBindInput() {
        resetCandidatePagingModeCache()
        val uid = currentInputBinding.uid
        val pkgName = pkgNameCache.forUid(uid)
        val bindingGeneration = ++inputBindingGeneration
        Timber.d("onBindInput: uid=$uid pkg=$pkgName")
        postFcitxBindingJob(bindingGeneration) {
            // ensure InputContext has been created before focusing it
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype)
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }

    /**
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    private var skipNextSubtypeChange: String? = null

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im")
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            if (skipNextSubtypeChange == im) {
                skipNextSubtypeChange = null
                return
            }
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        android.util.Log.i("FcitxColdStart", "service.onStartInput restarting=$restarting")
        isInInputLifecycleCriticalPhase = true
        try {
            val inputSessionGeneration = ++this.inputSessionGeneration
            MainService.startSyncService(this, "ime-start-input", imeSyncActive = true)
            selection.resetTo(attribute.initialSelStart, attribute.initialSelEnd)
            resetComposingState()
            val flags = CapabilityFlags.fromEditorInfo(attribute)
            capabilityFlags = flags
            inputDeviceManager.notifyOnStartInput(attribute)
            Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
            val isNullType = attribute.isTypeNull()
            postFcitxSessionJob(inputSessionGeneration) {
                if (restarting) {
                    focus(false)
                }
                setCapFlags(flags)
                if (!isNullType) {
                    focus(true)
                }
            }
        } finally {
            contentView.post {
                isInInputLifecycleCriticalPhase = false
                applyPendingThemeIfPossible()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        android.util.Log.i("FcitxColdStart", "service.onStartInputView restarting=$restarting")
        isInInputLifecycleCriticalPhase = true
        try {
            val inputSessionGeneration = this.inputSessionGeneration
            Timber.d("onStartInputView: restarting=$restarting")
            MainService.startSyncService(this, "ime-start-input-view", imeSyncActive = true)
            ensurePreferredVoiceInputProviderAvailable()
            if (org.fcitx.fcitx5.android.input.font.FontProviders.needsRefresh()) {
                refreshViewsForFontChange()
            }
            postFcitxSessionJob(inputSessionGeneration) {
                focus(true)
                // Keep paged candidate mode enabled so candidate cursor/highlight is available.
                applyCandidatePagingModeIfNeeded(1)
            }
            if (inputDeviceManager.evaluateOnStartInputView(info, this) ||
                inputDeviceManager.isPhysicalCandidateBarMode
            ) {
                inputView?.startInput(info, capabilityFlags, restarting)
            } else {
                if (currentInputConnection?.monitorCursorAnchor() != true) {
                    if (!decorLocationUpdated) {
                        updateDecorLocation()
                    }
                    workaroundNullCursorAnchorInfo()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
        } finally {
            contentView.post {
                isInInputLifecycleCriticalPhase = false
                applyPendingThemeIfPossible()
            }
            updateStatusIcon()
        }
    }

    private fun ensurePreferredVoiceInputProviderAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) return
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        val voiceInputReachable =
            keyboardPrefs.showVoiceInputButton.getValue() ||
                keyboardPrefs.spaceKeyLongPressBehavior.getValue() == SpaceLongPressBehavior.VoiceInput ||
                keyboardPrefs.spaceKeyLongPressBehavior.getValue() == SpaceLongPressBehavior.VoiceInputHold
        if (!voiceInputReachable) return
        val preferredVoiceInput = keyboardPrefs.preferredVoiceInput.getValue()
        VoiceInputProviderManager.ensureProviderAvailable(this, preferredVoiceInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        // onUpdateSelection can left behind when user types quickly enough, eg. long press backspace
        cursorUpdateIndex += 1
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd] cand=[$candidatesStart,$candidatesEnd]")
        handleCursorUpdate(
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
            cursorUpdateIndex
        )
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = contentView.height.toFloat()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = floatArrayOf(0f, 0f, 0f, 0f)

    private fun workaroundNullCursorAnchorInfo() {
        anchorPosition[0] = 0f
        anchorPosition[1] = contentSize[1]
        anchorPosition[2] = 0f
        anchorPosition[3] = contentSize[1]
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal =
                if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition[0] = horizontal
            anchorPosition[1] = bounds.bottom
            anchorPosition[2] = horizontal
            anchorPosition[3] = bounds.top
        } else {
            anchorPosition[0] = info.insertionMarkerHorizontal
            anchorPosition[1] = info.insertionMarkerBottom
            anchorPosition[2] = info.insertionMarkerHorizontal
            anchorPosition[3] = info.insertionMarkerTop
        }
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(anchorPosition)
        val (xOffset, yOffset) = decorLocation
        anchorPosition[0] -= xOffset
        anchorPosition[1] -= yOffset
        anchorPosition[2] -= xOffset
        anchorPosition[3] -= yOffset
        
        // In "Always" floating mode, avoid doing the normal cursor-anchor
        // positioning first and then immediately re-positioning again.
        val floatingMode = AppPrefs.getInstance().candidates.mode.getValue()
        if (floatingMode == FloatingCandidatesMode.Always) {
            updateCursorAnchorForFloatingCandidates()
        } else {
            candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
        }
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        newComposingStart: Int,
        newComposingEnd: Int,
        updateIndex: Int
    ) {
        if (selection.consume(newSelStart, newSelEnd)) {
            // try restore composing range in case it was dropped by InputFilter
            // but only when prediction matches, since InputFilter can also change editor content
            // ref:
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/Editor.java#2083
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/TextView.java#7351
            if (newComposingStart == -1 && newComposingEnd == -1 && composing.isNotEmpty()) {
                currentInputConnection?.setComposingRegion(composing.start, composing.end)
            }
            return // do nothing if prediction matches
        } else {
            // cursor update can't match any prediction: it's treated as a user input
            selection.resetTo(newSelStart, newSelEnd)
        }
        // skip selection range update, we only care about selection cursor (zero width) here
        if (newSelStart != newSelEnd) return
        // do reset if composing is empty && input panel is not empty
        if (composing.isEmpty()) {
            postFcitxJob {
                if (!isEmpty()) {
                    Timber.d("handleCursorUpdate: reset")
                    reset()
                }
            }
            return
        }
        // check if cursor inside composing text
        if (composing.contains(newSelStart)) {
            if (ignoreSystemCursor) return
            // fcitx cursor position is relative to client preedit (composing text)
            val position = newSelStart - composing.start
            // move fcitx cursor when cursor position changed
            if (position != composingText.cursor) {
                // cursor in InvokeActionEvent counts by "UTF-8 characters"
                val codePointPosition = composingText.codePointCountUntil(position)
                postFcitxJob {
                    if (updateIndex != cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to $codePointPosition")
                    moveCursor(codePointPosition)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: focus out/in")
            resetComposingState()
            // cursor outside composing range, finish composing as-is
            currentInputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            postFcitxJob {
                focusOutIn()
            }
        }
    }

    // because setComposingText(text, cursor) can only put cursor at end of composing,
    // sometimes onUpdateSelection would receive event with wrong cursor position.
    // those events need to be filtered.
    // because of https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r45/core/java/android/view/inputmethod/BaseInputConnection.java#851
    // it's not possible to set cursor inside composing text
    private fun updateComposingText(text: FormattedText) {
        val ic = currentInputConnection ?: return
        val lastSelection = selection.latest
        ic.beginBatchEdit()
        if (composingText.spanEquals(text)) {
            // composing text content is up-to-date
            // update cursor only when it's not empty AND cursor position is valid
            if (text.length > 0 && text.cursor >= 0) {
                val p = text.cursor + composing.start
                if (p != lastSelection.start) {
                    Timber.d("updateComposingText: set Android selection ($p, $p)")
                    ic.setSelection(p, p)
                    selection.predict(p)
                }
            }
        } else {
            // composing text content changed
            Timber.d("updateComposingText: '$text' lastSelection=$lastSelection")
            if (text.isEmpty()) {
                if (composing.isEmpty()) {
                    // do not reset saved selection range when incoming composing
                    // and saved composing range are both empty:
                    // composing.start is invalid when it's empty.
                    selection.predict(lastSelection.start)
                } else {
                    // clear composing text, put cursor at start of original composing
                    selection.predict(composing.start)
                    composing.clear()
                }
                ic.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                // skip cursor reposition when:
                // - preedit cursor is at the end
                // - cursor position is invalid
                if (text.cursor == text.length || text.cursor < 0) {
                    selection.predict(composing.end)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                    ic.setSelection(p, p)
                }
            }
            Timber.d("updateComposingText: composing=$composing")
        }
        composingText = text
        ic.endBatchEdit()
    }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        composingText = FormattedText.Empty
        ic.finishComposingText()
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // ignore inline suggestion when disabled by user || using physical keyboard with floating candidates view
        if (!inlineSuggestions || !inputDeviceManager.isVirtualKeyboard) return null
        val theme = ThemeManager.activeTheme
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions || !inputDeviceManager.isVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        MainService.stopSyncService(this)
        if (VoiceInputProviderManager.isActive()) {
            VoiceInputProviderManager.stop(this)
        }
        decorLocationUpdated = false
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        resetComposingState()
        val inputSessionGeneration = this.inputSessionGeneration
        postFcitxSessionJob(inputSessionGeneration) {
            focusOutIn()
        }
        hideStatusIcon()
        showingDialog?.dismiss()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput")
        MainService.stopSyncService(this)
        val inputSessionGeneration = this.inputSessionGeneration
        postFcitxSessionJob(inputSessionGeneration) {
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        cachedKeyEvents.evictAll()
        cachedKeyEventIndex = 0
        cursorUpdateIndex = 0
        resetCandidatePagingModeCache()
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        val uid = currentInputBinding?.uid ?: return
        val bindingGeneration = inputBindingGeneration
        Timber.d("onUnbindInput: uid=$uid")
        postFcitxBindingJob(bindingGeneration) {
            deactivate(uid)
        }
    }

    override fun onDestroy() {
        MainService.stopSyncService(this)
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        prefs.candidates.mode.unregisterOnChangeListener(onFloatingModeChangeListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        runCatching { unregisterReceiver(voiceInputCommitReceiver) }
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val DefaultHighlightColor = 0x66008577 // material_deep_teal_500 with alpha 0.4
        const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"
    }
}
