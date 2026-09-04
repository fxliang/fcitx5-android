/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.behavior.preview.PreviewKeyBlurMaskView
import org.fcitx.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry
import org.fcitx.fcitx5.android.utils.DeviceInfoCollector
import org.fcitx.fcitx5.android.utils.DeviceType
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor

/**
 * Split keyboard calibration activity.
 *
 * Features:
 * 1. Real TextKeyboard preview for split effect
 * 2. Real-time threshold and gap adjustment
 * 3. Automatic recommended values
 * 4. Support for foldable devices with multiple DPI
 */
class SplitKeyboardCalibrationActivity : AppCompatActivity() {
    private val previewIme = PreviewInputMethodEntry.create()

    private lateinit var toolbar: Toolbar
    private lateinit var previewKeyboardContainer: FrameLayout
    private var previewKeyboard: TextKeyboard? = null
    private val previewBlurMask by lazy { PreviewKeyBlurMaskView(this) }

    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var gapSeekBar: SeekBar
    private lateinit var thresholdValueText: TextView
    private lateinit var gapValueText: TextView
    private lateinit var deviceInfoText: TextView

    private val splitKeyboardManager by lazy { SplitKeyboardStateManager.getInstance() }
    private val prefs by lazy { AppPrefs.getInstance() }
    private val keyboardPrefs by lazy { prefs.keyboard }

    private var currentThreshold: Int = 470
    private var currentGap: Int = 20
    private var currentKeyboardWidth: Int = 0

