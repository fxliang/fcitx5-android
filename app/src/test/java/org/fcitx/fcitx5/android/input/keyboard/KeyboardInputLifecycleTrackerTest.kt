/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardInputLifecycleTrackerTest {
    private companion object {
        const val TEXT_INPUT_CLASS = 1
        const val NUMBER_INPUT_CLASS = 2
    }

    private fun ime(
        uniqueName: String = "rime",
        displayName: String = "Rime",
        subMode: InputMethodSubMode = InputMethodSubMode(
            name = "wanxiang",
            label = "Wanxiang",
            icon = "fcitx-rime:wanxiang"
        )
    ) = InputMethodEntry(
        uniqueName = uniqueName,
        name = displayName,
        icon = "fcitx-rime",
        nativeName = "",
        label = "R",
        languageCode = "zh",
        addon = "rime",
        isConfigurable = true,
        subMode = subMode
    )

    @Test
    fun firstUpdateOnlyEstablishesBaseline() {
        val tracker = KeyboardInputLifecycleTracker()

        assertFalse(tracker.onInputMethodUpdate(ime()))
    }

    @Test
    fun duplicateAndDisplayMetadataRefreshAreNotSelectionChanges() {
        val tracker = KeyboardInputLifecycleTracker().apply { resetInputMethod(ime()) }

        assertFalse(tracker.onInputMethodUpdate(ime()))
        assertFalse(tracker.onInputMethodUpdate(ime(displayName = "Rime refreshed")))
    }

    @Test
    fun inputMethodChangeIsReported() {
        val tracker = KeyboardInputLifecycleTracker().apply { resetInputMethod(ime()) }

        assertTrue(tracker.onInputMethodUpdate(ime(uniqueName = "keyboard-us")))
    }

    @Test
    fun subModeChangeIsReported() {
        val tracker = KeyboardInputLifecycleTracker().apply { resetInputMethod(ime()) }
        val englishMode = InputMethodSubMode(
            name = "ascii",
            label = "English",
            icon = "fcitx-rime:ascii"
        )

        assertTrue(tracker.onInputMethodUpdate(ime(subMode = englishMode)))
    }

    @Test
    fun firstEditorCountsAsInputClassChange() {
        val tracker = KeyboardInputLifecycleTracker()

        assertTrue(tracker.isInputClassChanged(TEXT_INPUT_CLASS))
    }

    @Test
    fun inputClassComparisonIsIdempotentUntilRecorded() {
        val tracker = KeyboardInputLifecycleTracker()
        tracker.recordInputClass(TEXT_INPUT_CLASS)

        // Reading the comparison must not mutate the remembered class, so repeated reads
        // within one onStartInput always agree.
        assertFalse(tracker.isInputClassChanged(TEXT_INPUT_CLASS))
        assertFalse(tracker.isInputClassChanged(TEXT_INPUT_CLASS))
        assertTrue(tracker.isInputClassChanged(NUMBER_INPUT_CLASS))
        assertTrue(tracker.isInputClassChanged(NUMBER_INPUT_CLASS))
    }

    @Test
    fun recordedInputClassBecomesTheNewBaseline() {
        val tracker = KeyboardInputLifecycleTracker()
        tracker.recordInputClass(TEXT_INPUT_CLASS)
        tracker.recordInputClass(NUMBER_INPUT_CLASS)

        assertFalse(tracker.isInputClassChanged(NUMBER_INPUT_CLASS))
        assertTrue(tracker.isInputClassChanged(TEXT_INPUT_CLASS))
    }
}
