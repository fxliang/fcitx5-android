/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.SystemColorResourceId
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fcitx.fcitx5.android.ui.main.settings.theme.SystemColorResourcePickerDialog
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.serializable
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import java.io.Serializable
import java.util.Arrays
import java.util.LinkedHashMap

class KeyEditorActivity : AppCompatActivity() {

    private data class ThemeColorToken(
        val token: String,
        val resolver: (org.fcitx.fcitx5.android.data.theme.Theme) -> Int
    ) {
        val displayName: String
            get() = token.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }

    private data class EditableColorField(
        val customKey: String,
        val monetKey: String,
        val labelRes: Int,
        val themeColorGetter: (org.fcitx.fcitx5.android.data.theme.Theme) -> Int,
        val supportedTypes: Set<String>? = null
    )

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
            setSubtitleTextAppearance(context, android.R.style.TextAppearance_Small)
            setSubtitleTextColor(styledColor(android.R.attr.textColorSecondary))
        }
    }

    private val contentContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
    }

    private val scrollView by lazy {
        ScrollView(this).apply {
            addView(contentContainer, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private val rootView by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(matchParent, wrapContent))
            addView(scrollView, LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f })
        }
    }

    private val uiBuilder by lazy { KeyboardEditorUiBuilder(this) }

    private var rowIndex: Int = -1
    private var keyIndex: Int? = null
    private var isEditingSubModeLayout: Boolean = false
    private var currentSubModeLabel: String? = null
    private var hasMultiSubmodeSupport: Boolean = false
    private var lockTypeSelection: Boolean = false
    private var disableWeightEditing: Boolean = false
    private var composeOverrideEditorMode: Boolean = false
    private var hasInitialComposeOverride: Boolean = false
    private var availableLayoutTargets: List<String> = emptyList()
    private var independentColor: Boolean = false
    private var keyData: MutableMap<String, Any?> = mutableMapOf()
    private var keyColorOverrides: LinkedHashMap<String, Any?> = LinkedHashMap()
    private var inheritedBaseKeyColorData: MutableMap<String, Any?> = mutableMapOf()
    private var composeOverrideData: MutableMap<String, Any?>? = null

    private lateinit var typeSpinner: Spinner
    private lateinit var fieldsContainer: LinearLayout
    private var selectedType: String = "AlphabetKey"

    private var alphabetMainEdit: EditText? = null
    private var alphabetAltEdit: EditText? = null
    private var alphabetWeightEdit: EditText? = null
    private var alphabetDisplayTextSimpleEdit: EditText? = null
    private var alphabetDisplayTextModeSpecific = false
    private var alphabetDisplayTextSimpleValue = ""
    private val alphabetDisplayTextModeItems = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
    private val alphabetDisplayTextRowBindings = mutableListOf<KeyboardEditorUiBuilder.DisplayTextRowBinding>()

    private var layoutSwitchLabelEdit: EditText? = null
    private var layoutSwitchTargetSpinner: Spinner? = null
    private var layoutSwitchWeightEdit: EditText? = null

    private var numPadLabelEdit: EditText? = null
    private var numPadSymSpinner: Spinner? = null

    private var symbolLabelEdit: EditText? = null
    private var symbolWeightEdit: EditText? = null

    private var macroLabelEdit: EditText? = null
    private var macroDisplayTextSimpleEdit: EditText? = null
    private var macroDisplayTextModeSpecific = false
    private var macroDisplayTextSimpleValue = ""
    private val macroDisplayTextModeItems = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
    private val macroDisplayTextRowBindings = mutableListOf<KeyboardEditorUiBuilder.DisplayTextRowBinding>()
    private var macroAltLabelEdit: EditText? = null
    private var macroLongPressLabelEdit: EditText? = null
    private var macroWeightEdit: EditText? = null

    private var simpleWeightEdit: EditText? = null
    private var rowHeightPercentEdit: EditText? = null
    private var nonMacroSwipeLabelEdit: EditText? = null

    private var macroTapStepsData: List<Any> = emptyList()
    private var macroSwipeStepsData: List<Any> = emptyList()
    private var macroLongPressStepsData: List<Any> = emptyList()
    private var nonMacroSwipeStepsData: List<Any> = emptyList()
    private var macroEditCallback: ((List<Any>) -> Unit)? = null
    private var saveMenuItem: MenuItem? = null
    private var deleteMenuItem: MenuItem? = null
    private var baselineKeySnapshot: String = ""
    private val editableColorFields = listOf(
        EditableColorField(
            customKey = "textColor",
            monetKey = "textColorMonet",
            labelRes = R.string.text_keyboard_layout_key_text_color,
            themeColorGetter = { it.keyTextColor }
        ),
        EditableColorField(
            customKey = "altTextColor",
            monetKey = "altTextColorMonet",
            labelRes = R.string.text_keyboard_layout_key_alt_text_color,
            themeColorGetter = { it.altKeyTextColor },
            supportedTypes = setOf("AlphabetKey", "MacroKey")
        ),
        EditableColorField(
            customKey = "backgroundColor",
            monetKey = "backgroundColorMonet",
            labelRes = R.string.text_keyboard_layout_key_background_color,
            themeColorGetter = { it.keyBackgroundColor }
        ),
        EditableColorField(
            customKey = "shadowColor",
            monetKey = "shadowColorMonet",
            labelRes = R.string.text_keyboard_layout_key_shadow_color,
            themeColorGetter = { it.keyShadowColor }
        )
    )

    private val themeColorTokens = listOf(
        ThemeColorToken("backgroundColor") { it.backgroundColor },
        ThemeColorToken("barColor") { it.barColor },
        ThemeColorToken("keyboardColor") { it.keyboardColor },
        ThemeColorToken("keyBackgroundColor") { it.keyBackgroundColor },
        ThemeColorToken("keyTextColor") { it.keyTextColor },
        ThemeColorToken("candidateTextColor") { it.candidateTextColor },
        ThemeColorToken("candidateLabelColor") { it.candidateLabelColor },
        ThemeColorToken("candidateCommentColor") { it.candidateCommentColor },
        ThemeColorToken("altKeyBackgroundColor") { it.altKeyBackgroundColor },
        ThemeColorToken("altKeyTextColor") { it.altKeyTextColor },
        ThemeColorToken("accentKeyBackgroundColor") { it.accentKeyBackgroundColor },
        ThemeColorToken("accentKeyTextColor") { it.accentKeyTextColor },
        ThemeColorToken("keyPressHighlightColor") { it.keyPressHighlightColor },
        ThemeColorToken("keyShadowColor") { it.keyShadowColor },
        ThemeColorToken("popupBackgroundColor") { it.popupBackgroundColor },
        ThemeColorToken("popupTextColor") { it.popupTextColor },
        ThemeColorToken("spaceBarColor") { it.spaceBarColor },
        ThemeColorToken("dividerColor") { it.dividerColor },
        ThemeColorToken("clipboardEntryColor") { it.clipboardEntryColor },
        ThemeColorToken("genericActiveBackgroundColor") { it.genericActiveBackgroundColor },
        ThemeColorToken("genericActiveForegroundColor") { it.genericActiveForegroundColor }
    )

    private val macroEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val steps = data.serializable<ArrayList<Map<*, *>>>(MacroEditorActivity.EXTRA_MACRO_RESULT) ?: return@registerForActivityResult
            macroEditCallback?.invoke(steps.map { it as Any })
        }

    private val colorEditorLauncher =
        registerForActivityResult(ThemeColorEditorActivity.Contract()) { result ->
            result ?: return@registerForActivityResult
            val field = editableColorFields.firstOrNull { it.customKey == result.fieldName } ?: return@registerForActivityResult
            persistCurrentDraft()
            setColorOverride(field, result.color, null)
            rebuildFields()
            updateActionButtonState()
        }

    private val composeOverrideEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val colorOverrides = LinkedHashMap(keyColorOverrides)
            persistCurrentDraft()
            restoreColorOverrides(colorOverrides)
            when (data.getStringExtra(EXTRA_RESULT_ACTION)) {
                RESULT_ACTION_SAVE -> {
                    val returned = serializableExtraCompat<HashMap<String, Any?>>(data, EXTRA_RESULT_KEY_DATA)
                        ?.toMutableMap() ?: return@registerForActivityResult
                    returned.remove("weight")
                    composeOverrideData = returned
                }
                RESULT_ACTION_DELETE -> {
                    composeOverrideData = null
                }
            }
            rebuildFields()
            updateActionButtonState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(rootView)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        readIntentExtras()
        baselineKeySnapshot = snapshotOf(keyData)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        buildForm()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (keyIndex != null || (composeOverrideEditorMode && hasInitialComposeOverride)) {
            deleteMenuItem = menu.add(Menu.NONE, MENU_DELETE_ID, 1, getString(R.string.delete)).apply {
                setIcon(android.R.drawable.ic_menu_delete)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }

        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, 2, getString(R.string.save)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        }

        updateActionButtonState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_SAVE_ID -> {
            saveAndFinish()
            true
        }
        MENU_DELETE_ID -> {
            confirmDelete()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun readIntentExtras() {
        rowIndex = intent.getIntExtra(EXTRA_ROW_INDEX, -1)
        keyIndex = intent.takeIf { it.hasExtra(EXTRA_KEY_INDEX) }?.getIntExtra(EXTRA_KEY_INDEX, -1)?.takeIf { it >= 0 }
        isEditingSubModeLayout = intent.getBooleanExtra(EXTRA_IS_EDITING_SUBMODE_LAYOUT, false)
        currentSubModeLabel = intent.getStringExtra(EXTRA_CURRENT_SUBMODE_LABEL)
        hasMultiSubmodeSupport = intent.getBooleanExtra(EXTRA_HAS_MULTI_SUBMODE_SUPPORT, false)
        availableLayoutTargets = intent.getStringArrayListExtra(EXTRA_AVAILABLE_LAYOUT_TARGETS)?.toList() ?: emptyList()
        lockTypeSelection = intent.getBooleanExtra(EXTRA_LOCK_TYPE_SELECTION, false)
        disableWeightEditing = intent.getBooleanExtra(EXTRA_DISABLE_WEIGHT_EDITING, false)
        composeOverrideEditorMode = intent.getBooleanExtra(EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE, false)
        hasInitialComposeOverride = intent.getBooleanExtra(EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE, false)

        val received = serializableExtraCompat<HashMap<String, Any?>>(intent, EXTRA_KEY_DATA)
        keyData = received?.toMutableMap() ?: mutableMapOf()
        keyColorOverrides = extractColorOverrides(keyData)
        inheritedBaseKeyColorData = serializableExtraCompat<HashMap<String, Any?>>(intent, EXTRA_BASE_KEY_COLOR_DATA)
            ?.toMutableMap()
            ?: mutableMapOf()
        selectedType = intent.getStringExtra(EXTRA_FIXED_TYPE)
            ?: keyData["type"] as? String
            ?: "AlphabetKey"
        keyData["type"] = selectedType
        composeOverrideData = (keyData["composeOverride"] as? Map<*, *>)?.let { map ->
            map.entries.associate { (k, v) -> k.toString() to v }.toMutableMap()
        }
        if (composeOverrideEditorMode) {
            composeOverrideData = null
            keyData.remove("composeOverride")
            independentColor = keyData["independentColor"] as? Boolean ?: false
        }

        val titleRes = if (keyIndex != null) R.string.edit else R.string.text_keyboard_layout_add_key
        val customTitle = intent.getStringExtra(EXTRA_TITLE_OVERRIDE)
        if (customTitle.isNullOrBlank()) {
            supportActionBar?.setTitle(titleRes)
        } else {
            supportActionBar?.title = customTitle
        }
        toolbar.subtitle = currentSubModeLabel?.takeIf { isEditingSubModeLayout }
    }

    private fun buildForm() {
        contentContainer.removeAllViews()
        typeSpinner = uiBuilder.setupTypeSpinner(contentContainer, keyData)
        if (lockTypeSelection) {
            typeSpinner.isEnabled = false
            typeSpinner.alpha = 0.6f
        }
        fieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        contentContainer.addView(fieldsContainer)

        if (!lockTypeSelection) {
            typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedType = KeyboardEditorUiBuilder.KEY_TYPES[position]
                    rebuildFields()
                    updateActionButtonState()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        rebuildFields()
    }

    private fun rebuildFields() {
        fieldsContainer.clearFocus()
        for (i in 0 until fieldsContainer.childCount) {
            fieldsContainer.getChildAt(i).clearFocus()
        }

        fieldsContainer.removeAllViews()
        alphabetMainEdit = null
        alphabetAltEdit = null
        alphabetWeightEdit = null
        alphabetDisplayTextSimpleEdit = null
        alphabetDisplayTextRowBindings.clear()
        layoutSwitchLabelEdit = null
        layoutSwitchTargetSpinner = null
        layoutSwitchWeightEdit = null
        numPadLabelEdit = null
        numPadSymSpinner = null
        symbolLabelEdit = null
        symbolWeightEdit = null
        macroLabelEdit = null
        macroDisplayTextSimpleEdit = null
        macroDisplayTextModeSpecific = false
        macroDisplayTextSimpleValue = ""
        macroDisplayTextModeItems.clear()
        macroDisplayTextRowBindings.clear()
        macroAltLabelEdit = null
        macroWeightEdit = null
        simpleWeightEdit = null
        rowHeightPercentEdit = null
        nonMacroSwipeLabelEdit = null
        nonMacroSwipeStepsData = emptyList()

        initDisplayText(
            keyData,
            isEditingSubModeLayout,
            currentSubModeLabel
        ) { modeSpecific, simpleValue, items ->
            alphabetDisplayTextModeSpecific = modeSpecific
            alphabetDisplayTextSimpleValue = simpleValue
            alphabetDisplayTextModeItems.clear()
            alphabetDisplayTextModeItems.addAll(items)
        }

        initDisplayText(
            keyData,
            isEditingSubModeLayout,
            currentSubModeLabel,
            labelTextKey = "displayText"
        ) { modeSpecific, simpleValue, items ->
            macroDisplayTextModeSpecific = modeSpecific
            macroDisplayTextSimpleValue = simpleValue
            macroDisplayTextModeItems.clear()
            macroDisplayTextModeItems.addAll(items)
        }

        if (!composeOverrideEditorMode) {
            val rowHeightEdit = uiBuilder.createEditField(
                getString(R.string.text_keyboard_layout_key_row_height_percent),
                (keyData["rowHeightPercent"] as? Number)?.toString()
                    ?: (keyData["rowHeightPercent"] as? String).orEmpty()
            )
            fieldsContainer.addView(rowHeightEdit.first)
            rowHeightPercentEdit = rowHeightEdit.second
        }

        when (selectedType) {
            "AlphabetKey" -> {
                val mainEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_main),
                    keyData["main"] as? String ?: ""
                )
                val altEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_alt),
                    keyData["alt"] as? String ?: ""
                )
                fieldsContainer.addView(mainEdit.first)
                fieldsContainer.addView(altEdit.first)
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    fieldsContainer.addView(weightEdit.first)
                    alphabetWeightEdit = weightEdit.second
                }

                val displayTextContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                fieldsContainer.addView(displayTextContainer)

                alphabetMainEdit = mainEdit.second
                alphabetAltEdit = altEdit.second

                uiBuilder.renderDisplayTextEditor(
                    displayTextContainer,
                    alphabetDisplayTextModeSpecific,
                    alphabetDisplayTextSimpleValue,
                    alphabetDisplayTextModeItems,
                    alphabetDisplayTextRowBindings,
                    isEditingSubModeLayout,
                    hasMultiSubmodeSupport
                ) { modeSpecific, simpleValue, items, bindings, simpleTextEdit ->
                    alphabetDisplayTextModeSpecific = modeSpecific
                    alphabetDisplayTextSimpleValue = simpleValue
                    alphabetDisplayTextModeItems.clear()
                    alphabetDisplayTextModeItems.addAll(items)
                    alphabetDisplayTextRowBindings.clear()
                    alphabetDisplayTextRowBindings.addAll(bindings)
                    alphabetDisplayTextSimpleEdit = simpleTextEdit ?: bindings.lastOrNull()?.valueEdit
                    updateActionButtonState()
                }
            }

            "LayoutSwitchKey" -> {
                val labelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_label),
                    keyData["label"] as? String ?: "?123"
                )
                layoutSwitchLabelEdit = labelEdit.second
                fieldsContainer.addView(labelEdit.first)
                layoutSwitchTargetSpinner = uiBuilder.createSwitchTargetSpinner(
                    fieldsContainer,
                    keyData["subLabel"] as? String ?: ""
                )
                layoutSwitchTargetSpinner?.onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: android.view.View?,
                            position: Int,
                            id: Long
                        ) {
                            updateActionButtonState()
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    fieldsContainer.addView(weightEdit.first)
                    layoutSwitchWeightEdit = weightEdit.second
                }

                val swipeLabelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_swipe_label),
                    keyData["swipeLabel"] as? String ?: ""
                )
                nonMacroSwipeLabelEdit = swipeLabelEdit.second
                fieldsContainer.addView(swipeLabelEdit.first)

                val swipeAction = keyData["swipe"] as? Map<*, *>
                val swipeMacroSteps = (swipeAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()
                nonMacroSwipeStepsData = swipeMacroSteps
                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_swipe_event),
                    previewText = buildMacroPreview(swipeMacroSteps),
                    onClick = {
                        openMacroEditor(nonMacroSwipeStepsData, getString(R.string.text_keyboard_layout_macro_swipe_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            nonMacroSwipeStepsData = newSteps
                            if (newSteps.isNotEmpty()) {
                                draft["swipe"] = mapOf("macro" to newSteps)
                            } else {
                                draft.remove("swipe")
                            }
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }
            }

            "SymbolKey" -> {
                val labelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_label),
                    keyData["label"] as? String ?: "."
                )
                symbolLabelEdit = labelEdit.second
                fieldsContainer.addView(labelEdit.first)
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    symbolWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }

                val swipeLabelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_swipe_label),
                    keyData["swipeLabel"] as? String ?: ""
                )
                nonMacroSwipeLabelEdit = swipeLabelEdit.second
                fieldsContainer.addView(swipeLabelEdit.first)

                val swipeAction = keyData["swipe"] as? Map<*, *>
                val swipeMacroSteps = (swipeAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()
                nonMacroSwipeStepsData = swipeMacroSteps
                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_swipe_event),
                    previewText = buildMacroPreview(swipeMacroSteps),
                    onClick = {
                        openMacroEditor(nonMacroSwipeStepsData, getString(R.string.text_keyboard_layout_macro_swipe_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            nonMacroSwipeStepsData = newSteps
                            if (newSteps.isNotEmpty()) {
                                draft["swipe"] = mapOf("macro" to newSteps)
                            } else {
                                draft.remove("swipe")
                            }
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }
            }

            "MacroKey" -> {
                val labelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_label),
                    keyData["label"] as? String ?: ""
                )
                val altLabelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_alt_label),
                    keyData["altLabel"] as? String ?: ""
                )
                val longPressLabelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_longpress_label),
                    keyData["longPressLabel"] as? String ?: ""
                )
                longPressLabelEdit.second.hint = getString(R.string.text_keyboard_layout_longpress_label_fallback_hint)
                fieldsContainer.addView(labelEdit.first)
                fieldsContainer.addView(altLabelEdit.first)
                fieldsContainer.addView(longPressLabelEdit.first)
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    fieldsContainer.addView(weightEdit.first)
                    macroWeightEdit = weightEdit.second
                }

                macroLabelEdit = labelEdit.second
                macroAltLabelEdit = altLabelEdit.second
                macroLongPressLabelEdit = longPressLabelEdit.second

                val labelTextContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                fieldsContainer.addView(labelTextContainer)

                uiBuilder.renderDisplayTextEditor(
                    labelTextContainer,
                    macroDisplayTextModeSpecific,
                    macroDisplayTextSimpleValue,
                    macroDisplayTextModeItems,
                    macroDisplayTextRowBindings,
                    isEditingSubModeLayout,
                    hasMultiSubmodeSupport = !isEditingSubModeLayout,
                    callback = { modeSpecific, simpleValue, items, bindings, simpleTextEdit ->
                        macroDisplayTextModeSpecific = modeSpecific
                        macroDisplayTextSimpleValue = simpleValue
                        macroDisplayTextModeItems.clear()
                        macroDisplayTextModeItems.addAll(items)
                        macroDisplayTextRowBindings.clear()
                        macroDisplayTextRowBindings.addAll(bindings)
                        macroDisplayTextSimpleEdit = simpleTextEdit
                        updateActionButtonState()
                    }
                )

                val tapAction = keyData["tap"] as? Map<*, *>
                val swipeAction = keyData["swipe"] as? Map<*, *>
                val longPressAction = keyData["longPress"] as? Map<*, *>

                val tapMacroSteps = (tapAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()
                val swipeMacroSteps = (swipeAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()
                val longPressMacroSteps = (longPressAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()

                macroTapStepsData = tapMacroSteps
                macroSwipeStepsData = swipeMacroSteps
                macroLongPressStepsData = longPressMacroSteps

                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_tap_event),
                    previewText = buildMacroPreview(tapMacroSteps),
                    onClick = {
                        openMacroEditor(macroTapStepsData, getString(R.string.text_keyboard_layout_macro_tap_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            macroTapStepsData = newSteps
                            draft["tap"] = mapOf("macro" to newSteps)
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }

                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_swipe_event),
                    previewText = buildMacroPreview(swipeMacroSteps),
                    onClick = {
                        openMacroEditor(macroSwipeStepsData, getString(R.string.text_keyboard_layout_macro_swipe_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            macroSwipeStepsData = newSteps
                            draft["swipe"] = mapOf("macro" to newSteps)
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }

                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_longpress_event),
                    previewText = buildMacroPreview(longPressMacroSteps),
                    onClick = {
                        openMacroEditor(macroLongPressStepsData, getString(R.string.text_keyboard_layout_macro_longpress_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            macroLongPressStepsData = newSteps
                            draft["longPress"] = mapOf("macro" to newSteps)
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }
            }

            "CapsKey", "ReturnKey", "BackspaceKey" -> {
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }

                val swipeLabelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_swipe_label),
                    keyData["swipeLabel"] as? String ?: ""
                )
                nonMacroSwipeLabelEdit = swipeLabelEdit.second
                fieldsContainer.addView(swipeLabelEdit.first)

                val swipeAction = keyData["swipe"] as? Map<*, *>
                val swipeMacroSteps = (swipeAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()
                nonMacroSwipeStepsData = swipeMacroSteps
                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_swipe_event),
                    previewText = buildMacroPreview(swipeMacroSteps),
                    onClick = {
                        openMacroEditor(nonMacroSwipeStepsData, getString(R.string.text_keyboard_layout_macro_swipe_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            nonMacroSwipeStepsData = newSteps
                            if (newSteps.isNotEmpty()) {
                                draft["swipe"] = mapOf("macro" to newSteps)
                            } else {
                                draft.remove("swipe")
                            }
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }
            }

            "CommaKey", "LanguageKey", "SpaceKey" -> {
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }
            }

            "NumPadKey" -> {
                val labelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_label),
                    keyData["label"] as? String ?: "0"
                )
                numPadLabelEdit = labelEdit.second
                fieldsContainer.addView(labelEdit.first)
                val currentSym = when (val s = keyData["sym"]) {
                    is Number -> s.toInt()
                    is String -> LayoutJsonUtils.resolveKeysym(s) ?: 0
                    else -> 0
                }
                numPadSymSpinner = uiBuilder.createNumPadSymSpinner(fieldsContainer, currentSym)
                // Keep the displayed label in sync with the selected numpad symbol, unless the
                // user typed a custom label. Otherwise the preview would keep showing the old
                // label after switching the symbol.
                numPadSymSpinner?.onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: android.view.View?,
                            position: Int,
                            id: Long
                        ) {
                            val option = parent?.getItemAtPosition(position) as? KeyboardEditorUiBuilder.NumPadOption ?: return
                            val current = numPadLabelEdit?.text?.toString().orEmpty()
                            val synced = current.isEmpty() ||
                                KeyboardEditorUiBuilder.NUMPAD_OPTIONS.any { it.label == current }
                            if (synced) numPadLabelEdit?.setText(option.label)
                            updateActionButtonState()
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                            updateActionButtonState()
                        }
                    }
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }
            }

            "MiniSpaceKey" -> {
                if (!disableWeightEditing) {
                    val weightEdit = uiBuilder.createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }
            }
        }

        if (!composeOverrideEditorMode) {
            renderComposeOverrideEditorEntry()
        } else {
            renderFollowBaseKeyColorsToggle()
        }
        renderColorEditors()
        attachFieldWatchers(fieldsContainer)
        updateActionButtonState()
    }

    private fun renderComposeOverrideEditorEntry() {
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        val editButton = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_compose_override_edit_button)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(styledColor(android.R.attr.colorButtonNormal))
                setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { openComposeOverrideEditor() }
        }
        actions.addView(editButton)
        fieldsContainer.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val statusView = TextView(this).apply {
            text = buildComposeOverrideStatusPreview()
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }
        fieldsContainer.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun buildComposeOverrideStatusPreview(): String {
        return if (composeOverrideData == null) {
            getString(R.string.text_keyboard_layout_compose_override_not_configured)
        } else {
            getString(R.string.text_keyboard_layout_compose_override_configured)
        }
    }

    private fun renderFollowBaseKeyColorsToggle() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val label = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_compose_override_follow_base_key_colors)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        val switchView = SwitchCompat(this).apply {
            isChecked = independentColor
            setOnCheckedChangeListener { _, isChecked ->
                persistCurrentDraft()
                independentColor = isChecked
                rebuildFields()
                updateActionButtonState()
            }
        }
        row.addView(label)
        row.addView(switchView)
        fieldsContainer.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun openComposeOverrideEditor() {
        persistCurrentDraft()
        val hasOverride = composeOverrideData != null
        val editorData = (composeOverrideData ?: mutableMapOf()).toMutableMap()
        if (editorData["type"] == null) {
            editorData["type"] = selectedType
        }
        if (editorData["independentColor"] !is Boolean) {
            editorData["independentColor"] = false
        }
        val intent = Intent(this, KeyEditorActivity::class.java).apply {
            putExtra(EXTRA_KEY_DATA, toSerializableMap(editorData))
            putExtra(EXTRA_BASE_KEY_COLOR_DATA, toSerializableMap(keyColorOverrides))
            putExtra(EXTRA_ROW_INDEX, -1)
            putExtra(EXTRA_DISABLE_WEIGHT_EDITING, true)
            putExtra(EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE, true)
            putExtra(EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE, hasOverride)
            putExtra(EXTRA_TITLE_OVERRIDE, getString(R.string.text_keyboard_layout_compose_override_editor_title))
        }
        composeOverrideEditorLauncher.launch(intent)
    }

    private fun persistCurrentDraft() {
        keyData.clear()
        keyData.putAll(buildDraftKeyData())
    }

    private fun renderColorEditors() {
        val theme = ThemeManager.activeTheme
        val colorEditorEnabled = !composeOverrideEditorMode || independentColor
        availableColorFields().forEach { field ->
            val title = getString(field.labelRes)
            val colorSource = if (colorEditorEnabled) keyColorOverrides else inheritedBaseKeyColorData
            val customColor = parseColorInt(colorSource[field.customKey])
            val colorRef = colorSource[field.monetKey] as? String
            val modeText = when {
                colorRef != null -> formatColorReferenceName(colorRef)
                customColor != null -> formatAndroidColorCode(customColor)
                else -> getString(R.string.text_keyboard_layout_key_color_mode_theme)
            }
            val resolvedColor = resolveColorReference(theme, colorRef) ?: customColor ?: field.themeColorGetter(theme)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                background = resources.getDrawable(android.R.drawable.list_selector_background, null)
                if (colorEditorEnabled) {
                    setOnClickListener { showColorFieldOptions(field) }
                    isClickable = true
                    isFocusable = true
                } else {
                    isClickable = false
                    isFocusable = false
                    alpha = 0.55f
                }

                addView(
                    TextView(this@KeyEditorActivity).apply {
                        text = title
                        textSize = 13f
                        setTextColor(styledColor(android.R.attr.textColorSecondary))
                    },
                    LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(8)
                    }
                )

                addView(
                    LinearLayout(this@KeyEditorActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL

                        addView(
                            TextView(this@KeyEditorActivity).apply {
                                text = modeText
                                textSize = 13f
                                setTextColor(styledColor(android.R.attr.textColorPrimary))
                            },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                rightMargin = dp(8)
                            }
                        )

                        addView(
                            View(this@KeyEditorActivity).apply {
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    cornerRadius = dp(4).toFloat()
                                    setColor(resolvedColor)
                                }
                            },
                            LinearLayout.LayoutParams(dp(20), dp(20))
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        weight = 1f
                    }
                )
            }

            fieldsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun showColorFieldOptions(field: EditableColorField) {
        if (composeOverrideEditorMode && !independentColor) return
        val supportsMonet = ThemeMonet.supportsCustomMappingEditor(this)
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options += getString(R.string.text_keyboard_layout_key_color_mode_theme)
        actions += {
            persistCurrentDraft()
            setColorOverride(field, null, null)
            rebuildFields()
            updateActionButtonState()
        }

        options += getString(R.string.text_keyboard_layout_key_color_mode_custom)
        actions += { openCustomColorEditor(field) }

        options += getString(R.string.text_keyboard_layout_key_color_mode_theme_key_pick)
        actions += { openThemeColorTokenPicker(field) }

        if (supportsMonet) {
            options += getString(R.string.text_keyboard_layout_key_color_mode_monet_pick)
            actions += { openMonetColorPicker(field) }
        }
        AlertDialog.Builder(this)
            .setTitle(field.labelRes)
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .show()
    }

    private fun openThemeColorTokenPicker(field: EditableColorField) {
        val availableTokens = availableThemeTokensForField(field)
        if (availableTokens.isEmpty()) return

        val theme = ThemeManager.activeTheme
        val currentToken = (keyColorOverrides[field.monetKey] as? String)
            ?.takeIf { it.startsWith(THEME_COLOR_REF_PREFIX) }
            ?.removePrefix(THEME_COLOR_REF_PREFIX)
        val checkedIndex = availableTokens.indexOfFirst { it.token == currentToken }

        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_key_color_mode_theme_key_pick)
            .setSingleChoiceItems(
                ThemeColorTokenPreviewAdapter(this, availableTokens, theme),
                checkedIndex
            ) { dialog, which ->
                val token = availableTokens.getOrNull(which)?.token ?: return@setSingleChoiceItems
                persistCurrentDraft()
                setColorOverride(field, null, "$THEME_COLOR_REF_PREFIX$token")
                dialog.dismiss()
                rebuildFields()
                updateActionButtonState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openCustomColorEditor(field: EditableColorField) {
        persistCurrentDraft()
        val theme = ThemeManager.activeTheme
        val current = parseColorInt(keyColorOverrides[field.customKey])
            ?: resolveColorReference(theme, keyColorOverrides[field.monetKey] as? String)
            ?: resolveColorReference(theme, inheritedBaseKeyColorData[field.monetKey] as? String)
            ?: parseColorInt(inheritedBaseKeyColorData[field.customKey])
            ?: field.themeColorGetter(theme)
        if (DeviceUtil.isHMOS) {
            colorEditorLauncher.launch(
                ThemeColorEditorActivity.EditorInput(
                    fieldName = field.customKey,
                    titleRes = field.labelRes,
                    initialColor = current
                )
            )
            return
        }
        colorEditorLauncher.launch(
            ThemeColorEditorActivity.EditorInput(
                fieldName = field.customKey,
                titleRes = field.labelRes,
                initialColor = current
            )
        )
    }

    private fun openMonetColorPicker(field: EditableColorField) {
        if (!ThemeMonet.supportsCustomMappingEditor(this)) {
            Toast.makeText(this, R.string.text_keyboard_layout_key_color_monet_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val available = SystemColorResourceId.getAvailableForSdk(Build.VERSION.SDK_INT)
        val current = (keyColorOverrides[field.monetKey] as? String)
            ?.takeUnless { it.startsWith(THEME_COLOR_REF_PREFIX) }
            ?.let(SystemColorResourceId::fromResourceName)
            ?: available.firstOrNull()
            ?: return
        SystemColorResourcePickerDialog.show(
            this,
            current,
            listener = object : SystemColorResourcePickerDialog.OnColorResourceSelectedListener {
                override fun onColorResourceSelected(resourceId: SystemColorResourceId?) {
                    resourceId ?: return
                    persistCurrentDraft()
                    setColorOverride(field, null, resourceId.resourceId)
                    rebuildFields()
                    updateActionButtonState()
                }
            }
        )
    }

    private fun resolveMonetColor(resourceName: String?): Int? {
        val name = resourceName?.takeIf { it.isNotBlank() } ?: return null
        val colorResId = resources.getIdentifier(name, "color", "android")
        if (colorResId == 0) return null
        return runCatching { getColor(colorResId) }.getOrNull()
    }

    private fun resolveThemeTokenColor(theme: org.fcitx.fcitx5.android.data.theme.Theme, token: String?): Int? {
        val value = token?.takeIf { it.isNotBlank() } ?: return null
        return themeColorTokens.firstOrNull { it.token == value }?.resolver?.invoke(theme)
    }

    private fun resolveColorReference(theme: org.fcitx.fcitx5.android.data.theme.Theme, ref: String?): Int? {
        val value = ref?.takeIf { it.isNotBlank() } ?: return null
        return if (value.startsWith(THEME_COLOR_REF_PREFIX)) {
            resolveThemeTokenColor(theme, value.removePrefix(THEME_COLOR_REF_PREFIX))
        } else {
            resolveMonetColor(value)
        }
    }

    private fun formatColorReferenceName(ref: String): String {
        return if (ref.startsWith(THEME_COLOR_REF_PREFIX)) {
            val token = ref.removePrefix(THEME_COLOR_REF_PREFIX)
            getString(R.string.text_keyboard_layout_key_color_mode_theme_ref, formatThemeColorTokenName(token))
        } else {
            formatMonetResourceName(ref)
        }
    }

    private fun availableThemeTokensForField(field: EditableColorField): List<ThemeColorToken> {
        return themeColorTokens
    }

    private fun formatThemeColorTokenName(token: String): String {
        return themeColorTokens.firstOrNull { it.token == token }?.displayName
            ?: token.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }

    private class ThemeColorTokenPreviewAdapter(
        private val context: android.content.Context,
        private val tokens: List<ThemeColorToken>,
        private val theme: org.fcitx.fcitx5.android.data.theme.Theme
    ) : BaseAdapter() {
        override fun getCount(): Int = tokens.size
        override fun getItem(position: Int): Any = tokens[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                val colorPreview = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(context.dp(20), context.dp(20))
                }
                val nameText = TextView(context).apply {
                    setTextColor(context.styledColor(android.R.attr.textColorPrimary))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = context.dp(12)
                    }
                }
                addView(colorPreview)
                addView(nameText)
                tag = ThemeColorTokenRowHolder(colorPreview, nameText)
            }
            val holder = row.tag as ThemeColorTokenRowHolder
            val token = tokens[position]
            holder.colorPreview.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = context.dp(4).toFloat()
                setColor(token.resolver(theme))
            }
            holder.nameText.text = token.displayName
            return row
        }
    }

    private data class ThemeColorTokenRowHolder(
        val colorPreview: View,
        val nameText: TextView
    )

    private fun formatMonetResourceName(resourceName: String): String {
        return resourceName.removePrefix("system_").replace("_", " ")
    }

    private fun formatAndroidColorCode(color: Int): String {
        return String.format("#%08X", color)
    }

    private fun parseColorInt(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> {
                val raw = value.trim()
                when {
                    raw.startsWith("#") -> raw.removePrefix("#").toLongOrNull(16)?.toInt()
                    raw.startsWith("0x", ignoreCase = true) -> raw.removePrefix("0x").toLongOrNull(16)?.toInt()
                    else -> raw.toLongOrNull()?.toInt()
                }
            }
            else -> null
        }
    }

    private fun attachFieldWatchers(root: android.view.View) {
        when (root) {
            is EditText -> {
                root.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updateActionButtonState()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            is android.view.ViewGroup -> {
                for (i in 0 until root.childCount) {
                    attachFieldWatchers(root.getChildAt(i))
                }
            }
        }
    }

    private fun updateActionButtonState() {
        val changed = hasChanges()
        saveMenuItem?.isEnabled = changed
        saveMenuItem?.title = getString(R.string.save)
        saveMenuItem?.icon?.alpha = if (changed) 255 else 120
    }

    private fun hasChanges(): Boolean {
        return snapshotOf(buildDraftKeyData()) != baselineKeySnapshot
    }

    private fun buildDraftKeyData(): MutableMap<String, Any?> {
        val draft = mutableMapOf<String, Any?>()
        draft["type"] = selectedType

        when (selectedType) {
            "AlphabetKey" -> {
                val main = alphabetMainEdit?.text?.toString().orEmpty()
                val alt = alphabetAltEdit?.text?.toString().orEmpty()
                if (main.isNotEmpty()) draft["main"] = main
                if (alt.isNotEmpty()) draft["alt"] = alt
                if (!disableWeightEditing) {
                    parseWeight(alphabetWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }

                if (alphabetDisplayTextModeSpecific) {
                    val displayTextMap = mutableMapOf<String, String>()
                    alphabetDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        draft["displayText"] = displayTextMap
                    }
                } else {
                    val displayText = alphabetDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: alphabetDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        draft["displayText"] = displayText
                    }
                }
            }

            "LayoutSwitchKey" -> {
                val label = layoutSwitchLabelEdit?.text?.toString()?.ifEmpty { "?123" }.orEmpty()
                if (label.isNotEmpty()) draft["label"] = label
                val target = (layoutSwitchTargetSpinner?.selectedItem as? KeyboardEditorUiBuilder.SwitchTargetOption)
                    ?.value
                    .orEmpty()
                if (!target.isNullOrEmpty()) draft["subLabel"] = target
                val swipeLabel = nonMacroSwipeLabelEdit?.text?.toString()?.trim().orEmpty()
                if (swipeLabel.isNotEmpty()) draft["swipeLabel"] = swipeLabel
                if (!disableWeightEditing) {
                    parseWeight(layoutSwitchWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
                if (nonMacroSwipeStepsData.isNotEmpty()) {
                    draft["swipe"] = mapOf("macro" to nonMacroSwipeStepsData)
                }
            }

            "SymbolKey" -> {
                val label = symbolLabelEdit?.text?.toString()?.ifEmpty { "." }.orEmpty()
                if (label.isNotEmpty()) draft["label"] = label
                val swipeLabel = nonMacroSwipeLabelEdit?.text?.toString()?.trim().orEmpty()
                if (swipeLabel.isNotEmpty()) draft["swipeLabel"] = swipeLabel
                if (!disableWeightEditing) {
                    parseWeight(symbolWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
                if (nonMacroSwipeStepsData.isNotEmpty()) {
                    draft["swipe"] = mapOf("macro" to nonMacroSwipeStepsData)
                }
            }

            "MacroKey" -> {
                val baseLabel = macroLabelEdit?.text?.toString().orEmpty()
                if (baseLabel.isNotEmpty()) draft["label"] = baseLabel
                val altLabel = macroAltLabelEdit?.text?.toString().orEmpty()
                if (altLabel.isNotEmpty()) draft["altLabel"] = altLabel
                val longPressLabel = macroLongPressLabelEdit?.text?.toString().orEmpty()
                if (longPressLabel.isNotEmpty()) draft["longPressLabel"] = longPressLabel
                if (!disableWeightEditing) {
                    parseWeight(macroWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }

                if (macroDisplayTextModeSpecific) {
                    val displayTextMap = mutableMapOf<String, String>()
                    macroDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        draft["displayText"] = displayTextMap
                    }
                } else {
                    val displayText = macroDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: macroDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        draft["displayText"] = displayText
                    }
                }

                if (macroTapStepsData.isNotEmpty()) {
                    draft["tap"] = mapOf("macro" to macroTapStepsData)
                }

                if (macroSwipeStepsData.isNotEmpty()) {
                    draft["swipe"] = mapOf("macro" to macroSwipeStepsData)
                }

                if (macroLongPressStepsData.isNotEmpty()) {
                    draft["longPress"] = mapOf("macro" to macroLongPressStepsData)
                }
            }

            "CapsKey", "ReturnKey", "BackspaceKey" -> {
                val swipeLabel = nonMacroSwipeLabelEdit?.text?.toString()?.trim().orEmpty()
                if (swipeLabel.isNotEmpty()) draft["swipeLabel"] = swipeLabel
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
                if (nonMacroSwipeStepsData.isNotEmpty()) {
                    draft["swipe"] = mapOf("macro" to nonMacroSwipeStepsData)
                }
            }

            "CommaKey", "LanguageKey", "SpaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
            }

            "NumPadKey" -> {
                val label = numPadLabelEdit?.text?.toString()?.ifEmpty { "0" }.orEmpty()
                if (label.isNotEmpty()) draft["label"] = label
                (numPadSymSpinner?.selectedItem as? KeyboardEditorUiBuilder.NumPadOption)
                    ?.sym
                    ?.let { draft["sym"] = it }
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
            }

            "MiniSpaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
            }
        }
        if (!composeOverrideEditorMode) {
            parseRowHeightPercent(rowHeightPercentEdit?.text?.toString())?.let { draft["rowHeightPercent"] = it }
        }
        composeOverrideData?.let { draft["composeOverride"] = toSerializableMap(it) }
        if (composeOverrideEditorMode) {
            draft["independentColor"] = independentColor
        }

        appendColorOverrides(draft)

        return draft
    }

    private fun snapshotOf(value: Any?): String {
        val normalized = normalizeForSnapshot(value)
        return normalized.toString()
    }

    private fun normalizeForSnapshot(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> {
                val keys = value.keys.mapNotNull { it?.toString() }.sorted()
                keys.joinToString(prefix = "{", postfix = "}") { key ->
                    "$key=${normalizeForSnapshot(value[key])}"
                }
            }
            is List<*> -> value.map { normalizeForSnapshot(it) }
            is Float -> String.format(java.util.Locale.US, "%.6f", value)
            is Double -> String.format(java.util.Locale.US, "%.6f", value)
            is Array<*> -> Arrays.toString(value)
            else -> value
        }
    }

    private fun createMacroEditorButton(
        title: String,
        previewText: String,
        onClick: () -> Unit
    ): List<android.view.View> {
        val button = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(120)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(styledColor(android.R.attr.colorButtonNormal))
                setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }

        val preview = TextView(this).apply {
            text = previewText
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        return listOf(button, preview)
    }

    private fun openMacroEditor(
        initialSteps: List<*>,
        eventType: String,
        callback: (List<Any>) -> Unit
    ) {
        persistCurrentDraft()
        val intent = Intent(this, MacroEditorActivity::class.java)
        if (initialSteps.isNotEmpty()) {
            val serializableSteps = ArrayList<Map<*, *>>()
            initialSteps.forEach { step ->
                (step as? Map<*, *>)?.let { serializableSteps.add(it) }
            }
            if (serializableSteps.isNotEmpty()) {
                intent.putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS, serializableSteps)
            }
        }
        intent.putExtra(MacroEditorActivity.EXTRA_EVENT_TYPE, eventType)
        intent.putStringArrayListExtra(
            MacroEditorActivity.EXTRA_LAYOUT_TARGETS,
            ArrayList(availableLayoutTargets)
        )
        macroEditCallback = callback
        macroEditorLauncher.launch(intent)
    }

    private fun buildMacroPreview(macroSteps: List<*>?): String {
        if (macroSteps == null || macroSteps.isEmpty()) {
            return getString(R.string.text_keyboard_layout_macro_no_event)
        }

        val stepTexts = macroSteps.mapNotNull { step ->
            val stepMap = step as? Map<*, *>
            val type = stepMap?.get("type") as? String ?: return@mapNotNull null
            when (type) {
                "tap", "down", "up" -> {
                    val keys = stepMap["keys"] as? List<*>
                    val keysStr = keys?.mapNotNull { key ->
                        (key as? Map<*, *>)?.let {
                            it["fcitx"] as? String ?: it["android"] as? String
                        }
                    }?.joinToString(", ")
                    if (keysStr.isNullOrBlank()) null else "$type:[$keysStr]"
                }
                "edit" -> {
                    val action = stepMap["action"] as? String ?: return@mapNotNull null
                    "$type:$action"
                }
                "app" -> {
                    val id = stepMap["id"] as? String ?: return@mapNotNull null
                    "$type:$id"
                }
                "shortcut" -> {
                    val modifiers = (stepMap["modifiers"] as? List<*>)?.mapNotNull {
                        (it as? Map<*, *>)?.let { m ->
                            m["fcitx"] as? String ?: m["android"] as? String
                        }
                    } ?: emptyList()
                    val key = (stepMap["key"] as? Map<*, *>)?.let {
                        it["fcitx"] as? String ?: it["android"] as? String
                    } ?: return@mapNotNull null
                    "$type:${modifiers.joinToString("+")}+$key"
                }
                "text" -> {
                    val text = stepMap["text"] as? String ?: return@mapNotNull null
                    val displayText = if (text.length > 10) "${text.take(10)}..." else text
                    "$type:\"$displayText\""
                }
                "layer" -> {
                    val target = stepMap["target"] as? String ?: ""
                    val mode = (stepMap["mode"] as? String)?.uppercase() ?: "TO"
                    if (target.isBlank()) "layer($mode)" else "layer($mode):$target"
                }
                else -> type
            }
        }

        return if (stepTexts.size <= 3) {
            stepTexts.joinToString(" -> ")
        } else {
            getString(R.string.text_keyboard_layout_macro_event_preview, stepTexts.take(2).joinToString(" -> "), stepTexts.size)
        }
    }

    private fun saveAndFinish() {
        val result = validateAndSave(
            selectedType,
            alphabetMainEdit,
            alphabetAltEdit,
            alphabetWeightEdit,
            alphabetDisplayTextModeSpecific,
            alphabetDisplayTextSimpleEdit,
            alphabetDisplayTextSimpleValue,
            alphabetDisplayTextModeItems,
            alphabetDisplayTextRowBindings,
            layoutSwitchLabelEdit,
            layoutSwitchTargetSpinner,
            layoutSwitchWeightEdit,
            symbolLabelEdit,
            symbolWeightEdit,
            macroLabelEdit,
            macroDisplayTextModeSpecific,
            macroDisplayTextSimpleEdit,
            macroDisplayTextSimpleValue,
            macroDisplayTextModeItems,
            macroDisplayTextRowBindings,
            macroAltLabelEdit,
            macroLongPressLabelEdit,
            macroWeightEdit,
            simpleWeightEdit
        ) ?: return

        val data = Intent().apply {
            putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_SAVE)
            putExtra(EXTRA_RESULT_KEY_DATA, toSerializableMap(result))
            putExtra(EXTRA_ROW_INDEX, rowIndex)
            keyIndex?.let { putExtra(EXTRA_KEY_INDEX, it) }
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.text_keyboard_layout_delete_key_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val data = Intent().apply {
                    putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_DELETE)
                    putExtra(EXTRA_ROW_INDEX, rowIndex)
                    keyIndex?.let { putExtra(EXTRA_KEY_INDEX, it) }
                }
                setResult(RESULT_OK, data)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun initDisplayText(
        keyData: Map<String, Any?>,
        isEditingSubModeLayout: Boolean,
        currentSubModeLabel: String?,
        labelTextKey: String = "displayText",
        callback: (Boolean, String, MutableList<KeyboardEditorUiBuilder.DisplayTextItem>) -> Unit
    ) {
        val displayTextData = keyData[labelTextKey]
        val displayTextMap = when (displayTextData) {
            is JsonObject -> displayTextData.mapValues { entry ->
                LayoutJsonUtils.toAny(entry.value)
            }
            is Map<*, *> -> displayTextData
            else -> null
        }

        if (displayTextMap != null && displayTextMap.isNotEmpty()) {
            if (isEditingSubModeLayout && currentSubModeLabel != null) {
                val specificValue = displayTextMap[currentSubModeLabel]?.toString()
                val defaultValue = displayTextMap["default"]?.toString()
                    ?: displayTextMap[""]?.toString()
                callback(false, specificValue ?: defaultValue ?: "", mutableListOf())
            } else {
                val items = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
                displayTextMap.forEach { (k, v) ->
                    items.add(KeyboardEditorUiBuilder.DisplayTextItem(k?.toString().orEmpty(), v?.toString().orEmpty()))
                }
                callback(true, "", items)
            }
        } else {
            val labelTextSimple = keyData[labelTextKey] as? String
            if (!labelTextSimple.isNullOrBlank()) {
                callback(false, labelTextSimple, mutableListOf())
            } else {
                callback(false, "", mutableListOf())
            }
        }
    }

    private fun validateAndSave(
        selectedType: String,
        alphabetMainEdit: EditText?,
        alphabetAltEdit: EditText?,
        alphabetWeightEdit: EditText?,
        alphabetDisplayTextModeSpecific: Boolean,
        alphabetDisplayTextSimpleEdit: EditText?,
        alphabetDisplayTextSimpleValue: String,
        alphabetDisplayTextModeItems: List<KeyboardEditorUiBuilder.DisplayTextItem>,
        alphabetDisplayTextRowBindings: List<KeyboardEditorUiBuilder.DisplayTextRowBinding>,
        layoutSwitchLabelEdit: EditText?,
        layoutSwitchTargetSpinner: Spinner?,
        layoutSwitchWeightEdit: EditText?,
        symbolLabelEdit: EditText?,
        symbolWeightEdit: EditText?,
        macroLabelEdit: EditText?,
        macroDisplayTextModeSpecific: Boolean,
        macroDisplayTextSimpleEdit: EditText?,
        macroDisplayTextSimpleValue: String,
        macroDisplayTextModeItems: List<KeyboardEditorUiBuilder.DisplayTextItem>,
        macroDisplayTextRowBindings: List<KeyboardEditorUiBuilder.DisplayTextRowBinding>,
        macroAltLabelEdit: EditText?,
        macroLongPressLabelEdit: EditText?,
        macroWeightEdit: EditText?,
        simpleWeightEdit: EditText?
    ): MutableMap<String, Any?>? {
        if (selectedType == "AlphabetKey") {
            val main = alphabetMainEdit?.text?.toString()?.trim().orEmpty()
            val alt = alphabetAltEdit?.text?.toString()?.trim().orEmpty()

            if (main.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_alphabet_key_main_required, Toast.LENGTH_SHORT).show()
                return null
            }
            if (alt.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_alphabet_key_alt_required, Toast.LENGTH_SHORT).show()
                return null
            }
            if (main.length != 1) {
                Toast.makeText(this, getString(R.string.text_keyboard_layout_alphabet_key_main_length_invalid), Toast.LENGTH_SHORT).show()
                return null
            }
            if (alt.length != 1) {
                Toast.makeText(this, getString(R.string.text_keyboard_layout_alphabet_key_alt_length_invalid), Toast.LENGTH_SHORT).show()
                return null
            }
        }

        if (selectedType == "MacroKey") {
            val label = macroLabelEdit?.text?.toString()?.trim().orEmpty()
            if (label.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_macro_key_label_required, Toast.LENGTH_SHORT).show()
                return null
            }
            val longPressLabel = macroLongPressLabelEdit?.text?.toString()?.trim().orEmpty()
            if (macroLongPressStepsData.isNotEmpty() && longPressLabel.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_longpress_label_fallback_notice, Toast.LENGTH_SHORT).show()
            }
        }

        if (!composeOverrideEditorMode) {
            val rawRowHeight = rowHeightPercentEdit?.text?.toString()?.trim().orEmpty()
            if (rawRowHeight.isNotEmpty() && parseRowHeightPercent(rawRowHeight) == null) {
                Toast.makeText(this, R.string.text_keyboard_layout_row_height_percent_invalid, Toast.LENGTH_SHORT).show()
                return null
            }
        }

        val newKey = mutableMapOf<String, Any?>()
        newKey["type"] = selectedType

        when (selectedType) {
            "AlphabetKey" -> {
                newKey["main"] = alphabetMainEdit?.text?.toString().orEmpty()
                newKey["alt"] = alphabetAltEdit?.text?.toString().orEmpty()
                if (!disableWeightEditing) {
                    parseWeight(alphabetWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }

                if (alphabetDisplayTextModeSpecific) {
                    val displayTextMap = mutableMapOf<String, String>()
                    alphabetDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        newKey["displayText"] = displayTextMap
                    }
                } else {
                    val displayText = alphabetDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: alphabetDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        newKey["displayText"] = displayText
                    }
                }
            }

            "LayoutSwitchKey" -> {
                newKey["label"] = layoutSwitchLabelEdit?.text?.toString()?.ifEmpty { "?123" }.orEmpty()
                val target = (layoutSwitchTargetSpinner?.selectedItem as? KeyboardEditorUiBuilder.SwitchTargetOption)
                    ?.value
                    .orEmpty()
                if (!target.isNullOrEmpty()) newKey["subLabel"] = target
                val swipeLabel = nonMacroSwipeLabelEdit?.text?.toString()?.trim().orEmpty()
                if (swipeLabel.isNotEmpty()) newKey["swipeLabel"] = swipeLabel
                if (!disableWeightEditing) {
                    parseWeight(layoutSwitchWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
                if (nonMacroSwipeStepsData.isNotEmpty()) {
                    newKey["swipe"] = mapOf("macro" to nonMacroSwipeStepsData)
                }
            }

            "SymbolKey" -> {
                newKey["label"] = symbolLabelEdit?.text?.toString()?.ifEmpty { "." }.orEmpty()
                val swipeLabel = nonMacroSwipeLabelEdit?.text?.toString()?.trim().orEmpty()
                if (swipeLabel.isNotEmpty()) newKey["swipeLabel"] = swipeLabel
                if (!disableWeightEditing) {
                    parseWeight(symbolWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
                if (nonMacroSwipeStepsData.isNotEmpty()) {
                    newKey["swipe"] = mapOf("macro" to nonMacroSwipeStepsData)
                }
            }

            "MacroKey" -> {
                val baseLabel = macroLabelEdit?.text?.toString().orEmpty()
                newKey["label"] = baseLabel
                val altLabel = macroAltLabelEdit?.text?.toString().orEmpty()
                if (altLabel.isNotEmpty()) newKey["altLabel"] = altLabel
                val longPressLabel = macroLongPressLabelEdit?.text?.toString().orEmpty()
                if (longPressLabel.isNotEmpty()) newKey["longPressLabel"] = longPressLabel
                if (!disableWeightEditing) {
                    parseWeight(macroWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }

                if (macroDisplayTextModeSpecific) {
                    val displayTextMap = mutableMapOf<String, String>()
                    macroDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        newKey["displayText"] = displayTextMap
                    }
                } else {
                    val displayText = macroDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: macroDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        newKey["displayText"] = displayText
                    }
                }

                if (macroTapStepsData.isNotEmpty()) {
                    newKey["tap"] = mapOf("macro" to macroTapStepsData)
                } else {
                    newKey["tap"] = mapOf(
                        "macro" to listOf(
                            mapOf("type" to "text", "text" to "")
                        )
                    )
                }

                if (macroSwipeStepsData.isNotEmpty()) {
                    newKey["swipe"] = mapOf("macro" to macroSwipeStepsData)
                }

                if (macroLongPressStepsData.isNotEmpty()) {
                    newKey["longPress"] = mapOf("macro" to macroLongPressStepsData)
                }
            }

            "CapsKey", "ReturnKey", "BackspaceKey" -> {
                val swipeLabel = nonMacroSwipeLabelEdit?.text?.toString()?.trim().orEmpty()
                if (swipeLabel.isNotEmpty()) newKey["swipeLabel"] = swipeLabel
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
                if (nonMacroSwipeStepsData.isNotEmpty()) {
                    newKey["swipe"] = mapOf("macro" to nonMacroSwipeStepsData)
                }
            }

            "CommaKey", "LanguageKey", "SpaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
            }

            "NumPadKey" -> {
                newKey["label"] = numPadLabelEdit?.text?.toString()?.ifEmpty { "0" }.orEmpty()
                (numPadSymSpinner?.selectedItem as? KeyboardEditorUiBuilder.NumPadOption)
                    ?.sym
                    ?.let { newKey["sym"] = it }
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
            }

            "MiniSpaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
            }
        }
        if (!composeOverrideEditorMode) {
            parseRowHeightPercent(rowHeightPercentEdit?.text?.toString())?.let { newKey["rowHeightPercent"] = it }
        }
        composeOverrideData?.let { newKey["composeOverride"] = toSerializableMap(it) }
        if (composeOverrideEditorMode) {
            newKey["independentColor"] = independentColor
        }

        appendColorOverrides(newKey)

        return newKey
    }

    private fun appendColorOverrides(target: MutableMap<String, Any?>) {
        if (composeOverrideEditorMode && !independentColor) return
        availableColorFields().forEach { field ->
            val monet = (keyColorOverrides[field.monetKey] as? String)?.takeIf { it.isNotBlank() }
            if (monet != null) {
                target[field.monetKey] = monet
            } else {
                parseColorInt(keyColorOverrides[field.customKey])?.let { target[field.customKey] = it }
            }
        }
    }

    private fun setColorOverride(field: EditableColorField, customColor: Int?, colorRef: String?) {
        keyColorOverrides.remove(field.customKey)
        keyColorOverrides.remove(field.monetKey)
        keyData.remove(field.customKey)
        keyData.remove(field.monetKey)
        when {
            colorRef != null -> {
                keyColorOverrides[field.monetKey] = colorRef
                keyData[field.monetKey] = colorRef
            }
            customColor != null -> {
                keyColorOverrides[field.customKey] = customColor
                keyData[field.customKey] = customColor
            }
        }
    }

    private fun extractColorOverrides(source: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val colors = LinkedHashMap<String, Any?>()
        editableColorFields.forEach { field ->
            source[field.customKey]?.let { colors[field.customKey] = it }
            source[field.monetKey]?.let { colors[field.monetKey] = it }
        }
        return colors
    }

    private fun restoreColorOverrides(colors: Map<String, Any?>) {
        editableColorFields.forEach { field ->
            keyData.remove(field.customKey)
            keyData.remove(field.monetKey)
        }
        keyColorOverrides = LinkedHashMap(colors)
        colors.forEach { (key, value) ->
            keyData[key] = value
        }
    }

    private fun availableColorFields(): List<EditableColorField> {
        return editableColorFields.filter { field ->
            field.supportedTypes?.contains(selectedType) ?: true
        }
    }

    private fun parseWeight(text: String?): Float? {
        val weight = text?.toFloatOrNull()
        return weight?.takeIf { it in 0.0f..1.0f }
    }

    private fun parseRowHeightPercent(text: String?): Float? {
        val value = text?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
            ?.toFloatOrNull()
        return value?.takeIf { it in 1f..100f }
    }

    companion object {
        private const val THEME_COLOR_REF_PREFIX = "theme:"
        private const val MENU_SAVE_ID = 5001
        private const val MENU_DELETE_ID = 5002

        const val EXTRA_KEY_DATA = "key_data"
        const val EXTRA_ROW_INDEX = "row_index"
        const val EXTRA_KEY_INDEX = "key_index"
        const val EXTRA_IS_EDITING_SUBMODE_LAYOUT = "is_editing_submode_layout"
        const val EXTRA_CURRENT_SUBMODE_LABEL = "current_submode_label"
        const val EXTRA_HAS_MULTI_SUBMODE_SUPPORT = "has_multi_submode_support"
        const val EXTRA_AVAILABLE_LAYOUT_TARGETS = "available_layout_targets"
        const val EXTRA_LOCK_TYPE_SELECTION = "lock_type_selection"
        const val EXTRA_DISABLE_WEIGHT_EDITING = "disable_weight_editing"
        const val EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE = "compose_override_editor_mode"
        const val EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE = "has_initial_compose_override"
        const val EXTRA_FIXED_TYPE = "fixed_type"
        const val EXTRA_TITLE_OVERRIDE = "title_override"
        private const val EXTRA_BASE_KEY_COLOR_DATA = "base_key_color_data"

        const val EXTRA_RESULT_ACTION = "result_action"
        const val EXTRA_RESULT_KEY_DATA = "result_key_data"

        const val RESULT_ACTION_SAVE = "save"
        const val RESULT_ACTION_DELETE = "delete"

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun toSerializableMap(input: Map<String, Any?>): LinkedHashMap<String, Any?> {
            return normalizeValue(input) as LinkedHashMap<String, Any?>
        }

        private fun normalizeValue(value: Any?): Any? {
            return when (value) {
                is Map<*, *> -> {
                    val normalized = LinkedHashMap<String, Any?>()
                    value.forEach { (k, v) ->
                        val key = k?.toString() ?: return@forEach
                        normalized[key] = normalizeValue(v)
                    }
                    normalized
                }
                is List<*> -> {
                    val normalized = ArrayList<Any?>()
                    value.forEach { item -> normalized.add(normalizeValue(item)) }
                    normalized
                }
                is String, is Number, is Boolean, null -> value
                else -> value.toString()
            }
        }

        @Suppress("DEPRECATION")
        private inline fun <reified T : Serializable> serializableExtraCompat(intent: Intent, key: String): T? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(key, T::class.java)
            } else {
                intent.getSerializableExtra(key) as? T
            }
        }
    }
}
