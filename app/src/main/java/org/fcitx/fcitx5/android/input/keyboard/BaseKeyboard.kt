/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.SparseIntArray
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.graphics.ColorUtils
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.AuxBarAction
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.SwipeAxis
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.utils.DeviceInfoCollector
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable

// Import Macro types
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

abstract class BaseKeyboard(
    context: Context,
    protected var theme: Theme,
    private val layoutProvider: () ->List<List<KeyDef>>,
    private val auxBarConfigProvider: () -> AuxBarConfig? = { null },
    private val auxBarKeysProvider: () -> List<KeyDef> = { emptyList() }
) : ConstraintLayout(context) {

    private val keyLayout: List<List<KeyDef>>
        get() = layoutProvider()
    private val auxBarConfig: AuxBarConfig?
        get() = auxBarConfigProvider()
    private val auxBarKeyDefs: List<KeyDef>
        get() = auxBarKeysProvider()
    var keyActionListener: KeyActionListener? = null
    var auxBarListener: ((List<AuxBarAction>, List<AuxBarAction>) -> Unit)? = null

    private val prefs = AppPrefs.getInstance()
    private val splitKeyboardManager = SplitKeyboardStateManager.getInstance()
    private val cachedMacroScancodes = SparseIntArray(64)
    private val shiftKeyCode = KeyEvent.KEYCODE_SHIFT_LEFT
    private val shiftScanCode by lazy { mapFcitxToScanCode("Shift_L", shiftKeyCode) }

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach {
            it.swipeEnabled = v
        }
    }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val bounds = Rect()
    private val childLocationInWindow = intArrayOf(0, 0)
    private lateinit var keyRows: List<ConstraintLayout>
    private var keyboardWaterRippleView: KeyboardWaterRippleView? = null
    private var cachedWaterRippleColor: Int? = null
    private var rowHeightPercents: List<Float> = emptyList()
    private var horizontalGapScale = 1f
    private var composing = false

    // Edge zone views
    private var auxBarInnerContainer: ConstraintLayout? = null
    private var auxBarScrollableAdapter: AuxBarAdapter? = null
    private var auxBarPinnedAdapter: AuxBarAdapter? = null
    private var auxBarScrollableRv: RecyclerView? = null
    private var auxBarKeyAdapter: AuxBarKeyAdapter? = null
    private var mainGridContainer: ConstraintLayout? = null

    private data class GestureBaseline(
        val swipeEnabled: Boolean,
        val swipeRepeatEnabled: Boolean,
        val swipeThresholdX: Float,
        val swipeThresholdY: Float,
        val onGestureListener: OnGestureListener?
    )

    private data class ComposeAwareKey(
        val baseDef: KeyDef,
        var keyView: KeyView,
        var baseline: GestureBaseline
    )

    private val composeAwareKeys = mutableListOf<ComposeAwareKey>()

    private data class ReusableRows(
        val defs: List<List<KeyDef>>,
        val containers: List<ConstraintLayout>
    )

    // Rows built by a previous reload are cached per layout/style signature and reused on
    // the next reload with the same signature. Rebuilding the whole view tree on every key
    // press (macrokey "layer to" or shift-based language switching) is the dominant source
    // of input latency; reusing prebuilt rows skips the expensive view construction entirely.
    // NOTE: must be declared before the init block, which triggers the first reloadLayout().
    private val reusableRowsCache = HashMap<String, ReusableRows>()
    private var lastRowsSignature: String? = null

    private var lastSplitLandscapeState = false

    @Keep
    private val splitStateChangeListener = SplitKeyboardStateManager.OnSplitStateChangeListener { shouldSplit ->
        // Only reload if split state actually changed
        if (shouldSplit != lastSplitLandscapeState) {
            lastSplitLandscapeState = shouldSplit
            reloadLayout()
            reapplyTextScale()
            requestLayout()
            updateBounds()
        }
    }

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [KeyView]
     */
    private val touchTarget = hashMapOf<Int, View>()

    /**
     * Find a key view by its type tag. Returns the first matching view or null if not found.
     */
    protected inline fun <reified T : View> findKeyViewById(tagId: Int): T? {
        return allViews.firstOrNull { it.tag == tagId && it is T } as? T
    }

    init {
        isMotionEventSplittingEnabled = true
        clipChildren = false
        clipToPadding = false
        reloadLayout()
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        splitKeyboardManager.registerListener(splitStateChangeListener)
    }

    /**
     * Signature identifying the semantic keyboard content (layer, input method, sub mode, ...).
     * Subclasses rendering different KeyDef sets must override this so cached rows are never
     * reused across different layouts.
     */
    protected open fun currentLayoutSignature(): String = ""

    private fun currentRowsSignature(splitKeyboard: Boolean): String {
        val prefs = ThemeManager.prefs
        return buildString {
            append(currentLayoutSignature())
            append("|split:").append(splitKeyboard)
            append("|orient:").append(resources.configuration.orientation)
            append("|composing:").append(composing)
            append("|gapScale:").append(horizontalGapScale)
            append("|border:").append(prefs.keyBorder.getValue()).append(prefs.keyBorderStroke.getValue())
            append("|ripple:").append(prefs.keyRippleEffect.getValue())
            append("|radius:").append(prefs.keyRadius.getValue())
            append("|oval:").append(prefs.specialKeyOvalShape.getValue())
            append("|hMargins:").append(prefs.keyHorizontalMargin.getValue())
                .append(",").append(prefs.keyHorizontalMarginLandscape.getValue())
            append("|vMargins:").append(prefs.keyVerticalMargin.getValue())
                .append(",").append(prefs.keyVerticalMarginLandscape.getValue())
            append("|fontRefresh:").append(FontProviders.needsRefresh())
        }
    }

    /**
     * Re-register keyboard-level state for a reused row set. KeyViews keep the gesture
     * configuration they were built with, so only the registries rebuilt on every reload
     * (space keys, compose-aware keys) need to be repopulated.
     */
    private fun registerReusableRowState(rows: List<List<KeyDef>>, containers: List<ConstraintLayout>) {
        rows.forEachIndexed { rowIndex, rowDefs ->
            val container = containers.getOrNull(rowIndex) ?: return@forEachIndexed
            val keyViews = container.children.mapNotNull { it as? KeyView }.toList()
            rowDefs.forEachIndexed { keyIndex, def ->
                val keyView = keyViews.getOrNull(keyIndex) ?: return@forEachIndexed
                // Only register when the reused view really belongs to this def;
                // when in doubt, skip to avoid wiring the wrong def to the view.
                val matchesDef = keyView.def === def.appearance ||
                    def.composeOverride?.appearance === keyView.def
                if (!matchesDef) return@forEachIndexed
                if (def is SpaceKey || def is MiniSpaceKey) {
                    if (!spaceKeys.contains(keyView)) spaceKeys.add(keyView)
                }
                if (def.composeOverride != null) {
                    composeAwareKeys += ComposeAwareKey(
                        def,
                        keyView,
                        GestureBaseline(
                            swipeEnabled = keyView.swipeEnabled,
                            swipeRepeatEnabled = keyView.swipeRepeatEnabled,
                            swipeThresholdX = keyView.swipeThresholdX,
                            swipeThresholdY = keyView.swipeThresholdY,
                            onGestureListener = keyView.onGestureListener
                        )
                    )
                }
            }
        }
    }

    protected open fun reloadLayout() {
        val startedAt = SystemClock.elapsedRealtime()
        // Detach ripple-occluder listeners from views of the outgoing tree before it is
        // discarded or cached; the ripple view itself is recreated on every reload.
        keyboardWaterRippleView?.setOccluders(emptyList())
        removeAllViews()
        auxBarInnerContainer = null
        auxBarScrollableAdapter = null
        auxBarPinnedAdapter = null
        auxBarScrollableRv = null
        auxBarKeyAdapter = null
        mainGridContainer = null
        cachedWaterRippleColor = null
        keyboardWaterRippleView = KeyboardWaterRippleView(context).also { rippleView ->
            add(rippleView, lParams(matchParent, matchParent))
        }
        spaceKeys.clear()
        touchTarget.clear()
        composeAwareKeys.clear()

        val splitKeyboard = splitKeyboardManager.shouldUseSplitKeyboard(width)
        lastSplitLandscapeState = splitKeyboard
        val rows = keyLayout
        rowHeightPercents = resolveRowHeightPercents(rows)

        val rowsSignature = currentRowsSignature(splitKeyboard)
        val cachedRows = reusableRowsCache[rowsSignature]
        // Reuse only when the cached rows were built from the exact same KeyDef instances;
        // providers that re-create defs on every call (e.g. the builtin fallback layout)
        // then rebuild instead of silently re-registering mismatched state.
        keyRows = if (cachedRows != null && cachedRows.defs === rows) {
            registerReusableRowState(rows, cachedRows.containers)
            cachedRows.containers
        } else {
            val built = rows.map { row ->
                val keyViews = row.map(::createKeyView).apply {
                    // Batch apply fontset mappings for all key labels.
                    forEach(::applyConfiguredFonts)
                }
                if (splitKeyboard) {
                    buildSplitRow(row, keyViews)
                } else {
                    buildRegularRow(row, keyViews)
                }
            }
            if (reusableRowsCache.size >= 12) {
                reusableRowsCache.clear()
            }
            reusableRowsCache[rowsSignature] = ReusableRows(rows, built)
            built
        }
        lastRowsSignature = rowsSignature

        val auxBarConfig = auxBarConfig
        if (auxBarConfig != null && auxBarConfig.position != AuxBarPosition.AbovePreedit) {
            val isVertical = auxBarConfig.position == AuxBarPosition.Left || auxBarConfig.position == AuxBarPosition.Right
            val keyPrefs = ThemeManager.prefs
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val vMargin = dp(
                (if (isLandscape) keyPrefs.keyVerticalMarginLandscape else keyPrefs.keyVerticalMargin).getValue()
            )
            val hMargin = dp(
                (if (isLandscape) keyPrefs.keyHorizontalMarginLandscape else keyPrefs.keyHorizontalMargin).getValue()
            )
            val auxBarInnerLayout = constraintLayout()
            val scrollableRv = recyclerView {
                layoutManager = LinearLayoutManager(context).apply {
                    orientation = if (isVertical) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL
                }
                overScrollMode = OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }
            // Use the dominant text size in the current layout instead of the first
            // textual key, which may be a small special key.
            val keyTextSize = rows.asSequence()
                .flatMap { it.asSequence() }
                .mapNotNull { (it.appearance as? KeyDef.Appearance.Text)?.textSize }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: 16f
            val scrollableAdapter = AuxBarAdapter(theme, vMargin, hMargin, auxBarConfig.position, keyTextSize) { id ->
                keyActionListener?.onKeyAction(KeyAction.AuxBarTrigger(id), KeyActionListener.Source.Keyboard)
            }
            scrollableRv.adapter = scrollableAdapter
            val keyAdapter = AuxBarKeyAdapter(
                vertical = isVertical,
                keyViewFactory = { def ->
                    createKeyView(def, registerComposeAware = false).also(::applyConfiguredFonts)
                }
            )
            val pinnedRv = recyclerView {
                layoutManager = LinearLayoutManager(context).apply {
                    orientation = if (isVertical) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL
                }
                isNestedScrollingEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }
            val pinnedAdapter = AuxBarAdapter(theme, vMargin, hMargin, auxBarConfig.position, keyTextSize) { id ->
                keyActionListener?.onKeyAction(KeyAction.AuxBarTrigger(id), KeyActionListener.Source.Keyboard)
            }
            pinnedRv.adapter = pinnedAdapter

            if (isVertical) {
                auxBarInnerLayout.add(scrollableRv, lParams {
                    topOfParent()
                    centerHorizontally()
                    above(pinnedRv)
                })
                auxBarInnerLayout.add(pinnedRv, lParams(height = wrapContent) {
                    bottomOfParent()
                    centerHorizontally()
                    topMargin = vMargin
                })
            } else {
                auxBarInnerLayout.add(scrollableRv, lParams {
                    topOfParent()
                    bottomOfParent()
                    leftOfParent()
                    rightToLeftOf(pinnedRv)
                })
                auxBarInnerLayout.add(pinnedRv, lParams(width = wrapContent) {
                    topOfParent()
                    bottomOfParent()
                    rightOfParent()
                    leftMargin = hMargin
                })
            }

            val mainGrid = constraintLayout()
            keyRows.forEachIndexed { index, row ->
                mainGrid.add(row, lParams {
                    height = 0
                    verticalWeight = rowHeightPercents.getOrElse(index) { 1f }
                    if (index == 0) topOfParent()
                    else below(keyRows[index - 1])
                    if (index == keyRows.size - 1) bottomOfParent()
                    else above(keyRows[index + 1])
                    centerHorizontally()
                })
            }

            val sizeFraction = auxBarConfig.sizePercent / 100f
            when (auxBarConfig.position) {
                AuxBarPosition.Left -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentWidth = sizeFraction
                        topOfParent(); bottomOfParent(); leftOfParent()
                    })
                    add(mainGrid, lParams {
                        topOfParent(); bottomOfParent(); rightOfParent()
                        leftToRightOf(auxBarInnerLayout)
                    })
                }
                AuxBarPosition.Right -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentWidth = sizeFraction
                        topOfParent(); bottomOfParent(); rightOfParent()
                    })
                    add(mainGrid, lParams {
                        topOfParent(); bottomOfParent(); leftOfParent()
                        rightToLeftOf(auxBarInnerLayout)
                    })
                }
                AuxBarPosition.Top -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentHeight = sizeFraction
                        topOfParent(); leftOfParent(); rightOfParent()
                    })
                    add(mainGrid, lParams {
                        bottomOfParent(); leftOfParent(); rightOfParent()
                        below(auxBarInnerLayout)
                    })
                }
                AuxBarPosition.Bottom -> {
                    add(auxBarInnerLayout, lParams {
                        matchConstraintPercentHeight = sizeFraction
                        bottomOfParent(); leftOfParent(); rightOfParent()
                    })
                    add(mainGrid, lParams {
                        topOfParent(); leftOfParent(); rightOfParent()
                        above(auxBarInnerLayout)
                    })
                }
            }
            auxBarInnerContainer = auxBarInnerLayout
            auxBarScrollableAdapter = scrollableAdapter
            auxBarPinnedAdapter = pinnedAdapter
            auxBarScrollableRv = scrollableRv
            auxBarKeyAdapter = keyAdapter
            mainGridContainer = mainGrid
            // Keep aux bar usable before first candidate update (e.g. in preview or idle startup):
            // if no tabs are available, show configured fallback keys immediately.
            applyAuxBarContent(emptyList(), emptyList())
            keyRows.firstOrNull()?.let { firstRow ->
                post {
                    val rowHeight = firstRow.height
                    if (rowHeight > 0) {
                        val visualHeight = rowHeight - 2 * vMargin
                        if (visualHeight > 0) {
                            scrollableAdapter.setMinItemHeight(visualHeight)
                            pinnedAdapter.setMinItemHeight(visualHeight)
                        }
                        if (rowHeight > 0) {
                            // KeyView already includes key vertical margins in its own drawing logic,
                            // so use full row height to keep aux bar key height aligned with normal rows.
                            keyAdapter.setMinItemHeight(rowHeight)
                        }
                    }
                    auxBarScrollableAdapter?.applyConfiguredFonts(scrollableRv)
                    auxBarPinnedAdapter?.applyConfiguredFonts(pinnedRv)
                }
            }
        } else {
            keyRows.forEachIndexed { index, row ->
                add(row, lParams {
                    height = 0
                    verticalWeight = rowHeightPercents.getOrElse(index) { 1f }
                    if (index == 0) topOfParent()
                    else below(keyRows[index - 1])
                    if (index == keyRows.size - 1) bottomOfParent()
                    else above(keyRows[index + 1])
                    centerHorizontally()
                })
            }
        }

        keyboardWaterRippleView?.setOccluders(
            keyRows.flatMap { row -> row.children.mapNotNull { it as? KeyView }.toList() }
        )
        Log.i(
            "FcitxColdStart",
            "${javaClass.simpleName}.reloadLayout rows=${keyRows.size} " +
                "duration=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
    }

    fun updateAuxBarActions(actions: List<AuxBarAction>) {
        val scrollable = actions.takeWhile { !it.isSeparator }
        val pinned = actions.drop(scrollable.size + 1).filter { !it.isSeparator }
        val cfg = auxBarConfig
        if (cfg?.position == AuxBarPosition.AbovePreedit) {
            auxBarListener?.invoke(scrollable, pinned)
            return
        }
        if (cfg != null) {
            auxBarListener?.invoke(emptyList(), emptyList())
            applyAuxBarContent(scrollable, pinned)
        }
    }

    private fun applyAuxBarContent(scrollable: List<AuxBarAction>, pinned: List<AuxBarAction>) {
        val cfg = auxBarConfig ?: return
        val customKeys = auxBarKeyDefs
        val hasTabs = scrollable.isNotEmpty() || pinned.isNotEmpty()
        val maxKeyCount = when (cfg.position) {
            AuxBarPosition.Left, AuxBarPosition.Right -> keyRows.size
            else -> Int.MAX_VALUE
        }
        val visibleCustomKeys = customKeys.take(maxKeyCount)
        if (!hasTabs && visibleCustomKeys.isNotEmpty() && auxBarKeyAdapter != null) {
            // No tabs: fill the aux bar with the user-configured custom keys.
            // For left/right aux bar, clamp count to keyboard row count.
            val rv = auxBarScrollableRv
            if (rv?.adapter != auxBarKeyAdapter) {
                rv?.adapter = auxBarKeyAdapter
            }
            auxBarKeyAdapter?.updateKeys(visibleCustomKeys)
            auxBarPinnedAdapter?.updateActions(emptyList())
        } else {
            val rv = auxBarScrollableRv
            if (rv?.adapter != auxBarScrollableAdapter) {
                rv?.adapter = auxBarScrollableAdapter
            }
            auxBarScrollableAdapter?.updateActions(scrollable)
            auxBarPinnedAdapter?.updateActions(pinned)
        }
    }

    fun auxBarPosition(): AuxBarPosition? = auxBarConfig?.position

    private fun resolveRowHeightPercents(rows: List<List<KeyDef>>): List<Float> {
        if (rows.isEmpty()) return emptyList()

        val parsedPercents = rows.map { row ->
            row.mapNotNull { it.rowHeightPercent }
                .maxOrNull()
                ?.takeIf { it in 1f..100f }
        }
        val definedSum = parsedPercents.filterNotNull().sum()
        val undefinedCount = parsedPercents.count { it == null }

        val distributed = if (undefinedCount == 0) {
            parsedPercents.map { it ?: 0f }
        } else {
            val remaining = (100f - definedSum).coerceAtLeast(0f)
            val avg = remaining / undefinedCount
            parsedPercents.map { it ?: avg }
        }

        val sum = distributed.sum()
        if (sum <= 0f) {
            val fallback = defaultRowHeightPercent(rows.size)
            return List(rows.size) { fallback }
        }

        return distributed.map { it * 100f / sum }
    }

    protected open fun defaultRowHeightPercent(rowCount: Int): Float {
        if (rowCount <= 0) return 25f
        return (100f / rowCount.toFloat()).coerceAtLeast(1f)
    }

    open fun keyboardHeightScaleFactor(): Float {
        if (rowHeightPercents.isEmpty()) return 1f
        return (rowHeightPercents.sum() / 100f).coerceAtLeast(0.1f)
    }

    open fun preferredKeyboardHeightPercentOverride(): Int? = null

    private fun splitGapPercent(): Float {
        return splitKeyboardManager.getSplitGapPercent()
    }

    private fun resolveRowWidths(row: List<KeyDef>): List<Float> {
        if (row.isEmpty()) return emptyList()
        val fixedSum = row.sumOf { def ->
            val width = def.appearance.percentWidth
            if (width > 0f) width.toDouble() else 0.0
        }.toFloat()
        val flexCount = row.count { it.appearance.percentWidth <= 0f }
        val remaining = (1f - fixedSum).coerceAtLeast(0f)
        val flexWidth = if (flexCount > 0) remaining / flexCount else 0f
        val widths = row.map { def ->
            val width = def.appearance.percentWidth
            if (width > 0f) width else flexWidth
        }
        val sum = widths.sum()
        return if (sum > 0f) {
            widths.map { it / sum }
        } else {
            val equal = 1f / widths.size
            widths.map { equal }
        }
    }

    private fun chooseSplitIndex(row: List<KeyDef>, normalizedWidths: List<Float>): Int {
        if (row.size <= 1) return 0
        val candidates = (0 until row.lastIndex)
        var prefix = 0f
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        candidates.forEach { i ->
            prefix += normalizedWidths[i]
            val distance = kotlin.math.abs(prefix - 0.5f)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }

        val spaceIndices = row.mapIndexedNotNull { index, def ->
            if (def is SpaceKey || def is MiniSpaceKey) index else null
        }.filter { it in 1 until row.lastIndex }
        if (spaceIndices.isNotEmpty()) {
            var running = 0f
            val prefixByBoundary = HashMap<Int, Float>(row.size)
            for (i in 0 until row.lastIndex) {
                running += normalizedWidths[i]
                prefixByBoundary[i] = running
            }
            spaceIndices.forEach { spaceIndex ->
                val aroundSpaceCandidates = listOf(spaceIndex - 1, spaceIndex)
                    .filter { it in 0 until row.lastIndex }
                aroundSpaceCandidates.forEach { index ->
                    val p = prefixByBoundary[index] ?: return@forEach
                    val d = kotlin.math.abs(p - 0.5f)
                    if (d <= bestDistance + 0.06f) {
                        bestDistance = d
                        bestIndex = index
                    }
                }
            }
        }
        return bestIndex
    }

    private fun buildSplitRow(row: List<KeyDef>, keyViews: List<KeyView>): ConstraintLayout = constraintLayout {
        clipChildren = false
        clipToPadding = false
        keyViews.forEach { keyView ->
            keyView.onWaterRippleRequest = { view, _, _ ->
                val cx = (view.parent as? View)?.x.orZero() + view.x + view.width * 0.5f
                val cy = (view.parent as? View)?.y.orZero() + view.y + view.height * 0.5f
                val radius = waterRippleRadiusPx(view)
                keyboardWaterRippleView?.startRipple(cx, cy, waterRippleColor(), radius)
            }
        }
        if (row.isEmpty()) return@constraintLayout
        val gap = splitGapPercent()
        val normalizedWidths = resolveRowWidths(row)

        val flexCount = row.count { it.appearance.percentWidth <= 0f }
        val bridgeIndex = row.indexOfFirst { it is SpaceKey || it is MiniSpaceKey }
            .takeIf { it in 1 until row.lastIndex && flexCount <= 1 }
        if (bridgeIndex != null) {
            val minSideReach = 0.06f
            val bridgeMinWidth = (gap + minSideReach * 2f).coerceAtMost(0.75f)
            val bridgeMaxWidth = 0.75f
            val splitScale = (1f - gap).coerceIn(0.40f, 0.95f)

            val leftIndices = 0 until bridgeIndex
            val rightIndices = (bridgeIndex + 1)..keyViews.lastIndex

            val nonBridgeIndices = row.indices.filter { it != bridgeIndex }
            val preferredNormalWidths = nonBridgeIndices.mapNotNull { index ->
                val width = row[index].appearance.percentWidth
                if (width > 0f && row[index] !is SpaceKey && row[index] !is MiniSpaceKey) width else null
            }
            val fallbackWidths = nonBridgeIndices.mapNotNull { index ->
                val width = row[index].appearance.percentWidth
                if (width > 0f) width else null
            }
            val referenceNormalWidth = when {
                preferredNormalWidths.isNotEmpty() -> preferredNormalWidths.sum() / preferredNormalWidths.size
                fallbackWidths.isNotEmpty() -> fallbackWidths.sum() / fallbackWidths.size
                else -> 1f / row.size.coerceAtLeast(1)
            }

            val desiredNonBridgeWidths = mutableMapOf<Int, Float>()
            nonBridgeIndices.forEach { index ->
                val width = row[index].appearance.percentWidth
                val baseWidth = if (width > 0f) width else referenceNormalWidth
                desiredNonBridgeWidths[index] = baseWidth * splitScale
            }

            val assignedNonBridgeWidths = desiredNonBridgeWidths.toMutableMap()
            fun sumAssigned(): Float = assignedNonBridgeWidths.values.sum().coerceIn(0f, 1f)

            val flexIndices = nonBridgeIndices.filter { row[it].appearance.percentWidth <= 0f }
            val fixedIndices = nonBridgeIndices.filter { row[it].appearance.percentWidth > 0f }

            var overflowForMinBridge = (sumAssigned() + bridgeMinWidth - 1f).coerceAtLeast(0f)
            if (overflowForMinBridge > 0f && flexIndices.isNotEmpty()) {
                val flexSum = flexIndices.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (flexSum > 0f) {
                    val reduce = overflowForMinBridge.coerceAtMost(flexSum)
                    flexIndices.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current - reduce * (current / flexSum)
                    }
                    overflowForMinBridge = (sumAssigned() + bridgeMinWidth - 1f).coerceAtLeast(0f)
                }
            }
            if (overflowForMinBridge > 0f && fixedIndices.isNotEmpty()) {
                val fixedSum = fixedIndices.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (fixedSum > 0f) {
                    fixedIndices.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current - overflowForMinBridge * (current / fixedSum)
                    }
                }
            }

            var bridgeWidth = (1f - sumAssigned()).coerceAtLeast(bridgeMinWidth)
            if (bridgeWidth > bridgeMaxWidth) {
                val extra = bridgeWidth - bridgeMaxWidth
                val growTargets = if (flexIndices.isNotEmpty()) flexIndices else fixedIndices
                val targetSum = growTargets.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (targetSum > 0f) {
                    growTargets.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current + extra * (current / targetSum)
                    }
                }
                bridgeWidth = bridgeMaxWidth
            }

            keyViews.forEachIndexed { index, view ->
                add(view, lParams {
                    centerVertically()
                    matchConstraintPercentWidth = if (index == bridgeIndex) {
                        bridgeWidth
                    } else {
                        assignedNonBridgeWidths[index] ?: 0f
                    }
                    if (index == 0) {
                        leftOfParent()
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == keyViews.lastIndex) {
                        rightOfParent()
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                })
            }
            return@constraintLayout
        }

        val splitIndex = chooseSplitIndex(row, normalizedWidths)
        val sideCapacity = ((1f - gap) / 2f).coerceAtLeast(0.05f)

        val leftIndices = 0..splitIndex
        val rightIndices = (splitIndex + 1)..keyViews.lastIndex

        fun adjustedSideWidths(indices: IntRange): Map<Int, Float> {
            if (indices.isEmpty()) return emptyMap()
            val base = indices.associateWith { normalizedWidths[it] }
            val flexible = indices.filter { row[it].appearance.percentWidth <= 0f }
            val fixed = indices.filter { row[it].appearance.percentWidth > 0f }

            val fixedSum = fixed.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
            val flexSum = flexible.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
            val total = (fixedSum + flexSum).coerceAtLeast(0.0001f)

            // Side without flexible keys: just scale proportionally to side capacity.
            if (flexible.isEmpty()) {
                val ratio = sideCapacity / total
                return base.mapValues { (_, w) -> w * ratio }
            }

            // Keep at least part of side capacity for flexible keys (e.g. Space)
            // to avoid "space key too tiny" when gap is large.
            val minFlexShare = (0.30f + (gap - 0.20f) * 0.80f).coerceIn(0.30f, 0.55f)
            val targetFlex = maxOf(
                sideCapacity * minFlexShare,
                (sideCapacity - fixedSum).coerceAtLeast(0f)
            ).coerceAtMost(sideCapacity)
            val targetFixed = (sideCapacity - targetFlex).coerceAtLeast(0f)

            val fixedScale = if (fixedSum > 0f) targetFixed / fixedSum else 0f
            val result = mutableMapOf<Int, Float>()
            fixed.forEach { idx ->
                result[idx] = (base[idx] ?: 0f) * fixedScale
            }
            val flexScale = if (flexSum > 0f) targetFlex / flexSum else 0f
            flexible.forEach { idx ->
                result[idx] = (base[idx] ?: 0f) * flexScale
            }
            return result
        }

        val leftAdjusted = adjustedSideWidths(leftIndices)
        val rightAdjusted = adjustedSideWidths(rightIndices)

        val leftGuide = Guideline(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                orientation = LayoutParams.VERTICAL
                guidePercent = (0.5f - gap / 2f).coerceIn(0.2f, 0.8f)
            }
        }
        val rightGuide = Guideline(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                orientation = LayoutParams.VERTICAL
                guidePercent = (0.5f + gap / 2f).coerceIn(0.2f, 0.8f)
            }
        }
        addView(leftGuide)
        addView(rightGuide)

        keyViews.forEachIndexed { index, view ->
            add(view, lParams {
                centerVertically()
                val isLeftGroup = index <= splitIndex
                matchConstraintPercentWidth = if (isLeftGroup) {
                    leftAdjusted[index] ?: 0f
                } else {
                    rightAdjusted[index] ?: 0f
                }
                if (isLeftGroup) {
                    if (index == 0) {
                        leftOfParent()
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == splitIndex) {
                        rightToLeft = leftGuide.id
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                } else {
                    if (index == splitIndex + 1) {
                        leftToRight = rightGuide.id
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == keyViews.lastIndex) {
                        rightOfParent()
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                }
            })
        }
    }

    private fun buildRegularRow(row: List<KeyDef>, keyViews: List<KeyView>): ConstraintLayout = constraintLayout Row@{
        clipChildren = false
        clipToPadding = false
        keyViews.forEach { keyView ->
            keyView.onWaterRippleRequest = { view, _, _ ->
                val cx = (view.parent as? View)?.x.orZero() + view.x + view.width * 0.5f
                val cy = (view.parent as? View)?.y.orZero() + view.y + view.height * 0.5f
                val radius = waterRippleRadiusPx(view)
                keyboardWaterRippleView?.startRipple(cx, cy, waterRippleColor(), radius)
            }
        }
        var totalWidth = 0f
        keyViews.forEachIndexed { index, view ->
            add(view, lParams {
                centerVertically()
                if (index == 0) {
                    leftOfParent()
                    horizontalChainStyle = LayoutParams.CHAIN_PACKED
                } else {
                    leftToRightOf(keyViews[index - 1])
                }
                if (index == keyViews.size - 1) {
                    rightOfParent()
                    // for RTL
                    horizontalChainStyle = LayoutParams.CHAIN_PACKED
                } else {
                    rightToLeftOf(keyViews[index + 1])
                }
                val def = row[index]
                matchConstraintPercentWidth = def.appearance.percentWidth
            })
            row[index].appearance.percentWidth.let {
                // 0f means fill remaining space, thus does not need expanding
                totalWidth += if (it != 0f) it else 1f
            }
        }
        if (expandKeypressArea && totalWidth < 1f) {
            val free = (1f - totalWidth) / 2f
            keyViews.first().apply {
                updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth += free
                }
                layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
            }
            keyViews.last().apply {
                updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth += free
                }
                layoutMarginRight = free / (row.last().appearance.percentWidth + free)
            }
        }
    }

    private fun Float?.orZero(): Float = this ?: 0f

    private fun waterRippleRadiusPx(view: View): Float {
        val base = minOf(width, height).takeIf { it > 0 }?.toFloat() ?: dp(120).toFloat()
        val minFromKey = max(view.width, view.height) * 1.0f
        return max(base * 0.20f, minFromKey)
    }

    private fun waterRippleColor(): Int {
        cachedWaterRippleColor?.let { return it }
        theme.waterRippleColor?.let {
            cachedWaterRippleColor = it
            return it
        }
        val shadow = theme.keyShadowColor
        val background = ColorUtils.setAlphaComponent(theme.keyboardColor, 255)
        val contrast = ColorUtils.calculateContrast(shadow, background)
        val computed = if (contrast < 1.35) {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.72f)
        } else {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.28f)
        }
        cachedWaterRippleColor = computed
        return computed
    }

    private var currentTextScale = 1.0f

    fun setTextScale(scale: Float) {
        val scaleChanged = currentTextScale != scale
        currentTextScale = scale
        keyRows.forEach { row ->
            row.children.forEach { child ->
                (child as? KeyView)?.let { keyView ->
                    keyView.setTextScale(scale)
                    if (!scaleChanged) {
                        keyView.requestLayout()
                        keyView.invalidate()
                    }
                }
            }
        }
    }

    fun reapplyTextScale() {
        setTextScale(currentTextScale)
    }

    fun refreshStyle() {
        reloadLayout()
        reapplyTextScale()
        onStyleRefreshFinished()
        requestLayout()
        updateBounds()
    }

    /**
     * Drop all cached row sets. Call before a reload that must rebuild rows even though
     * the layout/style signature did not change (e.g. font set refresh whose flag was
     * already consumed).
     */
    fun clearReusableRowsCache() {
        reusableRowsCache.clear()
        lastRowsSignature = null
    }

    /**
     * Lightweight style refresh, updates colors without rebuilding layout
     */
    fun refreshStyleLight() {
        onStyleRefreshFinished()
        invalidate()
    }

    fun refreshIconTheme() {
        if (!::keyRows.isInitialized) return
        keyRows.forEach { row ->
            row.children.forEach { child ->
                (child as? KeyView)?.let { keyView ->
                    when (keyView) {
                        is ImageAltTextKeyView -> keyView.reapplyIconThemeOverride()
                        is ImageTextKeyView -> keyView.reapplyIconThemeOverride()
                        is ImageKeyView -> keyView.reapplyIconThemeOverride()
                    }
                }
            }
        }
    }

    fun setHorizontalGapScale(scale: Float) {
        val target = scale.coerceIn(0.5f, 1f)
        if (kotlin.math.abs(horizontalGapScale - target) < 0.01f) return
        horizontalGapScale = target
        refreshStyle()
    }

    protected open fun onStyleRefreshFinished() {
        // do nothing by default
    }

    /**
     * Update theme without rebuilding views.
     * Iterates all KeyViews and updates their theme properties.
     */
    fun updateTheme(newTheme: Theme) {
        theme = newTheme
        cachedWaterRippleColor = null

        if (::keyRows.isInitialized) {
            keyRows.forEach { row ->
                row.children.forEach { child ->
                    (child as? KeyView)?.updateTheme(newTheme)
                }
            }
        }

        onThemeUpdate(newTheme)
    }

    /**
     * Called after theme update for keyboard-specific updates
     */
    protected open fun onThemeUpdate(newTheme: Theme) {
        // default: no-op
    }

    private fun createKeyView(
        def: KeyDef,
        registerComposeAware: Boolean = true,
        appearanceOverride: KeyDef.Appearance? = null
    ): KeyView {
        val (activeDef, resolvedAppearance) = resolveComposeActiveDef(def)
        val activeAppearance = appearanceOverride ?: resolvedAppearance
        return when (activeAppearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, activeAppearance, horizontalGapScale)
            is KeyDef.Appearance.ImageAltText -> ImageAltTextKeyView(context, theme, activeAppearance, horizontalGapScale, def.iconSlot)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, activeAppearance, horizontalGapScale, def.iconSlot)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, activeAppearance, horizontalGapScale)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, activeAppearance, horizontalGapScale, def.iconSlot)
        }.apply {
            setTextScale(currentTextScale)
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                // Use a larger threshold for Y axis to avoid accidental up/down triggers
                // when user intends to swipe left/right
                swipeThresholdY = selectionSwipeThreshold * 1.5f
                // Track the locked swipe direction to avoid conflicting gestures
                var swipeDirectionLocked: SwipeAxis? = null
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val countX = event.countX
                            val countY = event.countY

                            // Lock direction on first swipe
                            if (swipeDirectionLocked == null && (countX != 0 || countY != 0)) {
                                swipeDirectionLocked = if (kotlin.math.abs(countX) >= kotlin.math.abs(countY)) {
                                    SwipeAxis.X
                                } else {
                                    SwipeAxis.Y
                                }
                            }

                            val handled = when (swipeDirectionLocked) {
                                SwipeAxis.X -> {
                                    if (countX != 0) {
                                        val sym =
                                            if (countX > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                        val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                        repeat(countX.absoluteValue) {
                                            onAction(action)
                                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                SwipeAxis.Y -> {
                                    if (countY != 0) {
                                        val sym =
                                            if (countY > 0) FcitxKeyMapping.FcitxKey_Down else FcitxKeyMapping.FcitxKey_Up
                                        val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                        repeat(countY.absoluteValue) {
                                            onAction(action)
                                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                null -> false
                            }
                            handled
                        }
                        GestureType.Up -> {
                            // Reset direction lock on finger up
                            swipeDirectionLocked = null
                            onAction(KeyAction.VoiceInputHoldEnd)
                            false
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = if (def.swipe != null) inputSwipeThreshold else disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            if (
                                def.swipe != null &&
                                kotlin.math.abs(event.totalY) > kotlin.math.abs(event.totalX) &&
                                shouldTriggerSymbolBySwipe(view, event.totalY)
                            ) {
                                onAction(def.swipe)
                                true
                            } else {
                                onAction(KeyAction.DeleteSelectionAction(event.totalX))
                                false
                            }
                        }
                        else -> false
                    }
                }
            }
            val baseline = GestureBaseline(
                swipeEnabled = swipeEnabled,
                swipeRepeatEnabled = swipeRepeatEnabled,
                swipeThresholdX = swipeThresholdX,
                swipeThresholdY = swipeThresholdY,
                onGestureListener = onGestureListener
            )
            applyAppearance(this, activeAppearance)
            applyBehaviorPopupBindings(this, baseline, activeDef.behaviors, activeDef.popup)
            if (registerComposeAware && def.composeOverride != null) {
                composeAwareKeys += ComposeAwareKey(def, this, baseline)
            }
        }
    }

    open fun onCompositionStateChanged(composing: Boolean) {
        if (this.composing == composing) return
        this.composing = composing
        // The attached rows are about to be mutated in place (compose-aware views get
        // recreated). Evict the cache entry holding them so a later reload can never
        // reuse rows that were modified under a different composition state.
        lastRowsSignature?.let { reusableRowsCache.remove(it) }
        lastRowsSignature = null
        data class PendingUpdate(
            val item: ComposeAwareKey,
            val activeDef: KeyDef,
            val activeAppearance: KeyDef.Appearance,
            val needsRecreate: Boolean
        )

        val updates = composeAwareKeys.map { item ->
            val (activeDef, activeAppearance) = resolveComposeActiveDef(item.baseDef)
            val needsRecreate = shouldRecreateComposeAwareView(item.keyView.def, item.keyView, activeAppearance)
            PendingUpdate(item, activeDef, activeAppearance, needsRecreate)
        }

        // First, rebind behaviors on existing views so taps during transition already use new actions.
        updates.forEach { u ->
            applyBehaviorPopupBindings(u.item.keyView, u.item.baseline, u.activeDef.behaviors, u.activeDef.popup)
        }

        val recreateList = updates.filter { it.needsRecreate }
        if (recreateList.isNotEmpty()) {
            val parents = recreateList
                .mapNotNull { it.item.keyView.parent as? ViewGroup }
                .distinct()
            parents.forEach { it.suppressLayout(true) }
            try {
                recreateList.forEach { u ->
                    recreateComposeAwareKeyView(u.item, u.activeDef, u.activeAppearance)
                }
            } finally {
                parents.forEach { it.suppressLayout(false) }
            }
            // Update occluders so water ripple masking uses the recreated views
            keyboardWaterRippleView?.setOccluders(
                keyRows.flatMap { row -> row.children.mapNotNull { it as? KeyView }.toList() }
            )
        }

        updates.forEach { u ->
            applyAppearance(u.item.keyView, u.activeAppearance)
            applyBehaviorPopupBindings(u.item.keyView, u.item.baseline, u.activeDef.behaviors, u.activeDef.popup)
        }
    }

    private fun shouldRecreateComposeAwareView(
        oldAppearance: KeyDef.Appearance,
        currentView: KeyView,
        newAppearance: KeyDef.Appearance
    ): Boolean {
        if (!isAppearanceCompatible(currentView, newAppearance)) return true

        val textMetricsChanged = when {
            oldAppearance is KeyDef.Appearance.Text && newAppearance is KeyDef.Appearance.Text -> {
                oldAppearance.textStyle != newAppearance.textStyle ||
                    oldAppearance.textSize != newAppearance.textSize
            }
            oldAppearance is KeyDef.Appearance.Text || newAppearance is KeyDef.Appearance.Text -> true
            else -> false
        }

        // Recreate only when style-affecting attributes changed. For text/action-only changes,
        // keep the same view instance to avoid touch race and expensive hierarchy churn.
        return oldAppearance.variant != newAppearance.variant ||
            oldAppearance.border != newAppearance.border ||
            oldAppearance.margin != newAppearance.margin ||
            oldAppearance.percentWidth != newAppearance.percentWidth ||
            oldAppearance.viewId != newAppearance.viewId ||
            textMetricsChanged ||
            oldAppearance.textColor != newAppearance.textColor ||
            oldAppearance.textColorMonet != newAppearance.textColorMonet ||
            oldAppearance.altTextColor != newAppearance.altTextColor ||
            oldAppearance.altTextColorMonet != newAppearance.altTextColorMonet ||
            oldAppearance.backgroundColor != newAppearance.backgroundColor ||
            oldAppearance.backgroundColorMonet != newAppearance.backgroundColorMonet ||
            oldAppearance.shadowColor != newAppearance.shadowColor ||
            oldAppearance.shadowColorMonet != newAppearance.shadowColorMonet
    }

    private fun isAppearanceCompatible(view: KeyView, appearance: KeyDef.Appearance): Boolean {
        return when (view) {
            is AltTextKeyView -> appearance is KeyDef.Appearance.AltText
            is ImageAltTextKeyView -> appearance is KeyDef.Appearance.ImageAltText
            is ImageTextKeyView -> appearance is KeyDef.Appearance.ImageText
            is ImageKeyView -> appearance is KeyDef.Appearance.Image
            is TextKeyView -> appearance is KeyDef.Appearance.Text &&
                    appearance !is KeyDef.Appearance.AltText &&
                    appearance !is KeyDef.Appearance.ImageText
            else -> false
        }
    }

    private fun recreateComposeAwareKeyView(
        item: ComposeAwareKey,
        def: KeyDef,
        appearance: KeyDef.Appearance
    ) {
        val oldView = item.keyView
        val parent = oldView.parent as? ConstraintLayout ?: return
        val index = parent.indexOfChild(oldView)
        if (index < 0) return
        val oldLayoutParams = oldView.layoutParams
        val copiedLayoutParams = when (oldLayoutParams) {
            is ConstraintLayout.LayoutParams -> ConstraintLayout.LayoutParams(oldLayoutParams)
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(oldLayoutParams)
            else -> oldLayoutParams
        }
        val newView = createKeyView(def, registerComposeAware = false, appearanceOverride = appearance)
        applyConfiguredFonts(newView)
        // Keep the same identity so sibling constraints (leftToRight/rightToLeft) remain valid.
        newView.id = oldView.id
        newView.tag = oldView.tag
        // Preserve water ripple request callback on recreated view
        newView.onWaterRippleRequest = { view, _, _ ->
            val cx = (view.parent as? View)?.x.orZero() + view.x + view.width * 0.5f
            val cy = (view.parent as? View)?.y.orZero() + view.y + view.height * 0.5f
            val radius = waterRippleRadiusPx(view)
            keyboardWaterRippleView?.startRipple(cx, cy, waterRippleColor(), radius)
        }
        parent.removeViewAt(index)
        if (copiedLayoutParams != null) {
            parent.addView(newView, index, copiedLayoutParams)
        } else {
            parent.addView(newView, index)
        }
        item.keyView = newView
        item.baseline = GestureBaseline(
            swipeEnabled = newView.swipeEnabled,
            swipeRepeatEnabled = newView.swipeRepeatEnabled,
            swipeThresholdX = newView.swipeThresholdX,
            swipeThresholdY = newView.swipeThresholdY,
            onGestureListener = newView.onGestureListener
        )
        applyAppearance(newView, appearance)
    }

    private fun applyConfiguredFonts(keyView: KeyView) {
        // Check AltTextKeyView before TextKeyView since AltTextKeyView is a subclass of TextKeyView.
        when (keyView) {
            is AltTextKeyView -> {
                keyView.mainText.setFontTypeFace("key_main_font")
                keyView.altText.setFontTypeFace("key_alt_font")
            }
            is ImageAltTextKeyView -> keyView.altText.setFontTypeFace("key_alt_font")
            is TextKeyView -> keyView.mainText.setFontTypeFace("key_main_font")
        }
    }

    private fun resolveComposeActiveDef(baseDef: KeyDef): Pair<KeyDef, KeyDef.Appearance> {
        if (!composing) return baseDef to baseDef.appearance
        val overrideDef = baseDef.composeOverride ?: return baseDef to baseDef.appearance
        val appearance = if (overrideDef.independentColor) {
            overrideDef.appearance.withIdentityFrom(baseDef.appearance)
        } else {
            overrideDef.appearance.withColorsFrom(baseDef.appearance)
        }.withTextMetricsFrom(baseDef.appearance)
        return overrideDef to appearance
    }

    private fun KeyDef.Appearance.withTextMetricsFrom(source: KeyDef.Appearance): KeyDef.Appearance {
        val sourceText = source as? KeyDef.Appearance.Text ?: return this
        return when (this) {
            is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
                displayText = displayText,
                altText = altText,
                character = character,
                textSize = sourceText.textSize,
                textStyle = sourceText.textStyle,
                percentWidth = percentWidth,
                variant = variant,
                border = border,
                margin = margin,
                viewId = viewId,
                textColor = textColor,
                textColorMonet = textColorMonet,
                altTextColor = altTextColor,
                altTextColorMonet = altTextColorMonet,
                backgroundColor = backgroundColor,
                backgroundColorMonet = backgroundColorMonet,
                shadowColor = shadowColor,
                shadowColorMonet = shadowColorMonet
            )
            is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
                displayText = displayText,
                textSize = sourceText.textSize,
                textStyle = sourceText.textStyle,
                src = src,
                percentWidth = percentWidth,
                variant = variant,
                border = border,
                margin = margin,
                viewId = viewId,
                textColor = textColor,
                textColorMonet = textColorMonet,
                altTextColor = altTextColor,
                altTextColorMonet = altTextColorMonet,
                backgroundColor = backgroundColor,
                backgroundColorMonet = backgroundColorMonet,
                shadowColor = shadowColor,
                shadowColorMonet = shadowColorMonet
            )
            is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
                displayText = displayText,
                textSize = sourceText.textSize,
                textStyle = sourceText.textStyle,
                percentWidth = percentWidth,
                variant = variant,
                border = border,
                margin = margin,
                viewId = viewId,
                soundEffect = soundEffect,
                textColor = textColor,
                textColorMonet = textColorMonet,
                altTextColor = altTextColor,
                altTextColorMonet = altTextColorMonet,
                backgroundColor = backgroundColor,
                backgroundColorMonet = backgroundColorMonet,
                shadowColor = shadowColor,
                shadowColorMonet = shadowColorMonet
            )
            else -> this
        }
    }

    private fun KeyDef.Appearance.withIdentityFrom(source: KeyDef.Appearance): KeyDef.Appearance = when (this) {
        is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
            displayText = displayText,
            altText = altText,
            character = character,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
        is KeyDef.Appearance.ImageAltText -> KeyDef.Appearance.ImageAltText(
            src = src,
            altText = altText,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
        is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
        is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
        is KeyDef.Appearance.Image -> KeyDef.Appearance.Image(
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = textColor,
            textColorMonet = textColorMonet,
            altTextColor = altTextColor,
            altTextColorMonet = altTextColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    }

    private fun KeyDef.Appearance.withColorsFrom(source: KeyDef.Appearance): KeyDef.Appearance = when (this) {
        is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
            displayText = displayText,
            altText = altText,
            character = character,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
        is KeyDef.Appearance.ImageAltText -> KeyDef.Appearance.ImageAltText(
            src = src,
            altText = altText,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
        is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
        is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
            displayText = displayText,
            textSize = textSize,
            textStyle = textStyle,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
        is KeyDef.Appearance.Image -> KeyDef.Appearance.Image(
            src = src,
            percentWidth = percentWidth,
            variant = source.variant,
            border = source.border,
            margin = source.margin,
            viewId = source.viewId,
            soundEffect = source.soundEffect,
            textColor = source.textColor,
            textColorMonet = source.textColorMonet,
            altTextColor = source.altTextColor,
            altTextColorMonet = source.altTextColorMonet,
            backgroundColor = source.backgroundColor,
            backgroundColorMonet = source.backgroundColorMonet,
            shadowColor = source.shadowColor,
            shadowColorMonet = source.shadowColorMonet
        )
    }

    private fun applyAppearance(view: KeyView, appearance: KeyDef.Appearance) {
        when (view) {
            is AltTextKeyView -> if (appearance is KeyDef.Appearance.AltText) {
                view.mainText.text = appearance.displayText
                view.altText.text = appearance.altText
                view.refreshAltTextLayout()
            }
            is ImageAltTextKeyView -> if (appearance is KeyDef.Appearance.ImageAltText) {
                view.img.setImageResource(appearance.src)
                view.reapplyIconThemeOverride()
                view.altText.text = appearance.altText
                view.refreshAltTextLayout()
            }
            is ImageTextKeyView -> if (appearance is KeyDef.Appearance.ImageText) {
                view.mainText.text = appearance.displayText
                view.img.setImageResource(appearance.src)
                view.reapplyIconThemeOverride()
            }
            is TextKeyView -> if (appearance is KeyDef.Appearance.Text) {
                view.mainText.text = appearance.displayText
            }
            is ImageKeyView -> if (appearance is KeyDef.Appearance.Image) {
                view.img.setImageResource(appearance.src)
                view.reapplyIconThemeOverride()
            }
        }
    }

    private fun applyBehaviorPopupBindings(
        view: KeyView,
        baseline: GestureBaseline,
        behaviors: Set<KeyDef.Behavior>,
        popup: Array<KeyDef.Popup>?
    ) {
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        view.repeatEnabled = false
        view.onRepeatListener = null
        view.doubleTapEnabled = false
        view.onDoubleTapListener = null
        view.swipeEnabled = baseline.swipeEnabled
        view.swipeRepeatEnabled = baseline.swipeRepeatEnabled
        view.swipeThresholdX = baseline.swipeThresholdX
        view.swipeThresholdY = baseline.swipeThresholdY
        view.onGestureListener = baseline.onGestureListener

        val interactive = behaviors.isNotEmpty() || !popup.isNullOrEmpty()
        view.isEnabled = interactive
        view.isClickable = interactive
        if (!interactive) return

        var hasLongPressBehavior = false
        behaviors.forEach {
            when (it) {
                is KeyDef.Behavior.Press -> {
                    view.setOnClickListener { _ ->
                        onAction(it.action)
                    }
                }
                is KeyDef.Behavior.LongPress -> {
                    hasLongPressBehavior = true
                    view.setOnLongClickListener { _ ->
                        onAction(it.action)
                        true
                    }
                }
                is KeyDef.Behavior.Repeat -> {
                    view.repeatEnabled = true
                    view.onRepeatListener = { currentView ->
                        onAction(it.action)
                        if (hapticOnRepeat) InputFeedbacks.hapticFeedback(currentView)
                    }
                }
                is KeyDef.Behavior.Swipe -> {
                    view.swipeEnabled = true
                    view.swipeThresholdX = disabledSwipeThreshold
                    view.swipeThresholdY = inputSwipeThreshold
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        when (event.type) {
                            GestureType.Up -> {
                                if (!event.consumed && shouldTriggerSymbolBySwipe(currentView, event.totalY)) {
                                    onAction(it.action)
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        } || oldOnGestureListener.onGesture(currentView, event)
                    }
                }
                is KeyDef.Behavior.DoubleTap -> {
                    view.doubleTapEnabled = true
                    view.onDoubleTapListener = { _ ->
                        onAction(it.action)
                    }
                }
            }
        }
        val hasLongPressKeyboard = popup?.any { it is KeyDef.Popup.LongPressKeyboard } == true
        popup?.forEach {
            when (it) {
                is KeyDef.Popup.Menu -> {
                    if (!hasLongPressKeyboard && !hasLongPressBehavior) {
                        view.setOnLongClickListener { currentView ->
                            currentView as KeyView
                            dismissAllPopups()
                            onPopupAction(PopupAction.ShowMenuAction(currentView.id, it, currentView.bounds))
                            false
                        }
                        val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                        view.swipeEnabled = true
                        view.onGestureListener = OnGestureListener { currentView, event ->
                            currentView as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(currentView.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(currentView.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(currentView, event)
                        }
                    }
                }
                is KeyDef.Popup.LongPressKeyboard -> {
                    view.setOnLongClickListener { currentView ->
                        currentView as KeyView
                        dismissAllPopups()
                        onPopupAction(PopupAction.ShowLongPressKeyboardAction(currentView.id, it, currentView.bounds))
                        false
                    }
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.swipeEnabled = true
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        currentView as KeyView
                        when (event.type) {
                            GestureType.Move -> {
                                onPopupChangeFocus(currentView.id, event.x, event.y)
                            }
                            GestureType.Up -> {
                                onPopupTrigger(currentView.id)
                            }
                            else -> false
                        } || oldOnGestureListener.onGesture(currentView, event)
                    }
                }
                is KeyDef.Popup.Keyboard -> {
                    if (!hasLongPressKeyboard && !hasLongPressBehavior) {
                        view.setOnLongClickListener { currentView ->
                            currentView as KeyView
                            dismissAllPopups()
                            onPopupAction(PopupAction.ShowKeyboardAction(currentView.id, it, currentView.bounds))
                            false
                        }
                        val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                        view.swipeEnabled = true
                        view.onGestureListener = OnGestureListener { currentView, event ->
                            currentView as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(currentView.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(currentView.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(currentView, event)
                        }
                    }
                }
                is KeyDef.Popup.AltPreview -> {
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        currentView as KeyView
                        if (popupOnKeyPress) {
                            when (event.type) {
                                GestureType.Down -> onPopupAction(
                                    PopupAction.PreviewAction(currentView.id, it.content, currentView.bounds)
                                )
                                GestureType.Move -> {
                                    val triggered = shouldTriggerSymbolBySwipe(currentView, event.totalY)
                                    val text = if (triggered) it.alternative else it.content
                                    onPopupAction(
                                        PopupAction.PreviewUpdateAction(currentView.id, text)
                                    )
                                }
                                GestureType.Up -> {
                                    onPopupAction(PopupAction.DismissAction(currentView.id))
                                    dismissAllPopups()
                                }
                            }
                        }
                        oldOnGestureListener.onGesture(currentView, event)
                    }
                }
                is KeyDef.Popup.Preview -> {
                    val oldOnGestureListener = view.onGestureListener ?: OnGestureListener.Empty
                    view.onGestureListener = OnGestureListener { currentView, event ->
                        currentView as KeyView
                        if (popupOnKeyPress) {
                            when (event.type) {
                                GestureType.Down -> onPopupAction(
                                    PopupAction.PreviewAction(currentView.id, it.content, currentView.bounds)
                                )
                                GestureType.Up -> {
                                    onPopupAction(PopupAction.DismissAction(currentView.id))
                                    dismissAllPopups()
                                }
                                else -> {}
                            }
                        }
                        oldOnGestureListener.onGesture(currentView, event)
                    }
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Re-evaluate split keyboard when view width changes
        if (w != oldw) {
            val shouldSplit = splitKeyboardManager.shouldUseSplitKeyboard(w)
            if (shouldSplit != lastSplitLandscapeState) {
                reloadLayout()
                reapplyTextScale()
                onStyleRefreshFinished()
                requestLayout()
            }
        }

        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
    }

    fun updateBounds() {
        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
        if (::keyRows.isInitialized) {
            keyRows.forEach { row ->
                row.children.forEach {
                    (it as? KeyView)?.invalidateCachedBounds()
                }
            }
        }
    }

    fun keyBoundsInKeyboard(): List<Rect> {
        if (!::keyRows.isInitialized) return emptyList()
        updateBounds()
        val result = ArrayList<Rect>(48)
        keyRows.forEach { row ->
            row.children.forEach { child ->
                val key = child as? KeyView ?: return@forEach
                key.updateBounds()
                val rect = Rect(key.bounds)
                rect.offset(-bounds.left, -bounds.top)
                // Exclude key outer margins so only actual key area gets blur
                // and keep inter-key gaps/side-bottom paddings unblurred.
                if (key.hMargin > 0 || key.vMargin > 0) {
                    rect.inset(key.hMargin, key.vMargin)
                }
                if (rect.width() <= 0 || rect.height() <= 0) return@forEach
                result.add(rect)
            }
        }
        return result
    }

    private fun findTargetChild(x: Float, y: Float): View? {
        updateBounds()
        val x1 = x.roundToInt() + bounds.left
        val y1 = y.roundToInt() + bounds.top
        return keyRows.asSequence().flatMap { it.children }.find {
            if (it !is KeyView) false else it.isEnabled && it.bounds.contains(x1, y1)
        }
    }

    private fun transformMotionEventToChild(
        child: View,
        event: MotionEvent,
        action: Int,
        pointerIndex: Int
    ): MotionEvent {
        if (child !is KeyView) {
            Timber.w("child view is not KeyView when transforming MotionEvent $event")
            return event
        }
        val (childWindowX, childWindowY) = childLocationInWindow.also { child.getLocationInWindow(it) }
        val childX = event.getX(pointerIndex) + bounds.left - childWindowX
        val childY = event.getY(pointerIndex) + bounds.top - childWindowY
        return MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept only key presses for the workaround. Auxiliary bar RecyclerViews
        // must receive their own touch stream for scrolling and clicks.
        return if (
            vivoKeypressWorkaround &&
            ev.actionMasked == MotionEvent.ACTION_DOWN &&
            findTargetChild(ev.x, ev.y) != null
        ) {
            true
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (vivoKeypressWorkaround) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val target = findTargetChild(event.x, event.y) ?: return false
                    touchTarget[event.getPointerId(0)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, 0)
                    )
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val target = findTargetChild(event.getX(i), event.getY(i)) ?: return false
                    touchTarget[event.getPointerId(i)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, i)
                    )
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val target = touchTarget[event.getPointerId(i)] ?: continue
                        target.dispatchTouchEvent(
                            transformMotionEventToChild(target, event, MotionEvent.ACTION_MOVE, i)
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[pid] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_CANCEL, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        when (action) {
            is MacroAction -> executeMacro(preprocessMacroAction(action, source))
            else -> keyActionListener?.onKeyAction(action, source)
        }
    }

    private fun shouldTriggerSymbolBySwipe(view: View, totalY: Int): Boolean {
        val altTextView = view as? SwipeHintAwareKeyView
        return altTextView?.shouldTriggerAltBySwipe(totalY, swipeSymbolDirection)
            ?: swipeSymbolDirection.checkY(totalY)
    }

    protected open fun preprocessMacroAction(
        action: MacroAction,
        source: KeyActionListener.Source
    ): MacroAction = action

    /**
     * Execute a Macro action
     * @param macroAction the MacroAction to execute
     */
    private fun executeMacro(macroAction: MacroAction) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            Timber.v("executeMacro: steps=%d", macroAction.steps.size)

            // General macro execution
            for ((index, step) in macroAction.steps.withIndex()) {
                Timber.v("executeMacro: executing step %d type=%s", index, step::class.java.simpleName)
                when (step) {
                    is MacroStep.Down -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    // Fcitx keys follow the Fcitx path:
                                    // - Mappable keys are sent by simulating physical keyboard events
                                    // - Unmappable keys fall back to Fcitx sendKey to avoid silent drops
                                    Timber.v("executeMacro: Down Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyDown(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyDown(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Up -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    Timber.v("executeMacro: Up Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyUp(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyUp(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Tap -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    Timber.v("executeMacro: Tap Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyTap(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyTap(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Text -> {
                        Timber.v("executeMacro: Text length=%d", step.text.length)
                        commitText(step.text)
                    }
                    is MacroStep.Edit -> {
                        Timber.v("executeMacro: Edit action=%s", step.action)
                        executeEditAction(step.action)
                    }
                    is MacroStep.AppAction -> {
                        Timber.v("executeMacro: App action id=%s", step.id)
                        executeAppAction(step.id)
                    }
                    is MacroStep.Shortcut -> {
                        Timber.v("executeMacro: Shortcut modifiers=%d key=%s", step.modifiers.size, step.key)
                        executeShortcut(step.modifiers, step.key)
                    }
                    is MacroStep.LayerSwitch -> {
                        Timber.v("executeMacro: %s target=%s", step.mode, step.target)
                        keyActionListener?.onKeyAction(
                            KeyAction.LayerSwitchAction(
                                step.mode,
                                step.target
                            ),
                            KeyActionListener.Source.Keyboard
                        )
                    }
                }
            }
            if (macroAction.hasOneShotLayerConsumingStep()) {
                keyActionListener?.onKeyAction(
                    KeyAction.MacroConsumedAction,
                    KeyActionListener.Source.Keyboard
                )
            }
        }
    }

    private fun MacroAction.hasOneShotLayerConsumingStep(): Boolean {
        return steps.any { step ->
            when (step) {
                is MacroStep.Down -> step.keys.isNotEmpty()
                is MacroStep.Up -> step.keys.isNotEmpty()
                is MacroStep.Tap -> step.keys.isNotEmpty()
                is MacroStep.Text -> step.text.isNotEmpty()
                is MacroStep.Edit -> step.action.isNotBlank()
                is MacroStep.AppAction -> step.id.isNotBlank()
                is MacroStep.Shortcut -> true
                is MacroStep.LayerSwitch -> false
            }
        }
    }

    /**
     * Execute shortcut as syntactic sugar:
     * modifiers down + key tap + modifiers up.
     */
    private suspend fun executeShortcut(modifiers: List<KeyRef>, key: KeyRef) {
        val modifierDownSteps = mutableListOf<KeyRef>()
        val modifierUpSteps = mutableListOf<KeyRef>()
        val normalizedKey = when (key) {
            is KeyRef.Fcitx -> {
                if (key.code.length == 1 && key.code[0].isLetter()) {
                    key.copy(code = key.code.lowercase())
                } else {
                    key
                }
            }
            is KeyRef.Android -> key
        }
        
        for (mod in modifiers) {
            val isSupportedModifier = when ((mod as? KeyRef.Fcitx)?.code) {
                "Ctrl_L", "Ctrl_R",
                "Alt_L", "Alt_R",
                "Shift_L", "Shift_R",
                "Meta_L", "Meta_R",
                "Super_L", "Super_R",
                "Hyper_L", "Hyper_R",
                "Mode_switch",
                "ISO_Level3_Shift",
                "ISO_Level5_Shift" -> true
                else -> false
            }
            if (isSupportedModifier || mod is KeyRef.Android) {
                modifierDownSteps.add(mod)
                modifierUpSteps.add(mod)
            }
        }

        // Generic down/tap/up flow
        // Press all modifiers down
        for (mod in modifierDownSteps) {
            when (mod) {
                is KeyRef.Fcitx -> sendFcitxKeyDown(mod.code)
                is KeyRef.Android -> sendAndroidKeyDown(mod.code)
            }
            delay(50)
        }
        
        // key tap
        when (normalizedKey) {
            is KeyRef.Fcitx -> sendFcitxKeyTap(normalizedKey.code)
            is KeyRef.Android -> sendAndroidKeyTap(normalizedKey.code)
        }
        delay(50)
        
        // Release all modifiers (reverse order)
        for (mod in modifierUpSteps.asReversed()) {
            when (mod) {
                is KeyRef.Fcitx -> sendFcitxKeyUp(mod.code)
                is KeyRef.Android -> sendAndroidKeyUp(mod.code)
            }
            delay(50)
        }
    }

    /**
     * Execute edit actions (copy/cut/paste/selectAll/undo/redo)
     */
    private suspend fun executeEditAction(action: String) {
        val service = getService() ?: return
        val ic = service.currentInputConnection ?: return

        when (action.lowercase()) {
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "selectall", "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "undo" -> ic.performContextMenuAction(android.R.id.undo)
            "redo" -> ic.performContextMenuAction(android.R.id.redo)
        }
    }

    private fun executeAppAction(actionId: String) {
        getService()?.inputView?.executeButtonAction(actionId)
    }

    /**
     * Check whether it is a function key (F1-F12)
     */
    private fun isFunctionKey(code: String): Boolean {
        return code.matches(Regex("^F(1[0-2]|[1-9])$"))
    }

    /**
     * Check whether it is a modifier key
     * Includes Ctrl, Alt, Shift, Meta, Super, Hyper, and Mode_switch
     */
    private fun isModifierKey(code: String): Boolean {
        return code in arrayOf(
            "Ctrl_L", "Ctrl_R",
            "Alt_L", "Alt_R",
            "Shift_L", "Shift_R",
            "Meta_L", "Meta_R",
            "Super_L", "Super_R",
            "Hyper_L", "Hyper_R",
            "Mode_switch",
            "ISO_Level3_Shift",
            "ISO_Level5_Shift"
        )
    }

    /**
     * Map Fcitx key name to Android key code
     */
    private fun mapFcitxToAndroidKey(code: String): Int {
        return when (code) {
            "Ctrl_L" -> android.view.KeyEvent.KEYCODE_CTRL_LEFT
            "Ctrl_R" -> android.view.KeyEvent.KEYCODE_CTRL_RIGHT
            "Shift_L" -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
            "Shift_R" -> android.view.KeyEvent.KEYCODE_SHIFT_RIGHT
            "Alt_L" -> android.view.KeyEvent.KEYCODE_ALT_LEFT
            "Alt_R" -> android.view.KeyEvent.KEYCODE_ALT_RIGHT
            "Meta_L" -> android.view.KeyEvent.KEYCODE_META_LEFT
            "Meta_R" -> android.view.KeyEvent.KEYCODE_META_RIGHT
            "Enter" -> android.view.KeyEvent.KEYCODE_ENTER
            "Tab" -> android.view.KeyEvent.KEYCODE_TAB
            "Escape" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "Space" -> android.view.KeyEvent.KEYCODE_SPACE
            "Delete" -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
            "BackSpace" -> android.view.KeyEvent.KEYCODE_DEL
            "Home" -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            "End" -> android.view.KeyEvent.KEYCODE_MOVE_END
            "Page_Up" -> android.view.KeyEvent.KEYCODE_PAGE_UP
            "Page_Down" -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
            "Left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "Right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "Up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "Down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "F1" -> android.view.KeyEvent.KEYCODE_F1
            "F2" -> android.view.KeyEvent.KEYCODE_F2
            "F3" -> android.view.KeyEvent.KEYCODE_F3
            "F4" -> android.view.KeyEvent.KEYCODE_F4
            "F5" -> android.view.KeyEvent.KEYCODE_F5
            "F6" -> android.view.KeyEvent.KEYCODE_F6
            "F7" -> android.view.KeyEvent.KEYCODE_F7
            "F8" -> android.view.KeyEvent.KEYCODE_F8
            "F9" -> android.view.KeyEvent.KEYCODE_F9
            "F10" -> android.view.KeyEvent.KEYCODE_F10
            "F11" -> android.view.KeyEvent.KEYCODE_F11
            "F12" -> android.view.KeyEvent.KEYCODE_F12
            "A" -> android.view.KeyEvent.KEYCODE_A
            "B" -> android.view.KeyEvent.KEYCODE_B
            "C" -> android.view.KeyEvent.KEYCODE_C
            "D" -> android.view.KeyEvent.KEYCODE_D
            "E" -> android.view.KeyEvent.KEYCODE_E
            "F" -> android.view.KeyEvent.KEYCODE_F
            "G" -> android.view.KeyEvent.KEYCODE_G
            "H" -> android.view.KeyEvent.KEYCODE_H
            "I" -> android.view.KeyEvent.KEYCODE_I
            "J" -> android.view.KeyEvent.KEYCODE_J
            "K" -> android.view.KeyEvent.KEYCODE_K
            "L" -> android.view.KeyEvent.KEYCODE_L
            "M" -> android.view.KeyEvent.KEYCODE_M
            "N" -> android.view.KeyEvent.KEYCODE_N
            "O" -> android.view.KeyEvent.KEYCODE_O
            "P" -> android.view.KeyEvent.KEYCODE_P
            "Q" -> android.view.KeyEvent.KEYCODE_Q
            "R" -> android.view.KeyEvent.KEYCODE_R
            "S" -> android.view.KeyEvent.KEYCODE_S
            "T" -> android.view.KeyEvent.KEYCODE_T
            "U" -> android.view.KeyEvent.KEYCODE_U
            "V" -> android.view.KeyEvent.KEYCODE_V
            "W" -> android.view.KeyEvent.KEYCODE_W
            "X" -> android.view.KeyEvent.KEYCODE_X
            "Y" -> android.view.KeyEvent.KEYCODE_Y
            "Z" -> android.view.KeyEvent.KEYCODE_Z
            "0" -> android.view.KeyEvent.KEYCODE_0
            "1" -> android.view.KeyEvent.KEYCODE_1
            "2" -> android.view.KeyEvent.KEYCODE_2
            "3" -> android.view.KeyEvent.KEYCODE_3
            "4" -> android.view.KeyEvent.KEYCODE_4
            "5" -> android.view.KeyEvent.KEYCODE_5
            "6" -> android.view.KeyEvent.KEYCODE_6
            "7" -> android.view.KeyEvent.KEYCODE_7
            "8" -> android.view.KeyEvent.KEYCODE_8
            "9" -> android.view.KeyEvent.KEYCODE_9
            // Shift+number symbols on standard layouts.
            "exclam", "Exclam" -> android.view.KeyEvent.KEYCODE_1
            "at", "At" -> android.view.KeyEvent.KEYCODE_2
            "numbersign", "Numbersign" -> android.view.KeyEvent.KEYCODE_3
            "dollar", "Dollar" -> android.view.KeyEvent.KEYCODE_4
            "percent", "Percent" -> android.view.KeyEvent.KEYCODE_5
            "asciicircum", "Asciicircum" -> android.view.KeyEvent.KEYCODE_6
            "ampersand", "Ampersand" -> android.view.KeyEvent.KEYCODE_7
            "asterisk", "Asterisk", "multiply", "Multiply" -> android.view.KeyEvent.KEYCODE_8
            // Parentheses are produced from Shift+9/Shift+0 on standard layouts.
            "parenleft", "Parenleft" -> android.view.KeyEvent.KEYCODE_9
            "parenright", "Parenright" -> android.view.KeyEvent.KEYCODE_0
            // Symbol keys (only Android-supported keycodes)
            "minus", "Minus" -> android.view.KeyEvent.KEYCODE_MINUS
            "underscore", "Underscore" -> android.view.KeyEvent.KEYCODE_MINUS
            "equal", "Equal" -> android.view.KeyEvent.KEYCODE_EQUALS
            "plus", "Plus", "Add" -> android.view.KeyEvent.KEYCODE_EQUALS
            "bracketleft", "Bracketleft", "Bracket_L" -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            "braceleft", "Braceleft" -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            "bracketright", "Bracketright", "Bracket_R" -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            "braceright", "Braceright" -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            "backslash", "Backslash" -> android.view.KeyEvent.KEYCODE_BACKSLASH
            "bar", "Bar" -> android.view.KeyEvent.KEYCODE_BACKSLASH
            "semicolon", "Semicolon" -> android.view.KeyEvent.KEYCODE_SEMICOLON
            "colon", "Colon" -> android.view.KeyEvent.KEYCODE_SEMICOLON
            "apostrophe", "Apostrophe" -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            "quotedbl", "Quotedbl" -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            "comma", "Comma", "less", "Less", "Separator" -> android.view.KeyEvent.KEYCODE_COMMA
            "period", "Period", "greater", "Greater" -> android.view.KeyEvent.KEYCODE_PERIOD
            "slash", "Slash", "question", "Question", "Divide" -> android.view.KeyEvent.KEYCODE_SLASH
            "grave", "Grave" -> android.view.KeyEvent.KEYCODE_GRAVE
            // KEYCODE_GRAVE + Shift can produce '~', so map asciitilde aliases here too.
            "asciitilde", "Asciitilde", "tilde", "Tilde" -> android.view.KeyEvent.KEYCODE_GRAVE
            // Special keys
            "Print" -> android.view.KeyEvent.KEYCODE_SYSRQ
            "Menu" -> android.view.KeyEvent.KEYCODE_MENU
            "Scroll_Lock" -> android.view.KeyEvent.KEYCODE_SCROLL_LOCK
            "Pause" -> android.view.KeyEvent.KEYCODE_BREAK
            "Break" -> android.view.KeyEvent.KEYCODE_BREAK
            "Insert" -> android.view.KeyEvent.KEYCODE_INSERT
            "Caps_Lock" -> android.view.KeyEvent.KEYCODE_CAPS_LOCK
            "Num_Lock" -> android.view.KeyEvent.KEYCODE_NUM_LOCK
            "Super_L" -> android.view.KeyEvent.KEYCODE_META_LEFT
            "Super_R" -> android.view.KeyEvent.KEYCODE_META_RIGHT
            // Numpad keys
            "KP_Space" -> android.view.KeyEvent.KEYCODE_SPACE
            "KP_Tab" -> android.view.KeyEvent.KEYCODE_TAB
            "KP_0" -> android.view.KeyEvent.KEYCODE_NUMPAD_0
            "KP_1" -> android.view.KeyEvent.KEYCODE_NUMPAD_1
            "KP_2" -> android.view.KeyEvent.KEYCODE_NUMPAD_2
            "KP_3" -> android.view.KeyEvent.KEYCODE_NUMPAD_3
            "KP_4" -> android.view.KeyEvent.KEYCODE_NUMPAD_4
            "KP_5" -> android.view.KeyEvent.KEYCODE_NUMPAD_5
            "KP_6" -> android.view.KeyEvent.KEYCODE_NUMPAD_6
            "KP_7" -> android.view.KeyEvent.KEYCODE_NUMPAD_7
            "KP_8" -> android.view.KeyEvent.KEYCODE_NUMPAD_8
            "KP_9" -> android.view.KeyEvent.KEYCODE_NUMPAD_9
            "KP_Enter" -> android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            "KP_Add" -> android.view.KeyEvent.KEYCODE_NUMPAD_ADD
            "KP_Subtract" -> android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            "KP_Multiply" -> android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            "KP_Divide" -> android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE
            "KP_Decimal" -> android.view.KeyEvent.KEYCODE_NUMPAD_DOT
            "KP_Equal" -> android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS
            "KP_Separator" -> android.view.KeyEvent.KEYCODE_NUMPAD_COMMA
            else -> {
                // For other keys, try converting to lowercase letters
                val lower = code.lowercase()
                if (lower.length == 1 && lower[0] in 'a'..'z') {
                    android.view.KeyEvent.KEYCODE_A + (lower[0] - 'a')
                } else {
                    // Unknown key: default to Fcitx
                    -1
                }
            }
        }
    }

    private fun mapSpecialFcitxToAndroidKey(code: String): Int? {
        return when (code) {
            "Shift_L" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "Shift_R" -> KeyEvent.KEYCODE_SHIFT_RIGHT
            "Ctrl_L" -> KeyEvent.KEYCODE_CTRL_LEFT
            "Ctrl_R" -> KeyEvent.KEYCODE_CTRL_RIGHT
            "Alt_L" -> KeyEvent.KEYCODE_ALT_LEFT
            "Alt_R" -> KeyEvent.KEYCODE_ALT_RIGHT
            "Meta_L" -> KeyEvent.KEYCODE_META_LEFT
            "Meta_R" -> KeyEvent.KEYCODE_META_RIGHT
            "Super_L" -> KeyEvent.KEYCODE_META_LEFT
            "Super_R" -> KeyEvent.KEYCODE_META_RIGHT
            "Hyper_L" -> KeyEvent.KEYCODE_FUNCTION
            "Hyper_R" -> KeyEvent.KEYCODE_FUNCTION
            "Mode_switch" -> KeyEvent.KEYCODE_ALT_RIGHT
            "ISO_Level3_Shift" -> KeyEvent.KEYCODE_ALT_RIGHT
            "ISO_Level5_Shift" -> KeyEvent.KEYCODE_ALT_RIGHT
            else -> null
        }
    }

    private fun shouldShiftSymbol(code: String): Boolean {
        return when (code.lowercase()) {
            "underscore", "plus", "braceleft", "braceright", "bar",
            "colon", "quotedbl", "less", "greater", "question",
            "exclam", "at", "numbersign", "dollar", "percent",
            "asciicircum", "ampersand", "asterisk", "multiply", "parenleft", "parenright",
            "add",
            "asciitilde", "tilde" -> true
            else -> false
        }
    }

    private fun mapFcitxToScanCode(code: String, keyCode: Int): Int {
        val known = when (code) {
            "Shift_L" -> 42
            "Shift_R" -> 54
            "Ctrl_L" -> 29
            "Ctrl_R" -> 97
            "Alt_L" -> 56
            "Alt_R" -> 100
            "Meta_L" -> 125
            "Meta_R" -> 126
            "F1" -> 59
            "F2" -> 60
            "F3" -> 61
            "F4" -> 62
            "F5" -> 63
            "F6" -> 64
            "F7" -> 65
            "F8" -> 66
            "F9" -> 67
            "F10" -> 68
            "F11" -> 87
            "F12" -> 88
            else -> null
        }
        if (known != null) {
            return known
        }
        return if (keyCode >= 0) {
            // Use try-catch to handle potential exceptions from generated code
            try {
                val uncached = Int.MIN_VALUE
                val cached = cachedMacroScancodes.get(keyCode, uncached)
                val scanCode = if (cached != uncached) cached else {
                    ScancodeMapping.keyCodeToScancode(keyCode).also {
                        cachedMacroScancodes.put(keyCode, it)
                    }
                }
                if (scanCode != 0) scanCode else 0
            } catch (e: Exception) {
                Timber.w("keyCodeToScancode failed for keyCode=$keyCode: ${e.message}")
                0
            }
        } else 0
    }

    /**
     * Send Fcitx key tap event (down + up)
     */
    private suspend fun sendFcitxKeyTap(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        // For modifier keys (e.g. Shift), keep press time longer so Rime can recognize standalone Shift
        val isMod = isModifierKey(code)
        val keyHoldDelayMs = if (isMod) 150L else 50L

        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            }
            // Key down
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            delay(keyHoldDelayMs)
            // Key up
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP, fromMacro = true)
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_UP, fromMacro = true)
            }
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = false),
                KeyActionListener.Source.Keyboard
            )
            delay(keyHoldDelayMs)
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = true),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Send Fcitx key down event
     */
    private fun sendFcitxKeyDown(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            }
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = false),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Send Fcitx key up event
     */
    private fun sendFcitxKeyUp(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP, fromMacro = true)
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_UP, fromMacro = true)
            }
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = true),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Get FcitxInputMethodService instance
     */
    private fun getService(): org.fcitx.fcitx5.android.input.FcitxInputMethodService? {
        // Try obtaining directly from context
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is org.fcitx.fcitx5.android.input.FcitxInputMethodService) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return context as? org.fcitx.fcitx5.android.input.FcitxInputMethodService
    }

    protected fun isSimulatedCapsLockOn(): Boolean {
        return getService()?.isSimulatedCapsLockOn() == true
    }

    /**
     * Send Android key down event
     */
    private fun sendAndroidKeyDown(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val event = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(event)
    }

    /**
     * Send Android key up event
     */
    private fun sendAndroidKeyUp(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val event = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(event)
    }

    /**
     * Send Android key tap event (down + up)
     */
    private fun sendAndroidKeyTap(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val downEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN,
            keyCode
        )
        val upEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(downEvent)
        service.currentInputConnection?.sendKeyEvent(upEvent)
    }

    /**
     * Commit text
     */
    private fun commitText(text: String) {
        keyActionListener?.onKeyAction(KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        if (action is PopupAction.PreviewAction || action is PopupAction.ShowKeyboardAction ||
            action is PopupAction.ShowLongPressKeyboardAction || action is PopupAction.ShowMenuAction
        ) {
            dismissAllPopups()
        }
        popupActionListener?.onPopupAction(action)
    }

    private fun dismissAllPopups() {
        popupActionListener?.onPopupAction(PopupAction.DismissAllAction())
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    open fun onAttach() {
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onReturnDrawableOverride(drawable: Drawable?) {
        // do nothing by default - override in subclasses that render return key icons
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        // do nothing by default
    }

    open fun onDetach() {
        // do nothing by default
    }

}

class AuxBarAdapter(
    private val theme: Theme,
    private val vMargin: Int,
    private val hMargin: Int,
    private val position: AuxBarPosition,
    private val keyTextSize: Float,
    private val onTrigger: (Int) -> Unit
) : RecyclerView.Adapter<AuxBarAdapter.ViewHolder>() {

    private var actions = listOf<AuxBarAction>()

    private val keyBorder: Boolean by lazy { ThemeManager.prefs.keyBorder.getValue() }
    private val keyBorderStroke: Boolean by lazy { ThemeManager.prefs.keyBorderStroke.getValue() }
    private var radius: Float = 0f
    private var minItemHeightPx: Int = -1

    fun setMinItemHeight(px: Int) {
        if (px > 0 && px != minItemHeightPx) {
            minItemHeightPx = px
            notifyDataSetChanged()
        }
    }

    fun updateActions(newActions: List<AuxBarAction>) {
        if (newActions == actions) return
        actions = newActions
        notifyDataSetChanged()
    }

    fun applyConfiguredFonts(rv: RecyclerView) {
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? ViewHolder ?: continue
            holder.label.setFontTypeFace("key_main_font")
        }
    }

    override fun getItemCount() = actions.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        radius = ctx.dp(ThemeManager.prefs.keyRadius.getValue().toFloat())
        val isHorizontal = position == AuxBarPosition.Top || position == AuxBarPosition.Bottom
        val view = CustomGestureView(ctx).apply {
            val extraHPad = if (isHorizontal) ctx.dp(12) else 0
            setPadding(hMargin + extraHPad, vMargin, hMargin + extraHPad, vMargin)
            layoutParams = ViewGroup.LayoutParams(
                if (isHorizontal) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (isHorizontal) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val label = AutoScaleTextView(ctx).apply {
                scaleMode = AutoScaleTextView.Mode.Proportional
                setTextSize(TypedValue.COMPLEX_UNIT_SP, FontProviders.getFontSize("key_main_font", keyTextSize))
                typeface = Typeface.DEFAULT
                fontKey = "key_main_font"
                isSingleLine = true
                gravity = gravityCenter
                setTextColor(theme.keyTextColor)
            }
            val childLp = ViewGroup.LayoutParams(
                if (isHorizontal) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (isHorizontal) ViewGroup.LayoutParams.MATCH_PARENT else
                    if (minItemHeightPx > 0) minItemHeightPx else ctx.dp(52)
            )
            addView(label, childLp)
            tag = label
        }
        applyThemeStyling(view)
        return ViewHolder(view)
    }

    private fun applyThemeStyling(view: CustomGestureView) {
        val ctx = view.context
        val drawHMargin = hMargin
        val drawVMargin = vMargin
        val bkgColor = theme.keyBackgroundColor
        val shadowColor = theme.keyShadowColor
        if (keyBorder && keyBorderStroke) {
            view.background = borderedKeyBackgroundDrawable(
                bkgColor, shadowColor, radius, ctx.dp(1), drawHMargin, drawVMargin
            )
            view.foreground = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    borderedKeyBackgroundDrawable(
                        0, theme.keyPressHighlightColor,
                        radius, ctx.dp(2), drawHMargin, drawVMargin
                    )
                )
            }
        } else if (keyBorder) {
            view.background = shadowedKeyBackgroundDrawable(
                bkgColor, shadowColor, radius, ctx.dp(1), drawHMargin, drawVMargin
            )
            view.foreground = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    insetRadiusDrawable(drawHMargin, drawVMargin, radius, theme.keyPressHighlightColor)
                )
            }
        } else {
            // Match borderless key rendering in KeyView: no persistent key background,
            // only pressed-state highlight over the keyboard/bar surface.
            view.background = null
            view.foreground = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    InsetDrawable(ColorDrawable(theme.keyPressHighlightColor), drawHMargin, drawVMargin, drawHMargin, drawVMargin)
                )
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions.getOrNull(position) ?: return
        holder.bind(action, onTrigger)
    }

    class ViewHolder(val view: CustomGestureView) : RecyclerView.ViewHolder(view) {
        internal val label: AutoScaleTextView get() = view.tag as AutoScaleTextView

        fun bind(action: AuxBarAction, onTrigger: (Int) -> Unit) {
            label.text = action.text
            label.setFontTypeFace("key_main_font")
            view.setOnClickListener { onTrigger(action.id) }
        }
    }
}

