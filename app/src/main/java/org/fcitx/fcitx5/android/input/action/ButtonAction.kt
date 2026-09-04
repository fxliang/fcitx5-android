/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.action

import android.content.Intent
import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FontsetEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerActivity
import org.fcitx.fcitx5.android.ui.main.settings.icon.IconThemeListActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.fcitx.fcitx5.android.utils.toast

/**
 * Represents a configurable button action that can be used in Kawaii Bar, Status Area, or keyboard.
 */
sealed class ButtonAction {
    /**
     * Unique identifier for this button action.
     */
    abstract val id: String

    /**
     * Default icon resource for this button.
     */
    @get:DrawableRes
    abstract val defaultIcon: Int

    /**
     * Default label string resource for this button.
     */
    abstract val defaultLabelRes: Int

    /**
     * Icon theme slot name for this button. Null if not covered by icon themes.
     */
    open val iconSlot: String? = null

    /**
     * Execute the action.
     * @param context Android context
     * @param service Input method service
     * @param fcitx Fcitx connection
     * @param windowManager Window manager for attaching/detaching windows
     * @param view The view that triggered this action (for popup menus, etc.)
     * @param onActionComplete Callback to be invoked after action is completed
     */
    abstract fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View? = null,
        onActionComplete: (() -> Unit)? = null
    )

    /**
     * Check if this button should be active (highlighted).
     * @param service Input method service
     * @return true if the button should be shown as active
     */
    open fun isActive(service: FcitxInputMethodService): Boolean = false

    /**
     * Long press action for this button, if different from short press.
     * @param context Android context
     * @param service Input method service
     * @param fcitx Fcitx connection
     * @param windowManager Window manager
     * @param view The view that triggered this action
     */
    open fun onLongPress(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View
    ) {
        // Default: no long press action
    }

    companion object {
        /**
         * Get a ButtonAction by its ID.
         * @param id The button ID
         * @return The corresponding ButtonAction, or null if not found
         */
        fun fromId(id: String): ButtonAction? = allActions.find { it.id == id }

        /**
         * All available button actions.
         */
        val allActions = listOf(
            UndoAction,
            RedoAction,
            CursorMoveAction,
            FloatingToggleAction,
            ClipboardAction,
            ThemeToggleAction,
            LanguageSwitchAction,
            ThemeAction,
            IconThemeAction,
            InputMethodOptionsAction,
            ReloadConfigAction,
            VirtualKeyboardAction,
            OneHandedKeyboardAction,
            BrowseUserDataDirAction,
            SettingsGlobalOptionsAction,
            SettingsInputMethodsAction,
            SettingsCandidatesWindowAction,
            SettingsClipboardSettingsAction,
            SettingsSymbolSettingsAction,
            SettingsPluginSettingsAction,
            SettingsAdvancedAction,
            SettingsDeveloperAction,
            SettingsAboutAction,
            SettingsLicenseAction,
            EditTextKeyboardLayoutAction,
            TextKeyboardLayoutFileSelectAction,
            EditFontsetAction,
            MoreAction
        )

        /**
         * Button actions available for Kawaii Bar.
         */
        val kawaiiBarActions = listOf(
            UndoAction,
            RedoAction,
            CursorMoveAction,
            FloatingToggleAction,
            ClipboardAction,
            ThemeToggleAction
        )

        /**
         * Button actions available for Status Area.
         */
        val statusAreaActions = listOf(
            LanguageSwitchAction,
            ThemeAction,
            IconThemeAction,
            InputMethodOptionsAction,
            ReloadConfigAction,
            VirtualKeyboardAction,
            OneHandedKeyboardAction
        )

        /**
         * All actions that can be added to either section.
         */
        val allConfigurableActions = kawaiiBarActions + statusAreaActions
    }
}

// Kawaii Bar Actions

data object UndoAction : ButtonAction() {
    override val id = "undo"
    override val defaultIcon = R.drawable.ic_baseline_undo_24
    override val defaultLabelRes = R.string.undo
    override val iconSlot = "toolbar.undo"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
    }
}

data object RedoAction : ButtonAction() {
    override val id = "redo"
    override val defaultIcon = R.drawable.ic_baseline_redo_24
    override val defaultLabelRes = R.string.redo
    override val iconSlot = "toolbar.redo"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
    }
}

data object CursorMoveAction : ButtonAction() {
    override val id = "cursor_move"
    override val defaultIcon = R.drawable.ic_cursor_move
    override val defaultLabelRes = R.string.text_editing
    override val iconSlot = "toolbar.cursor_move"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        windowManager.attachWindow(TextEditingWindow())
    }
}

data object FloatingToggleAction : ButtonAction() {
    override val id = "floating_toggle"
    override val defaultIcon = R.drawable.ic_floating_toggle_24
    override val defaultLabelRes = R.string.floating_keyboard
    override val iconSlot = "toolbar.floating_toggle"

