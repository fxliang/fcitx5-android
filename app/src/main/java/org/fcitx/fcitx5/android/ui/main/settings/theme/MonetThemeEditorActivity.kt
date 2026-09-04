/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.graphics.ColorUtils
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.MonetThemeMapping
import org.fcitx.fcitx5.android.data.theme.SystemColorResourceId
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.utils.parcelable
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.gravityCenter
import splitties.views.horizontalPadding
import splitties.views.topPadding
import splitties.views.verticalPadding

/**
 * Monet 主题编辑 Activity
 * 允许用户自定义 Monet 主题的颜色映射关系
 */
class MonetThemeEditorActivity : AppCompatActivity() {
    
    @Parcelize
    data class EditorResult(
        val themeName: String,
        val isDark: Boolean
    ) : Parcelable
    
    class Contract : ActivityResultContract<Theme.Monet, EditorResult?>() {
        override fun createIntent(context: Context, input: Theme.Monet): Intent =
            Intent(context, MonetThemeEditorActivity::class.java).apply {
                putExtra(EXTRA_THEME_NAME, input.name)
                putExtra(EXTRA_IS_DARK, input.isDark)
            }
        
        override fun parseResult(resultCode: Int, intent: Intent?): EditorResult? =
            intent?.parcelable(EXTRA_RESULT)
    }
    
    private lateinit var toolbar: Toolbar
    private lateinit var previewUi: KeyboardPreviewUi
    private var previewScale = 1f
    
    private lateinit var themeName: String
    private var isDark: Boolean = false
    private lateinit var mapping: MonetThemeMapping
    private lateinit var currentTheme: Theme.Monet
    private var waterRippleResource: SystemColorResourceId? = null
    private var waterRippleColorPreview: View? = null
    private var waterRippleColorText: TextView? = null
    private var resetToDefaultOnSave = false
    
    // 颜色编辑项列表
    private val colorEditItems = listOf<ColorEditItem>(
        ColorEditItem("Background", { it.backgroundColor }, { m, c -> m.copy(backgroundColor = c) }),
        ColorEditItem("Bar", { it.barColor }, { m, c -> m.copy(barColor = c) }),
        ColorEditItem("Keyboard", { it.keyboardColor }, { m, c -> m.copy(keyboardColor = c) }),
        ColorEditItem("Key Background", { it.keyBackgroundColor }, { m, c -> m.copy(keyBackgroundColor = c) }),
        ColorEditItem("Key Text", { it.keyTextColor }, { m, c -> m.copy(keyTextColor = c) }),
        ColorEditItem("Candidate Text", { it.candidateTextColor }, { m, c -> m.copy(candidateTextColor = c) }),
        ColorEditItem("Candidate Label", { it.candidateLabelColor }, { m, c -> m.copy(candidateLabelColor = c) }),
        ColorEditItem("Candidate Comment", { it.candidateCommentColor }, { m, c -> m.copy(candidateCommentColor = c) }),
        ColorEditItem("Alt Key Background", { it.altKeyBackgroundColor }, { m, c -> m.copy(altKeyBackgroundColor = c) }),
        ColorEditItem("Alt Key Text", { it.altKeyTextColor }, { m, c -> m.copy(altKeyTextColor = c) }),
        ColorEditItem("Accent Key Background", { it.accentKeyBackgroundColor }, { m, c -> m.copy(accentKeyBackgroundColor = c) }),
        ColorEditItem("Accent Key Text", { it.accentKeyTextColor }, { m, c -> m.copy(accentKeyTextColor = c) }),
        ColorEditItem("Key Press Highlight", { it.keyPressHighlightColor }, { m, c -> m.copy(keyPressHighlightColor = c) }),
        ColorEditItem("Key Shadow", { it.keyShadowColor }, { m, c -> m.copy(keyShadowColor = c) }),
        ColorEditItem("Popup Background", { it.popupBackgroundColor }, { m, c -> m.copy(popupBackgroundColor = c) }),
        ColorEditItem("Popup Text", { it.popupTextColor }, { m, c -> m.copy(popupTextColor = c) }),
        ColorEditItem("Space Bar", { it.spaceBarColor }, { m, c -> m.copy(spaceBarColor = c) }),
        ColorEditItem("Divider", { it.dividerColor }, { m, c -> m.copy(dividerColor = c) }),
        ColorEditItem("Clipboard Entry", { it.clipboardEntryColor }, { m, c -> m.copy(clipboardEntryColor = c) }),
        ColorEditItem("Generic Active Background", { it.genericActiveBackgroundColor }, { m, c -> m.copy(genericActiveBackgroundColor = c) }),
        ColorEditItem("Generic Active Foreground", { it.genericActiveForegroundColor }, { m, c -> m.copy(genericActiveForegroundColor = c) })
    )
    