/**
 * Adapter for the auxiliary bar that renders user-configured keys (used when the
 * layout has an in-keyboard aux bar position but there are no tabs to display).
 */
class AuxBarKeyAdapter(
    private val vertical: Boolean,
    private val keyViewFactory: (KeyDef) -> KeyView
) : RecyclerView.Adapter<AuxBarKeyAdapter.ViewHolder>() {

    private var keys = listOf<KeyDef>()
    private var keySignatures = listOf<String>()
    private var minItemHeightPx: Int = -1
    private var attachedRecyclerView: RecyclerView? = null
    private var lastHorizontalWidth: Int = -1
    private val horizontalLayoutChangeListener =
        View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (vertical) return@OnLayoutChangeListener
            val width = right - left
            val oldWidth = oldRight - oldLeft
            if (width > 0 && width != oldWidth && width != lastHorizontalWidth) {
                lastHorizontalWidth = width
                notifyDataSetChanged()
            }
        }

    fun setMinItemHeight(px: Int) {
        if (px > 0 && px != minItemHeightPx) {
            minItemHeightPx = px
            notifyDataSetChanged()
        }
    }

    fun updateKeys(newKeys: List<KeyDef>) {
        val newSignatures = newKeys.map(::buildKeySignature)
        if (newSignatures == keySignatures) return
        keys = newKeys
        keySignatures = newSignatures
        notifyDataSetChanged()
    }

    override fun getItemCount() = keys.size

    override fun getItemId(position: Int): Long {
        val sig = keySignatures.getOrNull(position) ?: return RecyclerView.NO_ID
        return sig.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val view = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                if (!vertical) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (!vertical) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (vertical) {
                minimumHeight = if (minItemHeightPx > 0) minItemHeightPx else ctx.dp(52)
            }
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = keys.getOrNull(position) ?: return
        val signature = keySignatures.getOrNull(position) ?: buildKeySignature(key)
        if (!vertical) {
            val rv = attachedRecyclerView ?: (holder.itemView.parent as? RecyclerView)
            val usableWidth = rv?.let { (it.width - it.paddingLeft - it.paddingRight).coerceAtLeast(0) } ?: 0
            if (usableWidth > 0 && itemCount > 0) {
                val lp = holder.itemView.layoutParams as? RecyclerView.LayoutParams
                    ?: RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                lp.width = usableWidth / itemCount
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                lp.rightMargin = 0
                holder.itemView.layoutParams = lp
            }
        }
        holder.bind(key, signature)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        if (!vertical) {
            recyclerView.addOnLayoutChangeListener(horizontalLayoutChangeListener)
            lastHorizontalWidth = recyclerView.width
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (!vertical) {
            recyclerView.removeOnLayoutChangeListener(horizontalLayoutChangeListener)
            lastHorizontalWidth = -1
        }
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(private val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        private var boundSignature: String? = null
        private var keyView: KeyView? = null

        fun bind(key: KeyDef, signature: String) {
            var current = keyView
            if (current == null || boundSignature != signature) {
                container.removeAllViews()
                current = keyViewFactory(key).also { created ->
                    created.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
                    container.addView(
                        created,
                        ViewGroup.LayoutParams(
                            if (!vertical) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                            if (!vertical) ViewGroup.LayoutParams.MATCH_PARENT else
                                if (minItemHeightPx > 0) minItemHeightPx else container.context.dp(52)
                        )
                    )
                }
                keyView = current
                boundSignature = signature
            }
            if (vertical) {
                current.layoutParams = (current.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )).apply {
                    height = if (minItemHeightPx > 0) minItemHeightPx else container.context.dp(52)
                }
            } else {
                current.layoutParams = (current.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }
    }

    private fun buildKeySignature(key: KeyDef): String {
        val app = key.appearance
        val popupSig = key.popup?.joinToString("|") { it::class.java.name }.orEmpty()
        val behaviorSig = key.behaviors
            .sortedBy { it::class.java.name + it.hashCode() }
            .joinToString("|") { it::class.java.name + ":" + it.hashCode() }
        return buildString(128) {
            append(key::class.java.name)
            append('#').append(app::class.java.name)
            append('#').append(app.variant.name)
            append('#').append(app.border.name)
            append('#').append(app.margin)
            append('#').append(app.percentWidth)
            append('#').append(app.viewId)
            append('#').append(app.textColor)
            append('#').append(app.textColorMonet)
            append('#').append(app.altTextColor)
            append('#').append(app.altTextColorMonet)
            append('#').append(app.backgroundColor)
            append('#').append(app.backgroundColorMonet)
            append('#').append(app.shadowColor)
            append('#').append(app.shadowColorMonet)
            append('#').append(behaviorSig)
            append('#').append(popupSig)
        }
    }

    init {
        setHasStableIds(true)
    }
}
