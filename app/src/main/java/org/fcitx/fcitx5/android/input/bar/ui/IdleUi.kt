/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageResource
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.math.sqrt

class IdleUi(
    override val ctx: Context,
    private val theme: Theme,
    private val popup: PopupComponent,
    private val commonKeyActionListener: CommonKeyActionListener,
    private val buttonsConfig: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons,
    private var toolbarToggleConfig: ConfigurableButton = ConfigurableButton("toolbar_toggle"),
    private var hideKeyboardConfig: ConfigurableButton = ConfigurableButton("hide_keyboard")
) : Ui {

    enum class State {
        Empty, Toolbar, Clipboard, NumberRow, InlineSuggestion
    }

    var currentState = State.Empty
        private set

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private var inPrivate = false

    private val translateDirection by lazy {
        if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1f else -1f
    }

    private val hasCustomMenuIcon get() = toolbarToggleConfig.icon != null || !toolbarToggleConfig.text.isNullOrEmpty()
    private var hideKeyboardInVoiceInputMode = false
    private val menuButtonRotation
        get() = when {
            inPrivate -> 0f
            currentState == State.Toolbar -> 90f * translateDirection
            else -> -90f * translateDirection
        }

    @DrawableRes
    private val defaultMenuIcon = R.drawable.ic_baseline_expand_more_24
    @DrawableRes
    private val defaultHideKeyboardIcon = R.drawable.ic_baseline_arrow_drop_down_24

    val menuButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        iconRotation = menuButtonRotation
        applySystemButtonConfig(this, toolbarToggleConfig, defaultMenuIcon)
    }

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, theme).apply {
        applySystemButtonConfig(this, hideKeyboardConfig, defaultHideKeyboardIcon)
    }

    val emptyBar = Space(ctx)

    val buttonsUi = ButtonsBarUi(ctx, theme, buttonsConfig)

    val clipboardUi = ClipboardSuggestionUi(ctx, theme)

    val numberRow = NumberRow(ctx, theme).apply {
        visibility = View.GONE
    }

    val inlineSuggestionsBar = InlineSuggestionsUi(ctx)

    private val voiceStatusText: TextView = textView {
        text = "Recording"
        textSize = 14f
        setTextColor(theme.keyTextColor)
        isSingleLine = true
    }

    private val voiceBars = List(12) {
        View(ctx).apply {
            setBackgroundColor(theme.keyTextColor)
            alpha = 0.35f
        }
    }

    private var voiceWavePhase = 0
    private var lastVoiceLevelAt = 0L

    private val voiceStatusBar = horizontalLayout {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(dp(8), 0, dp(8), 0)
        add(voiceStatusText, lParams(0, wrapContent, weight = 1f))
        voiceBars.forEach { bar ->
            add(bar, LinearLayout.LayoutParams(dp(3), dp(8)).apply {
                marginStart = dp(3)
            })
        }
    }

    private val voiceWaveTicker = object : Runnable {
        override fun run() {
            if (voiceStatusBar.visibility != View.VISIBLE) return
            val hasRecentLevel = System.currentTimeMillis() - lastVoiceLevelAt < 500L
            if (!hasRecentLevel) {
                updateVoiceBars(-1f)
            }
            voiceStatusBar.postDelayed(this, 120L)
        }
    }

    private val animator = ViewAnimator(ctx).apply {
        add(emptyBar, lParams(matchParent, matchParent))
        add(buttonsUi.root, lParams(matchParent, matchParent))
        add(clipboardUi.root, lParams(matchParent, matchParent))
        add(inlineSuggestionsBar.root, lParams(matchParent, matchParent))
    }

    private val inAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(0f, 1f))
            // 2 stands for Animation.RELATIVE_TO_PARENT
            addAnimation(TranslateAnimation(2, -0.3f * translateDirection, 2, 0f, 0, 0f, 0, 0f))
        }
    }

    private val outAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(1f, 0f))
            addAnimation(TranslateAnimation(2, 0f, 2, -0.3f * translateDirection, 0, 0f, 0, 0f))
        }
    }

    private val idleBody = constraintLayout {
        val size = dp(KawaiiBarComponent.HEIGHT)
        add(menuButton, lParams(size, size) {
            startOfParent()
            centerVertically()
        })
        add(hideKeyboardButton, lParams(size, size) {
            endOfParent()
            centerVertically()
        })
        add(animator, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(hideKeyboardButton)
            centerVertically()
        })
        add(voiceStatusBar, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(hideKeyboardButton)
            centerVertically()
        })
    }

    override val root = frameLayout {
        add(idleBody, lParams(matchParent, matchParent))
        add(numberRow, lParams(matchParent, matchParent))
    }

    fun clearTransientPressState() {
        menuButton.clearTransientPressState()
        hideKeyboardButton.clearTransientPressState()
        buttonsUi.clearTransientPressState()
    }

    fun privateMode(activate: Boolean = true) {
        if (activate == inPrivate) return
        inPrivate = activate
        updateMenuButtonIcon()
        updateMenuButtonContentDescription()
        updateMenuButtonRotation(instant = true)
    }

    private fun systemSlotFromButtonId(buttonId: String): String? = when (buttonId) {
        "toolbar_toggle" -> "system.toolbar_toggle"
        "hide_keyboard" -> "system.hide_keyboard"
        "voice_input" -> "system.voice_input"
        else -> buttonId.takeIf { it.startsWith("system.") }
    }

    private fun applyIconThemeToSystemButton(button: ToolButton, slotOrButtonId: String): Boolean {
        val slot = systemSlotFromButtonId(slotOrButtonId) ?: return false
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(slot)
        if (iconInfo != null) {
            button.setIconFromDrawable(iconInfo.drawable, tintWithTheme = iconInfo.tintWithTheme)
            return true
        }
        val textValue = IconThemeManager.resolveIcon(slot)
        if (textValue != null) {
            button.setText(textValue)
            return true
        }
        return false
    }

    private fun applyConfiguredSystemButton(button: ToolButton, config: ConfigurableButton): Boolean {
        if (!config.text.isNullOrEmpty()) {
            button.setText(config.text)
            return true
        }
        val icon = config.icon ?: return false
        if (icon.startsWith("file:")) {
            val drawable = ButtonIconFile.loadDrawable(icon) ?: return false
            button.setIconFromDrawable(drawable, tintWithTheme = ButtonIconFile.shouldTintIcon(icon))
            return true
        }
        val resId = ctx.resources.getIdentifier(icon, "drawable", ctx.packageName)
        if (resId != 0) {
            button.setIcon(resId)
            return true
        }
        return false
    }

    private fun applySystemButtonConfig(
        button: ToolButton,
        config: ConfigurableButton,
        @DrawableRes defaultIcon: Int,
        slotOverride: String? = null
    ) {
        val fromConfig = applyConfiguredSystemButton(button, config)
        val fromTheme = if (!fromConfig) applyIconThemeToSystemButton(button, slotOverride ?: config.id) else false
        if (!fromConfig && !fromTheme) {
            button.setIcon(defaultIcon)
        }
        if (!config.label.isNullOrEmpty()) {
            button.contentDescription = config.label
        }
    }

    fun reloadSystemButtonIcons() {
        val toolbarIcon = toolbarToggleConfig.icon
        if (toolbarIcon != null && toolbarIcon.startsWith("file:")) {
            applySystemButtonConfig(menuButton, toolbarToggleConfig, defaultMenuIcon)
        }
        val hideIcon = hideKeyboardConfig.icon
        if (hideIcon != null && hideIcon.startsWith("file:")) {
            refreshHideKeyboardButtonIcon()
        }
    }

    /** Refresh all toolbar and system button icons (called when icon theme changes). */
    fun refreshAllIcons() {
        // Re-apply icon theme to system buttons
        applySystemButtonConfig(menuButton, toolbarToggleConfig, defaultMenuIcon)
        refreshHideKeyboardButtonIcon()
        // Rebind toolbar buttons
        buttonsUi.refreshLayout()
    }

    fun updateSystemButtonConfigs(
        newToolbarToggleConfig: ConfigurableButton,
        newHideKeyboardConfig: ConfigurableButton
    ) {
        toolbarToggleConfig = newToolbarToggleConfig
        hideKeyboardConfig = newHideKeyboardConfig
        applySystemButtonConfig(menuButton, toolbarToggleConfig, defaultMenuIcon)
        refreshHideKeyboardButtonIcon()
        updateMenuButtonIcon()
        updateMenuButtonContentDescription()
    }

    private fun updateMenuButtonIcon() {
        if (inPrivate && !hasCustomMenuIcon) {
            menuButton.setIcon(R.drawable.ic_view_private)
            return
        }
        applySystemButtonConfig(menuButton, toolbarToggleConfig, defaultMenuIcon)
    }

    private fun updateMenuButtonContentDescription() {
        if (!toolbarToggleConfig.label.isNullOrEmpty()) return
        menuButton.contentDescription = when {
            inPrivate -> ctx.getString(R.string.private_mode)
            currentState == State.Toolbar -> ctx.getString(R.string.hide_toolbar)
            else -> ctx.getString(R.string.expand_toolbar)
        }
    }

    private fun updateMenuButtonRotation(instant: Boolean = false) {
        val targetRotation = menuButtonRotation
        menuButton.apply {
            if (targetRotation == iconRotation) return
            iconAnimate().cancel()
            if (!instant && !disableAnimation) {
                iconAnimate().setDuration(200L).rotation(targetRotation)
            } else {
                iconRotation = targetRotation
            }
        }
    }

    private fun refreshHideKeyboardButtonIcon() {
        if (hideKeyboardInVoiceInputMode) {
            val fromTheme = applyIconThemeToSystemButton(hideKeyboardButton, "system.voice_input")
            if (!fromTheme) {
                hideKeyboardButton.setIcon(R.drawable.ic_baseline_keyboard_voice_24)
            }
        } else {
            applySystemButtonConfig(hideKeyboardButton, hideKeyboardConfig, defaultHideKeyboardIcon)
        }
    }

    fun setHideKeyboardIsVoiceInput(isVoiceInput: Boolean, callback: View.OnClickListener) {
        hideKeyboardInVoiceInputMode = isVoiceInput
        if (isVoiceInput) {
            refreshHideKeyboardButtonIcon()
            hideKeyboardButton.contentDescription = ctx.getString(R.string.switch_to_voice_input)
        } else {
            refreshHideKeyboardButtonIcon()
            if (hideKeyboardConfig.label.isNullOrEmpty()) {
                hideKeyboardButton.contentDescription = ctx.getString(R.string.hide_keyboard)
            }
        }
        hideKeyboardButton.setOnClickListener(callback)
    }

    fun showVoiceStatus(label: String = "Recording") {
        voiceStatusText.text = label
        voiceStatusBar.visibility = View.VISIBLE
        animator.visibility = View.GONE
        idleBody.visibility = View.VISIBLE
        numberRow.visibility = View.GONE
        startVoiceWave()
    }

    fun updateVoiceLevel(rms: Int) {
        if (voiceStatusBar.visibility != View.VISIBLE) return
        lastVoiceLevelAt = System.currentTimeMillis()
        val normalized = sqrt((rms / 3500f).coerceIn(0f, 1f))
        updateVoiceBars(normalized)
    }

    private fun updateVoiceBars(normalized: Float) {
        val animated = normalized < 0f
        val active = if (animated) {
            voiceWavePhase = (voiceWavePhase + 1) % voiceBars.size
            voiceBars.size
        } else {
            (normalized * voiceBars.size).roundToInt().coerceIn(1, voiceBars.size)
        }
        voiceBars.forEachIndexed { index, bar ->
            val waveDistance = kotlin.math.abs(index - voiceWavePhase).let {
                minOf(it, voiceBars.size - it)
            }
            val animatedHeight = ctx.dp(5 + (4 - waveDistance.coerceAtMost(4)) * 4)
            val levelHeight = ctx.dp(5 + ((index % 4) + 1) * 4)
            val targetHeight = when {
                animated -> animatedHeight
                index < active -> levelHeight
                else -> ctx.dp(5)
            }
            bar.layoutParams = bar.layoutParams.apply { this.height = targetHeight }
            bar.alpha = when {
                animated -> if (waveDistance <= 3) 0.9f else 0.3f
                index < active -> 0.95f
                else -> 0.25f
            }
        }
    }

    fun hideVoiceStatus() {
        if (voiceStatusBar.visibility == View.GONE) return
        stopVoiceWave()
        voiceStatusBar.visibility = View.GONE
        animator.visibility = View.VISIBLE
        idleBody.visibility = View.VISIBLE
    }

    private fun startVoiceWave() {
        voiceStatusBar.removeCallbacks(voiceWaveTicker)
        voiceStatusBar.post(voiceWaveTicker)
    }

    private fun stopVoiceWave() {
        voiceStatusBar.removeCallbacks(voiceWaveTicker)
        lastVoiceLevelAt = 0L
    }

    private fun clearAnimation() {
        animator.inAnimation = null
        animator.outAnimation = null
    }

    private fun setAnimation() {
        animator.inAnimation = inAnimation
        animator.outAnimation = outAnimation
    }

    private fun enableSlideTransition(inTarget: View, outTarget: View, inGravity: Int, outGravity: Int) {
        val slideIn = Slide(inGravity).apply { duration = 200L }
        val slideOut = Slide(outGravity).apply { duration = 200L }
        slideIn.addTarget(inTarget)
        slideOut.addTarget(outTarget)
        val set = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(slideIn)
            addTransition(slideOut)
        }
        TransitionManager.beginDelayedTransition(root, set)
    }

    fun updateState(state: State, fromUser: Boolean = false) {
        Timber.d("Switch idle ui to $state")
        if (voiceStatusBar.visibility == View.VISIBLE && state != State.NumberRow) {
            currentState = state
            updateMenuButtonContentDescription()
            updateMenuButtonRotation(instant = !fromUser)
            return
        }
        if (
            !fromUser ||
            disableAnimation ||
            (state == State.InlineSuggestion || currentState == State.InlineSuggestion) ||
            (state == State.NumberRow || currentState == State.NumberRow)
        ) {
            clearAnimation()
        } else {
            setAnimation()
        }
        when (state) {
            State.Empty -> animator.displayedChild = 0
            State.Toolbar -> animator.displayedChild = 1
            State.Clipboard -> animator.displayedChild = 2
            State.NumberRow -> {}
            State.InlineSuggestion -> animator.displayedChild = 3
        }
        if (state == State.NumberRow) {
            numberRow.keyActionListener = commonKeyActionListener.listener
            numberRow.popupActionListener = popup.listener
            if (fromUser && !disableAnimation) {
                enableSlideTransition(numberRow, idleBody, Gravity.END, Gravity.START)
            }
            numberRow.visibility = View.VISIBLE
            idleBody.visibility = View.GONE
        } else if (currentState == State.NumberRow) {
            if (fromUser && !disableAnimation) {
                enableSlideTransition(idleBody, numberRow, Gravity.START, Gravity.END)
            }
            idleBody.visibility = View.VISIBLE
            numberRow.visibility = View.GONE
            numberRow.keyActionListener = null
            numberRow.popupActionListener = null
            popup.dismissAll()
        }
        currentState = state
        updateMenuButtonContentDescription()
        updateMenuButtonRotation(instant = !fromUser)
    }
}
