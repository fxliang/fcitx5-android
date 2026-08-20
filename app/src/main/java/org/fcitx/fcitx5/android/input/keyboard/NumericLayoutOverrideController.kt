/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Owns the session numeric override and the temporarily forced layer.
 * The keyboard view remains responsible for applying state changes to attached views.
 */
internal class NumericLayoutOverrideController {
    var sessionKey: String? = null
        private set
    var forcedKey: String? = null
        private set
    var manual: Boolean = false
        private set

    fun beginSession(key: String?) {
        sessionKey = key
        forcedKey = key
        manual = false
    }

    fun force(key: String?) {
        forcedKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: sessionKey
    }

    fun activateManual(key: String): Boolean {
        val wasActive = sessionKey != null
        if (!wasActive) manual = true
        forcedKey = key
        return wasActive
    }

    fun releaseManual(): Boolean {
        if (!manual) return false
        manual = false
        forcedKey = sessionKey
        return true
    }

    fun dismiss() {
        sessionKey = null
        forcedKey = null
        manual = false
    }

    fun revalidate(resolved: String?): Boolean {
        val current = sessionKey ?: return false
        if (resolved == current) return false
        sessionKey = resolved
        if (forcedKey == current) forcedKey = resolved
        return resolved == null
    }
}
