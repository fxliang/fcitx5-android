/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import android.util.Log
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.input.keyboard.*
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.core.FcitxKeyMapping

// Import Macro types explicitly
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.KeyRef

/**
 * JSON 转换工具类，用于键盘布局数据与 JSON 之间的双向转换。
 *
 * 主要功能：
 * - 解析：[parseKeyJsonArray], [parseLayoutRows], [parseOptionalFloat]
 * - 转换：[keyDefToJson], [convertToSaveJson], [convertToJsonProperty]
 * - 工具：[removeJsonComments], [resolveDisplayText]
 */
object LayoutJsonUtils {

    private const val TAG = "LayoutJsonUtils"
    const val LAYER_SUBMODE_PREFIX = "__layer__:"
    private val KEY_FIELD_ORDER = listOf(
        "type",
        "main",
        "alt",
        "displayText",
        "label",
        "altLabel",
        "subLabel",
        "swipeLabel",
        "sym",
        "weight",
        "rowHeightPercent",
        "tap",
        "swipe",
        "longPress",
        "composeOverride",
        "independentColor",
        "textColor",
        "textColorMonet",
        "altTextColor",
        "altTextColorMonet",
        "backgroundColor",
        "backgroundColorMonet",
        "shadowColor",
        "shadowColorMonet"
    )

    fun toLayerSubModeLabel(childName: String): String = "$LAYER_SUBMODE_PREFIX$childName"

    fun isLayerSubModeLabel(label: String): Boolean = label.startsWith(LAYER_SUBMODE_PREFIX)

    fun childNameFromLayerLabel(label: String): String =
        label.removePrefix(LAYER_SUBMODE_PREFIX)

    fun baseLayoutNameFromEntryKey(key: String): String {
        return when {
            key.contains(":$LAYER_SUBMODE_PREFIX") -> key.substringBefore(":$LAYER_SUBMODE_PREFIX")
            key.contains(':') -> key.substringBeforeLast(':')
            else -> key
        }
    }

    fun subModeLabelFromEntryKey(key: String, baseName: String): String {
        return if (key == baseName) {
            "default"
        } else {
            key.removePrefix("$baseName:")
        }
    }

    // ==================== 解析功能 ====================

    /**
     * 从 JSON 字符串中移除 // 注释。
     *
     * 改进的引号处理：正确识别转义引号 (\")
     *
     * @param jsonStr 原始 JSON 字符串
     * @return 移除注释后的 JSON 字符串
     */
    fun removeJsonComments(jsonStr: String): String {
        return jsonStr.lines()
            .joinToString("\n") { line ->
                val commentIdx = line.indexOf("//")
                if (commentIdx >= 0) {
                    val beforeComment = line.substring(0, commentIdx)
                    // 更准确的引号计数：考虑转义引号
                    var quoteCount = 0
                    var i = 0
                    while (i < beforeComment.length) {
                        if (beforeComment[i] == '"' && (i == 0 || beforeComment[i - 1] != '\\')) {
                            quoteCount++
                        }
                        i++
                    }
                    if (quoteCount % 2 == 0) line.substring(0, commentIdx) else line
                } else line
            }
    }

