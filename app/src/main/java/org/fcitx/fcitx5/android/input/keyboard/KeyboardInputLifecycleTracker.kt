/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode

/**
 * Whether an in-place input restart must keep the keyboard the user is currently looking at.
 *
 * Some editors call `InputConnection#restartInput` after every committed character (Alipay's
 * bank-card field is the known case) even though the field keeps the same input class.
 * Re-applying [android.view.inputmethod.EditorInfo] on such a restart would undo whatever the
 * user selected by hand, so a selection is kept whenever it *deviates* from what the input class
 * alone would produce. The deviation is deliberately symmetric:
 *
 * - a numeric layout manually opened on a text field must not fall back to the text keyboard;
 * - a text keyboard manually restored inside a numeric field (an "ABC"-style key) must not be
 *   pulled back onto the number pad.
 *
 * A restart carrying a *different* input class is still re-applied, because apps also use
 * `restartInput` to publish a genuinely changed EditorInfo (e.g. toggling password visibility).
 * A fresh (non-restarting) editor always follows its own input class, even when the previous
 * editor left a hand-picked layout on screen.
 *
 * Pure by design: every input is an explicit parameter, so repeated calls within one
 * `onStartInput` always agree and the result cannot be altered by the order in which callers
 * happen to read tracker state.
 *
 * @param restarting the framework's `restarting` flag for the current `onStartInput`.
 * @param inputClassChanged whether the editor's input class differs from the previous editor,
 * as reported by [KeyboardInputLifecycleTracker.isInputClassChanged].
 * @param numericLayoutShowing whether a numeric layout is on screen right now.
 * @param numericLayoutExpected whether the editor's input class implies a numeric layout.
 */
internal fun shouldKeepCurrentLayoutOnStartInput(
    restarting: Boolean,
    inputClassChanged: Boolean,
    numericLayoutShowing: Boolean,
    numericLayoutExpected: Boolean
): Boolean = restarting && !inputClassChanged && numericLayoutShowing != numericLayoutExpected

/**
 * Tracks the small amount of editor/IME identity needed to preserve keyboard UI state.
 */
internal class KeyboardInputLifecycleTracker {
    private data class Identity(
        val uniqueName: String,
        val subMode: InputMethodSubMode
    )

    private var lastIdentity: Identity? = null
    private var lastInputClass: Int? = null

    fun resetInputMethod(ime: InputMethodEntry) {
        lastIdentity = ime.identity()
    }

    /**
     * Whether [inputClass] differs from the one passed to the previous [recordInputClass].
     * The very first editor of a service lifetime counts as changed, so its input class is
     * always applied instead of inheriting whatever layout a freshly created keyboard shows.
     */
    fun isInputClassChanged(inputClass: Int): Boolean = lastInputClass != inputClass

    /** Remember the editor's input class for the next [isInputClassChanged] comparison. */
    fun recordInputClass(inputClass: Int) {
        lastInputClass = inputClass
    }

    /**
     * Updates the remembered identity and returns whether an established selection changed.
     * The first event establishes a baseline and is not itself treated as a user switch.
     */
    fun onInputMethodUpdate(ime: InputMethodEntry): Boolean {
        val nextIdentity = ime.identity()
        val changed = lastIdentity?.let { it != nextIdentity } ?: false
        lastIdentity = nextIdentity
        return changed
    }

    private fun InputMethodEntry.identity() = Identity(uniqueName, subMode)
}
