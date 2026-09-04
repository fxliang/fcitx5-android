/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.parcelable
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.setPaddingDp

class ThemeColorEditorActivity : AppCompatActivity() {

    @Parcelize
    data class EditorInput(
        val fieldName: String,
        @StringRes val titleRes: Int,
        val initialColor: Int,
        val allowClear: Boolean = false
    ) : Parcelable

    @Parcelize
    data class EditorResult(
        val fieldName: String,
        val color: Int?
    ) : Parcelable

    class Contract : ActivityResultContract<EditorInput, EditorResult?>() {
        override fun createIntent(context: Context, input: EditorInput): Intent =
            Intent(context, ThemeColorEditorActivity::class.java).apply {
                putExtra(EXTRA_INPUT, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): EditorResult? =
            intent?.parcelable(EXTRA_RESULT)
    }

    private lateinit var toolbar: Toolbar
    private lateinit var currentInput: EditorInput
    private lateinit var previewColorView: View
    private lateinit var argbField: EditText
    private lateinit var hueSlider: HueSliderView
    private lateinit var saturationValuePicker: SaturationValuePickerView
    private lateinit var alphaSlider: AlphaPreviewSlider

    private var currentColor: Int = Color.BLACK
    private var currentHue: Float = 0f
    private var currentSaturation: Float = 1f
    private var currentValue: Float = 1f
    private var internalTextUpdate = false
    private val alphaState = ThemeColorEditorAlphaState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        currentInput = intent.parcelable(EXTRA_INPUT) ?: run {
            finish()
            return
        }
        currentColor = currentInput.initialColor
        initUi()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (currentInput.allowClear) {
            menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, getString(R.string.clear)).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                icon?.setTint(styledColor(android.R.attr.textColorPrimary))
            }
        }
        menu.add(Menu.NONE, MENU_DONE, Menu.NONE, getString(R.string.save)).apply {
            setIcon(R.drawable.ic_baseline_check_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            icon?.setTint(styledColor(android.R.attr.textColorPrimary))
        }
        return true
    }

    private fun initUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            backgroundColor = styledColor(android.R.attr.colorBackground)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top
                bottomMargin = navigationBars.bottom
            }
            insets
        }

        toolbar = Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
            title = getString(currentInput.titleRes)
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingDp(16, 16, 16, 24)
        }

        val sliderGap = dp(12)
        val pickerHeight = dp(180)
        val svSize = dp(180)
        val sideSliderWidth = dp(28)
        val previewWidth = svSize + sliderGap + sideSliderWidth + sliderGap + sideSliderWidth
        val previewHeight = dp(96)

        val previewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(previewWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val checkerboard = View(this).apply {
            background = createCheckerboardDrawable(dp(8))
        }
        previewColorView = View(this).apply {
            backgroundColor = currentColor
        }
        previewContainer.addView(
            checkerboard,
            FrameLayout.LayoutParams(previewWidth, previewHeight).apply {
                gravity = Gravity.CENTER
            }
        )
        previewContainer.addView(
            previewColorView,
            FrameLayout.LayoutParams(previewWidth, previewHeight).apply {
                gravity = Gravity.CENTER
            }
        )
        content.addView(previewContainer)

        val pickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        saturationValuePicker = SaturationValuePickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(svSize, pickerHeight)
            val hsv = FloatArray(3)
            Color.colorToHSV(currentColor, hsv)
            currentHue = hsv[0]
            currentSaturation = hsv[1]
            currentValue = hsv[2]
            setHue(currentHue)
            setSaturation(currentSaturation)
            setValue(currentValue)
            onColorChanged = { saturation, value ->
                currentSaturation = saturation
                currentValue = value
                updateColorFromHsv()
                syncWidgets(syncInputText = true)
            }
        }
        pickerRow.addView(saturationValuePicker)

        hueSlider = HueSliderView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sideSliderWidth, pickerHeight).apply {
                marginStart = sliderGap
            }
            setHue(currentHue)
            onHueChanged = { hue ->
                currentHue = hue
                saturationValuePicker.setHue(currentHue)
                updateColorFromHsv()
                syncWidgets(syncInputText = true)
            }
        }
        pickerRow.addView(hueSlider)

        alphaSlider = AlphaPreviewSlider(this).apply {
            layoutParams = LinearLayout.LayoutParams(sideSliderWidth, pickerHeight).apply {
                marginStart = sliderGap
            }
            orientation = AlphaPreviewSlider.Orientation.Vertical
            setAlpha(Color.alpha(currentColor))
            setColor(currentColor)
            onAlphaChanged = { alpha ->
                alphaState.recordAlphaEdit()
                currentColor = Color.argb(
                    alpha,
                    Color.red(currentColor),
                    Color.green(currentColor),
                    Color.blue(currentColor)
                )
                syncWidgets(syncInputText = true)
            }
        }
        pickerRow.addView(alphaSlider)
        content.addView(pickerRow)

        val argbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        argbRow.addView(
            TextView(this).apply {
                text = getString(R.string.theme_color_editor_argb_label)
                setTextColor(styledColor(android.R.attr.textColorPrimary))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )
        argbField = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(styledColor(android.R.attr.textColorPrimary))
            setHintTextColor(styledColor(android.R.attr.textColorHint))
            backgroundTintList = ColorStateList.valueOf(styledColor(android.R.attr.colorControlActivated))
            setText(formatArgbHex(currentColor))
            setSelection(text.length)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (internalTextUpdate) return
                    parseArgbHex(s.toString())?.let { parsed ->
                        alphaState.recordArgbEdit()
                        currentColor = parsed
                        val hsv = FloatArray(3)
                        Color.colorToHSV(currentColor, hsv)
                        currentHue = hsv[0]
                        currentSaturation = hsv[1]
                        currentValue = hsv[2]
                        hueSlider.setHue(currentHue)
                        saturationValuePicker.setHue(currentHue)
                        saturationValuePicker.setSaturation(currentSaturation)
                        saturationValuePicker.setValue(currentValue)
                        syncWidgets(syncInputText = false)
                    }
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            })
        }
        argbRow.addView(argbField)
        content.addView(argbRow)

        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)

        syncWidgets(syncInputText = true)
    }

    private fun updateColorFromHsv() {
        currentColor = Color.HSVToColor(
            alphaState.alphaForHsvEdit(currentColor),
            floatArrayOf(currentHue, currentSaturation, currentValue)
        )
    }

    private fun syncWidgets(syncInputText: Boolean) {
        previewColorView.backgroundColor = currentColor
        alphaSlider.setColor(currentColor)
        alphaSlider.setAlpha(Color.alpha(currentColor))
        if (syncInputText) {
            val text = formatArgbHex(currentColor)
            if (argbField.text?.toString() != text) {
                internalTextUpdate = true
                argbField.setText(text)
                argbField.setSelection(text.length)
                internalTextUpdate = false
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_CLEAR -> {
            finishWithResult(null)
            true
        }
        MENU_DONE -> {
            finishWithResult()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun finishWithResult(color: Int? = currentColor) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_RESULT, EditorResult(currentInput.fieldName, color))
            }
        )
        finish()
    }

    private fun createCheckerboardDrawable(tileSize: Int): BitmapDrawable {
        val size = tileSize * 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = 0xFFCCCCCC.toInt()
        canvas.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), paint)
        canvas.drawRect(tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), size.toFloat(), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(tileSize.toFloat(), 0f, size.toFloat(), tileSize.toFloat(), paint)
        canvas.drawRect(0f, tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), paint)
        return BitmapDrawable(resources, bitmap).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }

    private fun formatArgbHex(color: Int): String = String.format(
        "#%02X%02X%02X%02X",
        Color.alpha(color),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun parseArgbHex(input: String): Int? {
        val normalized = input.trim().removePrefix("#")
        val hex = when (normalized.length) {
            6 -> "FF$normalized"
            8 -> normalized
            else -> return null
        }
        return try {
            java.lang.Long.parseLong(hex, 16).toInt()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EXTRA_INPUT = "theme_color_editor_input"
        const val EXTRA_RESULT = "theme_color_editor_result"
        private const val MENU_CLEAR = 1
        private const val MENU_DONE = 2
    }
}
