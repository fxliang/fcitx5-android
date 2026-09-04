/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.style.AbsoluteSizeSpan
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CustomTypefaceSpan
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    setupTextView: TextView.() -> Unit,
    private val highlightRadius: Float
) : Ui {

    override val root = textView {
        setupTextView(this)
    }

    private val highlightDrawable = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
        cornerRadius = highlightRadius
    }

    fun update(candidate: CandidateWord, active: Boolean) {
        val labelFg = if (active) theme.genericActiveForegroundColor else theme.candidateLabelColor
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        val commentTypeface = FontProviders.resolveCommentTypeface(root.typeface)
        val commentSizePx = FontProviders.commentFontSizePx(root.context)
        root.text = buildSpannedString {
            color(labelFg) {
                append(candidate.label)
            }
            color(fg) {
                append(candidate.text)
            }
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                inSpans(CustomTypefaceSpan(commentTypeface), AbsoluteSizeSpan(commentSizePx, false)) {
                    color(altFg) {
                        append(candidate.comment)
                    }
                }
            }
        }
        root.background = if (active) highlightDrawable else null
    }
}
