/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.input.picker.PickerWindow

/**
 * Macro 步骤类型
 * 参考 Vial 的 macro 设计，支持以下操作：
 * - Down: 按下键（不释放）
 * - Up: 释放键
 * - Tap: 点击并释放键
 * - Text: 提交文本
 * - Clipboard: 剪贴板操作（copy/cut/paste/selectAll/undo/redo）
 * - Shortcut: 快捷键（自动展开为 modifier down + key tap + modifier up）
 */
sealed class MacroStep {
    /**
     * 按下键（不释放）
     * @param keys 按键列表，按顺序执行
     */
    data class Down(val keys: List<KeyRef>) : MacroStep()

    /**
     * 释放键
     * @param keys 按键列表，按顺序执行
     */
    data class Up(val keys: List<KeyRef>) : MacroStep()

    /**
     * 点击键（按下并释放）
     * @param keys 按键列表，按顺序执行
     */
    data class Tap(val keys: List<KeyRef>) : MacroStep()

    /**
     * 提交文本
     * @param text 要提交的文本
     */
    data class Text(val text: String) : MacroStep()

    /**
     * 编辑操作（copy/cut/paste/selectAll/undo/redo）
     * @param action 操作类型：copy, cut, paste, selectAll, undo, redo
     */
    data class Edit(val action: String) : MacroStep()

    /**
     * 快捷键（自动展开为 modifier down + key tap + modifier up）
     * @param modifiers 修饰键列表：如 [Ctrl_L, Alt_L]
     * @param key 目标按键
     */
    data class Shortcut(val modifiers: List<KeyRef>, val key: KeyRef) : MacroStep()

    /**
     * 调用应用内动作（复用 ButtonAction id）
     * @param id 动作 ID，如 "theme", "virtual_keyboard", "more"
     */
    data class AppAction(val id: String) : MacroStep()

    /**
     * Layer switch action: TO/OSL
     */
    data class LayerSwitch(
        val mode: KeyAction.LayerSwitchMode,
        val target: String
    ) : MacroStep()
}

/**
 * 按键引用 - 使用字段名区分类型
 * - "fcitx": Fcitx 虚拟键
 * - "android": Android 实体键
 */
sealed class KeyRef {
    /**
     * Fcitx 虚拟键
     * @param code 键名，如 "Ctrl_L", "Shift_L", "Enter", "a" 等
     */
    data class Fcitx(val code: String) : KeyRef()

    /**
     * Android 实体键
     * @param code Android 键码，参考 android.view.KeyEvent
     */
    data class Android(val code: Int) : KeyRef()
}

/**
 * Macro 动作，由多个 MacroStep 组成
 * 支持 down/up/tap/text 组合
 */
data class MacroAction(val steps: List<MacroStep>) : KeyAction()

sealed class KeyAction {
    enum class LayerSwitchMode {
        TO, OSL
    }

    data class FcitxKeyAction(
        val act: String,
        val code: Int = ScancodeMapping.charToScancode(act[0]),
        val states: KeyStates = KeyStates.Virtual,
        val up: Boolean = false
    ) : KeyAction()

    data class SymAction(val sym: KeySym, val states: KeyStates = KeyStates.Virtual) : KeyAction()

    data class CommitAction(val text: String) : KeyAction()

    data class CapsAction(val lock: Boolean) : KeyAction()

    data object QuickPhraseAction : KeyAction()

    data object UnicodeAction : KeyAction()

    data object LangSwitchAction : KeyAction()

    data object ShowInputMethodPickerAction : KeyAction()

    data class LayoutSwitchAction(val act: String = "") : KeyAction()

    data class MoveSelectionAction(val start: Int = 0, val end: Int = 0) : KeyAction()

    data class DeleteSelectionAction(val totalCnt: Int = 0) : KeyAction()

    data class PickerSwitchAction(val key: PickerWindow.Key? = null) : KeyAction()

    data object SpaceLongPressAction : KeyAction()

    data object SpaceLongPressReleaseAction : KeyAction()

    data class LayerSwitchAction(
        val mode: LayerSwitchMode,
        val target: String
    ) : KeyAction()

    /**
     * Internal notification from BaseKeyboard after a MacroAction has performed
     * non-layer work. KeyboardWindow uses this to consume one-shot layers.
     */
    data object MacroConsumedAction : KeyAction()
}