    override fun isActive(service: FcitxInputMethodService): Boolean {
        return service.inputView?.isFloating == true
    }

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        val inputView = service.inputView ?: return
        if (inputView.isAdjustingMode) {
            inputView.exitAdjustingMode()
        } else {
            inputView.toggleFloatingMode()
        }
        onActionComplete?.invoke()
    }

    override fun onLongPress(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View
    ) {
        service.inputView?.enterAdjustingMode()
    }
}

data object ClipboardAction : ButtonAction() {
    override val id = "clipboard"
    override val defaultIcon = R.drawable.ic_clipboard
    override val defaultLabelRes = R.string.clipboard
    override val iconSlot = "toolbar.clipboard"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        windowManager.attachWindow(ClipboardWindow())
    }
}

data object MoreAction : ButtonAction() {
    override val id = "more"
    override val defaultIcon = R.drawable.ic_baseline_more_horiz_24
    override val defaultLabelRes = R.string.status_area
    override val iconSlot = "toolbar.more"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        windowManager.attachWindow(StatusAreaWindow())
    }
}

data object ThemeToggleAction : ButtonAction() {
    override val id = "theme_toggle"
    override val defaultIcon = R.drawable.ic_theme_light_dark_24
    override val defaultLabelRes = R.string.toggle_day_night_theme
    override val iconSlot = "toolbar.theme_toggle"

    override fun isActive(service: FcitxInputMethodService): Boolean {
        return ThemeManager.isUsingConfiguredDarkTheme()
    }

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        ThemeManager.toggleConfiguredDayNightTheme()
        onActionComplete?.invoke()
    }

    override fun onLongPress(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View
    ) {
        AppUtil.launchMainToThemeList(context)
    }
}

// Status Area Actions

data object LanguageSwitchAction : ButtonAction() {
    override val id = "language_switch"
    override val defaultIcon = R.drawable.ic_baseline_language_24
    override val defaultLabelRes = R.string.language_switch
    override val iconSlot = "toolbar.language_switch"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        val behavior = AppPrefs.getInstance().keyboard.langSwitchKeyBehavior.getValue()
        when (behavior) {
            LangSwitchBehavior.Enumerate -> {
                fcitx.launchOnReady { f ->
                    if (f.enabledIme().size < 2) {
                        service.lifecycleScope.launch {
                            service.showDialog(AddMoreInputMethodsPrompt.build(context))
                        }
                    } else {
                        f.enumerateIme()
                    }
                }
            }
            LangSwitchBehavior.ToggleActivate -> {
                fcitx.launchOnReady { it.toggleIme() }
            }
            LangSwitchBehavior.NextInputMethodApp -> {
                service.switchToNextIME()
            }
        }
    }

    override fun onLongPress(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View
    ) {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }
}

data object ThemeAction : ButtonAction() {
    override val id = "theme"
    override val defaultIcon = R.drawable.ic_baseline_palette_24
    override val defaultLabelRes = R.string.theme
    override val iconSlot = "toolbar.theme"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToThemeList(context)
    }
}

data object IconThemeAction : ButtonAction() {
    override val id = "icon_theme"
    override val defaultIcon = R.drawable.ic_icon_theme_24
    override val defaultLabelRes = R.string.icon_theme
    override val iconSlot = "toolbar.icon_theme"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        context.startActivity(Intent(context, IconThemeListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        })
    }
}

data object InputMethodOptionsAction : ButtonAction() {
    override val id = "input_method_options"
    override val defaultIcon = R.drawable.ic_baseline_language_24
    override val defaultLabelRes = R.string.input_method_options
    override val iconSlot = "toolbar.input_method_options"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        fcitx.runImmediately { inputMethodEntryCached }.let {
            AppUtil.launchMainToInputMethodConfig(context, it.uniqueName, it.displayName)
        }
    }
}

