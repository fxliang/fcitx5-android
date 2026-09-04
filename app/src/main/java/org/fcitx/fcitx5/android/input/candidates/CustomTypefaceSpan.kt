/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/**
 * Applies an arbitrary [Typeface] (e.g. one loaded from a custom font file) to a
 * text span. Unlike [android.text.style.TypefaceSpan], it works on all API levels
 * and does not require a font family name.
 */
class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = typeface
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = typeface
    }
}
