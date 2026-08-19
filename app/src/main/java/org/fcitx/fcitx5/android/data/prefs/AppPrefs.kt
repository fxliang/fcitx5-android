/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesVirtualKeyboardPosition
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.SpaceKeyLabelMode
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
        val floatingKeyboardWidthPortraitRatio = float("floating_keyboard_width_portrait_ratio", 0f)
        val floatingKeyboardWidthLandscapeRatio = float("floating_keyboard_width_landscape_ratio", 0f)
        val floatingKeyboardHeightPortraitRatio = float("floating_keyboard_height_portrait_ratio", 0f)
        val floatingKeyboardHeightLandscapeRatio = float("floating_keyboard_height_landscape_ratio", 0f)
        // legacy single (orientation-agnostic) ratio prefs kept for one-time migration
        val floatingKeyboardWidthRatio = float("floating_keyboard_width_ratio", 0f)
        val floatingKeyboardHeightRatio = float("floating_keyboard_height_ratio", 0f)
        // legacy px prefs kept for one-time migration to the ratio-based values above
        val floatingKeyboardWidthLegacy = int("floating_keyboard_width", 0)
        val floatingKeyboardHeightLegacy = int("floating_keyboard_height", 0)
        val floatingKeyboardXPortraitRatio = float("floating_keyboard_x_portrait_ratio", -1f)
        val floatingKeyboardYPortraitRatio = float("floating_keyboard_y_portrait_ratio", -1f)
        val floatingKeyboardXLandscapeRatio = float("floating_keyboard_x_landscape_ratio", -1f)
        val floatingKeyboardYLandscapeRatio = float("floating_keyboard_y_landscape_ratio", -1f)
        // legacy px prefs kept for one-time migration to the ratio-based values above
        val floatingKeyboardXPortrait = int("floating_keyboard_x_portrait", -1)
        val floatingKeyboardYPortrait = int("floating_keyboard_y_portrait", -1)
        val floatingKeyboardXLandscape = int("floating_keyboard_x_landscape", -1)
        val floatingKeyboardYLandscape = int("floating_keyboard_y_landscape", -1)
        val oneHandOnRightPortrait = bool("one_hand_on_right_portrait", true)
        val oneHandOnRightLandscape = bool("one_hand_on_right_landscape", true)
        val floatingModeEnabled = bool("floating_mode_enabled", false)
        val oneHandModeEnabled = bool("one_hand_mode_enabled", false)
        val oneHandKeyboardWidthPortraitRatio = float("one_hand_keyboard_width_portrait_ratio", 0f)
        val oneHandKeyboardWidthLandscapeRatio = float("one_hand_keyboard_width_landscape_ratio", 0f)
        // legacy single (orientation-agnostic) ratio pref kept for one-time migration
        val oneHandKeyboardWidthRatio = float("one_hand_keyboard_width_ratio", 0f)
        // legacy px pref kept for one-time migration to the ratio-based value above
        val oneHandKeyboardWidthLegacy = int("one_hand_keyboard_width", 0)

        // Settings initialization flag
        val settingsInitialized = bool("settings_initialized", false)
        val splitKeyboardMigrated = bool("split_keyboard_migrated", false)
        val lastShareReceiveDirectory = string("last_share_receive_directory", "")
        val lastShareReceiveDirectoryRemembered = bool("last_share_receive_directory_remembered", false)
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // Some vendor input windows can dispatch a key gesture more than once.
            DeviceUtil.isVivoOriginOS ||
                DeviceUtil.isMIUI ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
        val allowOriginalPlugins = switch(
            R.string.allow_original_plugins,
            "allow_original_plugins",
            false,
            R.string.allow_original_plugins_summary
        )
        val allowedPluginPrefixes = stringSet(
            R.string.allowed_plugin_prefixes,
            "allowed_plugin_prefixes",
            emptySet(),
            R.string.allowed_plugin_prefixes_summary
        ) { allowOriginalPlugins.getValue() }
        val keyboardHeightPercentBase = enumList(
            R.string.keyboard_height_percent_base,
            "keyboard_height_percent_base",
            KeyboardHeightPercentBase.DisplayMetrics
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                0,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                0,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val expandToolbarByDefault =
            switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", false)
        val toolbarManuallyToggled = switch(
            R.string.toolbar_manually_toggled,
            "toolbar_manually_toggled",
            false
        )
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) { showVoiceInputButton.getValue() }

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Down
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.None
        )
        val spaceKeyLabelMode = enumList(
            R.string.space_key_label_mode,
            "space_key_label_mode",
            SpaceKeyLabelMode.Default
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val textKeyboardLayoutProfile = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_profile",
            "default"
        ).apply { register() }
        /**
         * 数字输入框使用的自定义布局键（可为任意布局名或 ime:submode，如 rime:wanxiang）。
         * 留空表示使用内置数字键盘。仅存于应用设置，与布局文件解耦。
         */
        val numericLayoutOverride = ManagedPreference.PString(
            sharedPreferences,
            "numeric_layout_override",
            ""
        ).apply { register() }
        val langSwitchKeyBehavior = enumList(
            R.string.lang_switch_key_behavior,
            "lang_switch_key_behavior",
            LangSwitchBehavior.Enumerate
        ) { showLangSwitchKey.getValue() }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                30,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                49,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val dm = appContext.resources.displayMetrics
            val shortEdgeDp = (minOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()
            val longEdgeDp = (maxOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()
            // Keep keyboard width >= 1/2 screen width: sidePadding <= width/4.
            val portraitMaxPaddingDp = (shortEdgeDp / 4).coerceAtLeast(0)
            val landscapeMaxPaddingDp = (longEdgeDp / 4).coerceAtLeast(0)
            val sidePaddingMaxDp = maxOf(portraitMaxPaddingDp, landscapeMaxPaddingDp)
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                0,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                0,
                0,
                sidePaddingMaxDp,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                0,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                0,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        // ===== Split keyboard settings =====
        // Note: Threshold and gap are managed exclusively via SplitKeyboardCalibrationActivity
        // They are stored in Keyboard category to trigger InputView refresh when changed
        val splitKeyboardEnabled = switch(
            R.string.split_keyboard_enabled,
            "split_keyboard_enabled",
            true  // Default enabled; may be adjusted based on device type on first install
        )

        // Internal split keyboard settings (no UI, managed via calibration activity)
        val splitKeyboardThreshold = ManagedPreference.PInt(sharedPreferences, "split_keyboard_threshold", 470).apply { register() }
        val splitKeyboardGapPercent = ManagedPreference.PInt(sharedPreferences, "split_keyboard_gap_percent", 20).apply { register() }

        // When enabled, use landscape layout parameters (height/padding/etc.) while split keyboard is active
        val splitKeyboardUseLandscapeLayout = switch(
            R.string.split_keyboard_use_landscape_layout,
            "split_keyboard_use_landscape_layout",
            false
        )

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                6,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                8,
                4,
                12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val physicalKeyboardHorizontalCandidateBar = switch(
            R.string.physical_keyboard_horizontal_candidate_bar,
            "physical_keyboard_horizontal_candidate_bar",
            false,
            R.string.physical_keyboard_horizontal_candidate_bar_summary
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val virtualKeyboardPosition = enumList(
            R.string.candidates_position,
            "virtual_keyboard_candidates_position",
            FloatingCandidatesVirtualKeyboardPosition.TopLeft
        ) {
            mode.getValue() == FloatingCandidatesMode.Always
        }

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val candidateHighlightRadius =
            int(R.string.candidate_highlight_radius, "candidate_highlight_radius", 2, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        init {
            val legacyKey = "clipboard_limit"
            val localKey = "clipboard_limit_local"
            val remoteKey = "clipboard_limit_remote"
            val mediaKey = "clipboard_limit_media"
            if (!sharedPreferences.contains(localKey) ||
                !sharedPreferences.contains(remoteKey) ||
                !sharedPreferences.contains(mediaKey)
            ) {
                val legacyValue = if (sharedPreferences.contains(legacyKey)) {
                    sharedPreferences.getInt(legacyKey, 100)
                } else {
                    100
                }
                sharedPreferences.edit {
                    if (!sharedPreferences.contains(localKey)) putInt(localKey, legacyValue)
                    if (!sharedPreferences.contains(remoteKey)) putInt(remoteKey, legacyValue)
                    if (!sharedPreferences.contains(mediaKey)) putInt(mediaKey, legacyValue)
                    remove(legacyKey)
                }
            }
            val timeoutKey = "clipboard_item_timeout"
            val timeoutValue = sharedPreferences.getInt(timeoutKey, 30)
            if (timeoutValue < 0) {
                sharedPreferences.edit {
                    putInt(timeoutKey, 0)
                }
            }
        }

        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimitLocal = int(
            R.string.clipboard_limit_local,
            "clipboard_limit_local",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitRemote = int(
            R.string.clipboard_limit_remote,
            "clipboard_limit_remote",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitMedia = int(
            R.string.clipboard_limit_media,
            "clipboard_limit_media",
            100,
            0,
            1000,
            step = 10,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            0,
            600,
            "s",
            step = 5
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround,
                internal.floatingKeyboardWidthPortraitRatio,
                internal.floatingKeyboardWidthLandscapeRatio,
                internal.floatingKeyboardHeightPortraitRatio,
                internal.floatingKeyboardHeightLandscapeRatio,
                internal.floatingKeyboardXPortraitRatio,
                internal.floatingKeyboardYPortraitRatio,
                internal.floatingKeyboardXLandscapeRatio,
                internal.floatingKeyboardYLandscapeRatio,
                internal.oneHandOnRightPortrait,
                internal.oneHandOnRightLandscape,
                internal.floatingModeEnabled,
                internal.oneHandModeEnabled,
                internal.oneHandKeyboardWidthPortraitRatio,
                internal.oneHandKeyboardWidthLandscapeRatio
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