    private var deviceInfo: DeviceInfoCollector.DeviceInfo? = null
    private var shouldEnforceLowercaseAfterRotation: Boolean = false
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        // Refresh preview when keyboard size settings change
        if (key == keyboardPrefs.keyboardHeightPercent.key ||
            key == keyboardPrefs.keyboardHeightPercentLandscape.key ||
            key == keyboardPrefs.keyboardSidePadding.key ||
            key == keyboardPrefs.keyboardSidePaddingLandscape.key ||
            key == keyboardPrefs.splitKeyboardThreshold.key ||
            key == keyboardPrefs.splitKeyboardGapPercent.key) {
            updatePreview()
            updateDeviceInfo()
        }
    }

    companion object {
        private const val DEFAULT_THRESHOLD = 470
        private const val DEFAULT_GAP = 20
        private const val THRESHOLD_MIN = 300
        private const val THRESHOLD_MAX = 700
        private const val GAP_MIN = 5
        private const val GAP_MAX = 60
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(createContent())
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.split_keyboard_calibration_title)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        deviceInfo = DeviceInfoCollector.collect(this)
        // Read values via SplitKeyboardStateManager (which reads from SharedPreferences directly)
        currentThreshold = splitKeyboardManager.getSplitKeyboardThreshold()
        currentGap = (splitKeyboardManager.getSplitGapPercent() * 100).toInt()
        lastOrientation = resources.configuration.orientation
        refreshCurrentKeyboardWidth()

        // Sync SeekBar and TextView with current values
        thresholdSeekBar.progress = currentThreshold - THRESHOLD_MIN
        gapSeekBar.progress = currentGap - GAP_MIN
        thresholdValueText.text = "$currentThreshold dp"
        gapValueText.text = "$currentGap %"

        updatePreview()
        updateDeviceInfo()
        
        // Listen to keyboard height/size changes to update preview in real-time
        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        previewKeyboard?.onDetach()
        previewKeyboard = null
        previewBlurMask.bindKeyboard(null)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = newConfig.orientation
        if (orientation == Configuration.ORIENTATION_PORTRAIT || orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (orientation != lastOrientation) {
                shouldEnforceLowercaseAfterRotation = true
                lastOrientation = orientation
            }
            // Refresh device info cache for foldable inner/outer screen switch
            splitKeyboardManager.refreshDeviceInfo()
            refreshCurrentKeyboardWidth()
            updatePreview()
            updateDeviceInfo()
        }
    }

    private fun createContent(): View {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            backgroundColor = styledColor(android.R.attr.colorBackground)
        }

        toolbar = Toolbar(this).apply {
            setBackgroundColor(styledColor(android.R.attr.colorPrimary))
        }
        mainLayout.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(contentLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        contentLayout.addView(deviceInfoPanel(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(16), dp(8), dp(16), dp(8))
        })

        contentLayout.addView(previewSection(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        contentLayout.addView(controlPanel(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(16), dp(8), dp(16), dp(16))
        })

        mainLayout.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0
        ).apply {
            weight = 1f
        })

        return mainLayout
    }

    private fun deviceInfoPanel(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            backgroundColor = styledColor(android.R.attr.colorButtonNormal)
        }

        layout.addView(TextView(this).apply {
            text = getString(R.string.split_keyboard_device_info)
            textSize = 15f
            setTextColor(styledColor(android.R.attr.textColorPrimary))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), dp(10), dp(12), dp(4))
        })

        deviceInfoText = TextView(this).apply {
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }
        layout.addView(deviceInfoText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), 0, dp(12), dp(10))
        })

        return layout
    }

    private fun previewSection(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        previewKeyboardContainer = FrameLayout(this).apply {
            backgroundColor = styledColor(android.R.attr.colorButtonNormal)
            minimumHeight = dp(200)
        }
        layout.addView(previewKeyboardContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(16), dp(8), dp(16), dp(8))
        })

        return layout
    }

    private fun controlPanel(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val thresholdRow = createSeekBarRow(
            title = getString(R.string.split_keyboard_threshold_label),
            minValue = THRESHOLD_MIN,
            maxValue = THRESHOLD_MAX,
            initialValue = currentThreshold,
            onValueChanged = { value ->
                currentThreshold = value
                thresholdValueText.text = "$value dp"
                // Update internal prefs to trigger preview keyboard reload
                splitKeyboardManager.setSplitKeyboardThreshold(value)
                previewKeyboard?.refreshStyle()
                previewBlurMask.refreshMask(hierarchyChanged = true)
                updateDeviceInfo()
            },
            valueTextProvider = { "$it dp" }
        )
        layout.addView(thresholdRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        thresholdSeekBar = thresholdRow.findViewById<SeekBar>(R.id.seekbar)
        thresholdValueText = thresholdRow.findViewById<TextView>(R.id.value_text)

        val gapRow = createSeekBarRow(
            title = getString(R.string.split_keyboard_gap_label),
            minValue = GAP_MIN,
            maxValue = GAP_MAX,
            initialValue = currentGap,
            onValueChanged = { value ->
                currentGap = value
                gapValueText.text = "$value %"
                // Update internal prefs to trigger preview keyboard reload
                splitKeyboardManager.setSplitGapPercent(value)
                previewKeyboard?.refreshStyle()
                previewBlurMask.refreshMask(hierarchyChanged = true)
                updateDeviceInfo()
            },
            valueTextProvider = { "$it %" }
        )
        layout.addView(gapRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        gapSeekBar = gapRow.findViewById<SeekBar>(R.id.seekbar)
        gapValueText = gapRow.findViewById<TextView>(R.id.value_text)

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        buttonLayout.addView(Button(this).apply {
            text = getString(R.string.split_keyboard_auto_calibrate)
            isAllCaps = false
            setOnClickListener { autoCalibrate() }
        }, LinearLayout.LayoutParams(0, dp(44)).apply {
            weight = 1f
            setMargins(dp(16), dp(8), dp(8), dp(8))
        })

        buttonLayout.addView(Button(this).apply {
            text = getString(R.string.split_keyboard_reset)
            isAllCaps = false
            setOnClickListener { resetToDefaults() }
        }, LinearLayout.LayoutParams(0, dp(44)).apply {
            weight = 1f
            setMargins(dp(8), dp(8), dp(16), dp(8))
        })

        layout.addView(buttonLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        return layout
    }

    private fun createSeekBarRow(
        title: String,
        minValue: Int,
        maxValue: Int,
        initialValue: Int,
        onValueChanged: (Int) -> Unit,
        valueTextProvider: (Int) -> String = { "$it" }
    ): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        headerLayout.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(styledColor(android.R.attr.textColorPrimary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        })

        val valueTextView = TextView(this).apply {
            id = R.id.value_text
            text = valueTextProvider(initialValue)
            textSize = 14f
            setTextColor(styledColor(android.R.attr.colorAccent))
            minWidth = dp(60)
            gravity = Gravity.END
        }
        headerLayout.addView(valueTextView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        layout.addView(headerLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        layout.addView(SeekBar(this).apply {
            id = R.id.seekbar
            max = maxValue - minValue
            progress = initialValue - minValue

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val value = progress + minValue
                        valueTextView.text = valueTextProvider(value)
                        onValueChanged(value)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(4), 0, dp(4))
        })

        return layout
    }

    private fun updatePreview() {
        previewKeyboardContainer.removeAllViews()
        previewKeyboard?.onDetach()
        previewKeyboard = null
        previewBlurMask.bindKeyboard(null)
        refreshCurrentKeyboardWidth()
        TextKeyboard.cachedLayoutJsonMap = null

        try {
            val theme = ThemeManager.activeTheme
            val keyBorder = ThemeManager.prefs.keyBorder.getValue()
            // Match InputView behavior, including custom background images.
            previewKeyboardContainer.background = theme.backgroundDrawable(keyBorder)
            previewKeyboardContainer.addView(
                previewBlurMask,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels

            val heightPercent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE ->
                    keyboardPrefs.keyboardHeightPercentLandscape.getValue()
                else -> keyboardPrefs.keyboardHeightPercent.getValue()
            }
            val keyboardHeight = screenHeight * heightPercent / 100

            val sidePaddingDp = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE ->
                    keyboardPrefs.keyboardSidePaddingLandscape.getValue()
                else -> keyboardPrefs.keyboardSidePadding.getValue()
            }
            val sidePaddingPx = (sidePaddingDp * displayMetrics.density).toInt()

            previewKeyboard = TextKeyboard(this, theme, previewIme, isPreview = true).apply {
                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    keyboardHeight
                )
                layoutParams.setMargins(sidePaddingPx, 0, sidePaddingPx, 0)

                previewKeyboardContainer.addView(this, layoutParams)
                onAttach()
                setTextScale(1.0f)
                setHorizontalGapScale(currentGap / 100f)
                onInputMethodUpdate(previewIme)
                updateSplitState()
                previewBlurMask.applyTheme(theme, keyBorder)
                previewBlurMask.bindKeyboard(this)
                post { previewBlurMask.refreshMask(hierarchyChanged = true) }

                if (shouldEnforceLowercaseAfterRotation) {
                    enforcePreviewLowercase()
                    shouldEnforceLowercaseAfterRotation = false
                }
            }

            // Update container height to match keyboard height, avoiding wasted vertical space
            previewKeyboardContainer.updateLayoutParams {
                height = keyboardHeight
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.split_keyboard_preview_load_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSplitState() {
        previewKeyboard?.let { keyboard ->
            val displayMetrics = resources.displayMetrics
            val screenWidthPx = displayMetrics.widthPixels
            val shouldSplitByCalibration = currentKeyboardWidth >= currentThreshold
            val targetWidthDp = if (shouldSplitByCalibration) {
                maxOf(currentKeyboardWidth, currentThreshold + 10)
            } else {
                minOf(currentKeyboardWidth, currentThreshold - 10)
            }.coerceAtLeast(1)

            val simulatedKeyboardWidthPx = (targetWidthDp * displayMetrics.density).toInt()
            val simulatedSidePaddingPx = (screenWidthPx - simulatedKeyboardWidthPx) / 2

            // Update container padding instead of keyboard margins to avoid triggering onSizeChanged
            // This simulates split effect without affecting keyboard's internal state
            previewKeyboardContainer.setPadding(simulatedSidePaddingPx, 0, simulatedSidePaddingPx, 0)
            previewBlurMask.refreshMask()
        }
    }

    private fun enforcePreviewLowercase() {
        previewKeyboard?.let { keyboard ->
            keyboard.post {
                if (previewKeyboard === keyboard) {
                    keyboard.onAttach()
                    keyboard.onInputMethodUpdate(previewIme)
                }
            }
        }
    }

    private fun refreshCurrentKeyboardWidth() {
        val keyboardPrefs = prefs.keyboard
        currentKeyboardWidth = DeviceInfoCollector.calculateKeyboardWidthDp(
            this,
            keyboardPrefs.keyboardSidePadding.getValue(),
            keyboardPrefs.keyboardSidePaddingLandscape.getValue()
        )
    }

    private fun updateDeviceInfo() {
        val info = deviceInfo ?: return
        refreshCurrentKeyboardWidth()
        val orientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> getString(R.string.split_keyboard_orientation_landscape)
            else -> getString(R.string.split_keyboard_orientation_portrait)
        }
        val splitEnabled = keyboardPrefs.splitKeyboardEnabled.getValue()
        val willSplit = splitEnabled && currentKeyboardWidth >= currentThreshold
        val (recommendedThreshold, reason) = calculateRecommendedThreshold(info)

        val infoText = buildString {
            appendLine("${getString(R.string.split_keyboard_info_type)}: ${info.deviceType.getLocalizedName()}  ${getString(R.string.split_keyboard_info_orientation)}: $orientation  ${getString(R.string.split_keyboard_info_min_width)}: ${info.smallestWidthDp}dp")
            appendLine("${getString(R.string.split_keyboard_info_keyboard_width)}: ${currentKeyboardWidth}dp  ${getString(R.string.split_keyboard_info_split)}: ${if (willSplit) getString(R.string.split_keyboard_split_yes) else getString(R.string.split_keyboard_split_no)}")
            appendLine("${getString(R.string.split_keyboard_info_threshold)}: ${currentThreshold}dp  ${getString(R.string.split_keyboard_info_gap)}: ${currentGap}%")
            append("${getString(R.string.split_keyboard_info_recommended)}: ${recommendedThreshold}dp ($reason)")
            if (info.hasMultipleScreens) {
                append("  ${getString(R.string.split_keyboard_info_multi_screen)}: ${info.screenConfigs.size}")
            }
        }

        deviceInfoText.text = infoText
    }

    private fun calculateRecommendedThreshold(info: DeviceInfoCollector.DeviceInfo): Pair<Int, String> {
        return when (info.deviceType) {
            DeviceType.TABLET -> 420 to getString(R.string.split_keyboard_recommend_reason_tablet)
            DeviceType.FOLDABLE -> {
                if (info.hasMultipleScreens) {
                    val minSmallestWidth = info.screenConfigs.minOfOrNull { it.smallestWidthDp } ?: 440
                    440 to getString(R.string.split_keyboard_recommend_reason_foldable_multi, minSmallestWidth)
                } else {
                    440 to getString(R.string.split_keyboard_recommend_reason_foldable)
                }
            }
            DeviceType.LARGE_PHONE -> 520 to getString(R.string.split_keyboard_recommend_reason_large_phone)
            DeviceType.SMALL_PHONE -> 500 to getString(R.string.split_keyboard_recommend_reason_small_phone)
            DeviceType.PHONE -> 470 to getString(R.string.split_keyboard_recommend_reason_phone)
        }
    }

    private fun DeviceType.getLocalizedName(): String {
        return when (this) {
            DeviceType.TABLET -> getString(R.string.split_keyboard_device_type_tablet)
            DeviceType.FOLDABLE -> getString(R.string.split_keyboard_device_type_foldable)
            DeviceType.LARGE_PHONE -> getString(R.string.split_keyboard_device_type_large_phone)
            DeviceType.SMALL_PHONE -> getString(R.string.split_keyboard_device_type_small_phone)
            DeviceType.PHONE -> getString(R.string.split_keyboard_device_type_phone)
        }
    }

    private fun autoCalibrate() {
        val info = deviceInfo ?: return

        val (recommended, reason) = calculateRecommendedThreshold(info)

        currentThreshold = recommended
        thresholdSeekBar.progress = recommended - THRESHOLD_MIN

        val recommendedGap = when (info.deviceType) {
            DeviceType.TABLET -> 25
            DeviceType.FOLDABLE -> 20
            else -> 18
        }
        currentGap = recommendedGap
        gapSeekBar.progress = recommendedGap - GAP_MIN

        thresholdValueText.text = "$recommended dp"
        gapValueText.text = "$recommendedGap %"
        
        // Save settings to trigger external keyboard refresh
        splitKeyboardManager.setSplitKeyboardThreshold(recommended)
        splitKeyboardManager.setSplitGapPercent(recommendedGap)
        
        updatePreview()
        updateDeviceInfo()

        Toast.makeText(this, getString(R.string.split_keyboard_auto_calibrate_toast, recommended, recommendedGap, reason), Toast.LENGTH_SHORT).show()
    }

    private fun resetToDefaults() {
        currentThreshold = DEFAULT_THRESHOLD
        currentGap = DEFAULT_GAP
        refreshCurrentKeyboardWidth()

        thresholdSeekBar.progress = DEFAULT_THRESHOLD - THRESHOLD_MIN
        gapSeekBar.progress = DEFAULT_GAP - GAP_MIN

        thresholdValueText.text = "$DEFAULT_THRESHOLD dp"
        gapValueText.text = "$DEFAULT_GAP %"
        
        // Save settings to trigger external keyboard refresh
        splitKeyboardManager.setSplitKeyboardThreshold(DEFAULT_THRESHOLD)
        splitKeyboardManager.setSplitGapPercent(DEFAULT_GAP)
        
        updatePreview()
        updateDeviceInfo()

        Toast.makeText(this, R.string.split_keyboard_reset_toast, Toast.LENGTH_SHORT).show()
    }
}
