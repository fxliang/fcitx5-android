/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.view.KeyCharacterMap
import android.view.KeyEvent

@JvmInline
value class KeySym(val sym: Int) {

    val keyCode get() = FcitxKeyMapping.symToKeyCode(sym) ?: KeyEvent.KEYCODE_UNKNOWN

    override fun toString() = "0x" + sym.toString(16).padStart(4, '0')

    companion object {
        private fun numpadKeySym(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_0 -> FcitxKeyMapping.FcitxKey_KP_0
            KeyEvent.KEYCODE_NUMPAD_1 -> FcitxKeyMapping.FcitxKey_KP_1
            KeyEvent.KEYCODE_NUMPAD_2 -> FcitxKeyMapping.FcitxKey_KP_2
            KeyEvent.KEYCODE_NUMPAD_3 -> FcitxKeyMapping.FcitxKey_KP_3
            KeyEvent.KEYCODE_NUMPAD_4 -> FcitxKeyMapping.FcitxKey_KP_4
            KeyEvent.KEYCODE_NUMPAD_5 -> FcitxKeyMapping.FcitxKey_KP_5
            KeyEvent.KEYCODE_NUMPAD_6 -> FcitxKeyMapping.FcitxKey_KP_6
            KeyEvent.KEYCODE_NUMPAD_7 -> FcitxKeyMapping.FcitxKey_KP_7
            KeyEvent.KEYCODE_NUMPAD_8 -> FcitxKeyMapping.FcitxKey_KP_8
            KeyEvent.KEYCODE_NUMPAD_9 -> FcitxKeyMapping.FcitxKey_KP_9
            KeyEvent.KEYCODE_NUMPAD_ENTER -> FcitxKeyMapping.FcitxKey_KP_Enter
            KeyEvent.KEYCODE_NUMPAD_ADD -> FcitxKeyMapping.FcitxKey_KP_Add
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> FcitxKeyMapping.FcitxKey_KP_Subtract
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> FcitxKeyMapping.FcitxKey_KP_Multiply
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> FcitxKeyMapping.FcitxKey_KP_Divide
            KeyEvent.KEYCODE_NUMPAD_DOT -> FcitxKeyMapping.FcitxKey_KP_Decimal
            KeyEvent.KEYCODE_NUMPAD_COMMA -> FcitxKeyMapping.FcitxKey_KP_Separator
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> FcitxKeyMapping.FcitxKey_KP_Equal
            else -> null
        }

        fun fromKeyEvent(event: KeyEvent): KeySym? {
            // Preserve keypad semantics even when Android also exposes a Unicode character
            // such as '0' or '.'. Otherwise physical keypad keys become ordinary Rime keys.
            numpadKeySym(event.keyCode)?.let { return KeySym(it) }

            val charCode = event.unicodeChar
            // try charCode first, allow upper and lower case characters generating different KeySym
            if (charCode != 0 &&
                // skip \t, because it's charCode is different from KeySym
                charCode != '\t'.code &&
                // skip \n, because fcitx wants \r for return
                charCode != '\n'.code &&
                // skip Android's private-use character
                charCode != KeyCharacterMap.HEX_INPUT.code &&
                charCode != KeyCharacterMap.PICKER_DIALOG_INPUT.code
            ) {
                return KeySym(charCode)
            }
            // Special handling for function keys (F1-F12) to ensure correct mapping
            // On some devices, KeyCharacterMap may not map function keys correctly
            val functionKeySym = when (event.keyCode) {
                KeyEvent.KEYCODE_F1 -> 0xffbe
                KeyEvent.KEYCODE_F2 -> 0xffbf
                KeyEvent.KEYCODE_F3 -> 0xffc0
                KeyEvent.KEYCODE_F4 -> 0xffc1
                KeyEvent.KEYCODE_F5 -> 0xffc2
                KeyEvent.KEYCODE_F6 -> 0xffc3
                KeyEvent.KEYCODE_F7 -> 0xffc4
                KeyEvent.KEYCODE_F8 -> 0xffc5
                KeyEvent.KEYCODE_F9 -> 0xffc6
                KeyEvent.KEYCODE_F10 -> 0xffc7
                KeyEvent.KEYCODE_F11 -> 0xffc8
                KeyEvent.KEYCODE_F12 -> 0xffc9
                else -> null
            }
            if (functionKeySym != null) {
                return KeySym(functionKeySym)
            }
            return KeySym(FcitxKeyMapping.keyCodeToSym(event.keyCode) ?: return null)
        }

    }
}
