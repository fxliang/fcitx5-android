/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import org.fcitx.fcitx5.android.utils.toast
import java.io.File
import org.fcitx.fcitx5.android.daemon.FcitxDaemon

class FontsetEditorActivity : AppCompatActivity() {

    private data class FontEntry(
        val key: String,
        @StringRes val titleRes: Int,
        val sample: String,
        val defaultFontSize: Float = 20f
    )

    private data class FontRowViews(
        val preview: TextView,
        val value: TextView
    )

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val fontsetFile by lazy {
        org.fcitx.fcitx5.android.input.config.ConfigProviders.provider.fontsetFile()
            ?: File(File(filesDir, "fonts"), "fontset.json")
    }
    private val fontsDir by lazy {
        fontsetFile.parentFile ?: File(filesDir, "fonts")
    }

    private val entries by lazy {
        listOf(
            FontEntry("font", R.string.fontset_entry_font, getString(R.string.fontset_sample_main), 20f),
            FontEntry("key_main_font", R.string.fontset_entry_key_main, getString(R.string.fontset_sample_main), 23f),
            FontEntry("key_alt_font", R.string.fontset_entry_key_alt, getString(R.string.fontset_sample_alt), 10.67f),
            FontEntry("cand_font", R.string.fontset_entry_candidate, getString(R.string.fontset_sample_candidate), 20f),
            FontEntry(FontProviders.KEY_COMMENT_FONT, R.string.fontset_entry_comment, getString(R.string.fontset_sample_comment), FontProviders.DEFAULT_COMMENT_FONT_SIZE),
            FontEntry("popup_key_font", R.string.fontset_entry_popup_key, getString(R.string.fontset_sample_popup), 18f),
            FontEntry("preedit_font", R.string.fontset_entry_preedit, getString(R.string.fontset_sample_preedit), 18f)
        )
    }

    private val selectedFonts: MutableMap<String, MutableList<String>> = mutableMapOf()
    private val selectedFontSizes: MutableMap<String, Float> = mutableMapOf()
    private val rowViews: MutableMap<String, FontRowViews> = mutableMapOf()
    private val fontSizeViews: MutableMap<String, TextView> = mutableMapOf()
    private var availableFonts: List<String> = emptyList()
    private var saveMenuItem: MenuItem? = null

    // Snapshots of initial state for dirty detection
    private lateinit var initialFonts: Map<String, List<String>>
    private lateinit var initialSizes: Map<String, Float>

