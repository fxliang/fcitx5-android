/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.DraggableFlowLayout
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import kotlin.math.abs

/**
 * RecyclerView adapter for displaying and editing keyboard layout rows and keys.
 *
 * Features:
 * - Display rows and keys list
 * - Support row drag-to-reorder
 * - Support key drag-to-reorder
 * - Add/remove rows and keys
 */
class KeyboardLayoutAdapter(
    private val context: Context,
    private var rows: List<MutableList<MutableMap<String, Any?>>>,
    internal val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private data class KeyChildInfo(
        val dataIndex: Int,
        val centerX: Float,
        val centerY: Float,
        val height: Int
    )

    private data class CrossRowPreviewState(
        var initialRow: Int = -1,
        var initialIndex: Int = -1,
        var currentRow: Int = -1,
        var currentIndex: Int = -1,
        var targetRow: Int = -1,
        var targetIndex: Int = -1,
        var draggedKey: MutableMap<String, Any?>? = null,
        var lastStepAt: Long = 0L
    )

    // These are set via setupRowDragTrigger
    private var rowsRecyclerView: RecyclerView? = null
    private var rowTouchHelper: ItemTouchHelper? = null
    private val previewState = CrossRowPreviewState()

    /**
     * Setup row drag trigger - must be called after adapter is created
     */
    fun setupRowDragTrigger(recyclerView: RecyclerView, touchHelper: ItemTouchHelper?) {
        rowsRecyclerView = recyclerView
        rowTouchHelper = touchHelper
    }

    interface Listener {
        /** Called when a key is clicked */
        fun onKeyClick(rowIndex: Int, keyIndex: Int)

        /** Called when the add key button is clicked */
        fun onAddKeyClick(rowIndex: Int)

        /** Called when the delete row button is clicked */
        fun onDeleteRowClick(rowIndex: Int)

        /** Called when the add row button is clicked */
        fun onAddRowClick()

        /** Called when a row drag position changes */
        fun onRowPositionChanged(from: Int, to: Int)

        /** Called when a row drag ends */
        fun onRowDragEnded()

        /** Called when a key drag position changes */
        fun onKeyPositionChanged(rowIndex: Int, from: Int, to: Int)

        /** Called when a key drag ends */
        fun onKeyDragEnded(rowIndex: Int)

        /** Called when a key is moved across rows */
        fun onKeyMovedAcrossRows(fromRow: Int, fromIndex: Int, toRow: Int, toIndex: Int)
    }

    private companion object {
        private const val VIEW_TYPE_ROW = 0
        private const val VIEW_TYPE_ADD_ROW = 1
        private const val CROSS_ROW_STEP_DELAY_MS = 100L
    }

    /**
     * Update rows data.
     * Posts the update to avoid calling notifyDataSetChanged() during RecyclerView layout/scroll.
     */
    fun updateRows(newRows: List<MutableList<MutableMap<String, Any?>>>) {
        rows = newRows
        rowsRecyclerView?.post { notifyDataSetChanged() }
    }

    /**
     * Notify that a single key has changed (call after modifying key data).
     * Uses notifyItemChanged to trigger RecyclerView to re-measure the row (handles key size changes).
     */
    fun notifyKeyChanged(rowIndex: Int, keyIndex: Int) {
        notifyItemChanged(rowIndex)
    }

    /**
     * Notify that an entire row has changed (call after adding/removing keys).
     */
    fun notifyRowChanged(rowIndex: Int) {
        notifyItemChanged(rowIndex)
    }

    /**
     * Notify that a new row has been inserted.
     */
    fun notifyRowInserted(position: Int) {
        notifyItemInserted(position)
        // Notify that the "add row" button position has changed
        notifyItemChanged(itemCount - 1)
    }

    /**
     * Notify that a row has been removed.
     */
    fun notifyRowRemoved(position: Int) {
        notifyItemRemoved(position)
        // Notify that the "add row" button position has changed
        notifyItemChanged(itemCount - 1)
    }

    /**
     * Notify that a row has been moved (drag-to-reorder).
     */
    fun notifyRowMoved(fromPosition: Int, toPosition: Int) {
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == rows.size) VIEW_TYPE_ADD_ROW else VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ROW -> createRowViewHolder(parent)
            VIEW_TYPE_ADD_ROW -> createAddRowViewHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    private fun createRowViewHolder(parent: ViewGroup): RowViewHolder {
        val rowContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(4), 0, context.dp(4))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }

        // Drag handle on the left
        val dragHandle = TextView(context).apply {
            text = "☰"
            textSize = 16f
            setPadding(context.dp(8), context.dp(8), context.dp(4), context.dp(8))
            setTextColor(context.styledColor(android.R.attr.textColorSecondary))
            alpha = 0.5f
            minWidth = context.dp(32)
        }
        rowContainer.addView(dragHandle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        // FlowLayout for keys with auto-wrap
        val keysFlowContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }

        val keysFlow = DraggableFlowLayout(context).apply {
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        keysFlowContainer.addView(keysFlow)
        rowContainer.addView(keysFlowContainer)

        // Delete row button on the right
        val deleteRowButton = TextView(context).apply {
            text = "🗑"
            textSize = 14f
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
            minWidth = context.dp(36)
        }
        rowContainer.addView(deleteRowButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val viewHolder = RowViewHolder(rowContainer, dragHandle, keysFlow, deleteRowButton)
        // Setup listeners for fixed parts (called only once)
        setupRowViewHolder(viewHolder)
        return viewHolder
    }

    private fun createAddRowViewHolder(parent: ViewGroup): AddRowViewHolder {
        val addRowButton = TextView(context).apply {
            text = context.getString(R.string.text_keyboard_layout_add_row)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            minWidth = context.dp(160)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(context.styledColor(android.R.attr.colorPrimary))
                setStroke(context.dp(1), context.styledColor(android.R.attr.colorControlNormal))
                cornerRadius = context.dp(4).toFloat()
            }
            setOnClickListener { listener.onAddRowClick() }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, context.dp(16), 0, context.dp(8))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            addView(addRowButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return AddRowViewHolder(container, addRowButton)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is RowViewHolder -> {
                // Rebind keys (listeners for fixed parts like delete button are already set in createRowViewHolder)
                bindRowKeys(holder, position)
            }
            is AddRowViewHolder -> {
                // Add row button doesn't need binding, click listener is set in onCreateViewHolder
            }
        }
    }

    /**
     * Setup fixed parts of RowViewHolder (drag handle, delete button, etc.).
     * Called only once during creation, uses bindingAdapterPosition to get current position dynamically.
     */
    private fun setupRowViewHolder(holder: RowViewHolder) {
        // Delete button click listener - uses bindingAdapterPosition to get current position dynamically
        holder.deleteButton.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                listener.onDeleteRowClick(adapterPosition)
            }
        }

        var downOnKeyChip = false
        val startRowDragIfAllowed: () -> Boolean = {
            if (holder.keysFlow is DraggableFlowLayout && holder.keysFlow.isDragging) {
                false
            } else {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    rowsRecyclerView?.findViewHolderForAdapterPosition(adapterPosition)?.let { viewHolder ->
                        rowTouchHelper?.startDrag(viewHolder)
                        true
                    } ?: false
                } else {
                    false
                }
            }
        }

        // Setup drag handle for row reordering (only if not dragging a key)
        holder.dragHandle.setOnLongClickListener {
            startRowDragIfAllowed()
        }

        // Allow row drag when long-press starts in keysFlow blank area
        holder.keysFlow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downOnKeyChip = holder.keysFlow.isTouchOnAnyChild(event.x, event.y)
                }
            }
            false
        }
        holder.keysFlow.setOnLongClickListener {
            if (downOnKeyChip) false else startRowDragIfAllowed()
        }

        // Add touch feedback for drag handle
        holder.dragHandle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.3f
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.5f
                }
            }
            false
        }
    }

    /**
     * Bind keys in a row (can be called multiple times for partial refresh).
     */
    private fun bindRowKeys(holder: RowViewHolder, position: Int) {
        val row = rows[position]
        holder.keysFlow.removeAllViews()

        // Setup drag listener for key reordering (once per row, not per key)
        if (holder.keysFlow is DraggableFlowLayout && holder.keysFlow.onDragListener == null) {
            holder.keysFlow.onDragListener = object : DraggableFlowLayout.OnDragListener {
                override fun onDragStarted(view: View, position: Int) {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition in rows.indices && position in rows[adapterPosition].indices) {
                        clearCrossRowPreview()
                        previewState.initialRow = adapterPosition
                        previewState.initialIndex = position
                        previewState.currentRow = adapterPosition
                        previewState.currentIndex = position
                        previewState.draggedKey = rows[adapterPosition][position]
                    }
                }

                override fun onDragPositionChanged(from: Int, to: Int) {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        if (previewState.targetRow != -1 && previewState.targetRow != adapterPosition) return
                        listener.onKeyPositionChanged(adapterPosition, from, to)
                        if (previewState.currentRow == adapterPosition) {
                            previewState.currentIndex = to
                        }
                    }
                }

                override fun onDragMoved(view: View, rawX: Float, rawY: Float) {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        updateCrossRowPreview(holder, view, adapterPosition, rawX, rawY)
                        holder.keysFlow
                            .setInternalReorderSuppressed(previewState.targetRow != -1 && previewState.targetRow != adapterPosition)
                    }
                }

                override fun onDragEnded(view: View, position: Int) {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        val movedByPreview = commitCrossRowPreviewIfNeeded()
                        if (!movedByPreview) {
                            val flow = holder.keysFlow
                            maybeMoveDraggedKeyAcrossRows(
                                holder = holder,
                                dragView = view,
                                currentRowIndex = adapterPosition,
                                currentIndex = position,
                                touchRawX = flow.lastTouchRawX,
                                touchRawY = flow.lastTouchRawY
                            )
                        }
                        listener.onKeyDragEnded(adapterPosition)
                    }
                    holder.keysFlow.setInternalReorderSuppressed(false)
                    clearCrossRowPreviewAndRefresh()
                }
            }
        }

        val displayRow = buildDisplayRowForPreview(position, row)

        // Add keys - short click to edit, with drag support (directly on the key)
        displayRow.forEachIndexed { keyIndex, key ->
            val actualKeyIndex = resolveActualKeyIndex(position, keyIndex)
            val type = key["type"] as? String ?: ""
            val isMacroKey = type == "MacroKey"
            val hasComposeOverride = key["composeOverride"] is Map<*, *>
            val keyChip = TextView(context).apply {
                text = buildKeyLabel(key)
                textSize = 14f
                setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    if (isMacroKey) {
                        // MacroKey: 使用主题强调色区分
                        setColor(context.styledColor(android.R.attr.colorAccent))
                        setStroke(context.dp(2), context.styledColor(android.R.attr.colorControlHighlight))
                    } else {
                        setColor(context.styledColor(android.R.attr.colorButtonNormal))
                        setStroke(context.dp(1), context.styledColor(android.R.attr.colorControlNormal))
                    }
                    cornerRadius = context.dp(4).toFloat()
                }
                setTextColor(
                    when {
                        isMacroKey -> context.styledColor(android.R.attr.textColorPrimaryInverse)
                        hasComposeOverride -> context.styledColor(android.R.attr.colorAccent)
                        else -> context.styledColor(android.R.attr.textColorPrimary)
                    }
                )
                setOnClickListener {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION && actualKeyIndex >= 0) {
                        listener.onKeyClick(adapterPosition, actualKeyIndex)
                    }
                }
            }

            holder.keysFlow.addView(keyChip, ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = context.dp(6)
                bottomMargin = context.dp(4)
                topMargin = context.dp(4)
            })
        }

        // Add button (same style as other keys)
        val addKeyChip = TextView(context).apply {
            text = "+"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(context.styledColor(android.R.attr.colorPrimary))
                setStroke(context.dp(1), context.styledColor(android.R.attr.colorControlNormal))
                cornerRadius = context.dp(4).toFloat()
            }
            setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    listener.onAddKeyClick(adapterPosition)
                }
            }
        }
        holder.keysFlow.addView(addKeyChip, ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = context.dp(6)
            bottomMargin = context.dp(4)
            topMargin = context.dp(4)
        })
    }

    private fun buildDisplayRowForPreview(
        rowIndex: Int,
        baseRow: MutableList<MutableMap<String, Any?>>
    ): List<MutableMap<String, Any?>> {
        if (previewState.draggedKey == null || previewState.targetRow == -1 || previewState.targetIndex == -1) return baseRow
        if (previewState.targetRow == previewState.currentRow) return baseRow

        val draggedKey = previewState.draggedKey ?: return baseRow
        val display = baseRow.toMutableList()
        if (rowIndex == previewState.currentRow && previewState.currentIndex in display.indices) {
            display.removeAt(previewState.currentIndex)
        }
        if (rowIndex == previewState.targetRow) {
            val insertAt = previewState.targetIndex.coerceIn(0, display.size)
            display.add(insertAt, draggedKey)
        }
        return display
    }

    private fun resolveActualKeyIndex(rowIndex: Int, displayedIndex: Int): Int {
        if (previewState.draggedKey == null || previewState.targetRow == -1 || previewState.targetIndex == -1) return displayedIndex
        if (previewState.targetRow == previewState.currentRow) return displayedIndex
        if (rowIndex == previewState.targetRow && displayedIndex == previewState.targetIndex) {
            return -1
        }
        return displayedIndex
    }

    private fun clearCrossRowPreviewAndRefresh() {
        val rowsToRefresh = setOf(
            previewState.currentRow,
            previewState.targetRow
        ).filter { it in rows.indices }
        clearCrossRowPreview()
        rowsToRefresh.forEach { notifyItemChanged(it) }
    }

    private fun maybeMoveDraggedKeyAcrossRows(
        holder: RowViewHolder,
        dragView: View,
        currentRowIndex: Int,
        currentIndex: Int,
        touchRawX: Float?,
        touchRawY: Float?
    ) {
        if (rows.isEmpty() || currentRowIndex !in rows.indices) return
        val targetRowIndex = detectTargetRowIndex(holder, dragView, currentRowIndex, touchRawX, touchRawY)
        if (targetRowIndex == currentRowIndex) return

        val sourceRow = rows[currentRowIndex]
        if (currentIndex !in sourceRow.indices) return
        val moving = sourceRow.removeAt(currentIndex)
        val targetRow = rows[targetRowIndex]
        val rawX = touchRawX ?: run {
            val dragLoc = IntArray(2)
            dragView.getLocationOnScreen(dragLoc)
            dragLoc[0] + dragView.width / 2f
        }
        val rawY = touchRawY ?: run {
            val dragLoc = IntArray(2)
            dragView.getLocationOnScreen(dragLoc)
            dragLoc[1] + dragView.height / 2f
        }
        val safeInsertIndex = computeInsertIndexForRow(targetRowIndex, rawX, rawY, holder.keysFlow.width)
        targetRow.add(safeInsertIndex, moving)

        notifyItemChanged(currentRowIndex)
        notifyItemChanged(targetRowIndex)
        listener.onKeyMovedAcrossRows(
            currentRowIndex,
            currentIndex,
            targetRowIndex,
            safeInsertIndex
        )
    }

    private fun updateCrossRowPreview(
        holder: RowViewHolder,
        dragView: View,
        currentRowIndex: Int,
        rawX: Float,
        rawY: Float
    ) {
        if (previewState.currentRow !in rows.indices || previewState.currentIndex !in rows[previewState.currentRow].indices) return
        val targetRowIndex = detectTargetRowIndex(
            holder = holder,
            dragView = dragView,
            currentRowIndex = currentRowIndex,
            touchRawX = rawX,
            touchRawY = rawY
        )
        if (targetRowIndex == currentRowIndex) {
            if (previewState.targetRow != -1) {
                val rowsToRefresh = setOf(previewState.targetRow).filter { it in rows.indices }
                previewState.targetRow = -1
                previewState.targetIndex = -1
                rowsToRefresh.forEach { notifyItemChanged(it) }
            }
            return
        }
        if (targetRowIndex !in rows.indices) return

        val safeTargetIndex = computeInsertIndexForRow(targetRowIndex, rawX, rawY, holder.keysFlow.width)
        val steppedTargetIndex = stepPreviewTargetIndex(targetRowIndex, safeTargetIndex)

        val previewChanged = previewState.targetRow != targetRowIndex || previewState.targetIndex != steppedTargetIndex
        if (!previewChanged) return

        val rowsToRefresh = setOf(
            previewState.targetRow,
            targetRowIndex
        ).filter { it in rows.indices }
        previewState.targetRow = targetRowIndex
        previewState.targetIndex = steppedTargetIndex
        rowsToRefresh.forEach { notifyItemChanged(it) }
    }

    private fun commitCrossRowPreviewIfNeeded(): Boolean {
        val sourceRowIndex = previewState.currentRow
        val sourceIndex = previewState.currentIndex
        val targetRowIndex = previewState.targetRow
        val targetIndex = previewState.targetIndex
        if (sourceRowIndex !in rows.indices || sourceIndex !in rows[sourceRowIndex].indices) return false
        if (targetRowIndex !in rows.indices || targetIndex < 0) return false
        if (sourceRowIndex == targetRowIndex) return false

        val moving = rows[sourceRowIndex].removeAt(sourceIndex)
        val safeTargetIndex = targetIndex.coerceIn(0, rows[targetRowIndex].size)
        rows[targetRowIndex].add(safeTargetIndex, moving)
        notifyItemChanged(sourceRowIndex)
        notifyItemChanged(targetRowIndex)
        listener.onKeyMovedAcrossRows(sourceRowIndex, sourceIndex, targetRowIndex, safeTargetIndex)
        return true
    }

    private fun clearCrossRowPreview() {
        previewState.initialRow = -1
        previewState.initialIndex = -1
        previewState.currentRow = -1
        previewState.currentIndex = -1
        previewState.targetRow = -1
        previewState.targetIndex = -1
        previewState.draggedKey = null
        previewState.lastStepAt = 0L
    }

    private fun detectTargetRowIndex(
        holder: RowViewHolder,
        dragView: View,
        currentRowIndex: Int,
        touchRawX: Float?,
        touchRawY: Float?
    ): Int {
        val rv = rowsRecyclerView ?: return currentRowIndex
        val rvLoc = IntArray(2)
        rv.getLocationOnScreen(rvLoc)
        val rawX = touchRawX ?: run {
            val dragLoc = IntArray(2)
            dragView.getLocationOnScreen(dragLoc)
            dragLoc[0] + dragView.width / 2f
        }
        val rawY = touchRawY ?: run {
            val dragLoc = IntArray(2)
            dragView.getLocationOnScreen(dragLoc)
            dragLoc[1] + dragView.height / 2f
        }
        val localX = rawX - rvLoc[0]
        val localY = rawY - rvLoc[1]

        val rowFromCenter = rv.findChildViewUnder(localX, localY)
            ?.let { rv.getChildViewHolder(it) as? RowViewHolder }
            ?.bindingAdapterPosition
            ?.takeIf { it in rows.indices }
        if (rowFromCenter != null) return rowFromCenter

        val threshold = context.dp(12).toFloat()
        return when {
            dragView.y < -threshold && currentRowIndex > 0 -> currentRowIndex - 1
            dragView.y + dragView.height > holder.keysFlow.height + threshold && currentRowIndex < rows.lastIndex -> currentRowIndex + 1
            else -> currentRowIndex
        }
    }

    private fun computeInsertIndexForRow(targetRowIndex: Int, rawX: Float, rawY: Float, fallbackFlowWidth: Int): Int {
        val targetRow = rows.getOrNull(targetRowIndex) ?: return 0
        val targetSize = targetRow.size
        if (targetSize == 0) return 0

        val targetHolder = rowsRecyclerView
            ?.findViewHolderForAdapterPosition(targetRowIndex) as? RowViewHolder
        val targetFlow = targetHolder?.keysFlow
        if (targetFlow != null && targetFlow.width > 0) {
            val flowLoc = IntArray(2)
            targetFlow.getLocationOnScreen(flowLoc)
            val localX = rawX - flowLoc[0]
            val localY = rawY - flowLoc[1]
            val keyChildCount = (targetFlow.childCount - 1).coerceAtLeast(0) // exclude trailing "+"
            if (keyChildCount > 0) {
                val hasPreviewPlaceholder =
                    previewState.targetRow == targetRowIndex &&
                        previewState.targetRow != previewState.currentRow &&
                        previewState.targetIndex in 0..targetSize
                var dataIndexCursor = 0
                val keyChildren = mutableListOf<KeyChildInfo>()
                for (i in 0 until keyChildCount) {
                    if (hasPreviewPlaceholder && i == previewState.targetIndex) continue
                    val child = targetFlow.getChildAt(i) ?: continue
                    keyChildren.add(
                        KeyChildInfo(
                            dataIndex = dataIndexCursor,
                            centerX = child.x + child.width / 2f,
                            centerY = child.y + child.height / 2f,
                            height = child.height
                        )
                    )
                    dataIndexCursor++
                }
                if (keyChildren.isEmpty()) return 0

                // Match the visual line first (y), then compute insertion in that line by x.
                val anchor = keyChildren.minByOrNull { abs(it.centerY - localY) } ?: keyChildren.first()
                val lineThreshold = maxOf(anchor.height * 0.6f, context.dp(8).toFloat())
                val lineChildren = keyChildren.filter { abs(it.centerY - anchor.centerY) <= lineThreshold }
                    .sortedBy { it.centerX }

                val line = if (lineChildren.isNotEmpty()) lineChildren else keyChildren.sortedBy { it.centerX }
                for (childInfo in line) {
                    if (localX < childInfo.centerX) return childInfo.dataIndex
                }
                return (line.last().dataIndex + 1).coerceIn(0, targetSize)
            }
            val ratio = (localX / targetFlow.width).coerceIn(0f, 1f)
            return (ratio * (targetSize + 1)).toInt().coerceIn(0, targetSize)
        }

        return if (rawX < fallbackFlowWidth / 2f) 0 else targetSize
    }

    private fun stepPreviewTargetIndex(targetRowIndex: Int, rawTargetIndex: Int): Int {
        val targetSize = rows.getOrNull(targetRowIndex)?.size ?: return rawTargetIndex
        val boundedRaw = rawTargetIndex.coerceIn(0, targetSize)
        if (previewState.targetRow != targetRowIndex || previewState.targetIndex !in 0..targetSize) {
            previewState.lastStepAt = 0L
            return boundedRaw
        }
        val current = previewState.targetIndex
        val now = System.currentTimeMillis()
        if (now - previewState.lastStepAt < CROSS_ROW_STEP_DELAY_MS) return current
        previewState.lastStepAt = now
        return when {
            boundedRaw > current -> (current + 1).coerceAtMost(boundedRaw)
            boundedRaw < current -> (current - 1).coerceAtLeast(boundedRaw)
            else -> current
        }.coerceIn(0, targetSize)
    }

    override fun getItemCount(): Int = rows.size + 1  // +1 for add row button

    /**
     * Build key display label
     */
    private fun buildKeyLabel(key: Map<String, Any?>): String {
        val type = key["type"] as? String ?: "?"
        return when (type) {
            "AlphabetKey" -> {
                val main = key["main"] as? String ?: ""
                main.ifEmpty { "?" }
            }
            "CapsKey" -> context.getString(R.string.text_keyboard_layout_key_label_caps)
            "LayoutSwitchKey" -> {
                val label = key["label"] as? String ?: "?123"
                label
            }
            "CommaKey" -> ","
            "LanguageKey" -> context.getString(R.string.text_keyboard_layout_key_label_lang)
            "SpaceKey" -> context.getString(R.string.text_keyboard_layout_key_label_space)
            "SymbolKey" -> key["label"] as? String ?: "."
            "ReturnKey" -> context.getString(R.string.text_keyboard_layout_key_label_enter)
            "BackspaceKey" -> "⌫"
            "NumPadKey" -> key["label"] as? String ?: "0"
            "MiniSpaceKey" -> context.getString(R.string.text_keyboard_layout_key_label_minispace)
            "MacroKey" -> {
                // 显示 label 值，如果有 labelText 且当前在 submode 中，优先显示 labelText
                val label = key["label"] as? String ?: "M"
                val labelText = key["labelText"]
                if (labelText is Map<*, *>) {
                    // 如果有 labelText Map，尝试获取当前 submode 的值
                    // 但由于这里没有 submode 上下文，返回 label 并依靠 UI 样式区分
                    label.ifEmpty { "M" }
                } else {
                    label.ifEmpty { "M" }
                }
            }
            else -> type
        }
    }

    /**
     * Check if touch point is on any child view
     */
    private fun ViewGroup.isTouchOnAnyChild(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) {
                return true
            }
        }
        return false
    }

    /**
     * Row ViewHolder
     */
    data class RowViewHolder(
        val container: LinearLayout,
        val dragHandle: TextView,
        val keysFlow: ViewGroup,
        val deleteButton: TextView
    ) : RecyclerView.ViewHolder(container)

    /**
     * Add row ViewHolder
     */
    data class AddRowViewHolder(
        val container: LinearLayout,
        val addRowButton: TextView
    ) : RecyclerView.ViewHolder(container)
}
