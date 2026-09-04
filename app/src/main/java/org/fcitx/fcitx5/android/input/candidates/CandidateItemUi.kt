/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.style.AbsoluteSizeSpan
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CustomTypefaceSpan
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class CandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    // Optional: external font for batch setting (avoids repeated FontProviders access)
    private val font: Typeface? = null,
    commentFont: Typeface? = null,
) : Ui {
    private var cachedCommentFont: Typeface? = commentFont

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        // Use configured font size with fallback to default (20f)
        val fontSize = org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
            "cand_font", 20f
        )
        textSize = fontSize
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    init {
        applyConfiguredTypeface()
    }

    private val normalBackground = pressHighlightDrawable(theme.keyPressHighlightColor)

    private val activeBackground = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
        cornerRadius = 8f
    }

    private var active = false
    private var candidate = CandidateWord.Empty

    fun applyConfiguredTypeface(fontOverride: Typeface? = font) {
        // Priority: explicit override > constructor font > cand_font > font > current/system default
        val resolved = fontOverride ?: FontProviders.resolveTypeface("cand_font", text.typeface)
        if (text.typeface !== resolved) {
            text.typeface = resolved
        }
    }

    /**
     * Cache the comment typeface for the next render. Falls back to the
     * configured comment_font, then cand_font, then the current view typeface.
     */
    fun applyConfiguredCommentTypeface(commentFontOverride: Typeface? = cachedCommentFont) {
        cachedCommentFont = commentFontOverride ?: FontProviders.resolveCommentTypeface(text.typeface)
    }

    fun setActive(active: Boolean) {
        if (this.active != active) {
            this.active = active
            renderCandidate()
        }
        text.setTextColor(if (this.active) theme.genericActiveForegroundColor else theme.candidateTextColor)
        text.background = null
        root.background = if (this.active) activeBackground else normalBackground
    }

    override val root = view(::CustomGestureView) {
        background = normalBackground
        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false
        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    fun updateCandidate(candidate: CandidateWord) {
        this.candidate = candidate
        renderCandidate()
    }

    private fun renderCandidate() {
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        val commentTypeface = cachedCommentFont ?: FontProviders.resolveCommentTypeface(text.typeface)
        val commentSizePx = FontProviders.commentFontSizePx(ctx)
        text.text = buildSpannedString {
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
    }
}