    private val listContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
    }

    private val scrollView by lazy {
        ScrollView(this).apply {
            isFillViewport = true
            addView(
                listContainer,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val ui by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                toolbar,
                LinearLayout.LayoutParams(matchParent, wrapContent)
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(matchParent, 0).apply {
                    weight = 1f
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(ui)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.edit_fontset)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        loadState()
        buildRows()
        refreshRows()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val item = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, "${getString(R.string.save)}")
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        saveMenuItem = item
        item.isEnabled = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        MENU_SAVE_ID -> {
            saveFontset()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun loadState() {
        if (!fontsDir.exists()) fontsDir.mkdirs()
        availableFonts = fontsDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.filter {
                val l = it.lowercase()
                l.endsWith(".ttf") || l.endsWith(".otf") || l.endsWith(".ttc") || l.endsWith(".otc")
            }
            ?.sorted()
            .orEmpty()

        val parsed = org.fcitx.fcitx5.android.input.config.ConfigProviders
            .readFontsetPathMapSnapshot()
            .fold(
                onSuccess = { it?.value ?: emptyMap() },
                onFailure = {
                    toast(it)
                    emptyMap()
                }
            )
        entries.forEach { entry ->
            selectedFonts[entry.key] = parsed[entry.key]?.toMutableList() ?: mutableListOf()
            // Load font size if exists (independent from font path)
            val sizeKey = "${entry.key}_size"
            selectedFontSizes[entry.key] = parsed[sizeKey]?.firstOrNull()?.toFloatOrNull()
                ?.coerceIn(8f, 72f) ?: entry.defaultFontSize
        }
        initialFonts = selectedFonts.mapValues { it.value.toList() }
        initialSizes = selectedFontSizes.toMap()
    }

    private fun updateSaveButtonState() {
        val hasChanges = entries.any { entry ->
            selectedFonts[entry.key] != initialFonts[entry.key] ||
            selectedFontSizes[entry.key] != initialSizes[entry.key]
        }
        saveMenuItem?.isEnabled = hasChanges
    }

    private fun buildRows() {
        listContainer.removeAllViews()

        val hint = TextView(this).apply {
            text = getString(R.string.fontset_editor_hint)
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(8))
        }
        listContainer.addView(hint)

        entries.forEachIndexed { index, entry ->
            val openPicker = { openFontPicker(entry) }
            val openFontSizeEditor = { openFontSizeEditor(entry) }

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(4))
                setOnClickListener { openPicker() }
            }

            val title = TextView(this).apply {
                text = getString(entry.titleRes)
                setTextColor(styledColor(android.R.attr.textColorPrimary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
            }
            titleRow.addView(title)

            val fontSizeValue = TextView(this).apply {
                textSize = 13f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                setPadding(dp(8), 0, dp(4), 0)
                gravity = android.view.Gravity.END
                setOnClickListener { openFontSizeEditor(entry) }
            }
            titleRow.addView(fontSizeValue)

            val fontSizeGear = TextView(this).apply {
                text = "⚙"
                textSize = 14f
                alpha = 0.6f
                setPadding(0, 0, dp(4), 0)
                setOnClickListener { openFontSizeEditor(entry) }
            }
            titleRow.addView(fontSizeGear)

            listContainer.addView(titleRow)

            val preview = TextView(this).apply {
                text = entry.sample
                textSize = 20f
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { openPicker() }
            }
            listContainer.addView(preview)

            val value = TextView(this).apply {
                textSize = 13f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { openPicker() }
            }
            listContainer.addView(value)

            val divider = View(this).apply {
                setBackgroundColor(
                    runCatching { styledColor(android.R.attr.colorControlNormal) }
                        .getOrDefault(0x33000000)
                )
                alpha = 0.35f
            }
            listContainer.addView(
                divider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
            )

            rowViews[entry.key] = FontRowViews(preview, value)
            fontSizeViews[entry.key] = fontSizeValue
        }
    }

    private fun refreshRows() {
        entries.forEach { entry ->
            val selected = selectedFonts[entry.key].orEmpty()
            val row = rowViews[entry.key] ?: return@forEach
            row.value.text = if (selected.isEmpty()) {
                ""
            } else {
                selected.joinToString(", ")
            }
            row.value.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            row.preview.typeface = buildTypeface(selected)
            
            // Update font size display
            val fontSizeView = fontSizeViews[entry.key]
            val fontSize = selectedFontSizes[entry.key] ?: entry.defaultFontSize
            fontSizeView?.text = getString(R.string.font_size_value, fontSize.toInt())
        }
    }

    private fun openFontPicker(entry: FontEntry) {
        if (availableFonts.isEmpty()) {
            toast(getString(R.string.no_fonts_found))
            return
        }
        val current = selectedFonts[entry.key].orEmpty().filter { it in availableFonts }
        val selectedOrder = current.toMutableList()
        val allOrdered = mutableListOf<String>().apply {
            addAll(selectedOrder)
            addAll(availableFonts.filter { it !in selectedOrder })
        }

        class FontItemHolder(
            row: LinearLayout,
            val order: TextView,
            val checkbox: CheckBox
        ) : RecyclerView.ViewHolder(row)

        val adapter = object : RecyclerView.Adapter<FontItemHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FontItemHolder {
                val row = LinearLayout(this@FontsetEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pv = dp(4)
                    setPadding(0, pv, 0, pv)
                }
                val orderLabel = TextView(this@FontsetEditorActivity).apply {
                    width = dp(24)
                }
                val checkBox = CheckBox(this@FontsetEditorActivity)
                row.addView(orderLabel)
                row.addView(
                    checkBox,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                return FontItemHolder(row, orderLabel, checkBox)
            }

            override fun getItemCount(): Int = allOrdered.size

            override fun onBindViewHolder(holder: FontItemHolder, position: Int) {
                val fontName = allOrdered[position]
                val selectedIndex = selectedOrder.indexOf(fontName)
                val selected = selectedIndex >= 0

                holder.order.text = if (selected) "${selectedIndex + 1}." else ""

                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.text = fontName
                holder.checkbox.isChecked = selected
                holder.checkbox.setOnCheckedChangeListener { _, checked ->
                    val adapterPos = holder.bindingAdapterPosition
                    if (adapterPos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                    val name = allOrdered[adapterPos]
                    val currentlySelected = name in selectedOrder
                    if (checked) {
                        if (!currentlySelected) {
                            allOrdered.removeAt(adapterPos)
                            val insertPos = selectedOrder.size
                            selectedOrder.add(name)
                            allOrdered.add(insertPos, name)
                            // 精确刷新受影响的 item
                            notifyItemRemoved(adapterPos)
                            notifyItemInserted(insertPos)
                        }
                    } else {
                        if (currentlySelected) {
                            selectedOrder.remove(name)
                            allOrdered.removeAt(adapterPos)
                            allOrdered.add(name)
                            // 精确刷新受影响的 item
                            notifyItemRemoved(adapterPos)
                            notifyItemInserted(allOrdered.size - 1)
                        }
                    }
                }
            }
        }

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FontsetEditorActivity)
            this.adapter = adapter
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false

                val selectedCount = selectedOrder.size
                if (from >= selectedCount || to >= selectedCount) return false

                val moving = allOrdered.removeAt(from)
                allOrdered.add(to, moving)

                selectedOrder.clear()
                selectedOrder.addAll(allOrdered.take(selectedCount))

                adapter.notifyItemMoved(from, to)
                // 只刷新受影响的 item 范围
                val minPos = minOf(from, to)
                val maxPos = maxOf(from, to)
                adapter.notifyItemRangeChanged(minPos, maxPos - minPos + 1)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    // 高亮拖拽中的 item
                    viewHolder.itemView.alpha = 0.85f
                    viewHolder.itemView.translationZ = 10f
                    // 添加背景色增强反馈
                    viewHolder.itemView.setBackgroundColor(
                        this@FontsetEditorActivity.styledColor(android.R.attr.colorControlHighlight)
                    )
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 恢复外观
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.translationZ = 0f
                viewHolder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // 启用长按拖拽
            override fun isLongPressDragEnabled(): Boolean = true
        })
        itemTouchHelper.attachToRecyclerView(recycler)

        AlertDialog.Builder(this)
            .setTitle(entry.titleRes)
            .setView(recycler)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedFonts[entry.key] = selectedOrder.toMutableList()
                updateSaveButtonState()
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openFontSizeEditor(entry: FontEntry) {
        val currentSize = selectedFontSizes[entry.key] ?: entry.defaultFontSize
        val minSize = 8f
        val maxSize = 72f
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        
        val seekBar = android.widget.SeekBar(this).apply {
            max = (maxSize - minSize).toInt()
            progress = (currentSize - minSize).toInt().coerceIn(0, max)
            setPadding(0, dp(16), 0, dp(8))
        }
        
        val sizeDisplay = TextView(this).apply {
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            text = getString(R.string.font_size_value, currentSize.toInt())
        }
        
        val previewText = TextView(this).apply {
            text = entry.sample
            textSize = currentSize
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
            typeface = buildTypeface(selectedFonts[entry.key].orEmpty())
        }
        
        val hint = TextView(this).apply {
            text = getString(R.string.font_size_editor_hint, minSize.toInt(), maxSize.toInt())
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        
        container.addView(seekBar)
        container.addView(sizeDisplay)
        container.addView(previewText)
        container.addView(hint)
        
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newSize = minSize + progress
                sizeDisplay.text = getString(R.string.font_size_value, newSize.toInt())
                previewText.textSize = newSize
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.font_size_editor_title, getString(entry.titleRes)))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newSize = minSize + seekBar.progress
                selectedFontSizes[entry.key] = newSize.coerceIn(minSize, maxSize)
                updateSaveButtonState()
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset) { _, _ ->
                selectedFontSizes[entry.key] = entry.defaultFontSize
                updateSaveButtonState()
                refreshRows()
            }
            .show()
    }

    private fun saveFontset() {
        // Build font size map (save all font sizes, not just when fonts are set)
        val fontSizeMap = entries
            .associate { entry ->
                "${entry.key}_size" to mutableListOf(
                    (selectedFontSizes[entry.key] ?: entry.defaultFontSize).toString()
                )
            }
            .filterValues { it.isNotEmpty() && it.firstOrNull()?.toFloatOrNull() != null }

        val nonEmptyMap = entries
            .associate { entry -> entry.key to selectedFonts[entry.key].orEmpty() }
            .filterValues { it.isNotEmpty() }

        // Merge font paths and font sizes
        // Font sizes are saved independently from font paths
        val mergedMap = nonEmptyMap + fontSizeMap

        // allow saving empty fontset (means use system default fonts)
        org.fcitx.fcitx5.android.input.config.ConfigProviders.provider.writeFontsetPathMap(mergedMap)
            .onSuccess { file ->
                FontProviders.markNeedsRefresh()
                val fcitxConnection = FcitxDaemon.getFirstConnectionOrNull()
                if (fcitxConnection != null) {
                    runCatching {
                        fcitxConnection.runIfReady { reloadConfig() }
                    }.onFailure { e ->
                        android.util.Log.w("FontsetEditor", "Failed to reload Fcitx config after saving fontset", e)
                    }
                } else {
                    android.util.Log.w("FontsetEditor", "Fcitx connection not available, config reload skipped")
                }
                toast(getString(R.string.fontset_saved_at))
                initialFonts = selectedFonts.mapValues { it.value.toList() }
                initialSizes = selectedFontSizes.toMap()
                saveMenuItem?.isEnabled = false
            }.onFailure { e ->
                val errorMsg = when (e) {
                    is java.io.IOException -> getString(R.string.fontset_save_failed_io_error)
                    is SecurityException -> getString(R.string.fontset_save_failed_permission_error)
                    else -> getString(R.string.fontset_save_failed_unknown_error, e.message.orEmpty())
                }
                toast(errorMsg)
                android.util.Log.e("FontsetEditor", "Failed to save fontset", e)
            }
    }

    private fun buildTypeface(fontNames: List<String>): Typeface? {
        val files = fontNames.map { File(fontsDir, it) }.filter { it.exists() }
        if (files.isEmpty()) return null

        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val firstFont = Font.Builder(files.first()).build()
                var builder = Typeface.CustomFallbackBuilder(
                    FontFamily.Builder(firstFont).build()
                )
                files.drop(1).forEach { file ->
                    val family = FontFamily.Builder(Font.Builder(file).build()).build()
                    builder = builder.addCustomFallback(family)
                }
                builder.build()
            } else {
                Typeface.createFromFile(files.first())
            }
        }.getOrNull()
    }

    companion object {
        private const val MENU_SAVE_ID = 1001
    }
}
