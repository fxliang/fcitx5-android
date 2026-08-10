/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.behavior.migration.DataMigrationManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import java.io.File

/**
 * 布局数据管理器，管理键盘布局的数据结构。
 *
 * 主要功能：
 * - 数据加载与保存
 * - 布局增删改查操作
 * - 子模式（submode）支持
 * - 数据验证
 */
class LayoutDataManager(private val context: Context) {
    private companion object {
        private const val LAYOUT_META_KEY = "__meta__"
        private const val LAYOUT_META_HEIGHT_PERCENT_KEY = "keyboard_height_percent"
        private const val LAYOUT_META_HEIGHT_PERCENT_LANDSCAPE_KEY = "keyboard_height_percent_landscape"
        private const val LAYOUT_META_AUX_BAR_KEY = "aux_bar"
    }
    
    /**
     * 所有布局条目
     * Key 格式：
     * - "layoutName" - 基础布局
     * - "layoutName:subModeLabel" - 子模式布局
     */
    val entries = mutableMapOf<String, MutableList<MutableList<MutableMap<String, Any?>>>>()
    val layoutHeightPercentOverrides = mutableMapOf<String, Int>()
    val layoutHeightPercentOverridesLandscape = mutableMapOf<String, Int>()
    val layoutAuxBarConfigs = mutableMapOf<String, AuxBarConfig?>()
    val layoutAuxBarKeys = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
    
    /**
     * 原始数据快照，用于检测是否有更改
     */
    private var originalEntries: Map<String, List<List<Map<String, Any?>>>> = emptyMap()
    private var originalLayoutHeightPercentOverrides: Map<String, Int> = emptyMap()
    private var lastParsedLayoutHeightPercentOverrides: Map<String, Int> = emptyMap()
    private var originalLayoutHeightPercentOverridesLandscape: Map<String, Int> = emptyMap()
    private var lastParsedLayoutHeightPercentOverridesLandscape: Map<String, Int> = emptyMap()
    private var originalLayoutAuxBarConfigs: Map<String, AuxBarConfig?> = emptyMap()
    private var lastParsedLayoutAuxBarConfigs: Map<String, AuxBarConfig?> = emptyMap()
    private var originalLayoutAuxBarKeys: Map<String, List<Map<String, Any?>>> = emptyMap()
    private var lastParsedLayoutAuxBarKeys: Map<String, List<Map<String, Any?>>> = emptyMap()
    
    /**
     * 迁移管理器
     */
    private val migrationManager = DataMigrationManager(this)
    
    // ==================== 数据加载与保存 ====================
    
    /**
     * 从文件加载布局数据
     * 
     * @param file 布局文件
     * @return 加载是否成功
     */
    fun loadFromFile(file: File?): Boolean {
        entries.clear()
        layoutHeightPercentOverrides.clear()
        layoutHeightPercentOverridesLandscape.clear()
        layoutAuxBarConfigs.clear()
        layoutAuxBarKeys.clear()
        
        val parsed = if (file?.exists() == true && file.length() > 0) {
            parseJsonText(file.readText(), file.name)
        } else {
            loadDefaultPreset()
        }
        
        // 将解析结果复制到 entries
        parsed.toSortedMap().forEach { (k, v) ->
            entries[k] = v.map { row ->
                row.map { key -> key.toMutableMap() }.toMutableList()
            }.toMutableList()
        }
        layoutHeightPercentOverrides.putAll(lastParsedLayoutHeightPercentOverrides)
        layoutHeightPercentOverridesLandscape.putAll(lastParsedLayoutHeightPercentOverridesLandscape)
        layoutAuxBarConfigs.putAll(lastParsedLayoutAuxBarConfigs)
        layoutAuxBarKeys.putAll(
            lastParsedLayoutAuxBarKeys.mapValues { (_, keys) ->
                keys.map { it.toMutableMap() }.toMutableList()
            }
        )
        pruneLayoutHeightOverrides()
        
        // 确保至少有一个布局
        if (entries.isEmpty()) {
            val defaultLayout = loadDefaultPreset()
            defaultLayout.forEach { (k, v) ->
                entries[k] = v.map { row ->
                    row.map { key -> key.toMutableMap() }.toMutableList()
                }.toMutableList()
            }
        }

        // 执行迁移（如果需要）
        if (file != null) {
            runCatching {
                migrationManager.migrateAllDisplayTextToSubmodeStructure()
            }.onFailure { e ->
                android.util.Log.e("LayoutDataManager", "Migration failed", e)
                migrationManager.restoreFromBackup(file)
                // 迁移失败恢复备份后，重新加载数据确保内存与文件一致
                entries.clear()
                val restoredParsed = parseJsonText(file.readText(), file.name)
                restoredParsed.toSortedMap().forEach { (k, v) ->
                    entries[k] = v.map { row ->
                        row.map { key -> key.toMutableMap() }.toMutableList()
                    }.toMutableList()
                }
                layoutHeightPercentOverrides.clear()
                layoutHeightPercentOverrides.putAll(lastParsedLayoutHeightPercentOverrides)
                layoutHeightPercentOverridesLandscape.clear()
                layoutHeightPercentOverridesLandscape.putAll(lastParsedLayoutHeightPercentOverridesLandscape)
                layoutAuxBarConfigs.clear()
                layoutAuxBarConfigs.putAll(lastParsedLayoutAuxBarConfigs)
                layoutAuxBarKeys.clear()
                layoutAuxBarKeys.putAll(
                    lastParsedLayoutAuxBarKeys.mapValues { (_, keys) ->
                        keys.map { it.toMutableMap() }.toMutableList()
                    }
                )
                pruneLayoutHeightOverrides()
                // 确保至少有一个布局
                if (entries.isEmpty()) {
                    val defaultLayout = loadDefaultPreset()
                    defaultLayout.forEach { (k, v) ->
                        entries[k] = v.map { row ->
                            row.map { key -> key.toMutableMap() }.toMutableList()
                        }.toMutableList()
                    }
                }
            }
        }

        // 保存原始数据快照
        originalEntries = normalizedEntries()
        originalLayoutHeightPercentOverrides = layoutHeightPercentOverrides.toSortedMap()
        originalLayoutHeightPercentOverridesLandscape = layoutHeightPercentOverridesLandscape.toSortedMap()
        originalLayoutAuxBarConfigs = layoutAuxBarConfigs.toSortedMap()
        originalLayoutAuxBarKeys = normalizedLayoutAuxBarKeys()

        return true
    }
    