    // 存储颜色编辑器视图以便更新
    private val colorEditorViews = mutableMapOf<String, ColorEditorViewHolder>()
    
    data class ColorEditorViewHolder(
        val colorPreview: View,
        val resourceNameText: TextView,
        val item: ColorEditItem
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ThemeMonet.supportsCustomMappingEditor(this)) {
            finish()
            return
        }
        enableEdgeToEdge()
        
        themeName = intent.getStringExtra(EXTRA_THEME_NAME) ?: "Monet"
        isDark = intent.getBooleanExtra(EXTRA_IS_DARK, false)
        
        // 加载当前的映射配置
        mapping = MonetThemePrefs.getMapping(themeName) ?: MonetThemeMapping.createDefault(isDark)
        waterRippleResource = MonetThemePrefs.getWaterRippleResource(themeName)
        currentTheme = buildThemeFromMapping()
        
        initUi()
    }
    
    private fun buildThemeFromMapping(): Theme.Monet {
        return ThemeMonet.createFromMapping(
            isDark = isDark,
            mapping = mapping,
            waterRippleResource = waterRippleResource,
            context = this
        )
    }

    private fun computeWaterRippleColor(theme: Theme.Monet): Int {
        val shadow = theme.keyShadowColor
        val background = ColorUtils.setAlphaComponent(theme.keyboardColor, 255)
        val contrast = ColorUtils.calculateContrast(shadow, background)
        return if (contrast < 1.35) {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.72f)
        } else {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.28f)
        }
    }
    
    private fun getColorForResource(resourceId: SystemColorResourceId): Int {
        return try {
            val colorResId = resources.getIdentifier(resourceId.resourceId, "color", "android")
            if (colorResId != 0) {
                getColor(colorResId)
            } else {
                Color.GRAY
            }
        } catch (e: Exception) {
            Color.GRAY
        }
    }
    
    private fun initUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        
        // Toolbar
        toolbar = Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = 4f // dp(4f)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = toThemeLabel(themeName)
        }
        
        toolbar.setNavigationOnClickListener { finish() }

        // 固定在顶部并水平居中的键盘预览
        previewUi = KeyboardPreviewUi(this, currentTheme.toCustom())
        val previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(
                previewUi.root,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            previewContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
        )

        // 下方可滚动的颜色映射列表
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            verticalPadding = dp(12f)
        }

        TextView(this).apply {
            text = getString(R.string.monet_editor_color_mapping_title)
            textSize = 18f
            setTextColor(styledColor(android.R.attr.textColorPrimary))
            topPadding = dp(16f)
            bottomPadding = dp(8f)
            horizontalPadding = dp(16f)
            contentLayout.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // 描述
        TextView(this).apply {
            text = getString(R.string.monet_editor_description)
            textSize = 14f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            bottomPadding = dp(10f)
            horizontalPadding = dp(16f)
            contentLayout.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        // 颜色编辑器容器
        colorEditItems.forEach { item ->
            val editorView = createColorEditor(item)
            contentLayout.addView(editorView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        contentLayout.addView(createWaterRippleEditor(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        contentLayout.bottomPadding = dp(28f)

        scrollView.addView(contentLayout)
        root.addView(scrollView)
        
        setContentView(root)
        
        // 初始化预览
        applyThemePreview(currentTheme)
        updatePreviewScale()
        registerLayoutChangeObserver()
    }
    
    private fun toThemeLabel(name: String): String {
        return if (name.length == 36 && name.count { it == '-' } == 4) {
            name.take(8)
        } else {
            name
        }
    }
    
    private fun createColorEditor(item: ColorEditItem): View {
        val currentResource = item.getter(mapping)
        val color = getColorForResource(currentResource)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = gravityCenter
            verticalPadding = dp(8f)
            horizontalPadding = dp(16f)
            background = resources.getDrawable(android.R.drawable.list_selector_background, null)

            // 颜色预览
            val colorPreview = View(this@MonetThemeEditorActivity).apply {
                backgroundColor = color
                layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
                    marginEnd = dp(12f)
                }
            }
            addView(colorPreview)

            // 颜色名称
            TextView(this@MonetThemeEditorActivity).apply {
                text = item.name
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
                setTextColor(styledColor(android.R.attr.textColorPrimary))
            }.also { addView(it) }

            // 当前资源名称（截断显示）
            val resourceNameText = TextView(this@MonetThemeEditorActivity).apply {
                text = formatResourceName(currentResource)
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                }
            }
            addView(resourceNameText)
            
            // 存储视图引用
            colorEditorViews[item.name] = ColorEditorViewHolder(colorPreview, resourceNameText, item)
            setOnClickListener { showColorResourcePicker(item) }
        }
    }

    private fun createWaterRippleEditor(): View {
        val currentResource = waterRippleResource
        val color = waterRippleResource?.let { getColorForResource(it) } ?: computeWaterRippleColor(currentTheme)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = gravityCenter
            verticalPadding = dp(8f)
            horizontalPadding = dp(16f)
            background = resources.getDrawable(android.R.drawable.list_selector_background, null)

            val colorPreview = View(this@MonetThemeEditorActivity).apply {
                backgroundColor = color
                layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
                    marginEnd = dp(12f)
                }
            }
            waterRippleColorPreview = colorPreview
            addView(colorPreview)

            TextView(this@MonetThemeEditorActivity).apply {
                text = "Water Ripple"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
                setTextColor(styledColor(android.R.attr.textColorPrimary))
            }.also { addView(it) }

            val colorText = TextView(this@MonetThemeEditorActivity).apply {
                text = waterRippleResource?.let { formatResourceName(it) } ?: "Auto"
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                }
            }
            waterRippleColorText = colorText
            addView(colorText)

            setOnClickListener {
                SystemColorResourcePickerDialog.show(
                    this@MonetThemeEditorActivity,
                    currentResource,
                    allowClear = true,
                    listener = object : SystemColorResourcePickerDialog.OnColorResourceSelectedListener {
                        override fun onColorResourceSelected(resourceId: SystemColorResourceId?) {
                            waterRippleResource = resourceId
                            resetToDefaultOnSave = false
                            currentTheme = buildThemeFromMapping()
                            applyThemePreview(currentTheme)
                            updateWaterRippleEditorUi(resourceId)
                        }
                    }
                )
            }
        }
    }
    
    private fun showColorResourcePicker(item: ColorEditItem) {
        val currentResource = item.getter(mapping)
        
        SystemColorResourcePickerDialog.show(
            this,
            currentResource,
            listener = object : SystemColorResourcePickerDialog.OnColorResourceSelectedListener {
                override fun onColorResourceSelected(resourceId: SystemColorResourceId?) {
                    resourceId ?: return
                // 更新映射
                    mapping = item.setter(mapping, resourceId)
                    resetToDefaultOnSave = false
                
                // 更新主题预览
                    currentTheme = buildThemeFromMapping()
                    applyThemePreview(currentTheme)
                
                // 更新 UI
                    updateColorEditorUi(item, resourceId)
                }
            }
        )
    }
    
    private fun resetToDefaultColors() {
        mapping = MonetThemeMapping.createDefault(isDark)
        waterRippleResource = null
        resetToDefaultOnSave = true
        currentTheme = buildThemeFromMapping()
        colorEditItems.forEach { item ->
            updateColorEditorUi(item, item.getter(mapping))
        }
        updateWaterRippleEditorUi(null)
        applyThemePreview(currentTheme)
    }

    private fun updateColorEditorUi(item: ColorEditItem, resourceId: SystemColorResourceId) {
        val holder = colorEditorViews[item.name] ?: return
        val color = getColorForResource(resourceId)
        
        // 更新颜色预览
        holder.colorPreview.backgroundColor = color
        
        // 更新资源名称显示
        holder.resourceNameText.text = formatResourceName(resourceId)
    }

    private fun updateWaterRippleEditorUi(resourceId: SystemColorResourceId?) {
        val color = resourceId?.let { getColorForResource(it) } ?: computeWaterRippleColor(currentTheme)
        waterRippleColorPreview?.backgroundColor = color
        waterRippleColorText?.text = resourceId?.let { formatResourceName(it) } ?: "Auto"
    }
    
    private fun applyThemePreview(theme: Theme) {
        if (theme is Theme.Monet) {
            previewUi.setTheme(theme.toCustom())
        }
    }
    
    private fun updatePreviewScale() {
        if (!::previewUi.isInitialized) return
        val contentHeight = window.decorView.height - toolbar.height
        val currentHeight = previewUi.intrinsicHeight
        if (contentHeight <= 0 || currentHeight <= 0 || previewScale <= 0f) return
        val baseHeight = (currentHeight / previewScale).toInt().coerceAtLeast(1)
        val maxPreviewHeight = (contentHeight * 0.36f).toInt().coerceAtLeast(dp(140f))
        val newScale = (maxPreviewHeight.toFloat() / baseHeight).coerceIn(0.35f, 1f)
        if (kotlin.math.abs(newScale - previewScale) < 0.01f) return
        previewScale = newScale
        previewUi.setSizeScale(previewScale)
    }
    
    private var layoutChangeJob: android.view.Choreographer.FrameCallback? = null
    private fun registerLayoutChangeObserver() {
        val observer = object : android.view.Choreographer.FrameCallback {
            private var lastWidth = 0
            private var lastHeight = 0
            
            override fun doFrame(frameTimeNanos: Long) {
                val w = window.decorView.width
                val h = window.decorView.height
                if (w != lastWidth || h != lastHeight) {
                    lastWidth = w
                    lastHeight = h
                    updatePreviewScale()
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        layoutChangeJob = observer
        android.view.Choreographer.getInstance().postFrameCallback(observer)
    }
    
    private fun unregisterLayoutChangeObserver() {
        layoutChangeJob?.let {
            android.view.Choreographer.getInstance().removeFrameCallback(it)
        }
        layoutChangeJob = null
    }
    
    private fun saveAndFinish() {
        // 保存映射配置
        if (resetToDefaultOnSave) {
            MonetThemePrefs.deleteMapping(themeName)
            MonetThemePrefs.saveWaterRippleResource(themeName, null)
        } else {
            MonetThemePrefs.saveMapping(themeName, mapping)
            MonetThemePrefs.saveWaterRippleResource(themeName, waterRippleResource)
        }
        
        // 返回结果
        val result = EditorResult(themeName, isDark)
        val intent = Intent().apply {
            putExtra(EXTRA_RESULT, result)
        }
        setResult(RESULT_OK, intent)
        finish()
    }
    
    override fun onDestroy() {
        unregisterLayoutChangeObserver()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(
            Menu.NONE,
            MENU_RESET,
            Menu.NONE,
            getString(R.string.reset_theme_colors)
        ).apply {
            setIcon(R.drawable.ic_baseline_settings_backup_restore_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            icon?.setTint(styledColor(android.R.attr.textColorPrimary))
        }
        menu.add(
            Menu.NONE,
            MENU_SAVE,
            Menu.NONE,
            getString(android.R.string.ok)
        ).apply {
            setIcon(R.drawable.ic_baseline_check_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

            // Use themed text color so the icon follows other UI elements and respects theming
            icon?.let { ic ->
                val tintColor = styledColor(android.R.attr.textColorPrimary)
                ic.setTint(tintColor)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_RESET -> {
            resetToDefaultColors()
            true
        }
        MENU_SAVE -> {
            saveAndFinish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun formatResourceName(resourceId: SystemColorResourceId): String {
        return resourceId.resourceId
            .removePrefix("system_")
            .replace("_", " ")
    }
    
    private fun dp(value: Float): Int = resources.displayMetrics.density.times(value).toInt()
    
    companion object {
        private const val MENU_RESET = 1
        private const val MENU_SAVE = 2
        private const val EXTRA_THEME_NAME = "monet_editor_theme_name"
        private const val EXTRA_IS_DARK = "monet_editor_is_dark"
        private const val EXTRA_RESULT = "monet_editor_result"
    }
}

data class ColorEditItem(
    val name: String,
    val getter: (MonetThemeMapping) -> SystemColorResourceId,
    val setter: (MonetThemeMapping, SystemColorResourceId) -> MonetThemeMapping
)
