/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import splitties.dimensions.dp
import splitties.resources.styledColor

/**
 * UI builder for keyboard editor, responsible for building UI components
 * for the keyboard edit dialog.
 */
class KeyboardEditorUiBuilder(private val activity: AppCompatActivity) {

    /**
     * A selectable switch target for [org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey].
     * The value is stored as the key's `subLabel`; an empty value selects the default
     * behavior (last symbol layout, "?123").
     */
    data class SwitchTargetOption(val value: String, val labelRes: Int? = null)

    /**
     * A selectable numpad symbol for [org.fcitx.fcitx5.android.input.keyboard.NumPadKey].
     * [sym] is the Fcitx keysym sent with NumLock state on press.
     */
    data class NumPadOption(val label: String, val sym: Int) {
        override fun toString(): String = label
    }

    companion object {
        private const val DIALOG_LABEL_TEXT_SIZE_SP = 13f
        private const val DIALOG_CONTENT_TEXT_SIZE_SP = 14f

        val KEY_TYPES = arrayOf(
            "AlphabetKey",
            "CapsKey",
            "LayoutSwitchKey",
            "CommaKey",
            "LanguageKey",
            "SpaceKey",
            "SymbolKey",
            "ReturnKey",
            "BackspaceKey",
            "NumPadKey",
            "MiniSpaceKey",
            "MacroKey"
        )

        val SWITCH_TARGET_OPTIONS = listOf(
            SwitchTargetOption("", R.string.text_keyboard_layout_switch_target_default),
            SwitchTargetOption("Text", R.string.text_keyboard_layout_switch_target_text),
            SwitchTargetOption("Number", R.string.text_keyboard_layout_switch_target_number),
            SwitchTargetOption("Symbol", R.string.text_keyboard_layout_switch_target_symbol)
        )

        val NUMPAD_OPTIONS = listOf(
            NumPadOption("0", FcitxKeyMapping.FcitxKey_KP_0),
            NumPadOption("1", FcitxKeyMapping.FcitxKey_KP_1),
            NumPadOption("2", FcitxKeyMapping.FcitxKey_KP_2),
            NumPadOption("3", FcitxKeyMapping.FcitxKey_KP_3),
            NumPadOption("4", FcitxKeyMapping.FcitxKey_KP_4),
            NumPadOption("5", FcitxKeyMapping.FcitxKey_KP_5),
            NumPadOption("6", FcitxKeyMapping.FcitxKey_KP_6),
            NumPadOption("7", FcitxKeyMapping.FcitxKey_KP_7),
            NumPadOption("8", FcitxKeyMapping.FcitxKey_KP_8),
            NumPadOption("9", FcitxKeyMapping.FcitxKey_KP_9),
            NumPadOption("+", FcitxKeyMapping.FcitxKey_KP_Add),
            NumPadOption("-", FcitxKeyMapping.FcitxKey_KP_Subtract),
            NumPadOption("*", FcitxKeyMapping.FcitxKey_KP_Multiply),
            NumPadOption("/", FcitxKeyMapping.FcitxKey_KP_Divide),
            NumPadOption(",", FcitxKeyMapping.FcitxKey_KP_Separator),
            NumPadOption(".", FcitxKeyMapping.FcitxKey_KP_Decimal),
            NumPadOption("=", FcitxKeyMapping.FcitxKey_KP_Equal)
        )
    }

    /**
     * Create type selector spinner
     *
     * @param container Parent container
     * @param keyData Current key data
     * @return Spinner instance
     */
    fun setupTypeSpinner(
        container: LinearLayout,
        keyData: Map<String, Any?>
    ): Spinner {
        val typeLabel = TextView(activity).apply {
            text = activity.getString(R.string.text_keyboard_layout_key_type)
            textSize = DIALOG_LABEL_TEXT_SIZE_SP
            setTextColor(activity.styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(activity.dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = activity.dp(8)
            }
        }

        val typeSpinner = Spinner(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        val typeAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, KEY_TYPES)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = typeAdapter

        val currentType = keyData["type"] as? String ?: "AlphabetKey"
        val typePosition = KEY_TYPES.indexOf(currentType)
        if (typePosition >= 0) typeSpinner.setSelection(typePosition)

        val typeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(4), 0, activity.dp(4))
            addView(typeLabel)
            addView(typeSpinner)
        }
        container.addView(typeRow)