    /**
     * 解析 JSON 文件
     */
    private fun parseJsonFile(file: File): Map<String, List<List<Map<String, Any?>>>> {
        return parseJsonText(file.readText(), file.name)
    }

    fun parseJsonText(
        jsonText: String,
        sourceName: String = "<memory>",
        fallbackToDefault: Boolean = true
    ): Map<String, List<List<Map<String, Any?>>>> {
        lastParsedLayoutHeightPercentOverrides = emptyMap()
        lastParsedLayoutHeightPercentOverridesLandscape = emptyMap()
        lastParsedLayoutAuxBarConfigs = emptyMap()
        lastParsedLayoutAuxBarKeys = emptyMap()
        val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        return runCatching {
            var jsonStr = jsonText
            // 移除 // 注释 - 使用统一的工具方法
            jsonStr = LayoutJsonUtils.removeJsonComments(jsonStr)

            val jsonElement = lenientJson.parseToJsonElement(jsonStr)
            val jsonObject = jsonElement.jsonObject
            val result = mutableMapOf<String, List<List<Map<String, Any?>>>>()
            val parsedLayoutHeightOverrides = mutableMapOf<String, Int>()
            val parsedLayoutHeightOverridesLandscape = mutableMapOf<String, Int>()
            val parsedAuxBarConfigs = mutableMapOf<String, AuxBarConfig?>()
            val parsedAuxBarKeys = mutableMapOf<String, List<Map<String, Any?>>>()

            // 处理每个布局条目
            jsonObject.entries.forEach { (layoutName, layoutValue) ->
                try {
                    when (layoutValue) {
                        is JsonArray -> {
                            val rows = normalizeRowsForParsedData(LayoutJsonUtils.parseLayoutRows(layoutValue))
                            result[layoutName] = rows
                        }
                        is JsonObject -> {
                            parseLayoutHeightPercent(layoutValue)?.let { parsedLayoutHeightOverrides[layoutName] = it }
                            parseLayoutHeightPercentLandscape(layoutValue)?.let { parsedLayoutHeightOverridesLandscape[layoutName] = it }
                            parseLayoutAuxBar(layoutValue)?.let { parsedAuxBarConfigs[layoutName] = it }
                            parseLayoutAuxBarKeys(layoutValue)?.let { parsedAuxBarKeys[layoutName] = it }
                            layoutValue.jsonObject.entries.forEach { (subModeLabel, subModeValue) ->
                                if (subModeLabel == LAYOUT_META_KEY) return@forEach
                                val rowsElement = when (subModeValue) {
                                    is JsonArray -> subModeValue
                                    is JsonObject -> {
                                        val subKey = if (subModeLabel == "default") layoutName else "$layoutName:$subModeLabel"
                                        parseLayoutHeightPercent(subModeValue)?.let { subPercent ->
                                            parsedLayoutHeightOverrides[subKey] = subPercent
                                        }
                                        parseLayoutHeightPercentLandscape(subModeValue)?.let { subPercent ->
                                            parsedLayoutHeightOverridesLandscape[subKey] = subPercent
                                        }
                                        parseLayoutAuxBar(subModeValue)?.let { parsedAuxBarConfigs[subKey] = it }
                                        parseLayoutAuxBarKeys(subModeValue)?.let { parsedAuxBarKeys[subKey] = it }
                                        (subModeValue["default"] as? JsonArray) ?: (subModeValue[""] as? JsonArray)
                                    }
                                    else -> null
                                }
                                if (rowsElement != null) {
                                    val rows = normalizeRowsForParsedData(LayoutJsonUtils.parseLayoutRows(rowsElement))
                                    val key = if (subModeLabel == "default") {
                                        layoutName
                                    } else {
                                        "$layoutName:$subModeLabel"
                                    }
                                    result[key] = rows
                                }
                            }
                        }
                        else -> {
                            android.util.Log.w("LayoutDataManager", "Skipping invalid layout value for: $layoutName, type: ${layoutValue::class.simpleName}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LayoutDataManager", "Failed to parse layout: $layoutName", e)
                }
            }
            // 向后兼容：旧文件仅有竖屏高度时，横屏沿用同一数值
            parsedLayoutHeightOverrides.forEach { (key, value) ->
                parsedLayoutHeightOverridesLandscape.putIfAbsent(key, value)
            }
            lastParsedLayoutHeightPercentOverrides = parsedLayoutHeightOverrides.toSortedMap()
            lastParsedLayoutHeightPercentOverridesLandscape = parsedLayoutHeightOverridesLandscape.toSortedMap()
            lastParsedLayoutAuxBarConfigs = parsedAuxBarConfigs.toSortedMap()
            lastParsedLayoutAuxBarKeys = parsedAuxBarKeys.toSortedMap()
            
            if (result.isEmpty()) {
                android.util.Log.w("LayoutDataManager", "No valid layouts found in JSON file")
            }
            
            result
        }.onFailure { e ->
            android.util.Log.e("LayoutDataManager", "Failed to parse JSON from: $sourceName", e)
        }.getOrNull() ?: if (fallbackToDefault) loadDefaultPreset() else emptyMap()
    }

    fun exportCurrentJsonString(): String {
        val jsonElement = LayoutJsonUtils.convertToSaveJson(
            entries,
            layoutHeightPercentOverrides,
            layoutHeightPercentOverridesLandscape,
            layoutAuxBarConfigs,
            normalizedLayoutAuxBarKeys()
        )
        val prettyJson = Json { prettyPrint = true }
        return prettyJson.encodeToString(jsonElement) + "\n"
    }
    
    /**
     * 加载默认预设布局
     */
    private fun loadDefaultPreset(): Map<String, List<List<Map<String, Any?>>>> {
        val defaultLayout = TextKeyboard.getDefaultLayout(showLangSwitch = true)
        val rows = defaultLayout.map { row ->
            row.map { keyDef ->
                LayoutJsonUtils.keyDefToJson(keyDef)
            }
        }
        return mapOf("default" to rows)
    }
    
    /**
     * 保存布局到文件
     * 
     * @param file 目标文件
     * @return 保存是否成功
     */
    fun saveToFile(file: File): Boolean {
        return runCatching {
            pruneLayoutHeightOverrides()
            normalizeAllRowsForSave()
            // 验证数据
            val errors = validateEntries()
            if (errors.isNotEmpty()) {
                throw ValidationException(errors)
            }

            // 创建备份
            migrationManager.createBackup(file)

            // Only clean the base layout's displayText when submode layouts exist
            val baseLayoutNames = entries.keys.map { key ->
                LayoutJsonUtils.baseLayoutNameFromEntryKey(key)
            }.distinct()
            
            // Only clear base layout displayText for layouts that actually have submode variants
            baseLayoutNames.forEach { layoutName ->
                val hasSubModeLayouts = entries.keys.any { 
                    it.startsWith("$layoutName:") && it != "$layoutName:default" 
                }
                if (hasSubModeLayouts) {
                    migrationManager.cleanupBaseLayoutDisplayText(layoutName)
                }
            }

            // Create directory
            file.parentFile?.mkdirs()

            // Convert to JSON and save, using compact format (each key object on one line)
            val jsonElement = LayoutJsonUtils.convertToSaveJson(
                entries,
                layoutHeightPercentOverrides,
                layoutHeightPercentOverridesLandscape,
                layoutAuxBarConfigs,
                normalizedLayoutAuxBarKeys()
            )
            val compactJson = LayoutJsonUtils.formatJsonCompact(jsonElement)
            file.writeText(compactJson + "\n")
            
            // 清除缓存
            TextKeyboard.clearCachedKeyDefLayouts()
            
            // 更新原始数据快照
            originalEntries = normalizedEntries()
            originalLayoutHeightPercentOverrides = layoutHeightPercentOverrides.toSortedMap()
            originalLayoutHeightPercentOverridesLandscape = layoutHeightPercentOverridesLandscape.toSortedMap()
            originalLayoutAuxBarConfigs = layoutAuxBarConfigs.toSortedMap()
            originalLayoutAuxBarKeys = normalizedLayoutAuxBarKeys()
            
            true
        }.getOrElse { e ->
            android.util.Log.e("LayoutDataManager", "Save failed", e)
            false
        }
    }
    
    // ==================== 布局操作 ====================
    
    /**
     * 添加新布局
     * 
     * @param newName 新布局名称
     * @param copyFrom 从中复制的源布局名称（可选）
     * @return 是否成功
     */
    fun addLayout(newName: String, copyFrom: String? = null): Boolean {
        if (entries.containsKey(newName)) {
            return false
        }

        val newRows = if (copyFrom != null) {
            entries[copyFrom]?.let { copyLayout(it) } ?: mutableListOf()
        } else {
            mutableListOf()
        }

        entries[newName] = newRows
        return true
    }
    
    /**
     * 删除布局
     * 
     * @param layoutKey 布局键（可以是 "layoutName" 或 "layoutName:subModeLabel"）
     * @return 删除后剩余的布局键列表
     */
    fun deleteLayout(layoutKey: String): List<String> {
        val layoutName = LayoutJsonUtils.baseLayoutNameFromEntryKey(layoutKey)
        
        // 删除指定布局
        entries.remove(layoutKey)
        
        // 如果删除的是基础布局，需要处理子模式布局
        if (layoutKey == layoutName) {
            val remainingSubModeKeys = entries.keys.filter {
                it.startsWith("$layoutName:")
            }.sorted()
            
            if (remainingSubModeKeys.isNotEmpty()) {
                // 提升第一个子模式为基础布局
                val firstSubModeKey = remainingSubModeKeys.first()
                val subModeLayout = entries[firstSubModeKey]

                if (subModeLayout != null) {
                    entries[layoutName] = copyLayout(subModeLayout)
                    entries.remove(firstSubModeKey)
                }
            }
        }
        
        // 如果没有布局了，加载默认布局
        if (entries.isEmpty()) {
            val defaultLayout = loadDefaultPreset()
            defaultLayout.forEach { (k, v) ->
                entries[k] = v.map { row ->
                    row.map { key -> key.toMutableMap() }.toMutableList()
                }.toMutableList()
            }
        }
        
        return entries.keys.toList()
    }
    
    /**
     * Add submode layout
     *
     * @param layoutName Base layout name
     * @param subModeLabel Submode label
     * @return Whether successful
     */
    fun addSubModeLayout(layoutName: String, subModeLabel: String): Boolean {
        val subModeKey = "$layoutName:$subModeLabel"

        if (entries.containsKey(subModeKey)) {
            return false
        }

        // Get source layout (prefer base layout, then other submode layouts)
        val baseLayout = entries[layoutName]
        val existingSubModeKeys = entries.keys.filter {
            it.startsWith("$layoutName:") && it != "$layoutName:default"
        }

        val sourceLayout = if (baseLayout != null && baseLayout.isNotEmpty()) {
            baseLayout
        } else if (existingSubModeKeys.isNotEmpty()) {
            entries[existingSubModeKeys.first()]
        } else {
            null
        }

        // Copy and migrate data
        val newLayout = sourceLayout?.let { copyLayout(it) } ?: mutableListOf()
        migrationManager.migrateDisplayTextForSubMode(newLayout, subModeLabel)

        entries[subModeKey] = newLayout
        return true
    }

    /**
     * 添加子布局（用于层切换功能）。
     *
     * 子布局使用保留前缀，避免与真实 submode 标签冲突。
     *
     * @param layoutName 基础布局名称
     * @param childName 子布局名称（不包含前缀）
     * @return 新建后的完整布局键，失败返回 null
     */
    fun addChildLayout(layoutName: String, childName: String): String? {
        val normalizedChild = childName.trim()
        if (normalizedChild.isEmpty()) return null
        if (normalizedChild.contains(':')) return null
        val subModeLabel = LayoutJsonUtils.toLayerSubModeLabel(normalizedChild)
        val childKey = "$layoutName:$subModeLabel"
        if (entries.containsKey(childKey)) return null

        val sourceLayout = entries[layoutName]
            ?: entries.keys.firstOrNull { it.startsWith("$layoutName:") }?.let { entries[it] }
            ?: return null

        entries[childKey] = copyLayout(sourceLayout)
        return childKey
    }
    
    /**
     * 删除子模式布局
     * 
     * @param layoutName 基础布局名称
     * @param subModeLabel 子模式标签
     */
    fun deleteSubModeLayout(layoutName: String, subModeLabel: String) {
        val subModeKey = "$layoutName:$subModeLabel"
        entries.remove(subModeKey)
    }
    
    /**
     * 添加新行
     * 
     * @param layoutKey 布局键
     * @param rowIndex 行索引（插入位置），如果为 null 则添加到末尾
     */
    fun addRow(layoutKey: String, rowIndex: Int? = null) {
        val rows = getRowsRef(layoutKey) ?: return
        if (rowIndex != null && rowIndex in 0..rows.size) {
            rows.add(rowIndex, mutableListOf())
        } else {
            rows.add(mutableListOf())
        }
    }
    
    /**
     * 删除行
     * 
     * @param layoutKey 布局键
     * @param rowIndex 行索引
     * @return 是否成功
     */
    fun deleteRow(layoutKey: String, rowIndex: Int): Boolean {
        val rows = getRowsRef(layoutKey) ?: return false
        return if (rowIndex in 0 until rows.size) {
            rows.removeAt(rowIndex)
            true
        } else {
            false
        }
    }
    
    /**
     * 添加键
     * 
     * @param layoutKey 布局键
     * @param rowIndex 行索引
     * @param keyData 键数据
     * @param keyIndex 键索引（插入位置），如果为 null 则添加到末尾
     */
    fun addKey(layoutKey: String, rowIndex: Int, keyData: Map<String, Any?>, keyIndex: Int? = null) {
        val rows = getRowsRef(layoutKey) ?: return
        if (rowIndex !in 0 until rows.size) return
        
        val newRow = rows[rowIndex]
        val newKey = keyData.toMutableMap()
        
        if (keyIndex != null && keyIndex in 0..newRow.size) {
            newRow.add(keyIndex, newKey)
        } else {
            newRow.add(newKey)
        }
    }
    
    /**
     * 更新键
     * 
     * @param layoutKey 布局键
     * @param rowIndex 行索引
     * @param keyIndex 键索引
     * @param keyData 新键数据
     * @return 是否成功
     */
    fun updateKey(layoutKey: String, rowIndex: Int, keyIndex: Int, keyData: Map<String, Any?>): Boolean {
        val rows = getRowsRef(layoutKey) ?: return false
        return if (rowIndex in 0 until rows.size && keyIndex in 0 until rows[rowIndex].size) {
            rows[rowIndex][keyIndex] = keyData.toMutableMap()
            true
        } else {
            false
        }
    }
    
    /**
     * 删除键
     * 
     * @param layoutKey 布局键
     * @param rowIndex 行索引
     * @param keyIndex 键索引
     * @return 是否成功
     */
    fun deleteKey(layoutKey: String, rowIndex: Int, keyIndex: Int): Boolean {
        val rows = getRowsRef(layoutKey) ?: return false
        return if (rowIndex in 0 until rows.size && keyIndex in 0 until rows[rowIndex].size) {
            rows[rowIndex].removeAt(keyIndex)
            true
        } else {
            false
        }
    }
    
    /**
     * 交换行顺序
     * 
     * @param layoutKey 布局键
     * @param fromPosition 原始位置
     * @param toPosition 目标位置
     * @return 是否成功
     */
    fun swapRows(layoutKey: String, fromPosition: Int, toPosition: Int): Boolean {
        val rows = getRowsRef(layoutKey) ?: return false
        return if (fromPosition in 0 until rows.size && toPosition in 0 until rows.size) {
            val temp = rows[fromPosition]
            rows[fromPosition] = rows[toPosition]
            rows[toPosition] = temp
            true
        } else {
            false
        }
    }
    
    /**
     * 获取指定布局的行数据引用
     */
    private fun getRowsRef(layoutKey: String): MutableList<MutableList<MutableMap<String, Any?>>>? {
        return entries[layoutKey]
    }
    
    /**
     * 获取指定键的数据
     */
    fun getKey(layoutKey: String, rowIndex: Int, keyIndex: Int): Map<String, Any?>? {
        val rows = getRowsRef(layoutKey) ?: return null
        return if (rowIndex in 0 until rows.size && keyIndex in 0 until rows[rowIndex].size) {
            rows[rowIndex][keyIndex].toMap()
        } else {
            null
        }
    }
    
    /**
     * 获取子模式标签列表
     */
    fun getSubModeLabels(layoutName: String): List<String> {
        val labels = linkedSetOf<String>()
        
        // 从子模式布局键收集
        entries.keys.forEach { key ->
            if (key.startsWith("$layoutName:")) {
                val subModeLabel = key.substringAfter("$layoutName:")
                if (subModeLabel.isNotEmpty() && subModeLabel != "default") {
                    labels.add(subModeLabel)
                }
            }
        }
        
        // 从 displayText 收集
        val rows = entries[layoutName]
        rows?.forEach { row ->
            row.forEach { key ->
                when (val displayText = key["displayText"]) {
                    is JsonObject -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode.trim()
                            if (normalized.isNotEmpty() && normalized != "default") {
                                labels.add(normalized)
                            }
                        }
                    }
                    is Map<*, *> -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode?.toString()?.trim().orEmpty()
                            if (normalized.isNotEmpty() && normalized != "default") {
                                labels.add(normalized)
                            }
                        }
                    }
                }
            }
        }
        
        return labels.toList()
    }
    
    // ==================== 验证 ====================
    
    /**
     * 检查是否有更改
     */
    fun hasChanges(): Boolean {
        pruneLayoutHeightOverrides()
        return normalizedEntries() != originalEntries ||
            layoutHeightPercentOverrides.toSortedMap() != originalLayoutHeightPercentOverrides ||
            layoutHeightPercentOverridesLandscape.toSortedMap() != originalLayoutHeightPercentOverridesLandscape ||
            layoutAuxBarConfigs.toSortedMap() != originalLayoutAuxBarConfigs ||
            normalizedLayoutAuxBarKeys() != originalLayoutAuxBarKeys
    }
    
    /**
     * 验证数据完整性
     * 
     * @return 错误消息列表，如果为空则表示数据有效
     */
    fun validateEntries(): List<String> {
        val errors = mutableListOf<String>()

        layoutHeightPercentOverrides.forEach { (layoutName, percent) ->
            if (percent !in 10..90) {
                errors.add("布局 \"$layoutName\" 的 keyboard_height_percent 必须在 10 到 90 之间")
            }
        }

        layoutHeightPercentOverridesLandscape.forEach { (layoutName, percent) ->
            if (percent !in 10..90) {
                errors.add("布局 \"$layoutName\" 的 keyboard_height_percent_landscape 必须在 10 到 90 之间")
            }
        }
        
        // 检查重复的布局名称
        val layoutNames = entries.keys.toList()
        val duplicateNames = layoutNames.groupingBy { it }.eachCount().filter { it.value > 1 }
        duplicateNames.forEach { (name, count) ->
            errors.add("布局名称 \"$name\" 重复了 $count 次")
        }
        
        entries.forEach { (layoutName, rows) ->
            if (rows.isEmpty()) {
                errors.add("布局 \"$layoutName\" 没有任何行")
                return@forEach
            }
            
            rows.forEachIndexed { rowIndex, row ->
                if (row.isEmpty()) {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行为空")
                    return@forEachIndexed
                }
                
                row.forEachIndexed { keyIndex, key ->
                    validateKey(layoutName, rowIndex, keyIndex, key, errors)
                }
            }
        }
        
        return errors
    }
    
    /**
     * 验证单个键的数据
     */
    private fun validateKey(
        layoutName: String,
        rowIndex: Int,
        keyIndex: Int,
        key: Map<String, Any?>,
        errors: MutableList<String>
    ) {
        val type = key["type"] as? String
        if (type == null) {
            errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键缺少 type 字段")
            return
        }

        when (type) {
            "AlphabetKey" -> {
                val main = key["main"] as? String
                val alt = key["alt"] as? String
                if (main.isNullOrBlank()) {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 缺少 main 字段")
                } else if (main.length != 1) {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 的 main 字段必须是单个字符")
                }
                if (alt.isNullOrBlank()) {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 缺少 alt 字段")
                } else if (alt.length != 1) {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 的 alt 字段必须是单个字符")
                }
            }
            "LayoutSwitchKey", "SymbolKey" -> {
                val label = key["label"] as? String
                if (label.isNullOrBlank()) {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 缺少 label 字段")
                }
            }
        }

        // 验证 weight - 改进的类型检查
        key["weight"]?.let { weight ->
            when (weight) {
                is Number -> {
                    val floatValue = weight.toFloat()
                    if (floatValue < 0.0f || floatValue > 1.0f) {
                        errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 weight 字段必须在 0.0 到 1.0 之间")
                    }
                }
                is String -> {
                    // 尝试解析字符串为数字
                    val parsedValue = weight.trim()
                        .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                        ?.toFloatOrNull()
                    if (parsedValue == null) {
                        errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 weight 字段必须是数字，但得到：\"$weight\"")
                    } else if (parsedValue < 0.0f || parsedValue > 1.0f) {
                        errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 weight 字段必须在 0.0 到 1.0 之间")
                    }
                }
                else -> {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 weight 字段必须是数字，但得到：${weight::class.simpleName}")
                }
            }
        }

        key["rowHeightPercent"]?.let { rowHeightPercent ->
            when (rowHeightPercent) {
                is Number -> {
                    val floatValue = rowHeightPercent.toFloat()
                    if (floatValue < 1.0f || floatValue > 100.0f) {
                        errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 rowHeightPercent 必须在 1 到 100 之间")
                    }
                }
                is String -> {
                    val parsedValue = rowHeightPercent.trim()
                        .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                        ?.toFloatOrNull()
                    if (parsedValue == null) {
                        errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 rowHeightPercent 字段必须是数字，但得到：\"$rowHeightPercent\"")
                    } else if (parsedValue < 1.0f || parsedValue > 100.0f) {
                        errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 rowHeightPercent 必须在 1 到 100 之间")
                    }
                }
                else -> {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 rowHeightPercent 字段必须是数字，但得到：${rowHeightPercent::class.simpleName}")
                }
            }
        }

        // 验证 displayText 中的重复模式名称
        (key["displayText"] as? Map<*, *>)?.let { displayText ->
            val modeNames = displayText.keys.filterIsInstance<String>().toList()
            val duplicateModes = modeNames.groupingBy { it }.eachCount().filter { it.value > 1 }
            duplicateModes.forEach { (mode, count) ->
                errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 displayText 中模式名称 \"$mode\" 重复了 $count 次")
            }
        }
    }
    
    // ==================== 工具方法 ====================

    fun getLayoutHeightPercentOverride(layoutName: String): Int? {
        return layoutHeightPercentOverrides[layoutName]
    }

    fun setLayoutHeightPercentOverride(layoutName: String, value: Int?) {
        if (value == null) {
            layoutHeightPercentOverrides.remove(layoutName)
        } else {
            layoutHeightPercentOverrides[layoutName] = value.coerceIn(10, 90)
        }
    }

    fun getLayoutHeightPercentOverrideLandscape(layoutName: String): Int? {
        return layoutHeightPercentOverridesLandscape[layoutName]
    }

    fun setLayoutHeightPercentOverrideLandscape(layoutName: String, value: Int?) {
        if (value == null) {
            layoutHeightPercentOverridesLandscape.remove(layoutName)
        } else {
            layoutHeightPercentOverridesLandscape[layoutName] = value.coerceIn(10, 90)
        }
    }

    fun latestParsedLayoutHeightPercentOverrides(): Map<String, Int> {
        return lastParsedLayoutHeightPercentOverrides
    }

    fun latestParsedLayoutHeightPercentOverridesLandscape(): Map<String, Int> {
        return lastParsedLayoutHeightPercentOverridesLandscape
    }

    private fun parseLayoutHeightPercent(layoutObject: JsonObject): Int? {
        return parseLayoutHeightPercentMeta(layoutObject, LAYOUT_META_HEIGHT_PERCENT_KEY)
    }

    private fun parseLayoutHeightPercentLandscape(layoutObject: JsonObject): Int? {
        return parseLayoutHeightPercentMeta(layoutObject, LAYOUT_META_HEIGHT_PERCENT_LANDSCAPE_KEY)
    }

    private fun parseLayoutHeightPercentMeta(layoutObject: JsonObject, key: String): Int? {
        val meta = layoutObject[LAYOUT_META_KEY] as? JsonObject ?: return null
        val raw = (meta[key] as? JsonPrimitive)?.intOrNull
            ?: (meta[key] as? JsonPrimitive)?.content?.toIntOrNull()
        return raw?.takeIf { it in 10..90 }
    }

    private fun parseLayoutAuxBar(layoutObject: JsonObject): AuxBarConfig? {
        val meta = layoutObject[LAYOUT_META_KEY] as? JsonObject ?: return null
        val auxBarConfig = meta[LAYOUT_META_AUX_BAR_KEY] as? JsonObject ?: return null
        val position = when ((auxBarConfig["position"] as? JsonPrimitive)?.content) {
            "top" -> AuxBarPosition.Top
            "bottom" -> AuxBarPosition.Bottom
            "left" -> AuxBarPosition.Left
            "right" -> AuxBarPosition.Right
            "above_preedit" -> AuxBarPosition.AbovePreedit
            else -> return null
        }
        val sizePercent = if (position == AuxBarPosition.AbovePreedit) {
            0f
        } else {
            (auxBarConfig["size_percent"] as? JsonPrimitive)?.floatOrNull
                ?.takeIf { it.isFinite() }
                ?.coerceIn(5f, 95f)
                ?: return null
        }
        return AuxBarConfig(position, sizePercent)
    }

    fun getLayoutAuxBarConfig(layoutName: String): AuxBarConfig? {
        return layoutAuxBarConfigs[layoutName]
    }

    fun setLayoutAuxBarConfig(layoutName: String, config: AuxBarConfig?) {
        if (config == null) {
            layoutAuxBarConfigs.remove(layoutName)
        } else {
            val normalizedSize = config.sizePercent
                .takeIf { it.isFinite() }
                ?.coerceIn(5f, 95f)
                ?: 5f
            layoutAuxBarConfigs[layoutName] = config.copy(
                sizePercent = normalizedSize
            )
        }
    }

    fun getLayoutAuxBarKeys(layoutName: String): List<Map<String, Any?>> {
        return layoutAuxBarKeys[layoutName]?.map { it.toMap() } ?: emptyList()
    }

    fun getLayoutAuxBarKeysRef(layoutName: String): MutableList<MutableMap<String, Any?>> {
        return layoutAuxBarKeys.getOrPut(layoutName) { mutableListOf() }
    }

    fun setLayoutAuxBarKeys(layoutName: String, keys: List<Map<String, Any?>>) {
        if (keys.isEmpty()) {
            layoutAuxBarKeys.remove(layoutName)
        } else {
            layoutAuxBarKeys[layoutName] = keys.map { it.toMutableMap() }.toMutableList()
        }
    }

    private fun normalizedLayoutAuxBarKeys(): Map<String, List<Map<String, Any?>>> {
        return layoutAuxBarKeys.toSortedMap().mapValues { (_, keys) ->
            keys.map { it.toMap() }
        }
    }

    /**
     * 从 aux_bar 元数据中解析自定义按键列表（无 tabs 时用于填充辅助选择栏）。
     */
    private fun parseLayoutAuxBarKeys(layoutObject: JsonObject): List<Map<String, Any?>>? {
        val meta = layoutObject[LAYOUT_META_KEY] as? JsonObject ?: return null
        val auxBarConfig = meta[LAYOUT_META_AUX_BAR_KEY] as? JsonObject ?: return null
        val keysElement = auxBarConfig["keys"] as? JsonArray ?: return null
        val rows = LayoutJsonUtils.parseLayoutRows(JsonArray(listOf(keysElement)))
        return rows.firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeRowsForParsedData(rows: List<List<Map<String, Any?>>>): List<List<Map<String, Any?>>> {
        return copyLayout(rows).also { normalizeRowHeightPercents(it) }
    }

    private fun normalizeAllRowsForSave() {
        entries.values.forEach { rows ->
            normalizeRowHeightPercents(rows)
        }
    }

    private fun pruneLayoutHeightOverrides() {
        val validLayoutKeys = entries.keys.toSet()
        layoutHeightPercentOverrides.keys.retainAll(validLayoutKeys)
        layoutHeightPercentOverridesLandscape.keys.retainAll(validLayoutKeys)
        layoutAuxBarConfigs.keys.retainAll(validLayoutKeys)
        layoutAuxBarKeys.keys.retainAll(validLayoutKeys)
    }

    private fun normalizeRowHeightPercents(rows: MutableList<MutableList<MutableMap<String, Any?>>>) {
        rows.forEach { row ->
            row.forEach { key ->
                val parsed = when (val raw = key["rowHeightPercent"]) {
                    is Number -> raw.toFloat()
                    is String -> raw.trim().toFloatOrNull()
                    else -> null
                }?.takeIf { it in 1f..100f }

                if (parsed == null) {
                    key.remove("rowHeightPercent")
                } else {
                    key["rowHeightPercent"] = parsed
                }
            }
        }
    }
     
    /**
     * 从 JsonArray 解析布局行。
     *
     * @param rowsArray JSON 数组，包含布局行数据
     * @return 解析后的布局行列表
     */
    fun parseLayoutRows(rowsArray: JsonArray): List<List<Map<String, Any?>>> {
        val rows = mutableListOf<List<Map<String, Any?>>>()
        for (i in rowsArray.indices) {
            val rowArray = rowsArray[i].jsonArray
            val row = mutableListOf<Map<String, Any?>>()
            for (j in rowArray.indices) {
                val rowElement = rowArray[j]
                if (rowElement is JsonNull) continue
                if (rowElement !is JsonObject) continue

                val keyJson = rowElement
                val keyMap = mutableMapOf<String, Any?>()
                keyJson.entries.forEach { (key, value) ->
                    keyMap[key] = normalizeKeyValue(key, when (value) {
                        is JsonObject -> value.toMap().mapValues { it.value.let { e -> LayoutJsonUtils.toAny(e) } }
                        is JsonArray -> value.map { LayoutJsonUtils.toAny(it) }
                        is JsonPrimitive -> {
                            if (value.isString) value.content
                            else value.booleanOrNull ?: value.intOrNull ?: value.doubleOrNull ?: value.content
                        }
                        is JsonNull -> null
                    })
                }
                row.add(keyMap)
            }
            rows.add(row)
        }
        return rows
    }

    private fun normalizeKeyValue(key: String, value: Any?): Any? {
        if (key != "weight" &&
            key != "rowHeightPercent" &&
            key != "textColor" &&
            key != "altTextColor" &&
            key != "backgroundColor" &&
            key != "shadowColor"
        ) return value
        return when (value) {
            null -> null
            is Number -> if (key == "weight" || key == "rowHeightPercent") value.toFloat() else value.toInt()
            is String -> {
                value.trim()
                    .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                    ?.let {
                        if (key == "weight" || key == "rowHeightPercent") {
                            it.toFloatOrNull()
                        } else {
                            when {
                                it.startsWith("#") -> it.removePrefix("#").toLongOrNull(16)?.toInt()
                                it.startsWith("0x", ignoreCase = true) -> it.removePrefix("0x").toLongOrNull(16)?.toInt()
                                else -> it.toLongOrNull()?.toInt()
                            }
                        }
                    }
            }
            else -> null
        }
    }
    
    /**
     * 深拷贝布局数据。
     *
     * @param sourceLayout 源布局数据
     * @return 拷贝后的布局数据
     */
    fun copyLayout(sourceLayout: List<List<Map<String, Any?>>>): MutableList<MutableList<MutableMap<String, Any?>>> {
        val copiedLayout = mutableListOf<MutableList<MutableMap<String, Any?>>>()
        for (sourceRow in sourceLayout) {
            val newRow = mutableListOf<MutableMap<String, Any?>>()
            for (sourceKey in sourceRow) {
                val newKey = mutableMapOf<String, Any?>()
                sourceKey.forEach { (k, v) ->
                    newKey[k] = when (v) {
                        is Map<*, *> -> mutableMapOf<String, Any?>().apply {
                            v.forEach { (kk, vv) -> put(kk.toString(), vv) }
                        }
                        is List<*> -> v.toList()
                        else -> v
                    }
                }
                newRow.add(newKey)
            }
            copiedLayout.add(newRow)
        }
        return copiedLayout
    }
    
    /**
     * 标准化数据用于比较。
     *
     * @return 标准化后的数据
     */
    fun normalizedEntries(): Map<String, List<List<Map<String, Any?>>>> =
        entries.toSortedMap().mapValues { (_, rows) ->
            rows.map { row ->
                row.map { key -> key.toMap() }
            }
        }

    /**
     * 验证异常类
     */
    class ValidationException(val errors: List<String>) : Exception("Validation failed: ${errors.joinToString("; ")}")
}
