/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.InputMethodEntry

/**
 * Layout state owned by one [TextKeyboard] instance.
 *
 * Layout resolution used to read the shared `TextKeyboard.ime` companion field, so any
 * instance receiving an input method update — notably the keyboard previews in settings —
 * replaced the layout context of the real keyboard. Every keyboard now resolves its layout
 * and its aux bar from its own state, while the companion field keeps mirroring the real
 * input method for window-level callers such as keyboard height resolution.
 */
internal class TextKeyboardLayoutState(
    var ime: InputMethodEntry? = null
) {
    /** Aux bar config resolved by the last layout pass of this keyboard. */
    var auxBarConfig: AuxBarConfig? = null

    /** Raw aux bar keys resolved by the last layout pass of this keyboard. */
    var auxBarKeys: List<Map<String, Any?>> = emptyList()

    fun getLayout(): List<List<KeyDef>> = TextKeyboard.getLayout(this)

    fun getAuxBarKeyDefs(): List<KeyDef> = TextKeyboard.getAuxBarKeyDefs(this)
}