        return typeSpinner
    }

    /**
     * Create edit field
     *
     * @param label Label text
     * @param value Initial value
     * @return Pair(Container, EditText)
     */
    fun createEditField(label: String, value: String): Pair<LinearLayout, EditText> {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(4), 0, activity.dp(4))
        }
        val labelView = TextView(activity).apply {
            text = label
            textSize = DIALOG_LABEL_TEXT_SIZE_SP
            setTextColor(activity.styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(activity.dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = activity.dp(8)
            }
        }
        val editText = EditText(activity).apply {
            setText(value)
            textSize = DIALOG_CONTENT_TEXT_SIZE_SP
            isSingleLine = true
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        container.addView(labelView)
        container.addView(editText)
        return container to editText
    }

    /**
     * Create a dropdown selector for a [LayoutSwitchKey]'s switch target (stored as the
     * key's `subLabel`). Preselects the entry matching [currentValue]; empty matches the
     * default option.
     */
    fun createSwitchTargetSpinner(
        container: LinearLayout,
        currentValue: String
    ): Spinner {
        val labelView = TextView(activity).apply {
            text = activity.getString(R.string.text_keyboard_layout_switch_target)
            textSize = DIALOG_LABEL_TEXT_SIZE_SP
            setTextColor(activity.styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(activity.dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = activity.dp(8)
            }
        }
        val spinner = Spinner(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        val options = SWITCH_TARGET_OPTIONS.toMutableList().apply {
            if (none { it.value == currentValue }) add(SwitchTargetOption(currentValue))
        }
        val adapter = object : ArrayAdapter<SwitchTargetOption>(
            activity, android.R.layout.simple_spinner_item, options
        ) {
            private fun display(option: SwitchTargetOption): String = option.labelRes?.let(activity::getString)
                ?: option.value

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                getItem(position)?.let { option ->
                    (view as? TextView)?.text = display(option)
                }
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                getItem(position)?.let { option ->
                    (view as? TextView)?.text = display(option)
                }
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(options.indexOfFirst { it.value == currentValue })
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(4), 0, activity.dp(4))
            addView(labelView)
            addView(spinner)
        }
        container.addView(row)
        return spinner
    }

    /**
     * Create a dropdown selector for a [NumPadKey]'s symbol. Preselects the entry whose
     * keysym matches [currentSym]; an unknown keysym is retained as an additional option.
     */
    fun createNumPadSymSpinner(
        container: LinearLayout,
        currentSym: Int
    ): Spinner {
        val labelView = TextView(activity).apply {
            text = activity.getString(R.string.text_keyboard_layout_numpad_symbol)
            textSize = DIALOG_LABEL_TEXT_SIZE_SP
            setTextColor(activity.styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(activity.dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = activity.dp(8)
            }
        }
        val spinner = Spinner(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        val options = NUMPAD_OPTIONS.toMutableList().apply {
            if (none { it.sym == currentSym }) {
                add(NumPadOption("0x${currentSym.toString(16)}", currentSym))
            }
        }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(options.indexOfFirst { it.sym == currentSym })
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(4), 0, activity.dp(4))
            addView(labelView)
            addView(spinner)
        }
        container.addView(row)
        return spinner
    }

    /**
     * DisplayText data item
     */
    data class DisplayTextItem(var mode: String, var value: String)

    /**
     * DisplayText row binding
     */
    data class DisplayTextRowBinding(
        val modeEdit: EditText,
        val valueEdit: EditText
    )

    /**
     * Render displayText editor
     *
     * @param displayTextContainer Container
     * @param modeSpecific Whether mode-specific
     * @param simpleValue Simple value
     * @param modeItems Mode items list
     * @param rowBindings Row bindings list
     * @param isEditingSubModeLayout Whether editing submode layout
     * @param hasMultiSubmodeSupport Whether supports multi-submode
     * @param callback Callback (returns modeSpecific, simpleValue, items, bindings, simpleTextEdit)
     */
    fun renderDisplayTextEditor(
        displayTextContainer: LinearLayout,
        modeSpecific: Boolean,
        simpleValue: String,
        modeItems: List<DisplayTextItem>,
        rowBindings: MutableList<DisplayTextRowBinding>,
        isEditingSubModeLayout: Boolean,
        hasMultiSubmodeSupport: Boolean,
        callback: (Boolean, String, MutableList<DisplayTextItem>, MutableList<DisplayTextRowBinding>, EditText?) -> Unit
    ) {
        // 重建前先清除焦点，避免输入法在布局变化时崩溃
        displayTextContainer.clearFocus()
        for (i in 0 until displayTextContainer.childCount) {
            displayTextContainer.getChildAt(i).clearFocus()
        }

        displayTextContainer.removeAllViews()

        if (!modeSpecific) {
            val simpleText = createEditField(
                activity.getString(R.string.text_keyboard_layout_key_display_text),
                simpleValue
            )
            displayTextContainer.addView(simpleText.first)
            simpleText.second.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    callback(false, s?.toString().orEmpty(), mutableListOf(), mutableListOf(), simpleText.second)
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            if (!isEditingSubModeLayout && hasMultiSubmodeSupport) {
                val addModeBtn = TextView(activity).apply {
                    text = activity.getString(R.string.text_keyboard_layout_add_mode)
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    minWidth = activity.dp(120)
                    setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(activity.styledColor(android.R.attr.colorButtonNormal))
                        setStroke(activity.dp(1), activity.styledColor(android.R.attr.colorControlNormal))
                        cornerRadius = activity.dp(4).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = activity.dp(8)  // 增加与上方内容的间距
                    }
                    setOnClickListener {
                        val newValue = simpleText.second.text?.toString().orEmpty()
                        // Switch to mode-specific mode
                        callback(true, newValue, mutableListOf(DisplayTextItem("", newValue)), mutableListOf(), null)
                        // Re-render UI
                        renderDisplayTextEditor(
                            displayTextContainer,
                            true,
                            newValue,
                            mutableListOf(DisplayTextItem("", newValue)),
                            mutableListOf(),
                            isEditingSubModeLayout,
                            hasMultiSubmodeSupport,
                            callback
                        )
                    }
                }
                displayTextContainer.addView(addModeBtn)
            }
            // Call callback with simpleTextEdit reference
            callback(modeSpecific, simpleValue, mutableListOf(), mutableListOf(), simpleText.second)
            return
        }

        val mapLabel = TextView(activity).apply {
            text = activity.getString(R.string.text_keyboard_layout_key_display_text)
            textSize = DIALOG_LABEL_TEXT_SIZE_SP
            setTextColor(activity.styledColor(android.R.attr.textColorSecondary))
            setPadding(0, activity.dp(8), 0, activity.dp(8))
        }
        displayTextContainer.addView(mapLabel)

        val modeEntriesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        displayTextContainer.addView(modeEntriesContainer)

        val items = modeItems.toMutableList()
        // Use a new local list to accumulate bindings, then sync back via callback
        // This avoids the issue of clearing the same list reference
        val bindings = mutableListOf<DisplayTextRowBinding>()

        modeItems.forEachIndexed { index, item ->
            val entryRow = createDisplayTextEntryRow(
                item,
                index,
                items,
                bindings,
                displayTextContainer,
                modeSpecific,
                simpleValue,
                isEditingSubModeLayout,
                hasMultiSubmodeSupport,
                callback
            )
            modeEntriesContainer.addView(entryRow)
        }
        
        // 在 mode-specific 模式下，调用 callback 同步外部状态
        if (modeSpecific) {
            callback(modeSpecific, simpleValue, items, bindings, null)
        }

        val addModeBtn = TextView(activity).apply {
            text = activity.getString(R.string.text_keyboard_layout_add_mode)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = activity.dp(120)
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(activity.styledColor(android.R.attr.colorButtonNormal))
                setStroke(activity.dp(1), activity.styledColor(android.R.attr.colorControlNormal))
                cornerRadius = activity.dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = activity.dp(4)
            }
            var lastInvalidToastTime = 0L
            setOnClickListener {
                // 验证现有条目
                val hasInvalidEntry = bindings.any { binding ->
                    val mode = binding.modeEdit.text?.toString().orEmpty().trim()
                    val value = binding.valueEdit.text?.toString().orEmpty().trim()
                    mode.isEmpty() || value.isEmpty()
                }
                if (hasInvalidEntry) {
                    var firstInvalidIndex = -1
                    for ((index, binding) in bindings.withIndex()) {
                        val mode = binding.modeEdit.text?.toString().orEmpty().trim()
                        val value = binding.valueEdit.text?.toString().orEmpty().trim()
                        if (mode.isEmpty() || value.isEmpty()) {
                            binding.modeEdit.requestFocus()
                            firstInvalidIndex = index
                            break
                        }
                    }
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastInvalidToastTime > 2000 && firstInvalidIndex >= 0) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.text_keyboard_layout_display_text_mode_invalid, firstInvalidIndex + 1),
                            Toast.LENGTH_SHORT
                        ).show()
                        lastInvalidToastTime = currentTime
                    }
                    return@setOnClickListener
                }

                // 检查重复模式名称
                val modeNames = bindings.map { it.modeEdit.text?.toString().orEmpty().trim() }.filter { it.isNotEmpty() }
                val duplicateModes = modeNames.groupingBy { it }.eachCount().filter { it.value > 1 }
                if (duplicateModes.isNotEmpty()) {
                    val duplicateMode = duplicateModes.keys.first()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.text_keyboard_layout_display_text_mode_duplicate, duplicateMode),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val updatedItems = collectDisplayTextItems(bindings)
                updatedItems.add(DisplayTextItem("", ""))
                // Re-render
                callback(modeSpecific, simpleValue, updatedItems, bindings, null)
                renderDisplayTextEditor(
                    displayTextContainer,
                    modeSpecific,
                    simpleValue,
                    updatedItems,
                    bindings,
                    isEditingSubModeLayout,
                    hasMultiSubmodeSupport,
                    callback
                )
            }
        }
        displayTextContainer.addView(addModeBtn)
    }

    /**
     * Create displayText input row
     */
    private fun createDisplayTextEntryRow(
        item: DisplayTextItem,
        index: Int,
        items: MutableList<DisplayTextItem>,
        bindings: MutableList<DisplayTextRowBinding>,
        displayTextContainer: LinearLayout,
        modeSpecific: Boolean,
        simpleValue: String,
        isEditingSubModeLayout: Boolean,
        hasMultiSubmodeSupport: Boolean,
        callback: (Boolean, String, MutableList<DisplayTextItem>, MutableList<DisplayTextRowBinding>, EditText?) -> Unit
    ): LinearLayout {
        val entryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(2), 0, activity.dp(2))
        }

        val modeEdit = EditText(activity).apply {
            setText(item.mode)
            textSize = DIALOG_CONTENT_TEXT_SIZE_SP
            hint = activity.getString(R.string.text_keyboard_layout_mode_name_hint)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                rightMargin = activity.dp(4)
            }
        }

        val valueEdit = EditText(activity).apply {
            setText(item.value)
            textSize = DIALOG_CONTENT_TEXT_SIZE_SP
            hint = activity.getString(R.string.text_keyboard_layout_display_value_hint)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                rightMargin = activity.dp(4)
            }
        }

        val deleteBtn = TextView(activity).apply {
            text = "🗑"
            textSize = DIALOG_CONTENT_TEXT_SIZE_SP
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
            setOnClickListener {
                // 从 bindings 中删除对应的 binding
                val bindingToRemove = bindings.firstOrNull {
                    it.modeEdit === modeEdit && it.valueEdit === valueEdit
                }
                if (bindingToRemove != null) {
                    bindings.remove(bindingToRemove)
                }
                val updatedItems = collectDisplayTextItems(bindings)

                // If no submode left, switch to simple text mode
                if (updatedItems.isEmpty()) {
                    // Call callback to update external state first
                    val fallbackValue = valueEdit.text?.toString().orEmpty()
                    callback(false, fallbackValue, mutableListOf(), mutableListOf(), null)
                    // Then re-render UI
                    renderDisplayTextEditor(
                        displayTextContainer,
                        false,
                        fallbackValue,
                        mutableListOf(),
                        mutableListOf(),
                        isEditingSubModeLayout,
                        hasMultiSubmodeSupport,
                        callback
                    )
                } else {
                    callback(modeSpecific, simpleValue, updatedItems, bindings, null)
                    // Re-render the entire displayText editor
                    renderDisplayTextEditor(
                        displayTextContainer,
                        modeSpecific,
                        simpleValue,
                        updatedItems,
                        bindings,
                        isEditingSubModeLayout,
                        hasMultiSubmodeSupport,
                        callback
                    )
                }
            }
        }

        entryRow.addView(modeEdit)
        entryRow.addView(valueEdit)
        entryRow.addView(deleteBtn)
        bindings.add(DisplayTextRowBinding(modeEdit, valueEdit))
        val rowWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                callback(modeSpecific, simpleValue, collectDisplayTextItems(bindings), bindings, null)
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        modeEdit.addTextChangedListener(rowWatcher)
        valueEdit.addTextChangedListener(rowWatcher)

        return entryRow
    }

    private fun collectDisplayTextItems(bindings: List<DisplayTextRowBinding>): MutableList<DisplayTextItem> {
        return bindings.map { binding ->
            DisplayTextItem(
                binding.modeEdit.text?.toString().orEmpty(),
                binding.valueEdit.text?.toString().orEmpty()
            )
        }.toMutableList()
    }
}
