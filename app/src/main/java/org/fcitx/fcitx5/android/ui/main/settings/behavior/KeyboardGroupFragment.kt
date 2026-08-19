/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor.ImeWebEditorBridgeServer

/**
 * Sub-fragment showing only the preferences belonging to one keyboard settings group.
 * Extends [ManagedPreferenceFragment] to inherit consistent lifecycle, visibility
 * evaluation and styling, then filters out items not belonging to the requested group.
 */
class KeyboardGroupFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    private val group: Int get() = arguments?.getInt("group", -1) ?: -1
    private val viewModel: MainViewModel by activityViewModels()

    private var calibrationPreference: Preference? = null
    private var textLayoutFileSelectPreference: Preference? = null
    private var webEditorBridgePreference: Preference? = null
    private var numericLayoutOverridePreference: Preference? = null

    private val onSplitEnabledChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key == "split_keyboard_enabled") {
            val enabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            calibrationPreference?.isEnabled = enabled
            val useLandscapePref = preferenceScreen
                .findPreference<Preference>("split_keyboard_use_landscape_layout")
            useLandscapePref?.isEnabled = enabled
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val groupKeys = KEYS_BY_GROUP[group] ?: emptySet()

        // Remove managed preferences not belonging to this group
        val toRemove = mutableListOf<Preference>()
        for (i in 0 until screen.preferenceCount) {
            val pref = screen.getPreference(i)
            if (pref.key.isNotEmpty() && pref.key !in groupKeys) {
                toRemove.add(pref)
            }
        }
        toRemove.forEach { screen.removePreference(it) }

        // Group 0 extras: split calibration
        if (group == GROUP_LAYOUT) {
            calibrationPreference = addTool(screen, CALIBRATION_PREF_KEY,
                R.string.split_keyboard_calibration_title,
                ""
            ) {
                startActivity(Intent(requireContext(), SplitKeyboardCalibrationActivity::class.java))
            }
            calibrationPreference?.isEnabled =
                AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            val useLandscapePref = screen
                .findPreference<Preference>("split_keyboard_use_landscape_layout")
            useLandscapePref?.isEnabled =
                AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            AppPrefs.getInstance().keyboard.registerOnChangeListener(onSplitEnabledChangeListener)
        }

        // Group 5 extras: customization tool entries
        if (group == GROUP_EDITORS) {
            addTool(screen, "tool_fontset_editor",
                R.string.edit_fontset, ""
            ) { startActivity(Intent(requireContext(), FontsetEditorActivity::class.java)) }

            addTool(screen, "tool_popup_editor",
                R.string.edit_popup_preset, ""
            ) { startActivity(Intent(requireContext(), PopupEditorActivity::class.java)) }

            addTool(screen, "tool_text_layout_editor",
                R.string.edit_text_keyboard_layout, ""
            ) { startActivity(Intent(requireContext(), TextKeyboardLayoutEditorActivity::class.java)) }

            textLayoutFileSelectPreference = addTool(screen, TEXT_LAYOUT_FILE_SELECT_PREF_KEY,
                R.string.text_keyboard_layout_file_select_title,
                buildCurrentTextLayoutFileSummary()
            ) { showSelectTextLayoutFileDialog() }

            numericLayoutOverridePreference = addTool(screen, "tool_numeric_layout_override",
                R.string.numeric_layout_override_title,
                buildNumericLayoutOverrideSummary()
            ) { showNumericLayoutOverrideDialog() }

            webEditorBridgePreference = addTool(screen, "tool_web_editor_bridge",
                R.string.web_editor_bridge_title, ""
            ) { toggleWebEditorBridge() }
        }

        // Group 3 extras: edit buttons (toolbar customization)
        if (group == GROUP_TOOLBAR) {
            addTool(screen, "tool_edit_buttons",
                R.string.edit_buttons, R.string.edit_buttons_summary
            ) { startActivity(Intent(requireContext(), ButtonsCustomizerActivity::class.java)) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setToolbarTitle(getString(groupTitleRes(group)))
        if (group == GROUP_EDITORS) {
            textLayoutFileSelectPreference?.summary = buildCurrentTextLayoutFileSummary()
            numericLayoutOverridePreference?.summary = buildNumericLayoutOverrideSummary()
            updateWebEditorBridgeStatus()
        }
    }

    override fun onDestroy() {
        if (group == GROUP_LAYOUT) {
            AppPrefs.getInstance().keyboard
                .unregisterOnChangeListener(onSplitEnabledChangeListener)
        }
        super.onDestroy()
    }

    // ---- helpers ----

    private fun addTool(
        screen: PreferenceScreen, key: String, titleRes: Int, summary: CharSequence,
        onClick: () -> Unit
    ): Preference = Preference(requireContext()).apply {
        this.key = key
        setTitle(titleRes)
        setSummary(summary)
        isSingleLineTitle = false
        isIconSpaceReserved = false
        setOnPreferenceClickListener { onClick(); true }
    }.also { screen.addPreference(it) }

    private fun addTool(
        screen: PreferenceScreen, key: String, titleRes: Int, summaryRes: Int,
        onClick: () -> Unit
    ): Preference = addTool(screen, key, titleRes, getString(summaryRes), onClick)

    private fun buildCurrentTextLayoutFileSummary() = getString(
        R.string.text_keyboard_layout_file_select_summary,
        displayProfile(currentTextLayoutProfile())
    )

    private fun currentTextLayoutProfile() =
        UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

    private fun displayProfile(profile: String) =
        if (profile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
            getString(R.string.default_) else profile

    private fun buildNumericLayoutOverrideSummary(): String {
        val value = AppPrefs.getInstance().keyboard.numericLayoutOverride.getValue().trim()
        return if (value.isEmpty()) getString(R.string.numeric_layout_override_builtin) else value
    }

    /**
     * 列出当前启用的布局文件中的层：基础布局与 ime:submode 子布局。
     * 复用布局编辑器（LayoutDataManager.parseJsonText）的解析逻辑，与编辑页
     * 下拉框枚举的层完全一致。
     */
    private fun collectLayoutLayerEntries(): List<String> {
        val file = ConfigProviders.provider.textKeyboardLayoutFile() ?: return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            LayoutDataManager(requireContext()).parseJsonText(text, file.name, fallbackToDefault = false)
        }.getOrNull() ?: return emptyList()
        return parsed.keys.sorted()
    }

    private fun showNumericLayoutOverrideDialog() {
        val builtin = getString(R.string.numeric_layout_override_builtin)
        val layers = collectLayoutLayerEntries()
        val items = listOf(builtin) + layers
        val current = AppPrefs.getInstance().keyboard.numericLayoutOverride.getValue().trim()
        val checked = if (current.isEmpty()) 0 else items.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.numeric_layout_override_title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                AppPrefs.getInstance().keyboard.numericLayoutOverride
                    .setValue(if (which == 0) "" else items[which])
                numericLayoutOverridePreference?.summary = buildNumericLayoutOverrideSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }

    private fun showSelectTextLayoutFileDialog() {
        val profiles = UserConfigFiles.listTextKeyboardLayoutProfiles().toMutableList()
        val current = currentTextLayoutProfile()
        if (current !in profiles) profiles += current
        val sortedProfiles = profiles.distinct()
            .sortedWith(compareBy(
                { it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }
            ))
        val labels = sortedProfiles.map { displayProfile(it) }.toTypedArray()
        val initial = sortedProfiles.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_keyboard_layout_file_select_title)
            .setSingleChoiceItems(labels, initial) { dialog, which ->
                val sel = sortedProfiles.getOrNull(which) ?: return@setSingleChoiceItems
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(sel)
                ConfigProviders.provider = ConfigProviders.provider
                textLayoutFileSelectPreference?.summary = buildCurrentTextLayoutFileSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleWebEditorBridge() {
        val session = ImeWebEditorBridgeServer.currentSession()
        if (session != null) {
            // Already running — show dialog with open/stop options
            showWebEditorBridgeDialog(session)
        } else {
            // Not running — start it
            val result = runCatching { ImeWebEditorBridgeServer.start() }
            result.onSuccess {
                showWebEditorBridgeDialog(it)
            }.onFailure {
                toast(it.localizedMessage ?: getString(R.string.web_editor_bridge_start_failed, ""))
            }
        }
    }

    private fun updateWebEditorBridgeStatus() {
        val session = ImeWebEditorBridgeServer.currentSession()
        webEditorBridgePreference?.summary = if (session != null) {
            getString(R.string.web_editor_bridge_running_status, session.host, session.port)
        } else {
            getString(R.string.web_editor_bridge_summary)
        }
    }

    private fun showWebEditorBridgeDialog(session: ImeWebEditorBridgeServer.Session) {
        val msg = getString(
            R.string.web_editor_bridge_running_message, session.editorUrl, session.apiBaseUrl)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.web_editor_bridge_title)
            .setMessage(msg)
            .setPositiveButton(R.string.web_editor_bridge_open) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.editorUrl)))
            }
            .setNeutralButton(R.string.web_editor_bridge_stop) { _, _ ->
                ImeWebEditorBridgeServer.stop()
                updateWebEditorBridgeStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .setOnDismissListener {
                updateWebEditorBridgeStatus()
            }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CALIBRATION_PREF_KEY = "split_keyboard_calibration"
        private const val TEXT_LAYOUT_FILE_SELECT_PREF_KEY = "text_keyboard_layout_file_select"

        const val GROUP_LAYOUT = 0
        const val GROUP_BEHAVIOR = 1
        const val GROUP_FEEDBACK = 2
        const val GROUP_TOOLBAR = 3
        const val GROUP_EDITORS = 4

        val KEYS_BY_GROUP: Map<Int, Set<String>> = mapOf(
            GROUP_LAYOUT to setOf(
                "keyboard_height_percent", "keyboard_side_padding",
                "keyboard_bottom_padding", "expand_keypress_area",
                "split_keyboard_enabled", "split_keyboard_use_landscape_layout",
            ),
            GROUP_BEHAVIOR to setOf(
                "popup_on_key_press", "keyboard_long_press_delay",
                "swipe_symbol_behavior", "keep_keyboard_letters_uppercase",
                "reset_keyboard_on_focus_change",
                "space_long_press_behavior", "space_key_label_mode",
                "space_swipe_move_cursor", "show_lang_switch_key",
                "lang_switch_key_behavior",
            ),
            GROUP_FEEDBACK to setOf(
                "haptic_on_keypress", "haptic_on_keyup", "haptic_on_repeat",
                "button_vibration_press_milliseconds", "button_vibration_press_amplitude",
                "sound_on_keypress", "button_sound_volume"
            ),
            GROUP_TOOLBAR to setOf(
                "expand_toolbar_by_default", "toolbar_manually_toggled",
                "inline_suggestions", "toolbar_num_row_on_password",
                "horizontal_candidate_style", "expanded_candidate_style",
                "expanded_candidate_grid_span_count_portrait",
                "show_voice_input_button", "preferred_voice_input",
            ),
            GROUP_EDITORS to emptySet(),
        )

        fun groupTitleRes(group: Int): Int = when (group) {
            GROUP_LAYOUT -> R.string.keyboard_category_layout
            GROUP_BEHAVIOR -> R.string.keyboard_category_behavior
            GROUP_FEEDBACK -> R.string.keyboard_category_feedback
            GROUP_TOOLBAR -> R.string.keyboard_category_toolbar
            GROUP_EDITORS -> R.string.keyboard_category_editors
            else -> throw IllegalArgumentException("Unknown group: $group")
        }
    }
}
