/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

/**
 * Tracks whether the user has explicitly chosen an alpha value in a color editor session.
 *
 * Some theme colors default to fully transparent (e.g. `keyboardColor` / `keyShadowColor`
 * of the transparent presets). Reusing that alpha while the user drags the hue or
 * saturation/value picker keeps producing invisible colors, so the edit appears to do
 * nothing. Until the user touches the alpha slider or types an ARGB value, HSV edits are
 * treated as opaque; afterwards the chosen alpha is always preserved, including a
 * deliberately transparent one.
 */
internal class ThemeColorEditorAlphaState {
    private var explicitlyEdited = false

    /** Records an alpha value picked with the alpha slider. */
    fun recordAlphaEdit() {
        explicitlyEdited = true
    }

    /** Records an alpha value typed into the ARGB field. */
    fun recordArgbEdit() {
        explicitlyEdited = true
    }

    /** Alpha to use when rebuilding a color from HSV components. */
    fun alphaForHsvEdit(color: Int): Int {
        val alpha = color ushr 24
        return if (!explicitlyEdited && alpha == 0) 0xFF else alpha
    }
}