data object ReloadConfigAction : ButtonAction() {
    override val id = "reload_config"
    override val defaultIcon = R.drawable.ic_baseline_sync_24
    override val defaultLabelRes = R.string.reload_config
    override val iconSlot = "toolbar.reload_config"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        fcitx.launchOnReady { f ->
            f.reloadConfig()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SubtypeManager.syncWith(f.enabledIme())
            }
            service.lifecycleScope.launch {
                android.widget.Toast.makeText(service, R.string.done, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

data object VirtualKeyboardAction : ButtonAction() {
    override val id = "virtual_keyboard"
    override val defaultIcon = R.drawable.ic_baseline_keyboard_24
    override val defaultLabelRes = R.string.virtual_keyboard
    override val iconSlot = "toolbar.virtual_keyboard"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToKeyboard(context)
    }
}

data object OneHandedKeyboardAction : ButtonAction() {
    override val id = "one_handed_keyboard"
    override val defaultIcon = R.drawable.ic_baseline_keyboard_tab_24
    override val defaultLabelRes = R.string.one_handed_keyboard
    override val iconSlot = "toolbar.one_handed_keyboard"

    override fun isActive(service: FcitxInputMethodService): Boolean {
        return service.isOneHandKeyboardEnabled()
    }

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        service.toggleOneHandKeyboard()
    }
}

data object BrowseUserDataDirAction : ButtonAction() {
    override val id = "browse_user_data_dir"
    override val defaultIcon = R.drawable.ic_baseline_more_horiz_24
    override val defaultLabelRes = R.string.browse_user_data_dir
    override val iconSlot = "toolbar.browse_user_data"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        runCatching {
            context.startActivity(buildDocumentsProviderIntent())
        }.onFailure {
            context.toast(it)
        }
    }
}

data object SettingsGlobalOptionsAction : ButtonAction() {
    override val id = "settings_global_options"
    override val defaultIcon = R.drawable.ic_baseline_tune_24
    override val defaultLabelRes = R.string.global_options
    override val iconSlot = "toolbar.settings_global"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.GlobalConfig)
    }
}

data object SettingsInputMethodsAction : ButtonAction() {
    override val id = "settings_input_methods"
    override val defaultIcon = R.drawable.ic_baseline_language_24
    override val defaultLabelRes = R.string.input_methods
    override val iconSlot = "toolbar.settings_ime"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.InputMethodList)
    }
}

data object SettingsCandidatesWindowAction : ButtonAction() {
    override val id = "settings_candidates_window"
    override val defaultIcon = R.drawable.ic_baseline_list_alt_24
    override val defaultLabelRes = R.string.candidates_window

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.CandidatesWindow)
    }
}

data object SettingsClipboardSettingsAction : ButtonAction() {
    override val id = "settings_clipboard"
    override val defaultIcon = R.drawable.ic_clipboard
    override val defaultLabelRes = R.string.clipboard

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.Clipboard)
    }
}

data object SettingsSymbolSettingsAction : ButtonAction() {
    override val id = "settings_symbol"
    override val defaultIcon = R.drawable.ic_baseline_emoji_symbols_24
    override val defaultLabelRes = R.string.emoji_and_symbols

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.Symbol)
    }
}

data object SettingsPluginSettingsAction : ButtonAction() {
    override val id = "settings_plugin"
    override val defaultIcon = R.drawable.ic_baseline_android_24
    override val defaultLabelRes = R.string.plugins

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.Plugin)
    }
}

data object SettingsAdvancedAction : ButtonAction() {
    override val id = "settings_advanced"
    override val defaultIcon = R.drawable.ic_baseline_more_horiz_24
    override val defaultLabelRes = R.string.advanced

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.Advanced)
    }
}

data object SettingsDeveloperAction : ButtonAction() {
    override val id = "settings_developer"
    override val defaultIcon = R.drawable.ic_baseline_more_horiz_24
    override val defaultLabelRes = R.string.developer

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.Developer)
    }
}

data object SettingsAboutAction : ButtonAction() {
    override val id = "settings_about"
    override val defaultIcon = R.drawable.ic_baseline_more_horiz_24
    override val defaultLabelRes = R.string.about

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.About)
    }
}

data object SettingsLicenseAction : ButtonAction() {
    override val id = "settings_license"
    override val defaultIcon = R.drawable.ic_baseline_more_horiz_24
    override val defaultLabelRes = R.string.license

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToRoute(context, SettingsRoute.License)
    }
}

data object EditTextKeyboardLayoutAction : ButtonAction() {
    override val id = "edit_text_keyboard_layout"
    override val defaultIcon = R.drawable.ic_baseline_keyboard_24
    override val defaultLabelRes = R.string.edit_text_keyboard_layout
    override val iconSlot = "toolbar.edit_layout"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        context.startActivity(Intent(context, TextKeyboardLayoutEditorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

data object TextKeyboardLayoutFileSelectAction : ButtonAction() {
    override val id = "text_keyboard_layout_file_select"
    override val defaultIcon = R.drawable.ic_baseline_library_books_24
    override val defaultLabelRes = R.string.text_keyboard_layout_file_select_title

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        context.startActivity(Intent(context, TextKeyboardLayoutProfilePickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

data object EditFontsetAction : ButtonAction() {
    override val id = "edit_fontset"
    override val defaultIcon = R.drawable.ic_baseline_text_format_24
    override val defaultLabelRes = R.string.edit_fontset
    override val iconSlot = "toolbar.edit_fontset"

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        context.startActivity(Intent(context, FontsetEditorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
