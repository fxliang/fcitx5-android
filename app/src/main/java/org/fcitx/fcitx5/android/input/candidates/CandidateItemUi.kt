/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import org.json.JSONObject
import java.io.File
import android.graphics.Typeface
import org.fcitx.fcitx5.android.utils.appContext

class CandidateItemUi(override val ctx: Context, theme: Theme) : Ui {

    val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = 20f // sp
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    //*
    companion object {
        private var cachedTypeface: Typeface? = null
        private var cachedFontFilePath: String? = null
        fun getFontTypeFace(key: String): Typeface? {
            if (cachedTypeface != null) return cachedTypeface
            val fontsDir = File(appContext.getExternalFilesDir(null), "fonts")
            val jsonFile = File(fontsDir, "fontset.json")
            if (!jsonFile.exists()) return null
            return try {
                val json = JSONObject(jsonFile.readText())
                val fontName = if (json.has(key)) json.getString(key) else return null
                val fontFile = File(fontsDir, fontName)
                if (!fontFile.exists()) return null
                if (cachedFontFilePath != fontFile.absolutePath) {
                    cachedTypeface = Typeface.createFromFile(fontFile)
                    cachedFontFilePath = fontFile.absolutePath
                }
                cachedTypeface
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    //*/

    init {
      // text.setFontTypeFace("cand_font")
      getFontTypeFace("cand_font")?.let { typeface ->
        text.typeface = typeface
      }
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false

        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }
}