    /**
     * 解析可选的 Float 值。
     *
     * 支持以下格式：
     * - JsonPrimitive(Number) → Float
     * - JsonPrimitive(String) → 尝试解析为 Float
     * - JsonNull → null
     * - null → null
     *
     * @param element JSON 元素
     * @return 解析后的 Float 值，或 null
     */
    fun parseOptionalFloat(element: JsonElement?): Float? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString) {
            primitive.content
                .trim()
                .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                ?.toFloatOrNull()
        } else {
            primitive.floatOrNull ?: primitive.doubleOrNull?.toFloat()
        }
    }

    fun parseOptionalInt(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString) {
            primitive.content
                .trim()
                .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                ?.let { raw ->
                    when {
                        raw.startsWith("#") -> raw.removePrefix("#").toLongOrNull(16)?.toInt()
                        raw.startsWith("0x", ignoreCase = true) -> raw.removePrefix("0x").toLongOrNull(16)?.toInt()
                        else -> raw.toLongOrNull()?.toInt()
                    }
                }
        } else {
            primitive.intOrNull ?: primitive.longOrNull?.toInt()
        }
    }

    /**
     * 解析 displayText 字段。
     *
     * 根据当前子模式标签和名称，从 displayText 中解析出正确的显示文本。
     *
     * 优先级：
     * 1. 匹配 schemaId
     * 2. 匹配 subModeLabel
     * 3. 匹配 subModeName
     * 4. 匹配 "default"
     * 5. 匹配空字符串键 ""
     * 6. 返回 default 参数
     *
     * @param displayText displayText JSON 元素
     * @param schemaId 当前方案 ID（从 subMode icon 推导）
     * @param subModeLabel 当前子模式标签
     * @param subModeName 当前子模式名称
     * @param default 默认值
     * @return 解析后的显示文本
     */
    fun resolveDisplayText(
        displayText: JsonElement?,
        schemaId: String,
        subModeLabel: String,
        subModeName: String,
        default: String
    ): String {
        return when {
            displayText == null -> default
            displayText is JsonPrimitive -> displayText.content
            displayText is JsonObject -> resolveDisplayTextMap(displayText, schemaId, subModeLabel, subModeName) ?: default
            else -> default
        }
    }

    private fun resolveDisplayTextMap(
        map: JsonObject,
        schemaId: String,
        subModeLabel: String,
        subModeName: String
    ): String? {
        fun valueOf(key: String): String? {
            if (key.isBlank()) return null
            return map[key]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
        }

        // 与文本键盘子模式匹配一致：schemaId → subModeLabel → subModeName → default → ""
        return valueOf(schemaId)
            ?: valueOf(subModeLabel)
            ?: valueOf(subModeName)
            ?: map["default"]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
            ?: map[""]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
    }

    /**
     * 解析单个 KeyJson 对象。
     *
     * @param obj JSON 对象
     * @return 解析后的 KeyJson，如果 type 缺失则返回 null
     */
    fun parseKeyJson(obj: JsonObject): KeyJson? {
        return parseKeyJsonInternal(obj, allowComposeOverride = true)
    }

    private fun parseKeyJsonInternal(
        obj: JsonObject,
        allowComposeOverride: Boolean,
        isComposeOverride: Boolean = false
    ): KeyJson? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        val composeOverride = if (allowComposeOverride) {
            obj["composeOverride"]?.jsonObject?.let { overrideObj ->
                val normalized = if ("type" in overrideObj) {
                    overrideObj
                } else {
                    JsonObject(overrideObj + ("type" to JsonPrimitive(type)))
                }
                parseKeyJsonInternal(normalized, allowComposeOverride = false, isComposeOverride = true)
            }
        } else {
            null
        }
        val independentColor = if (isComposeOverride) {
            obj["independentColor"]?.jsonPrimitive?.booleanOrNull
        } else null
        return KeyJson(
            type = type,
            main = obj["main"]?.jsonPrimitive?.content,
            alt = obj["alt"]?.jsonPrimitive?.content,
            displayText = obj["displayText"],  // AlphabetKey 和 MacroKey 共用
            label = obj["label"]?.jsonPrimitive?.content,
            altLabel = obj["altLabel"]?.jsonPrimitive?.content,
            longPressLabel = obj["longPressLabel"]?.jsonPrimitive?.content,
            subLabel = obj["subLabel"]?.jsonPrimitive?.content,
            swipeLabel = obj["swipeLabel"]?.jsonPrimitive?.content,
            sym = obj["sym"]?.jsonPrimitive?.content?.let { resolveKeysym(it) },
            weight = parseOptionalFloat(obj["weight"]),
            rowHeightPercent = parseOptionalFloat(obj["rowHeightPercent"]),
            textColor = parseOptionalInt(obj["textColor"]),
            textColorMonet = obj["textColorMonet"]?.jsonPrimitive?.contentOrNull,
            altTextColor = parseOptionalInt(obj["altTextColor"]),
            altTextColorMonet = obj["altTextColorMonet"]?.jsonPrimitive?.contentOrNull,
            backgroundColor = parseOptionalInt(obj["backgroundColor"]),
            backgroundColorMonet = obj["backgroundColorMonet"]?.jsonPrimitive?.contentOrNull,
            shadowColor = parseOptionalInt(obj["shadowColor"]),
            shadowColorMonet = obj["shadowColorMonet"]?.jsonPrimitive?.contentOrNull,
            tap = obj["tap"]?.jsonObject?.let { parseMacroAction(it) },
            swipe = obj["swipe"]?.jsonObject?.let { parseMacroAction(it) },
            longPress = obj["longPress"]?.jsonObject?.let { parseMacroAction(it) },
            independentColor = independentColor,
            composeOverride = composeOverride
        )
    }

    /**
     * 解析 KeyRef（按键引用）
     * @param obj JSON 对象，包含 "fcitx" 或 "android" 字段
     * @return 解析后的 KeyRef
     */
    fun parseKeyRef(obj: JsonObject): KeyRef {
        // 优先解析 fcitx 类型
        obj["fcitx"]?.jsonPrimitive?.content?.let { return KeyRef.Fcitx(it) }
        
        // 解析 android 类型（只接受整数键码）
        obj["android"]?.let { androidValue ->
            val keyCode = androidValue.jsonPrimitive.intOrNull
                ?: androidValue.jsonPrimitive.content.toIntOrNull()
            if (keyCode != null) {
                return KeyRef.Android(keyCode)
            }
        }
        
        // 如果 android 值不是整数，可能是数据错误（如 {"android": "Left"}）
        // 尝试将其作为 fcitx 键名处理
        obj["android"]?.jsonPrimitive?.content?.let {
            return KeyRef.Fcitx(it)
        }
        
        throw IllegalArgumentException("Invalid KeyRef: $obj")
    }

    /**
     * 解析 MacroStep（宏步骤）
     * @param obj JSON 对象，包含 "type" 字段和其他参数字段
     * @return 解析后的 MacroStep
     */
    fun parseMacroStep(obj: JsonObject): MacroStep {
        val type = obj["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing step type")
        return when (type) {
            "down" -> MacroStep.Down(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "up" -> MacroStep.Up(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "tap" -> MacroStep.Tap(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "text" -> MacroStep.Text(obj["text"]?.jsonPrimitive?.content ?: "")
            "edit" -> MacroStep.Edit(obj["action"]?.jsonPrimitive?.content ?: "copy")
            "app" -> MacroStep.AppAction(obj["id"]?.jsonPrimitive?.content ?: "theme")
            "layer" -> MacroStep.LayerSwitch(
                mode = parseLayerSwitchMode(obj["mode"]?.jsonPrimitive?.content),
                target = obj["target"]?.jsonPrimitive?.content ?: ""
            )
            "shortcut" -> {
                val modifiers = obj["modifiers"]?.jsonArray?.map { parseKeyRef(it.jsonObject) }
                    ?: obj["modifier"]?.jsonObject?.let { listOf(parseKeyRef(it)) }
                    ?: throw IllegalArgumentException("Missing modifiers for shortcut")
                val key = obj["key"]?.jsonObject?.let { parseKeyRef(it) }
                    ?: throw IllegalArgumentException("Missing key for shortcut")
                MacroStep.Shortcut(modifiers, key)
            }
            else -> throw IllegalArgumentException("Unknown step type: $type")
        }
    }

    private fun parseLayerSwitchMode(raw: String?): KeyAction.LayerSwitchMode {
        return when (raw?.lowercase()) {
            "osl" -> KeyAction.LayerSwitchMode.OSL
            "back" -> KeyAction.LayerSwitchMode.BACK
            else -> KeyAction.LayerSwitchMode.TO
        }
    }

    private fun layerSwitchModeToString(mode: KeyAction.LayerSwitchMode): String {
        return when (mode) {
            KeyAction.LayerSwitchMode.TO -> "to"
            KeyAction.LayerSwitchMode.OSL -> "osl"
            KeyAction.LayerSwitchMode.BACK -> "back"
        }
    }

    /**
     * 解析 MacroAction（宏动作）
     * @param obj JSON 对象，包含 "macro" 字段
     * @return 解析后的 MacroAction
     */
    fun parseMacroAction(obj: JsonObject): MacroAction {
        val steps = obj["macro"]?.jsonArray?.map { parseMacroStep(it.jsonObject) } ?: emptyList()
        return MacroAction(steps)
    }

    /**
     * 解析一行按键数据。
     *
     * @param rowArray 包含按键的 JSON 数组
     * @param showLangSwitch 是否显示语言切换键（用于过滤 LanguageKey）
     * @return 解析后的 KeyJson 列表
     */
    fun parseKeyJsonArray(rowArray: JsonArray, showLangSwitch: Boolean = true): List<KeyJson> {
        return rowArray.mapNotNull { element ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: ""
            // 如果 showLangSwitch 为 false，跳过 LanguageKey
            if (type == "LanguageKey" && !showLangSwitch) {
                return@mapNotNull null
            }
            parseKeyJson(obj)
        }
    }

    /**
     * 从 JsonArray 解析布局行数据为 Map 表示。
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
                if (rowElement !is JsonObject) {
                    Log.w(TAG, "Skipping invalid key element at row $i, col $j: ${rowElement::class.simpleName}")
                    continue
                }

                val keyJson = rowElement
                val keyMap = mutableMapOf<String, Any?>()
                keyJson.entries.forEach { (key, value) ->
                    keyMap[key] = normalizeKeyValue(key, toAny(value))
                }
                row.add(keyMap)
            }
            rows.add(row)
        }
        return rows
    }

    /**
     * 规范化键值，特别处理 weight 字段。
     *
     * @param key 键名
     * @param value 原始值
     * @return 规范化后的值
     */
    private fun normalizeKeyValue(key: String, value: Any?): Any? {
        if (key != "weight" &&
            key != "rowHeightPercent" &&
            key != "sym" &&
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
                        } else if (key == "sym") {
                            resolveKeysym(it)
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
     * 将按键名解析为 Fcitx keysym（大小写不敏感），兼容以下格式：
     * - 十进制数字（如 "65458"）
     * - 十六进制（"0xffb2" 或 "#ffb2"）
     * - X11/Fcitx 按键名（如 "KP_2"、"KP_Add"、"plus"、"BackSpace"）
     *
     * 纯数字与运算符标签按数字键盘语义解析到 KP_* keysym。
     */
    fun resolveKeysym(input: String?): Int? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        // 先查按键名表：纯数字标签（如 "2"）按数字键盘语义解析为 KP_*，
        // 避免 "2" 被误当成十进制 keysym 2（0x02，控制符）。
        SYM_NAME_TO_CODE[trimmed]?.let { return it }
        SYM_NAME_TO_CODE[trimmed.lowercase()]?.let { return it }
        trimmed.toIntOrNull()?.let { return it }
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.removePrefix("0x").toIntOrNull(16)?.let { return it }
        }
        if (trimmed.startsWith("#")) {
            trimmed.removePrefix("#").toIntOrNull(16)?.let { return it }
        }
        return null
    }

    /**
     * 将 Fcitx keysym 转换为人类可读的规范按键名（如 65458 → "KP_2"）。
     * 未知的 keysym 原样返回整数，保证 round-trip 兼容。
     */
    fun keysymToName(sym: Int): Any? = SYM_CODE_TO_NAME[sym] ?: sym

    private val SYM_CODE_TO_NAME: Map<Int, String> = buildMap {
        for (i in 0..9) {
            put(FcitxKeyMapping.FcitxKey_KP_0 + i, "KP_$i")
        }
        put(FcitxKeyMapping.FcitxKey_KP_Add, "KP_Add")
        put(FcitxKeyMapping.FcitxKey_KP_Subtract, "KP_Subtract")
        put(FcitxKeyMapping.FcitxKey_KP_Multiply, "KP_Multiply")
        put(FcitxKeyMapping.FcitxKey_KP_Divide, "KP_Divide")
        put(FcitxKeyMapping.FcitxKey_KP_Decimal, "KP_Decimal")
        put(FcitxKeyMapping.FcitxKey_KP_Separator, "KP_Separator")
        put(FcitxKeyMapping.FcitxKey_KP_Equal, "KP_Equal")
        put(FcitxKeyMapping.FcitxKey_KP_Enter, "KP_Enter")
    }

    private val SYM_NAME_TO_CODE: Map<String, Int> = buildMap {
        for (i in 0..9) {
            put(i.toString(), FcitxKeyMapping.FcitxKey_KP_0 + i)
            put("kp_$i", FcitxKeyMapping.FcitxKey_KP_0 + i)
        }
        for (c in 'a'..'z') {
            put(c.toString(), FcitxKeyMapping.FcitxKey_a + (c - 'a'))
            put(c.uppercaseChar().toString(), FcitxKeyMapping.FcitxKey_a + (c - 'a'))
        }
        put("+", FcitxKeyMapping.FcitxKey_KP_Add)
        put("-", FcitxKeyMapping.FcitxKey_KP_Subtract)
        put("*", FcitxKeyMapping.FcitxKey_KP_Multiply)
        put("/", FcitxKeyMapping.FcitxKey_KP_Divide)
        put(",", FcitxKeyMapping.FcitxKey_KP_Separator)
        put(".", FcitxKeyMapping.FcitxKey_KP_Decimal)
        put("=", FcitxKeyMapping.FcitxKey_KP_Equal)
        put("kp_add", FcitxKeyMapping.FcitxKey_KP_Add)
        put("kp_subtract", FcitxKeyMapping.FcitxKey_KP_Subtract)
        put("kp_multiply", FcitxKeyMapping.FcitxKey_KP_Multiply)
        put("kp_divide", FcitxKeyMapping.FcitxKey_KP_Divide)
        put("kp_decimal", FcitxKeyMapping.FcitxKey_KP_Decimal)
        put("kp_separator", FcitxKeyMapping.FcitxKey_KP_Separator)
        put("kp_equal", FcitxKeyMapping.FcitxKey_KP_Equal)
        put("kp_enter", FcitxKeyMapping.FcitxKey_KP_Enter)
        // 常用 X11/Fcitx 规范键名（小写）
        put("space", FcitxKeyMapping.FcitxKey_space)
        put("numbersign", FcitxKeyMapping.FcitxKey_numbersign)
        put("apostrophe", FcitxKeyMapping.FcitxKey_apostrophe)
        put("asterisk", FcitxKeyMapping.FcitxKey_asterisk)
        put("plus", FcitxKeyMapping.FcitxKey_plus)
        put("comma", FcitxKeyMapping.FcitxKey_comma)
        put("minus", FcitxKeyMapping.FcitxKey_minus)
        put("period", FcitxKeyMapping.FcitxKey_period)
        put("slash", FcitxKeyMapping.FcitxKey_slash)
        put("semicolon", FcitxKeyMapping.FcitxKey_semicolon)
        put("equal", FcitxKeyMapping.FcitxKey_equal)
        put("at", FcitxKeyMapping.FcitxKey_at)
        put("bracketleft", FcitxKeyMapping.FcitxKey_bracketleft)
        put("bracketright", FcitxKeyMapping.FcitxKey_bracketright)
        put("backslash", FcitxKeyMapping.FcitxKey_backslash)
        put("grave", FcitxKeyMapping.FcitxKey_grave)
        put("return", FcitxKeyMapping.FcitxKey_Return)
        put("backspace", FcitxKeyMapping.FcitxKey_BackSpace)
        put("tab", FcitxKeyMapping.FcitxKey_Tab)
        put("escape", FcitxKeyMapping.FcitxKey_Escape)
        put("up", FcitxKeyMapping.FcitxKey_Up)
        put("down", FcitxKeyMapping.FcitxKey_Down)
        put("left", FcitxKeyMapping.FcitxKey_Left)
        put("right", FcitxKeyMapping.FcitxKey_Right)
        put("home", FcitxKeyMapping.FcitxKey_Home)
        put("end", FcitxKeyMapping.FcitxKey_End)
        put("page_up", FcitxKeyMapping.FcitxKey_Page_Up)
        put("page_down", FcitxKeyMapping.FcitxKey_Page_Down)
        put("insert", FcitxKeyMapping.FcitxKey_Insert)
        put("delete", FcitxKeyMapping.FcitxKey_Delete)
    }

    /**
     * 将 JsonElement 递归转换为 Any? 类型。
     *
     * 转换规则：
     * - JsonObject → Map<String, Any?>
     * - JsonArray → List<Any?>
     * - JsonPrimitive(String) → String
     * - JsonPrimitive(Boolean) → Boolean
     * - JsonPrimitive(Number) → Number
     * - JsonNull → null
     *
     * @param element JSON 元素
     * @return 转换后的 Any? 值
     */
    fun toAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.toMap().mapValues { it.value.let { v -> toAny(v) } }
        is JsonArray -> element.map { toAny(it) }
        is JsonPrimitive -> {
            if (element.isString) element.content
            else element.booleanOrNull ?: element.intOrNull ?: element.doubleOrNull
        }
        is JsonNull -> null
    }

    // ==================== 转换功能 ====================

    /**
     * 数据类，表示解析后的按键 JSON 数据。
     *
     * @property type 按键类型
     * @property main 主要字符（AlphabetKey）
     * @property alt 备选字符（AlphabetKey）
     * @property displayText 显示文本（支持子模式）
     * @property label 标签（LayoutSwitchKey, SymbolKey, MacroKey）
     * @property altLabel 备选标签（MacroKey）
     * @property subLabel 子标签（LayoutSwitchKey）
    * @property swipeLabel 划动标签（支持 swipe 的普通键）
     * @property weight 权重
     * @property tap 点击宏（MacroKey）
     * @property swipe 划动宏（MacroKey）
     * @property longPress 长按宏（MacroKey）
     */
    data class KeyJson(
        val type: String,
        val main: String? = null,
        val alt: String? = null,
        val displayText: JsonElement? = null,  // AlphabetKey 和 MacroKey 共用
        val label: String? = null,  // MacroKey/SymbolKey/LayoutSwitchKey 使用
        val altLabel: String? = null,  // MacroKey 使用
        val longPressLabel: String? = null,  // MacroKey 使用
        val subLabel: String? = null,  // LayoutSwitchKey 使用
        val swipeLabel: String? = null,  // 非 Macro 的 swipe 提示
        val sym: Int? = null,  // NumPadKey 使用（Fcitx keysym）
        val weight: Float? = null,
        val textColor: Int? = null,
        val textColorMonet: String? = null,
        val altTextColor: Int? = null,
        val altTextColorMonet: String? = null,
        val backgroundColor: Int? = null,
        val backgroundColorMonet: String? = null,
        val shadowColor: Int? = null,
        val shadowColorMonet: String? = null,
        val tap: MacroAction? = null,  // MacroKey 使用
        val swipe: MacroAction? = null,  // MacroKey 使用
        val longPress: MacroAction? = null,  // MacroKey 使用
        val independentColor: Boolean? = null,
        val rowHeightPercent: Float? = null,
        val composeOverride: KeyJson? = null
    )

    /**
     * 将 KeyDef 转换为 JSON 地图用于保存。
     *
     * @param keyDef 键盘定义对象
     * @return JSON 地图，包含 type、main、alt、weight 等字段
     */
    fun keyDefToJson(keyDef: KeyDef): MutableMap<String, Any?> {
        val type = when (keyDef) {
            is AlphabetKey -> "AlphabetKey"
            is CapsKey -> "CapsKey"
            is LayoutSwitchKey -> "LayoutSwitchKey"
            is CommaKey -> "CommaKey"
            is LanguageKey -> "LanguageKey"
            is SpaceKey -> "SpaceKey"
            is SymbolKey -> "SymbolKey"
            is ReturnKey -> "ReturnKey"
            is BackspaceKey -> "BackspaceKey"
            is MacroKey -> "MacroKey"
            is NumPadKey -> "NumPadKey"
            is MiniSpaceKey -> "MiniSpaceKey"
            else -> "SpaceKey"
        }

        val json = mutableMapOf<String, Any?>("type" to type)
        val appearance = keyDef.appearance
        appearance.textColor?.let { json["textColor"] = it }
        appearance.textColorMonet?.let { json["textColorMonet"] = it }
        appearance.altTextColor?.let { json["altTextColor"] = it }
        appearance.altTextColorMonet?.let { json["altTextColorMonet"] = it }
        appearance.backgroundColor?.let { json["backgroundColor"] = it }
        appearance.backgroundColorMonet?.let { json["backgroundColorMonet"] = it }
        appearance.shadowColor?.let { json["shadowColor"] = it }
        appearance.shadowColorMonet?.let { json["shadowColorMonet"] = it }

        when (keyDef) {
            is AlphabetKey -> {
                json["main"] = keyDef.character
                json["alt"] = keyDef.punctuation
                json["displayText"] = keyDef.displayText
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
            }
            is CapsKey -> {
                json["weight"] = appearance.percentWidth
                keyDef.swipeLabel?.let { json["swipeLabel"] = it }
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
            }
            is LayoutSwitchKey -> {
                json["label"] = (appearance as? KeyDef.Appearance.Text)?.displayText
                json["subLabel"] = keyDef.to
                json["weight"] = appearance.percentWidth
                keyDef.swipeLabel?.let { json["swipeLabel"] = it }
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
            }
            is CommaKey -> {
                json["weight"] = appearance.percentWidth
            }
            is LanguageKey -> {
                json["weight"] = appearance.percentWidth
            }
            is SpaceKey -> {
                json["weight"] = appearance.percentWidth
            }
            is SymbolKey -> {
                json["label"] = keyDef.symbol
                json["weight"] = appearance.percentWidth
                keyDef.swipeLabel?.let { json["swipeLabel"] = it }
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
            }
            is ReturnKey -> {
                json["weight"] = appearance.percentWidth
                keyDef.swipeLabel?.let { json["swipeLabel"] = it }
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
            }
            is BackspaceKey -> {
                json["weight"] = appearance.percentWidth
                keyDef.swipeLabel?.let { json["swipeLabel"] = it }
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
            }
            is NumPadKey -> {
                json["label"] = (appearance as? KeyDef.Appearance.Text)?.displayText
                json["sym"] = keysymToName(keyDef.sym)
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
            }
            is MiniSpaceKey -> {
                json["weight"] = appearance.percentWidth.takeIf { it != 0.15f }
            }
            is MacroKey -> {
                json["label"] = keyDef.label
                if (keyDef.altLabel != null) {
                    json["altLabel"] = keyDef.altLabel
                }
                if (keyDef.longPressLabel != null) {
                    json["longPressLabel"] = keyDef.longPressLabel
                }
                json["tap"] = macroActionToJson(keyDef.tap)
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
                keyDef.longPress?.let { json["longPress"] = macroActionToJson(it) }
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
            }
        }

        keyDef.composeOverride?.let { overrideDef ->
            val overrideJson = keyDefToJson(overrideDef).toMutableMap()
            overrideJson.remove("weight")
            overrideJson.remove("rowHeightPercent")
            if ((overrideJson["type"] as? String).isNullOrBlank()) {
                overrideJson["type"] = type
            }
            json["composeOverride"] = overrideJson
        }
        if (keyDef.independentColor) {
            json["independentColor"] = true
        }
        keyDef.rowHeightPercent?.let { rowHeight ->
            json["rowHeightPercent"] = rowHeight
        }

        return json
    }

    /**
     * 将 MacroAction 转换为 JSON 对象。
     *
     * @param action MacroAction 对象
     * @return JSON 对象，包含 "macro" 字段
     */
    fun macroActionToJson(action: MacroAction): JsonObject {
        val steps = JsonArray(action.steps.map { macroStepToJson(it) })
        return JsonObject(mapOf("macro" to steps))
    }

    /**
     * 将 MacroStep 转换为 JSON 对象。
     *
     * @param step MacroStep 对象
     * @return JSON 对象
     */
    fun macroStepToJson(step: MacroStep): JsonObject {
        return when (step) {
            is MacroStep.Down -> JsonObject(mapOf(
                "type" to JsonPrimitive("down"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Up -> JsonObject(mapOf(
                "type" to JsonPrimitive("up"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Tap -> JsonObject(mapOf(
                "type" to JsonPrimitive("tap"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Text -> JsonObject(mapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(step.text)
            ))
            is MacroStep.Edit -> JsonObject(mapOf(
                "type" to JsonPrimitive("edit"),
                "action" to JsonPrimitive(step.action)
            ))
            is MacroStep.AppAction -> JsonObject(mapOf(
                "type" to JsonPrimitive("app"),
                "id" to JsonPrimitive(step.id)
            ))
            is MacroStep.LayerSwitch -> JsonObject(mapOf(
                "type" to JsonPrimitive("layer"),
                "mode" to JsonPrimitive(layerSwitchModeToString(step.mode)),
                "target" to JsonPrimitive(step.target)
            ))
            is MacroStep.Shortcut -> JsonObject(mapOf(
                "type" to JsonPrimitive("shortcut"),
                "modifiers" to JsonArray(step.modifiers.map { keyRefToJson(it) }),
                "key" to keyRefToJson(step.key)
            ))
        }
    }

    /**
     * 将 KeyRef 转换为 JSON 对象。
     *
     * @param ref KeyRef 对象
     * @return JSON 对象
     */
    fun keyRefToJson(ref: KeyRef): JsonObject {
        return when (ref) {
            is KeyRef.Fcitx -> JsonObject(mapOf("fcitx" to JsonPrimitive(ref.code)))
            is KeyRef.Android -> JsonObject(mapOf("android" to JsonPrimitive(ref.code)))
        }
    }

    /**
     * 将 KeyJson 转换为 KeyDef。
     *
     * @param key 解析后的 KeyJson
     * @param subModeLabel 当前子模式标签（用于解析 displayText）
     * @param subModeName 当前子模式名称（用于解析 displayText）
     * @return 转换后的 KeyDef
     */
    fun createKeyDef(
        key: KeyJson,
        subModeLabel: String = "",
        schemaId: String = "",
        subModeName: String = ""
    ): KeyDef {
        val keyDef = when (key.type) {
            "AlphabetKey" -> AlphabetKey(
                character = key.main ?: "",
                punctuation = key.alt ?: "",
                displayText = resolveDisplayText(
                    key.displayText,
                    schemaId,
                    subModeLabel,
                    subModeName,
                    key.main ?: ""
                ),
                weight = key.weight,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                altTextColor = key.altTextColor,
                altTextColorMonet = key.altTextColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "CapsKey" -> CapsKey(
                swipe = key.swipe,
                swipeLabel = key.swipeLabel,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "LayoutSwitchKey" -> LayoutSwitchKey(
                displayText = key.label ?: "?123",
                to = key.subLabel ?: "",
                swipe = key.swipe,
                swipeLabel = key.swipeLabel,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "CommaKey" -> CommaKey(
                percentWidth = key.weight ?: 0.1f,
                variant = KeyDef.Appearance.Variant.Alternative,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "LanguageKey" -> LanguageKey(
                percentWidth = key.weight ?: 0.1f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "SpaceKey" -> SpaceKey(
                percentWidth = key.weight ?: 0f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "SymbolKey" -> SymbolKey(
                symbol = key.label ?: ".",
                swipe = key.swipe,
                swipeLabel = key.swipeLabel,
                percentWidth = key.weight ?: 0.1f,
                variant = KeyDef.Appearance.Variant.Alternative,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "ReturnKey" -> ReturnKey(
                swipe = key.swipe,
                swipeLabel = key.swipeLabel,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "BackspaceKey" -> BackspaceKey(
                swipe = key.swipe,
                swipeLabel = key.swipeLabel,
                percentWidth = key.weight ?: 0.15f,
                textColor = key.textColor,
                textColorMonet = key.textColorMonet,
                backgroundColor = key.backgroundColor,
                backgroundColorMonet = key.backgroundColorMonet,
                shadowColor = key.shadowColor,
                shadowColorMonet = key.shadowColorMonet
            )
            "NumPadKey" -> NumPadKey(
                displayText = key.label ?: "0",
                sym = key.sym ?: resolveKeysym(key.label) ?: FcitxKeyMapping.FcitxKey_KP_0,
                percentWidth = key.weight ?: 0.1f
            )
            "MiniSpaceKey" -> MiniSpaceKey(
                percentWidth = key.weight ?: 0.15f
            )
            "MacroKey" -> {
                val tap = key.tap ?: throw IllegalArgumentException("MacroKey requires 'tap' action")
                // 解析 label：基础 label + displayText 多模式覆盖
                // 优先使用 displayText 中当前 submode 的值，否则使用基础 label
                val baseLabel = key.label ?: ""
                val label = resolveDisplayText(
                    key.displayText,
                    schemaId,
                    subModeLabel,
                    subModeName,
                    baseLabel  // 默认值为基础 label（displayText 中不需要 default 键）
                )
                // altLabel 不随 submode 变化（像 AlphabetKey 的 alt 那样）
                val altLabel = key.altLabel ?: ""
                MacroKey(
                    label = label,
                    character = baseLabel.ifEmpty { label },
                    altLabel = altLabel.ifEmpty { null },
                    longPressLabel = key.longPressLabel,
                    tap = tap,
                    swipe = key.swipe,
                    longPress = key.longPress,
                    percentWidth = key.weight ?: 0.1f,
                    textColor = key.textColor,
                    textColorMonet = key.textColorMonet,
                    altTextColor = key.altTextColor,
                    altTextColorMonet = key.altTextColorMonet,
                    backgroundColor = key.backgroundColor,
                    backgroundColorMonet = key.backgroundColorMonet,
                    shadowColor = key.shadowColor,
                    shadowColorMonet = key.shadowColorMonet
                )
            }
            else -> SpaceKey() // Fallback
        }
        keyDef.rowHeightPercent = key.rowHeightPercent?.takeIf { it in 1f..100f }
        key.composeOverride?.let { override ->
            val overrideDef = createKeyDef(
                override.copy(composeOverride = null, weight = null, rowHeightPercent = null),
                subModeLabel,
                schemaId,
                subModeName
            )
            overrideDef.independentColor = override.independentColor ?: false
            keyDef.composeOverride = overrideDef
        }
        return keyDef
    }

    /**
     * 将内部数据结构转换为 JSON 格式用于保存。
     *
     * 支持子模式布局的嵌套结构：
     * ```json
     * {
     *   "rime": {
     *     "default": [...],
     *     "倉頡五代": [...]
     *   },
     *   "pinyin": [...]
     * }
     * ```
     *
     * @param entries 布局数据
     * @return JSON 对象
     */
    fun convertToSaveJson(
        entries: Map<String, List<List<Map<String, Any?>>>>,
        layoutHeightPercentOverrides: Map<String, Int> = emptyMap(),
        layoutHeightPercentOverridesLandscape: Map<String, Int> = emptyMap(),
        layoutAuxBarConfigs: Map<String, AuxBarConfig?> = emptyMap(),
        layoutAuxBarKeys: Map<String, List<Map<String, Any?>>> = emptyMap()
    ): JsonObject {
        val layoutMap = mutableMapOf<String, JsonElement>()

        fun buildMeta(overrideKey: String): JsonObject? {
            val portrait = layoutHeightPercentOverrides[overrideKey]?.takeIf { it in 10..90 }
            val landscape = layoutHeightPercentOverridesLandscape[overrideKey]?.takeIf { it in 10..90 }
            val auxBarConfig = layoutAuxBarConfigs[overrideKey]
            if (portrait == null && landscape == null && auxBarConfig == null) return null
            val meta = mutableMapOf<String, JsonElement>()
            portrait?.let { meta["keyboard_height_percent"] = JsonPrimitive(it) }
            landscape?.let { meta["keyboard_height_percent_landscape"] = JsonPrimitive(it) }
            auxBarConfig?.let { config ->
                val posStr = when (config.position) {
                    AuxBarPosition.Top -> "top"
                    AuxBarPosition.Bottom -> "bottom"
                    AuxBarPosition.Left -> "left"
                    AuxBarPosition.Right -> "right"
                    AuxBarPosition.AbovePreedit -> "above_preedit"
                }
                val auxBarObj = mutableMapOf<String, JsonElement>(
                    "position" to JsonPrimitive(posStr),
                    "size_percent" to JsonPrimitive(config.sizePercent)
                )
                layoutAuxBarKeys[overrideKey]?.takeIf { it.isNotEmpty() }?.let { keys ->
                    auxBarObj["keys"] = JsonArray(keys.map { keyMap ->
                        JsonObject(
                            keyMap
                                .filterValues { it != null }
                                .mapValues { (_, v) -> convertToJsonProperty(v) }
                        )
                    })
                }
                meta["aux_bar"] = JsonObject(auxBarObj)
            }
            return JsonObject(meta)
        }

        val baseLayoutNames = entries.keys.map { key ->
            baseLayoutNameFromEntryKey(key)
        }.distinct()

        for (baseName in baseLayoutNames) {
            val subModeKeys = entries.keys.filter { key ->
                key == baseName || key.startsWith("$baseName:")
            }

            val hasSubModeKeys = subModeKeys.any { key ->
                key != baseName && key.startsWith("$baseName:")
            }

            if (hasSubModeKeys) {
                val subModeMap = mutableMapOf<String, JsonElement>()
                buildMeta(baseName)?.let { subModeMap["__meta__"] = it }

                for (key in subModeKeys) {
                    val subModeLabel = subModeLabelFromEntryKey(key, baseName)

                    val rows = entries[key]!!
                    val jsonArray = JsonArray(rows.map { row ->
                        JsonArray(row.map { keyMap ->
                            val ordered = orderKeyFieldsForSave(keyMap)
                            JsonObject(
                                ordered
                                    .filterValues { it != null }
                                    .mapValues { (_, v) -> convertToJsonProperty(v) }
                            )
                        })
                    })
                    val overrideKey = if (subModeLabel == "default") {
                        baseName
                    } else {
                        "$baseName:$subModeLabel"
                    }
                    val subModeMeta = if (subModeLabel != "default") buildMeta(overrideKey) else null
                    subModeMap[subModeLabel] = if (subModeMeta != null) {
                        JsonObject(
                            mapOf(
                                "__meta__" to subModeMeta,
                                "default" to jsonArray
                            )
                        )
                    } else {
                        jsonArray
                    }
                }

                layoutMap[baseName] = JsonObject(subModeMap.toSortedMap())
            } else {
                val key = subModeKeys.firstOrNull() ?: baseName
                val rows = entries[key] ?: continue
                val jsonArray = JsonArray(rows.map { row ->
                    JsonArray(row.map { keyMap ->
                        val ordered = orderKeyFieldsForSave(keyMap)
                        JsonObject(
                            ordered
                                .filterValues { it != null }
                                .mapValues { (_, v) -> convertToJsonProperty(v) }
                        )
                    })
                })
                val overrideMeta = buildMeta(baseName)
                layoutMap[baseName] = if (overrideMeta == null) {
                    jsonArray
                } else {
                    JsonObject(
                        mapOf(
                            "__meta__" to overrideMeta,
                            "default" to jsonArray
                        )
                    )
                }
            }
        }

        return JsonObject(layoutMap.toSortedMap())
    }

    /**
     * 递归转换任意值为 JsonElement。
     *
     * 转换规则：
     * - Map → JsonObject
     * - List → JsonArray
     * - String → JsonPrimitive
     * - Number → JsonPrimitive
     * - Boolean → JsonPrimitive
     * - null → JsonNull
     * - 其他 → JsonPrimitive(value.toString())
     *
     * @param value 要转换的值
     * @return JsonElement
     */
    fun convertToJsonProperty(value: Any?): JsonElement = when (value) {
        is JsonObject -> value
        is JsonArray -> value
        is Map<*, *> -> {
            val map = value.mapValues { (subKey, subValue) ->
                convertToJsonProperty(subValue)
            }
            JsonObject(map.mapKeys { it.key.toString() }.toMap())
        }
        is List<*> -> {
            val list = value.map { convertToJsonProperty(it) }
            JsonArray(list)
        }
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /**
     * Format layout json with readable indentation while keeping each key object on a single line.
     */
    fun formatJsonCompact(element: JsonElement): String {
        return formatJsonElement(element, 0)
    }

    private fun formatJsonElement(element: JsonElement, level: Int): String {
        return when (element) {
            is JsonObject -> formatJsonObject(element, level)
            is JsonArray -> formatJsonArray(element, level)
            else -> Json.encodeToString(JsonElement.serializer(), element)
        }
    }

    private fun formatJsonObject(obj: JsonObject, level: Int): String {
        if (obj.isEmpty()) return "{}"
        if ("type" in obj) {
            return formatJsonObjectInline(obj)
        }

        val indent = "  ".repeat(level)
        val childIndent = "  ".repeat(level + 1)
        val body = obj.entries.joinToString(",\n") { (key, value) ->
            val keyLiteral = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key))
            "$childIndent$keyLiteral: ${formatJsonElement(value, level + 1)}"
        }
        return "{\n$body\n$indent}"
    }

    private fun formatJsonArray(array: JsonArray, level: Int): String {
        if (array.isEmpty()) return "[]"
        val allScalar = array.all { it is JsonPrimitive || it is JsonNull }
        if (allScalar) {
            return Json.encodeToString(JsonElement.serializer(), array)
        }

        val indent = "  ".repeat(level)
        val childIndent = "  ".repeat(level + 1)
        val body = array.joinToString(",\n") { child ->
            "$childIndent${formatJsonElement(child, level + 1)}"
        }
        return "[\n$body\n$indent]"
    }

    private fun formatJsonObjectInline(obj: JsonObject): String {
        val body = obj.entries.joinToString(",") { (key, value) ->
            val keyLiteral = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key))
            "$keyLiteral:${formatJsonElementInline(value)}"
        }
        return "{$body}"
    }

    private fun formatJsonElementInline(element: JsonElement): String {
        return when (element) {
            is JsonObject -> formatJsonObjectInline(element)
            is JsonArray -> {
                val body = element.joinToString(",") { child -> formatJsonElementInline(child) }
                "[$body]"
            }
            else -> Json.encodeToString(JsonElement.serializer(), element)
        }
    }

    private fun orderKeyFieldsForSave(keyMap: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val ordered = LinkedHashMap<String, Any?>()
        KEY_FIELD_ORDER.forEach { key ->
            if (keyMap.containsKey(key)) {
                ordered[key] = if (key == "sym" && keyMap["type"] == "NumPadKey") {
                    when (val sym = keyMap[key]) {
                        is Number -> keysymToName(sym.toInt())
                        else -> sym
                    }
                } else {
                    keyMap[key]
                }
            }
        }
        keyMap.forEach { (key, value) ->
            if (!ordered.containsKey(key)) {
                ordered[key] = value
            }
        }
        return ordered
    }
}
