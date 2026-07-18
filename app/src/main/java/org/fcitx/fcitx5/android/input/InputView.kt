/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import androidx.core.content.ContextCompat
import android.graphics.Outline
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.input.config.ConfigChangeListener
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.status.ButtonsAdjustingWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.backgroundColor
import splitties.views.imageDrawable

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val keyBlurMaskView = KeyBlurMaskView().apply {
        visibility = GONE
    }

    private inner class KeyBlurMaskView : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = Rect()
        private val clipRect = Rect()
        private val clipRectF = RectF()
        private val clipPath = Path()
        private val selfLoc = IntArray(2)
        private val keyLoc = IntArray(2)
        private val blurTargetViews = ArrayList<View>(128)
        private val keyClipRects = ArrayList<Rect>(96)
        private val keyClipRadii = ArrayList<Float>(96)
        private var blurBitmap: Bitmap? = null
        private var redrawRetryCount = 0
        private var keyRegionsDirty = true
        private var keyHierarchyDirty = true
        private var hasVisibleKey = false
        /**
         * RenderNode that records a single drawBitmap(sourceBitmap) and carries the blur RenderEffect.
         * Drawing this node onto a hardware canvas yields the GPU-blurred full-screen image; clipping
         * the canvas first then drawing the node produces "blur-then-hard-clip" semantics that match
         * the pre-GPU CPU path (loadBlurredBitmapForRendering + clipPath + drawBitmap).
         */
        private var blurNode: RenderNode? = null
        private var blurNodeDirty = true
        private var hasRenderEffect = false

        @RequiresApi(Build.VERSION_CODES.S)
        private fun obtainBlurNode(): RenderNode {
            return blurNode ?: RenderNode("keyBlur").also { blurNode = it }
        }

        private fun discardBlurNode() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurNode?.discardDisplayList()
            }
            blurNode = null
            blurNodeDirty = true
        }

        fun setBlurBitmap(
            bitmap: Bitmap?,
            brightness: Int = 70,
            blurRadius: Float = 0f,
            useRenderEffect: Boolean = false
        ) {
            blurBitmap = bitmap
            paint.colorFilter = bitmap?.let { DarkenColorFilter(100 - brightness) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The view itself never carries a RenderEffect; the blur lives on the offscreen node
                // so we can clip after the blur, not before it.
                setRenderEffect(null)
                hasRenderEffect = useRenderEffect && bitmap != null && blurRadius > 0f
                if (hasRenderEffect) {
                    val node = obtainBlurNode()
                    node.setRenderEffect(
                        RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                    )
                    blurNodeDirty = true
                } else {
                    discardBlurNode()
                }
            }
            visibility = if (bitmap == null) GONE else VISIBLE
            keyRegionsDirty = true
            keyHierarchyDirty = true
            invalidate()
        }

        fun markKeyRegionsDirty(hierarchyChanged: Boolean = false) {
            keyRegionsDirty = true
            if (hierarchyChanged) {
                keyHierarchyDirty = true
            }
        }

        fun hasBlurBitmap() = blurBitmap != null

        override fun onDraw(canvas: Canvas) {
            val bitmap = blurBitmap ?: return
            if (width <= 0 || height <= 0) return
            calculateCenterCropSource(bitmap.width, bitmap.height, width, height, srcRect)
            dstRect.set(0, 0, width, height)

            // No key border => keys have no visible gaps, so use full-layer blur.
            if (!keyBorder) {
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
                redrawRetryCount = 0
                return
            }

            val currentWindow = windowManager.currentWindowOrNull()
            if (currentWindow is PickerWindow && currentWindow.key == PickerWindow.Key.Emoji) {
                // Emoji pager contains large non-key areas; use full-layer blur to avoid transparent gaps.
                drawFullScreenBlur(canvas, bitmap)
                redrawRetryCount = 0
                return
            }

            if (keyRegionsDirty) {
                rebuildKeyRegions()
            }

            var drewKeyRegion = false
            keyClipRects.forEachIndexed { index, rect ->
                val saveId = canvas.save()
                val radius = keyClipRadii.getOrElse(index) { 0f }
                if (radius > 0f) {
                    clipRectF.set(rect)
                    clipPath.reset()
                    clipPath.addRoundRect(clipRectF, radius, radius, Path.Direction.CW)
                    canvas.clipPath(clipPath)
                } else {
                    canvas.clipRect(rect)
                }
                drawFullScreenBlur(canvas, bitmap)
                canvas.restoreToCount(saveId)
                drewKeyRegion = true
            }

            // Keyboard window should normally provide KeyView regions.
            // If none are available in this frame, fall back to window region to avoid transparent holes.
            if (keyClipRects.isEmpty() && currentWindow !is StatusAreaWindow && windowManager.view.isShown &&
                windowManager.view.width > 0 && windowManager.view.height > 0) {
                clipRect.set(
                    windowManager.view.left,
                    windowManager.view.top,
                    windowManager.view.right,
                    windowManager.view.bottom
                )
                if (clipRect.intersect(0, 0, width, height)) {
                    val saveId = canvas.save()
                    canvas.clipRect(clipRect)
                    drawFullScreenBlur(canvas, bitmap)
                    canvas.restoreToCount(saveId)
                }
            }

            if (kawaiiBar.view.isShown && kawaiiBar.view.width > 0 && kawaiiBar.view.height > 0) {
                clipRect.set(
                    kawaiiBar.view.left,
                    kawaiiBar.view.top,
                    kawaiiBar.view.right,
                    kawaiiBar.view.bottom
                )
                if (clipRect.intersect(0, 0, width, height)) {
                    val saveId = canvas.save()
                    canvas.clipRect(clipRect)
                    drawFullScreenBlur(canvas, bitmap)
                    canvas.restoreToCount(saveId)
                }
            }

            if (hasVisibleKey && !drewKeyRegion && keyClipRects.isEmpty()) {
                if (redrawRetryCount < 8) {
                    redrawRetryCount++
                    keyRegionsDirty = true
                    postInvalidateOnAnimation()
                }
            } else {
                redrawRetryCount = 0
            }
        }

        /**
         * Draws the source bitmap onto [canvas], passing it through the GPU blur on API ≥ S
         * (the bitmap is recorded once into a RenderNode that carries the blur RenderEffect, then
         * the node is replayed onto [canvas]), or directly on older APIs. Any clipping the caller
         * set on [canvas] applies AFTER the blur, matching the CPU "blur whole image, then clip"
         * semantics — so key edges stay sharp without GPU haloing.
         */
        private fun drawFullScreenBlur(canvas: Canvas, bitmap: Bitmap) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasRenderEffect && canvas.isHardwareAccelerated) {
                val node = obtainBlurNode()
                if (node.setPosition(0, 0, width, height) || blurNodeDirty || !node.hasDisplayList()) {
                    val rc = node.beginRecording(width, height)
                    try {
                        rc.drawBitmap(bitmap, srcRect, dstRect, paint)
                    } finally {
                        node.endRecording()
                    }
                    blurNodeDirty = false
                }
                canvas.drawRenderNode(node)
            } else {
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            }
        }

        private fun rebuildKeyRegions() {
            keyRegionsDirty = false
            hasVisibleKey = false
            keyClipRects.clear()
            keyClipRadii.clear()
            if (keyHierarchyDirty) {
                blurTargetViews.clear()
                collectBlurTargets(windowManager.view, blurTargetViews)
                keyHierarchyDirty = false
            }
            getLocationInWindow(selfLoc)
            fun buildClipRects() {
                hasVisibleKey = false
                keyClipRects.clear()
                keyClipRadii.clear()
                blurTargetViews.forEach { target ->
                    if (!target.isShown) return@forEach
                    hasVisibleKey = true
                    if (target.width <= 0 || target.height <= 0) return@forEach
                    target.getLocationInWindow(keyLoc)
                    val hMargin: Int
                    val vMargin: Int
                    val radius: Float
                    if (target is KeyView) {
                        hMargin = target.hMargin
                        vMargin = target.vMargin
                        radius = target.blurClipRadius(
                            (target.width - target.hMargin * 2).coerceAtLeast(0),
                            (target.height - target.vMargin * 2).coerceAtLeast(0)
                        )
                    } else {
                        hMargin = 0
                        vMargin = 0
                        radius = (target.getTag(R.id.blur_mask_clip_radius) as? Number)?.toFloat() ?: 0f
                    }
                    clipRect.set(
                        keyLoc[0] - selfLoc[0] + hMargin,
                        keyLoc[1] - selfLoc[1] + vMargin,
                        keyLoc[0] - selfLoc[0] + target.width - hMargin,
                        keyLoc[1] - selfLoc[1] + target.height - vMargin
                    )
                    if (!clipRect.intersect(0, 0, width, height)) return@forEach
                    keyClipRects.add(Rect(clipRect))
                    keyClipRadii.add(radius.coerceIn(0f, minOf(clipRect.width(), clipRect.height()) * 0.5f))
                }
            }
            buildClipRects()
            if (!hasVisibleKey && blurTargetViews.isNotEmpty()) {
                blurTargetViews.clear()
                collectBlurTargets(windowManager.view, blurTargetViews)
                buildClipRects()
            }
        }

        private fun collectBlurTargets(view: View, out: MutableList<View>) {
            if (view is KeyView || view.getTag(R.id.blur_mask_clip_radius) is Number) {
                out.add(view)
                return
            }
            val group = view as? ViewGroup ?: return
            for (i in 0 until group.childCount) {
                collectBlurTargets(group.getChildAt(i), out)
            }
        }
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private fun createHandleDrawable(radius: Float = dp(5).toFloat()) = GradientDrawable().apply {
        setColor(theme.accentKeyBackgroundColor)
        cornerRadius = radius
    }

    private class InsetSideHandleDrawable(
        private val attachToLeftEdge: Boolean,
        handleColor: Int,
        arrowColor: Int
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = handleColor
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = arrowColor
        }
        private val arrowPath = Path()

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val centerY = h * 0.5f

            val circleRadius = minOf(w, h) * 0.25f
            val circleInset = (w * 0.06f).coerceAtLeast(1f)
            val centerX = if (attachToLeftEdge) {
                w - circleInset - circleRadius
            } else {
                circleInset + circleRadius
            }
            canvas.drawCircle(centerX, centerY, circleRadius, fillPaint)

            val arrowHalfHeight = circleRadius * 0.55f
            val arrowReach = circleRadius * 0.88f
            arrowPath.reset()
            if (attachToLeftEdge) {
                val baseX = centerX - arrowReach * 0.32f
                arrowPath.moveTo(baseX, centerY - arrowHalfHeight)
                arrowPath.lineTo(baseX, centerY + arrowHalfHeight)
                arrowPath.lineTo(baseX + arrowReach, centerY)
            } else {
                val baseX = centerX + arrowReach * 0.32f
                arrowPath.moveTo(baseX, centerY - arrowHalfHeight)
                arrowPath.lineTo(baseX, centerY + arrowHalfHeight)
                arrowPath.lineTo(baseX - arrowReach, centerY)
            }
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            arrowPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            arrowPaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class TopMountainHintDrawable(
        mountainColor: Int,
        private val baseLengthPx: Float,
        private val depthPx: Float
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = mountainColor
        }
        private val mountainPath = Path()

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val base = baseLengthPx.coerceIn(2f, w)
            val depth = depthPx.coerceIn(1f, h)
            val centerX = w * 0.5f
            val leftX = (centerX - base * 0.5f).coerceAtLeast(0f)
            val rightX = (centerX + base * 0.5f).coerceAtMost(w)

            mountainPath.reset()
            mountainPath.moveTo(leftX, 0f)
            mountainPath.cubicTo(
                leftX + base * 0.42f, 0f,
                centerX - base * 0.12f, depth * 0.92f,
                centerX, depth
            )
            mountainPath.cubicTo(
                centerX + base * 0.12f, depth * 0.92f,
                rightX - base * 0.42f, 0f,
                rightX, 0f
            )
            mountainPath.close()
            canvas.drawPath(mountainPath, fillPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class InsetTopHandleDrawable(
        handleColor: Int,
        arrowColor: Int,
        private val insetFromEdgePx: Float
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = handleColor
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = arrowColor
        }
        private val arrowPath = Path()

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val centerX = w * 0.5f
            val circleRadius = minOf(w, h) * 0.25f
            val inset = insetFromEdgePx.coerceIn(circleRadius, h - circleRadius)
            val centerY = inset

            canvas.drawCircle(centerX, centerY, circleRadius, fillPaint)

            val arrowHalfWidth = circleRadius * 0.55f
            val arrowReach = circleRadius * 0.88f
            val baseY = centerY - arrowReach * 0.32f
            arrowPath.reset()
            arrowPath.moveTo(centerX - arrowHalfWidth, baseY)
            arrowPath.lineTo(centerX + arrowHalfWidth, baseY)
            arrowPath.lineTo(centerX, baseY + arrowReach)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            arrowPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            arrowPaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class EdgeMountainHintDrawable(
        private val attachToLeftEdge: Boolean,
        mountainColor: Int,
        private val baseLengthPx: Float,
        private val depthPx: Float
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = mountainColor
        }
        private val mountainPath = Path()

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val base = baseLengthPx.coerceIn(2f, h)
            val depth = depthPx.coerceIn(1f, w)
            val centerY = h * 0.5f
            val topY = (centerY - base * 0.5f).coerceAtLeast(0f)
            val bottomY = (centerY + base * 0.5f).coerceAtMost(h)
            val edgeX = if (attachToLeftEdge) 0f else w
            val apexX = if (attachToLeftEdge) depth else w - depth

            mountainPath.reset()
            mountainPath.moveTo(edgeX, topY)
            if (attachToLeftEdge) {
                mountainPath.cubicTo(
                    edgeX, topY + base * 0.42f,
                    apexX * 0.92f, centerY - base * 0.12f,
                    apexX, centerY
                )
                mountainPath.cubicTo(
                    apexX * 0.92f, centerY + base * 0.12f,
                    edgeX, bottomY - base * 0.42f,
                    edgeX, bottomY
                )
            } else {
                mountainPath.cubicTo(
                    edgeX, topY + base * 0.42f,
                    apexX + depth * 0.08f, centerY - base * 0.12f,
                    apexX, centerY
                )
                mountainPath.cubicTo(
                    apexX + depth * 0.08f, centerY + base * 0.12f,
                    edgeX, bottomY - base * 0.42f,
                    edgeX, bottomY
                )
            }
            mountainPath.close()
            canvas.drawPath(mountainPath, fillPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun calculateCenterCropSource(
        bitmapWidth: Int,
        bitmapHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        outRect: Rect
    ) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            outRect.set(0, 0, bitmapWidth.coerceAtLeast(0), bitmapHeight.coerceAtLeast(0))
            return
        }

        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        if (bitmapRatio > targetRatio) {
            val cropWidth = (bitmapHeight * targetRatio).toInt().coerceAtLeast(1)
            val left = (bitmapWidth - cropWidth) / 2
            outRect.set(left, 0, left + cropWidth, bitmapHeight)
        } else {
            val cropHeight = (bitmapWidth / targetRatio).toInt().coerceAtLeast(1)
            val top = (bitmapHeight - cropHeight) / 2
            outRect.set(0, top, bitmapWidth, top + cropHeight)
        }
    }

    private val blurUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var blurUpdateJob: Job? = null
    private var blurUpdateGeneration = 0

    private fun updateBlurMaskThemeData() {
        blurUpdateGeneration++
        val generation = blurUpdateGeneration
        blurUpdateJob?.cancel()
        val customTheme = theme as? Theme.Custom ?: run {
            keyBlurMaskView.setBlurBitmap(null)
            return
        }
        val bg = customTheme.backgroundImage
        if (bg == null || bg.blurRadius <= 0f) {
            keyBlurMaskView.setBlurBitmap(null)
            return
        }
        val useGpuBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        blurUpdateJob = blurUpdateScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                if (useGpuBlur) {
                    bg.loadBitmapForRendering()
                } else {
                    bg.loadBlurredBitmapForRendering()
                }
            }
            if (generation != blurUpdateGeneration) return@launch
            if (useGpuBlur) {
                keyBlurMaskView.setBlurBitmap(
                    bitmap = bitmap,
                    brightness = bg.brightness,
                    blurRadius = bg.blurRadius,
                    useRenderEffect = true
                )
            } else {
                keyBlurMaskView.setBlurBitmap(bitmap, bg.brightness)
            }
            refreshKeyboardBounds()
        }
    }

    private fun refreshKeyboardBounds(hierarchyChanged: Boolean = false) {
        if (!keyBlurMaskView.hasBlurBitmap()) return
        keyboardWindow.updateBounds()
        keyBlurMaskView.markKeyRegionsDirty(hierarchyChanged = hierarchyChanged)
        keyBlurMaskView.invalidate()
    }

    private var blurRefreshScheduled = false
    private var blurRefreshRemainingFrames = 0

    fun requestBlurRefresh(retryFrames: Int = 1, hierarchyChanged: Boolean = false) {
        if (!keyBlurMaskView.hasBlurBitmap()) return
        blurRefreshRemainingFrames = maxOf(blurRefreshRemainingFrames, retryFrames)
        if (blurRefreshScheduled) return
        blurRefreshScheduled = true
        postOnAnimation {
            blurRefreshScheduled = false
            if (!keyBlurMaskView.hasBlurBitmap()) {
                blurRefreshRemainingFrames = 0
                return@postOnAnimation
            }
            refreshKeyboardBounds(hierarchyChanged = hierarchyChanged)
            val remaining = blurRefreshRemainingFrames
            if (remaining > 0) {
                blurRefreshRemainingFrames = remaining - 1
                requestBlurRefresh(0, hierarchyChanged = hierarchyChanged)
            } else {
                blurRefreshRemainingFrames = 0
            }
        }
    }

    private fun updateHandlePosition() {
        if (!isFloating) return

        val kX = keyboardView.translationX
        val kY = keyboardView.translationY
        // Use layout dimensions if available, otherwise estimate
        val kWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
        val kHeight = if (keyboardView.height > 0) keyboardView.height else {
            // Default components height estimate
            resolveFloatingHeight() + dp(KawaiiBarComponent.HEIGHT) + keyboardBottomPaddingPx
        }
        // Visual dimensions
        val handleThickness = dp(6)
        val handleLength = dp(48)
        // Total view size including touch padding (24dp total padding, 12dp each side)
        val touchPadding = dp(12)
        val viewThickness = handleThickness + touchPadding * 2
        val viewLength = handleLength + touchPadding * 2

        // Right handle (centered vertically on right edge)
        floatingRightHandle.translationX = kX + kWidth - viewThickness / 2
        floatingRightHandle.translationY = kY + (kHeight - viewLength) / 2
        // Update drawable with insets so the visible part is small but touch area is large
        val rightDrawable = createHandleDrawable()
        floatingRightHandle.background = android.graphics.drawable.InsetDrawable(
            rightDrawable,
            touchPadding, // left
            touchPadding, // top
            touchPadding, // right
            touchPadding  // bottom
        )
        floatingRightHandle.updateLayoutParams {
            width = viewThickness
            height = viewLength
        }
        // Bottom handle (centered horizontally on bottom edge)
        floatingBottomHandle.translationX = kX + (kWidth - viewLength) / 2
        floatingBottomHandle.translationY = kY + kHeight - viewThickness / 2

        val bottomDrawable = createHandleDrawable()
        floatingBottomHandle.background = android.graphics.drawable.InsetDrawable(
            bottomDrawable,
            touchPadding, // left
            touchPadding, // top
            touchPadding, // right
            touchPadding  // bottom
        )
        floatingBottomHandle.updateLayoutParams {
            width = viewLength
            height = viewThickness
        }

        // Move handle (centered horizontally above keyboard)
        val moveHandleSize = dp(24)
        adjustableHandle.translationX = kX + (kWidth - moveHandleSize) / 2
        adjustableHandle.translationY = kY - moveHandleSize - dp(8)

        val moveBgDrawable = createHandleDrawable(moveHandleSize / 2f)
        val moveIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_move_handle_cross)?.mutate()
        val finalDrawable = if (moveIconDrawable != null) {
            moveIconDrawable.setTint(theme.keyTextColor)
            val inset = dp(4)
            val ld = LayerDrawable(arrayOf(moveBgDrawable, moveIconDrawable))
            ld.setLayerInset(1, inset, inset, inset, inset)
            ld
        } else {
            moveBgDrawable
        }
        adjustableHandle.background = finalDrawable
        adjustableHandle.updateLayoutParams {
            width = moveHandleSize
            height = moveHandleSize
        }
    }

    private fun clampFloatingPosition() {
        if (!isEffectiveFloating) return
        val containerWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        val containerHeight = if (height > 0) height else resources.displayMetrics.heightPixels
        val keyboardWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
        val keyboardHeight = if (keyboardView.height > 0) {
            keyboardView.height
        } else {
            resolveFloatingHeight() + dp(KawaiiBarComponent.HEIGHT) + keyboardBottomPaddingPx
        }

        val maxX = (containerWidth - keyboardWidth).coerceAtLeast(0)
        val maxY = (containerHeight - keyboardHeight).coerceAtLeast(0)
        val clampedX = keyboardView.translationX.coerceIn(0f, maxX.toFloat())
        val clampedY = keyboardView.translationY.coerceIn(0f, maxY.toFloat())

        if (clampedX != keyboardView.translationX || clampedY != keyboardView.translationY) {
            keyboardView.translationX = clampedX
            keyboardView.translationY = clampedY
        }
        preedit.ui.root.translationX = keyboardView.translationX
        preedit.ui.root.translationY = keyboardView.translationY
    }

    private val floatingRightHandle = view(::View) {
        // background set in updateHandlePosition
        visibility = GONE
        // No initial translation needed, controlled by updateHandlePosition
        setOnTouchListener { v, event ->
            if (!isFloating) return@setOnTouchListener false
            // Expand touch area check if needed, but since we're using padding,
            // the view itself is larger.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    floatingResizeStartWidth = resolveFloatingWidth()
                    lastResizeTouchX = event.rawX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawX - lastResizeTouchX).toInt()
                    floatingWidthPx =
                        (floatingResizeStartWidth + delta).coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
                    applyFloatingWidth()
                    // Handle position update is called in applyFloatingWidth
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    persistFloatingWidth()
                    // Also save position as resizing might have moved handlers
                    saveFloatingPosition(
                        keyboardView.translationX.toInt(),
                        keyboardView.translationY.toInt()
                    )
                    true
                }
                else -> false
            }
        }
    }

    private val floatingBottomHandle = view(::View) {
        // background set in updateHandlePosition
        visibility = GONE
        // No initial translation needed
        setOnTouchListener { v, event ->
            if (!isEffectiveFloating) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    floatingResizeStartHeight = resolveFloatingHeight()
                    lastResizeTouchY = event.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - lastResizeTouchY).toInt()
                    floatingHeightPx =
                        (floatingResizeStartHeight + delta).coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
                    applyFloatingHeight()
                    // Handle position update is called in applyFloatingHeight
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    persistFloatingHeight()
                    // Also save position as resizing might have moved handlers
                    saveFloatingPosition(
                        keyboardView.translationX.toInt(),
                        keyboardView.translationY.toInt()
                    )
                    true
                }
                else -> false
            }
        }
    }

    private val adjustableHandle = view(::View) {
        visibility = GONE
        setOnTouchListener { v, event ->
            // Check if we're in adjusting mode for bottom padding adjustment
            if (isAdjustingMode) {
                // Handle bottom padding adjustment
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        adjustingResizeStartBottomPadding = resolveKeyboardBottomPadding()
                        lastAdjustingTouchY = event.rawY
                        v.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (lastAdjustingTouchY - event.rawY).toInt()
                        // Scale: 100px drag = 20dp padding change
                        val deltaPadding = (delta * 0.2f).toInt()
                        val newPadding = (adjustingResizeStartBottomPadding + deltaPadding).coerceIn(0, 100)
                        val currentPadding = resolveKeyboardBottomPadding()
                        if (newPadding != currentPadding) {
                            if (isLayoutLandscape) {
                                keyboardPrefs.keyboardBottomPaddingLandscape.setValue(newPadding)
                            } else {
                                keyboardPrefs.keyboardBottomPadding.setValue(newPadding)
                            }
                            updateKeyboardSize()
                            keyboardView.post {
                                updateAdjustingHandlePosition()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        true
                    }
                    else -> false
                }
            } else if (isFloating) {
                // Handle floating move functionality
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        v.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        keyboardView.translationX += dx
                        keyboardView.translationY += dy
                        clampFloatingPosition()
                        refreshKeyboardBounds()

                        preedit.ui.root.translationX = keyboardView.translationX
                        preedit.ui.root.translationY = keyboardView.translationY

                        updateHandlePosition()

                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        refreshKeyboardBounds()
                        saveFloatingPosition(
                            keyboardView.translationX.toInt(),
                            keyboardView.translationY.toInt()
                        )
                        true
                    }
                    else -> false
                }
            } else {
                // Neither in adjusting mode nor floating mode, return false
                false
            }
        }
    }

    // Adjusting mode handles
    private val adjustingHeightHandle = view(::View) {
        visibility = GONE
        // Background set via updateAdjustingHandleAppearance
        // Larger touch area, visual handle is smaller and centered
        isClickable = true
        isFocusable = true
        setOnTouchListener { v, event ->
            if (!isAdjustingMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Store the current preference value and touch position
                    adjustingResizeStartHeight = resolveAdjustingHeightPercent()
                    adjustingPendingHeightPercent = adjustingResizeStartHeight
                    lastAdjustingTouchY = event.rawY
                    // Also store the keyboard height at touch start for accurate calculation
                    adjustingStartKeyboardTop = keyboardView.top
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - lastAdjustingTouchY
                    // Calculate percent change based on touch movement
                    // Moving up (negative delta) should increase height
                    // Moving down (positive delta) should decrease height
                    val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                    // Scale factor: 100px drag = 10% keyboard height change (more responsive)
                    val deltaPercent = (delta / screenHeight) * 100f
                    // Match the preference range: 10% to 90%
                    val newPercent = (adjustingResizeStartHeight - deltaPercent).toInt()
                        .coerceIn(10, 90)
                    val currentPercent = resolveAdjustingHeightPercent()
                    // Only update if value changed significantly
                    if (kotlin.math.abs(newPercent - currentPercent) >= 1) {
                        adjustingPendingHeightPercent = newPercent
                        updateKeyboardSize()
                        keyboardView.post {
                            updateAdjustingHandlePosition()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    adjustingPendingHeightPercent?.let { applyAdjustedHeightPercent(it) }
                    adjustingPendingHeightPercent = null
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    adjustingPendingHeightPercent = null
                    updateKeyboardSize()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private val adjustingLeftMarginHandle = view(::View) {
        visibility = GONE
        setOnTouchListener { v, event ->
            if (!isAdjustingMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    adjustingResizeStartSidePadding = resolveKeyboardSidePadding()
                    lastAdjustingTouchX = event.rawX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawX - lastAdjustingTouchX).toInt()
                    // Scale: 100px drag = 20dp padding change
                    val deltaPadding = (delta * 0.2f).toInt()
                    val newPadding = (adjustingResizeStartSidePadding + deltaPadding)
                        .coerceIn(0, maxKeyboardSidePaddingInAdjustingMode())
                    val currentPadding = resolveKeyboardSidePadding()
                    if (newPadding != currentPadding) {
                        if (isLayoutLandscape) {
                            keyboardPrefs.keyboardSidePaddingLandscape.setValue(newPadding)
                        } else {
                            keyboardPrefs.keyboardSidePadding.setValue(newPadding)
                        }
                        updateKeyboardSize()
                        keyboardView.post {
                            updateAdjustingHandlePosition()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private val adjustingRightMarginHandle = view(::View) {
        visibility = GONE
        setOnTouchListener { v, event ->
            if (!isAdjustingMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    adjustingResizeStartSidePadding = resolveKeyboardSidePadding()
                    lastAdjustingTouchX = event.rawX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (lastAdjustingTouchX - event.rawX).toInt()
                    // Scale: 100px drag = 20dp padding change
                    val deltaPadding = (delta * 0.2f).toInt()
                    val newPadding = (adjustingResizeStartSidePadding + deltaPadding)
                        .coerceIn(0, maxKeyboardSidePaddingInAdjustingMode())
                    val currentPadding = resolveKeyboardSidePadding()
                    if (newPadding != currentPadding) {
                        if (isLayoutLandscape) {
                            keyboardPrefs.keyboardSidePaddingLandscape.setValue(newPadding)
                        } else {
                            keyboardPrefs.keyboardSidePadding.setValue(newPadding)
                        }
                        updateKeyboardSize()
                        keyboardView.post {
                            updateAdjustingHandlePosition()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private val adjustingLeftEdgeHint = view(::View) {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    private val adjustingRightEdgeHint = view(::View) {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    private val adjustingTopEdgeHint = view(::View) {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }


    // Overlay to disable keyboard input during adjusting mode
    private val adjustingOverlay = view(::View) {
        visibility = GONE
        backgroundColor = Color.TRANSPARENT
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setOnTouchListener { _, _ -> true }
    }

    private val adjustingConfirmButton = view(::TextView) {
        visibility = GONE
        text = context.getString(R.string.adjusting_mode_confirm)
        gravity = Gravity.CENTER
        textSize = 13f
        setPadding(dp(12), dp(4), dp(12), dp(4))
        isClickable = true
        isFocusable = true
        setOnClickListener { exitAdjustingMode() }
    }

    private val adjustingDefaultButton = view(::TextView) {
        visibility = GONE
        text = context.getString(R.string.default_)
        gravity = Gravity.CENTER
        textSize = 13f
        setPadding(dp(12), dp(4), dp(12), dp(4))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val targetHeightDefault = if (isLayoutLandscape) {
                keyboardPrefs.keyboardHeightPercentLandscape.defaultValue
            } else {
                keyboardPrefs.keyboardHeightPercent.defaultValue
            }
            val targetSidePaddingDefault = if (isLayoutLandscape) {
                keyboardPrefs.keyboardSidePaddingLandscape.defaultValue
            } else {
                keyboardPrefs.keyboardSidePadding.defaultValue
            }
            val targetBottomPaddingDefault = if (isLayoutLandscape) {
                keyboardPrefs.keyboardBottomPaddingLandscape.defaultValue
            } else {
                keyboardPrefs.keyboardBottomPadding.defaultValue
            }
            applyAdjustedHeightPercent(targetHeightDefault)
            if (isLayoutLandscape) {
                keyboardPrefs.keyboardSidePaddingLandscape.setValue(targetSidePaddingDefault)
                keyboardPrefs.keyboardBottomPaddingLandscape.setValue(targetBottomPaddingDefault)
            } else {
                keyboardPrefs.keyboardSidePadding.setValue(targetSidePaddingDefault)
                keyboardPrefs.keyboardBottomPadding.setValue(targetBottomPaddingDefault)
            }
            adjustingPendingHeightPercent = null
            updateKeyboardSize()
            keyboardView.post { updateAdjustingHandlePosition() }
        }
    }

    // Adjusting mode state variables
    private var adjustingResizeStartHeight = 0
    private var adjustingResizeStartSidePadding = 0
    private var adjustingResizeStartBottomPadding = 0
    private var lastAdjustingTouchX = 0f
    private var lastAdjustingTouchY = 0f
    private var adjustingStartKeyboardTop = 0
    private var adjustingPendingHeightPercent: Int? = null

    private fun resolveKeyboardHeightPercent(): Int {
        return if (isLayoutLandscape) {
            keyboardPrefs.keyboardHeightPercentLandscape.getValue()
        } else {
            keyboardPrefs.keyboardHeightPercent.getValue()
        }
    }

    private fun resolveAdjustingHeightPercent(): Int {
        adjustingPendingHeightPercent?.let { return it }
        val keyboardWindow = windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow
        return keyboardWindow?.currentKeyboardHeightPercentOverride() ?: resolveKeyboardHeightPercent()
    }

    private fun applyAdjustedHeightPercent(newPercent: Int) {
        val keyboardWindow = windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow
        val updatedLayoutOverride = if (keyboardWindow?.currentKeyboardHeightPercentOverride() != null) {
            keyboardWindow.updateCurrentKeyboardHeightPercentOverride(newPercent)
        } else {
            false
        }
        if (!updatedLayoutOverride) {
            if (isLayoutLandscape) {
                keyboardPrefs.keyboardHeightPercentLandscape.setValue(newPercent)
            } else {
                keyboardPrefs.keyboardHeightPercent.setValue(newPercent)
            }
        }
    }

    private fun rawKeyboardSidePadding(): Int {
        return if (isLayoutLandscape) {
            keyboardPrefs.keyboardSidePaddingLandscape.getValue()
        } else {
            keyboardPrefs.keyboardSidePadding.getValue()
        }
    }

    private fun resolveKeyboardSidePadding(): Int {
        return rawKeyboardSidePadding().coerceIn(0, maxKeyboardSidePaddingInAdjustingMode())
    }

    private fun maxKeyboardSidePaddingInAdjustingMode(): Int {
        val containerWidthPx = keyboardView.width
            .takeIf { it > 0 }
            ?: width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val minKeyboardContentWidthPx = containerWidthPx / 2
        val maxPaddingPx = ((containerWidthPx - minKeyboardContentWidthPx) / 2).coerceAtLeast(0)
        return (maxPaddingPx / resources.displayMetrics.density).toInt()
    }

    private fun clampKeyboardSidePaddingToSafeRange() {
        val current = rawKeyboardSidePadding()
        val maxSafePadding = maxKeyboardSidePaddingInAdjustingMode()
        val clamped = current.coerceIn(0, maxSafePadding)
        if (current == clamped) return
        if (isLayoutLandscape) {
            keyboardPrefs.keyboardSidePaddingLandscape.setValue(clamped)
        } else {
            keyboardPrefs.keyboardSidePadding.setValue(clamped)
        }
    }

    private fun resolveKeyboardBottomPadding(): Int {
        return if (isLayoutLandscape) {
            keyboardPrefs.keyboardBottomPaddingLandscape.getValue()
        } else {
            keyboardPrefs.keyboardBottomPadding.getValue()
        }
    }

    private fun updateOneHandHandleAppearance() {
        val background = createHandleDrawable(dp(10).toFloat())
        val iconRes = if (oneHandOnRight) {
            R.drawable.ic_baseline_keyboard_arrow_left_24
        } else {
            R.drawable.ic_baseline_keyboard_arrow_right_24
        }
        val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (icon == null) {
            oneHandHandle.background = background
            return
        }
        icon.setTint(theme.keyTextColor)
        val inset = dp(4)
        val drawable = LayerDrawable(arrayOf(background, icon)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
        oneHandHandle.background = drawable
    }

    private fun updateOneHandHandlePosition() {
        val safeGap = dp(6)
        oneHandHandle.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = windowManager.view.id
            bottomToBottom = windowManager.view.id
            if (oneHandOnRight) {
                startToStart = unset
                endToEnd = unset
                startToEnd = unset
                endToStartOf(windowManager.view)
                marginStart = 0
                marginEnd = safeGap
            } else {
                startToStart = unset
                endToEnd = unset
                endToStart = unset
                startToEndOf(windowManager.view)
                marginStart = safeGap
                marginEnd = 0
            }
        }
    }

    private fun updateOneHandHandleVisibility() {
        oneHandHandle.visibility = if (isOneHanded && !isFloating) VISIBLE else GONE
    }

    private fun syncOneHandHandleUi(bringToFront: Boolean = false) {
        updateOneHandHandleAppearance()
        updateOneHandHandlePosition()
        updateOneHandHandleVisibility()
        if (bringToFront) {
            oneHandHandle.bringToFront()
        }
    }

    private fun syncKeyboardBoundsAfterLayout() {
        requestBlurRefresh(retryFrames = 2)
    }

    private fun switchOneHandSide() {
        oneHandOnRight = !oneHandOnRight
        saveOneHandSide(oneHandOnRight)
        if (!isDockedOneHandMode) return
        updateKeyboardSize()
        syncOneHandHandleUi(bringToFront = true)
        syncKeyboardBoundsAfterLayout()
        requestLayout()
    }

    private fun applyOneHandWidth() {
        if (!isDockedOneHandMode) return
        updateKeyboardSize()
        updateOneHandHandlePosition()
        syncKeyboardBoundsAfterLayout()
        keyboardView.invalidateOutline()
        requestLayout()
    }

    private fun updateOneHandGapScale(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOneHandGapRefreshAt < oneHandGapRefreshIntervalMs) {
            return
        }
        lastOneHandGapRefreshAt = now
        val keyboard = windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow ?: return
        if (!isDockedOneHandMode) {
            keyboard.setHorizontalGapScale(1f)
            return
        }
        val containerWidth = oneHandContainerWidth
        val scale = resolveOneHandWidth().toFloat() / containerWidth.toFloat()
        keyboard.setHorizontalGapScale(scale)
    }

    private val oneHandHandle = view(::View) {
        visibility = GONE
        setOnClickListener {
            if (!isDockedOneHandMode) return@setOnClickListener
            switchOneHandSide()
        }
        setOnTouchListener { v, event ->
            if (!isDockedOneHandMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    oneHandResizeStartWidth = resolveOneHandWidth()
                    lastOneHandTouchX = event.rawX
                    oneHandDragging = false
                    lastOneHandGapRefreshAt = 0L
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawX - lastOneHandTouchX
                    if (!oneHandDragging && kotlin.math.abs(delta) > oneHandTouchSlop) {
                        oneHandDragging = true
                    }
                    if (oneHandDragging) {
                        val target = if (oneHandOnRight) {
                            oneHandResizeStartWidth - delta.toInt()
                        } else {
                            oneHandResizeStartWidth + delta.toInt()
                        }
                        oneHandWidthPx = target.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
                        applyOneHandWidth()
                        updateOneHandGapScale()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    if (!oneHandDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    } else if (oneHandDragging) {
                        updateOneHandGapScale(force = true)
                        persistOneHandWidth()
                    }
                    oneHandDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()
    private val buttonsAdjustingOverlayView by lazy {
        ButtonsAdjustingWindow.onCreateView().apply {
            visibility = GONE
        }
    }

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        scope += ButtonsAdjustingWindow
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape
    private val candidatesPrefs = AppPrefs.getInstance().candidates
    private val physicalKeyboardHorizontalCandidateBar =
        candidatesPrefs.physicalKeyboardHorizontalCandidateBar
    private val splitKeyboardUseLandscapeLayout = keyboardPrefs.splitKeyboardUseLandscapeLayout
    private val textKeyboardLayoutProfile = keyboardPrefs.textKeyboardLayoutProfile

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        textKeyboardLayoutProfile,
        keyboardPrefs.splitKeyboardEnabled,
        keyboardPrefs.splitKeyboardThreshold,
        keyboardPrefs.splitKeyboardGapPercent,
        splitKeyboardUseLandscapeLayout,
    )

    var isFloating = false
        private set
    var isOneHanded = false
        private set
    var isAdjustingMode = false
        private set
    internal var isPhysicalCandidateBarMode = false
        private set

    private var oneHandOnRight = true
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var oneHandResizeStartWidth = 0
    private var lastOneHandTouchX = 0f
    private var oneHandDragging = false
    private var lastOneHandGapRefreshAt = 0L
    private val oneHandGapRefreshIntervalMs = 80L
    private val oneHandTouchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private val floatingCornerRadiusPx: Int
        get() = dp(10)

    private val keyboardOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return
            val radius = if (isEffectiveFloating) floatingCornerRadiusPx.toFloat() else 0f
            outline.setRoundRect(0, 0, width, height, radius)
        }
    }

    // Persistent storage for floating state and one-handed mode
    private val internalPrefs = AppPrefs.getInstance().internal

    private var floatingWidthPortraitRatioPref by internalPrefs.floatingKeyboardWidthPortraitRatio
    private var floatingWidthLandscapeRatioPref by internalPrefs.floatingKeyboardWidthLandscapeRatio
    private var floatingHeightPortraitRatioPref by internalPrefs.floatingKeyboardHeightPortraitRatio
    private var floatingHeightLandscapeRatioPref by internalPrefs.floatingKeyboardHeightLandscapeRatio
    private var floatingWidthRatioLegacyPref by internalPrefs.floatingKeyboardWidthRatio
    private var floatingHeightRatioLegacyPref by internalPrefs.floatingKeyboardHeightRatio
    private var floatingWidthLegacyPref by internalPrefs.floatingKeyboardWidthLegacy
    private var floatingHeightLegacyPref by internalPrefs.floatingKeyboardHeightLegacy
    private var floatingWidthPx = 0
    private var floatingHeightPx = 0
    private var floatingXPortraitRatio by internalPrefs.floatingKeyboardXPortraitRatio
    private var floatingYPortraitRatio by internalPrefs.floatingKeyboardYPortraitRatio
    private var floatingXLandscapeRatio by internalPrefs.floatingKeyboardXLandscapeRatio
    private var floatingYLandscapeRatio by internalPrefs.floatingKeyboardYLandscapeRatio
    private var floatingXPortrait by internalPrefs.floatingKeyboardXPortrait
    private var floatingYPortrait by internalPrefs.floatingKeyboardYPortrait
    private var floatingXLandscape by internalPrefs.floatingKeyboardXLandscape
    private var floatingYLandscape by internalPrefs.floatingKeyboardYLandscape
    private var oneHandOnRightPortrait by internalPrefs.oneHandOnRightPortrait
    private var oneHandOnRightLandscape by internalPrefs.oneHandOnRightLandscape
    private var floatingModeEnabledPref by internalPrefs.floatingModeEnabled
    private var oneHandModeEnabledPref by internalPrefs.oneHandModeEnabled
    private var oneHandWidthPortraitRatioPref by internalPrefs.oneHandKeyboardWidthPortraitRatio
    private var oneHandWidthLandscapeRatioPref by internalPrefs.oneHandKeyboardWidthLandscapeRatio
    private var oneHandWidthRatioLegacyPref by internalPrefs.oneHandKeyboardWidthRatio
    private var oneHandWidthLegacyPref by internalPrefs.oneHandKeyboardWidthLegacy
    private var oneHandWidthPx = 0

    private val isLandscapeOrientation: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Orientation-aware accessors for size ratios. Sizes must be stored per
    // orientation just like positions, otherwise a ratio captured in one
    // orientation gets applied to the other orientation's screen dimensions.
    private var floatingWidthRatioPref: Float
        get() = if (isLandscapeOrientation) floatingWidthLandscapeRatioPref else floatingWidthPortraitRatioPref
        set(value) {
            if (isLandscapeOrientation) floatingWidthLandscapeRatioPref = value
            else floatingWidthPortraitRatioPref = value
        }

    private var floatingHeightRatioPref: Float
        get() = if (isLandscapeOrientation) floatingHeightLandscapeRatioPref else floatingHeightPortraitRatioPref
        set(value) {
            if (isLandscapeOrientation) floatingHeightLandscapeRatioPref = value
            else floatingHeightPortraitRatioPref = value
        }

    private var oneHandWidthRatioPref: Float
        get() = if (isLandscapeOrientation) oneHandWidthLandscapeRatioPref else oneHandWidthPortraitRatioPref
        set(value) {
            if (isLandscapeOrientation) oneHandWidthLandscapeRatioPref = value
            else oneHandWidthPortraitRatioPref = value
        }

    // Whether layout-related preferences should be treated as landscape.
    // Enabled when device is landscape OR when "use landscape layout when split" is enabled and split keyboard is active.
    // Prefer using the actual keyboard view width when available to decide if split is active.
    private var layoutLandscapeReevaluationPosted = false

    private val isLayoutLandscape: Boolean
        get() {
            if (isLandscapeOrientation) return true

            if (!splitKeyboardUseLandscapeLayout.getValue()) return false

            // If split keyboard switch is off, don't treat as split
            if (!keyboardPrefs.splitKeyboardEnabled.getValue()) return false

            // Require manager initialized
            if (!SplitKeyboardStateManager.isInitialized()) return false
            val manager = SplitKeyboardStateManager.getInstance()

            // Try to use actual keyboardView width when available
            val realWidthPx = keyboardView.width.takeIf { it > 0 }
                ?: windowManager.view.width.takeIf { it > 0 }
                ?: -1

            val shouldSplit = if (realWidthPx > 0) {
                // Use the width-based API for an accurate decision
                manager.shouldUseSplitKeyboard(realWidthPx)
            } else {
                // Fallback to manager heuristic (based on display metrics)
                manager.shouldUseSplitKeyboard()
            }

            // This property is read several times while InputView is being constructed. Posting
            // from every read used to queue a full refresh of every keyboard for each access,
            // causing a large burst of main-thread work after the first layout. Coalesce the
            // width-dependent recheck and only resize when the fallback decision was wrong.
            if (realWidthPx <= 0 && !layoutLandscapeReevaluationPosted) {
                layoutLandscapeReevaluationPosted = true
                val fallbackShouldSplit = shouldSplit
                keyboardView.post {
                    layoutLandscapeReevaluationPosted = false
                    val measuredWidth = keyboardView.width.takeIf { it > 0 }
                        ?: windowManager.view.width.takeIf { it > 0 }
                    if (measuredWidth != null &&
                        manager.shouldUseSplitKeyboard(measuredWidth) != fallbackShouldSplit
                    ) {
                        updateKeyboardSize()
                        requestLayout()
                    }
                }
            }

            return shouldSplit
        }

    private val isDockedOneHandMode: Boolean
        get() = isOneHanded && !isFloating

    /**
     * Full container width available for docked one-handed layout.
     *
     * In docked mode [keyboardView] spans the whole InputView width, but right
     * after leaving floating mode its measured width is still the (narrower)
     * floating width because layout hasn't run yet. Reading [keyboardView.width]
     * then would compute a too-small edge gap. Prefer the stable InputView width
     * (which stays full-width across floating/docked transitions).
     */
    private val oneHandContainerWidth: Int
        get() = width.takeIf { it > 0 }
            ?: keyboardView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels

    private val isEffectiveFloating: Boolean
        get() = isFloating && !isPhysicalCandidateBarMode

    private fun getStoredFloatingPosition(): Pair<Int, Int> {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val xRatio: Float
        val yRatio: Float
        val legacyX: Int
        val legacyY: Int
        if (isLandscapeOrientation) {
            xRatio = floatingXLandscapeRatio; yRatio = floatingYLandscapeRatio
            legacyX = floatingXLandscape; legacyY = floatingYLandscape
        } else {
            xRatio = floatingXPortraitRatio; yRatio = floatingYPortraitRatio
            legacyX = floatingXPortrait; legacyY = floatingYPortrait
        }
        // already stored as ratio
        if (xRatio >= 0f && yRatio >= 0f) {
            return (w * xRatio).toInt() to (h * yRatio).toInt()
        }
        // migrate legacy px (per current orientation) -> ratio, then clear legacy
        if (legacyX != -1 && legacyY != -1 && w > 0 && h > 0) {
            saveFloatingPosition(legacyX, legacyY)
            if (isLandscapeOrientation) {
                floatingXLandscape = -1; floatingYLandscape = -1
            } else {
                floatingXPortrait = -1; floatingYPortrait = -1
            }
            return legacyX to legacyY
        }
        // not set
        return -1 to -1
    }

    private fun saveFloatingPosition(x: Int, y: Int) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return
        val xRatio = x.toFloat() / w
        val yRatio = y.toFloat() / h
        if (isLandscapeOrientation) {
            floatingXLandscapeRatio = xRatio
            floatingYLandscapeRatio = yRatio
        } else {
            floatingXPortraitRatio = xRatio
            floatingYPortraitRatio = yRatio
        }
    }

    private fun getStoredOneHandSide(): Boolean {
        return if (isLandscapeOrientation) {
            oneHandOnRightLandscape
        } else {
            oneHandOnRightPortrait
        }
    }

    private fun saveOneHandSide(onRight: Boolean) {
        if (isLandscapeOrientation) {
            oneHandOnRightLandscape = onRight
        } else {
            oneHandOnRightPortrait = onRight
        }
    }

    private fun applyStoredOneHandSideIfNeeded(forceRefresh: Boolean = false) {
        val stored = getStoredOneHandSide()
        if (!forceRefresh && stored == oneHandOnRight) return
        oneHandOnRight = stored
        if (isDockedOneHandMode) {
            updateKeyboardSize()
            syncOneHandHandleUi(bringToFront = true)
            syncKeyboardBoundsAfterLayout()
        } else {
            syncOneHandHandleUi()
        }
    }
    
    private var floatingResizeStartWidth = 0
    private var floatingResizeStartHeight = 0
    private var lastResizeTouchX = 0f
    private var lastResizeTouchY = 0f

    private val minFloatingWidthPx: Int
        get() = dp(180).coerceAtMost(resources.displayMetrics.widthPixels)

    private val maxFloatingWidthPx: Int
        get() = resources.displayMetrics.widthPixels.coerceAtLeast(minFloatingWidthPx)

    private val minFloatingHeightPx: Int
        get() = dp(100).coerceAtMost(resources.displayMetrics.heightPixels)

    private val maxFloatingHeightPx: Int
        get() = (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(minFloatingHeightPx)

    private val minOneHandWidthPx: Int
        get() = dp(180).coerceAtMost(resources.displayMetrics.widthPixels)

    private val maxOneHandWidthPx: Int
        get() = resources.displayMetrics.widthPixels.coerceAtLeast(minOneHandWidthPx)

    private fun resolveOneHandWidth(): Int {
        if (oneHandWidthPx <= 0) {
            val legacyRatio = oneHandWidthRatioLegacyPref
            val legacyPx = oneHandWidthLegacyPref
            oneHandWidthPx = when {
                oneHandWidthRatioPref > 0f ->
                    (resources.displayMetrics.widthPixels * oneHandWidthRatioPref).toInt()
                legacyRatio > 0f ->
                    (resources.displayMetrics.widthPixels * legacyRatio).toInt()
                legacyPx > 0 -> legacyPx
                else -> (resources.displayMetrics.widthPixels * 0.8f).toInt()
            }
            if (oneHandWidthRatioPref <= 0f && (legacyRatio > 0f || legacyPx > 0)) {
                persistOneHandWidth()
            }
        }
        oneHandWidthPx = oneHandWidthPx.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
        return oneHandWidthPx
    }

    private fun persistOneHandWidth() {
        val screenWidth = resources.displayMetrics.widthPixels
        if (screenWidth <= 0 || oneHandWidthPx <= 0) return
        val ratio = oneHandWidthPx.toFloat() / screenWidth
        if (ratio != oneHandWidthRatioPref) {
            oneHandWidthRatioPref = ratio
        }
    }

    private fun resolveFloatingWidth(): Int {
        if (floatingWidthPx <= 0) {
            val legacyRatio = floatingWidthRatioLegacyPref
            val legacyPx = floatingWidthLegacyPref
            floatingWidthPx = when {
                floatingWidthRatioPref > 0f ->
                    (resources.displayMetrics.widthPixels * floatingWidthRatioPref).toInt()
                legacyRatio > 0f ->
                    (resources.displayMetrics.widthPixels * legacyRatio).toInt()
                legacyPx > 0 -> legacyPx
                else -> (resources.displayMetrics.widthPixels * 0.8f).toInt()
            }
            if (floatingWidthRatioPref <= 0f && (legacyRatio > 0f || legacyPx > 0)) {
                persistFloatingWidth()
            }
        }
        floatingWidthPx = floatingWidthPx.coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
        return floatingWidthPx
    }

    private fun persistFloatingWidth() {
        val screenWidth = resources.displayMetrics.widthPixels
        if (screenWidth <= 0 || floatingWidthPx <= 0) return
        val ratio = floatingWidthPx.toFloat() / screenWidth
        if (ratio != floatingWidthRatioPref) {
            floatingWidthRatioPref = ratio
        }
    }

    private fun resolveFloatingHeight(): Int {
        if (floatingHeightPx <= 0) {
            val legacyRatio = floatingHeightRatioLegacyPref
            val legacyPx = floatingHeightLegacyPref
            floatingHeightPx = when {
                floatingHeightRatioPref > 0f ->
                    (resources.displayMetrics.heightPixels * floatingHeightRatioPref).toInt()
                legacyRatio > 0f ->
                    (resources.displayMetrics.heightPixels * legacyRatio).toInt()
                legacyPx > 0 -> legacyPx
                else -> keyboardHeightPx
            }
            if (floatingHeightRatioPref <= 0f && (legacyRatio > 0f || legacyPx > 0)) {
                persistFloatingHeight()
            }
        }
        floatingHeightPx = floatingHeightPx.coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
        return floatingHeightPx
    }

    private fun persistFloatingHeight() {
        val screenHeight = resources.displayMetrics.heightPixels
        if (screenHeight <= 0 || floatingHeightPx <= 0) return
        val ratio = floatingHeightPx.toFloat() / screenHeight
        if (ratio != floatingHeightRatioPref) {
            floatingHeightRatioPref = ratio
        }
    }

    private fun applyFloatingWidth() {
        keyboardView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = resolveFloatingWidth()
        }
        refreshKeyboardBounds()
        keyboardView.invalidateOutline()
        // Force layout pass to update positions
        requestLayout()

        // Sync handles position
        updateHandlePosition()
    }

    private fun applyFloatingHeight() {
        updateKeyboardSize()
        refreshKeyboardBounds()
        keyboardView.invalidateOutline()
        // Force layout pass to update positions
        requestLayout()

        // Sync handles position (updateKeyboardSize already calls it, but no harm to ensure)
        updateHandlePosition()
    }

    private fun updateFloatingHandlesVisibility() {
        if (isEffectiveFloating) {
            floatingRightHandle.visibility = VISIBLE
            floatingBottomHandle.visibility = VISIBLE
            adjustableHandle.visibility = VISIBLE
            return
        }
        floatingRightHandle.visibility = GONE
        floatingBottomHandle.visibility = GONE
        // In adjusting mode, floatingMoveHandle is used as bottom padding adjuster
        if (isAdjustingMode) {
            adjustableHandle.visibility = VISIBLE
        } else {
            adjustableHandle.visibility = GONE
        }
    }

    private fun updateAdjustingHandlesVisibility() {
        if (isAdjustingMode) {
            adjustingHeightHandle.visibility = VISIBLE
            adjustingLeftMarginHandle.visibility = VISIBLE
            adjustingRightMarginHandle.visibility = VISIBLE
            adjustingLeftEdgeHint.visibility = VISIBLE
            adjustingRightEdgeHint.visibility = VISIBLE
            adjustingTopEdgeHint.visibility = VISIBLE
            adjustingOverlay.visibility = VISIBLE
            adjustingConfirmButton.visibility = VISIBLE
            adjustingDefaultButton.visibility = VISIBLE
            return
        }
        adjustingHeightHandle.visibility = GONE
        adjustingLeftMarginHandle.visibility = GONE
        adjustingRightMarginHandle.visibility = GONE
        adjustingLeftEdgeHint.visibility = GONE
        adjustingRightEdgeHint.visibility = GONE
        adjustingTopEdgeHint.visibility = GONE
        adjustingOverlay.visibility = GONE
        adjustingConfirmButton.visibility = GONE
        adjustingDefaultButton.visibility = GONE
    }

    private fun updateAdjustingHandlePosition() {
        if (!isAdjustingMode) return
        // Use keyboardView which is always available
        if (keyboardView.width <= 0 || keyboardView.height <= 0) return

        val topHandleTouchAreaHeight = dp(44)

        // Height handle - top edge of inner keyboard area
        // Touch area extends above keyboard, visual handle centered in touch area
        val contentLeft = keyboardView.left + windowManager.view.left
        val contentTop = keyboardView.top + windowManager.view.top
        val contentWidth = windowManager.view.width.takeIf { it > 0 } ?: keyboardView.width
        val contentHeight = windowManager.view.height.takeIf { it > 0 } ?: keyboardView.height
        val contentRight = contentLeft + contentWidth
        val contentCenterY = contentTop + contentHeight / 2f
        val topHandleTouchAreaWidth = ((contentHeight / 3f).toInt())
            .coerceIn(dp(66), contentWidth.coerceAtLeast(dp(66)))
        adjustingHeightHandle.updateLayoutParams {
            width = dp(66)
            height = topHandleTouchAreaHeight
        }
        adjustingHeightHandle.translationX = (contentLeft + contentWidth / 2f - dp(66) / 2f)
        adjustingHeightHandle.translationY = keyboardView.top.toFloat()

        // Left/Right margin handles - positioned at the edges of visible keyboard content
        // The visible content is windowManager.view, constrained between padding spaces
        val marginTouchAreaWidth = dp(44)
        val marginTouchAreaHeight = dp(66)
        val edgeHintWidth = dp(14)
        val edgeHintHeight = contentHeight.coerceAtLeast(1)
        val topEdgeHintHeight = dp(14)

        val gestureSafeInset = dp(18).toFloat()
        val halfTouchWidth = marginTouchAreaWidth / 2f
        val minCenterX = contentLeft + halfTouchWidth
        val maxCenterX = contentRight - halfTouchWidth
        val leftCenterX = (contentLeft + gestureSafeInset).coerceIn(minCenterX, maxCenterX)
        val rightCenterX = (contentRight - gestureSafeInset).coerceIn(leftCenterX, maxCenterX)
        
        adjustingLeftMarginHandle.updateLayoutParams {
            width = marginTouchAreaWidth
            height = marginTouchAreaHeight
        }
        // Keep side handles slightly inset to avoid fullscreen edge gesture conflicts.
        adjustingLeftMarginHandle.translationX = leftCenterX - marginTouchAreaWidth / 2f
        adjustingLeftMarginHandle.translationY = (contentCenterY - marginTouchAreaHeight / 2f)

        adjustingRightMarginHandle.updateLayoutParams {
            width = marginTouchAreaWidth
            height = marginTouchAreaHeight
        }
        adjustingRightMarginHandle.translationX = rightCenterX - marginTouchAreaWidth / 2f
        adjustingRightMarginHandle.translationY = (contentCenterY - marginTouchAreaHeight / 2f)

        val containerWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        adjustingLeftEdgeHint.updateLayoutParams {
            width = edgeHintWidth
            height = edgeHintHeight
        }
        adjustingLeftEdgeHint.translationX = 0f
        adjustingLeftEdgeHint.translationY = contentTop.toFloat()

        adjustingRightEdgeHint.updateLayoutParams {
            width = edgeHintWidth
            height = edgeHintHeight
        }
        adjustingRightEdgeHint.translationX = (containerWidth - edgeHintWidth).toFloat()
        adjustingRightEdgeHint.translationY = contentTop.toFloat()

        adjustingTopEdgeHint.updateLayoutParams {
            width = topHandleTouchAreaWidth
            height = topEdgeHintHeight
        }
        adjustingTopEdgeHint.translationX = contentLeft + (contentWidth - topHandleTouchAreaWidth) / 2f
        adjustingTopEdgeHint.translationY = keyboardView.top.toFloat()

        // Position floatingMoveHandle (which also serves as bottom padding adjuster in adjusting mode) above keyboard
        if (isAdjustingMode) {
            val moveHandleSize = dp(24)
            // Position at keyboard center (used for bottom padding adjustment)
            adjustableHandle.translationX = (contentLeft + contentWidth / 2f - moveHandleSize / 2f)
            adjustableHandle.translationY = (contentCenterY - moveHandleSize / 2f)
            
            // Update layout params for adjusting mode
            adjustableHandle.updateLayoutParams {
                width = moveHandleSize
                height = moveHandleSize
            }

            val confirmWidth = dp(76)
            val confirmHeight = dp(32)
            val defaultWidth = dp(76)
            val defaultHeight = confirmHeight
            val buttonGap = dp(24)
            val buttonGroupWidth = defaultWidth + buttonGap + confirmWidth
            val buttonGroupStartX = contentLeft + (contentWidth - buttonGroupWidth) / 2f
            val buttonsY = (contentTop + contentHeight - confirmHeight - dp(6)).toFloat()

            adjustingConfirmButton.updateLayoutParams {
                width = confirmWidth
                height = confirmHeight
            }
            adjustingConfirmButton.translationX = buttonGroupStartX + defaultWidth + buttonGap
            adjustingConfirmButton.translationY = buttonsY

            adjustingDefaultButton.updateLayoutParams {
                width = defaultWidth
                height = defaultHeight
            }
            adjustingDefaultButton.translationX = buttonGroupStartX
            adjustingDefaultButton.translationY = buttonsY
        } else if (isEffectiveFloating) {
            // In floating mode, position according to original floating logic
            val kX = keyboardView.translationX
            val kY = keyboardView.translationY
            val kWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
            val moveHandleSize = dp(24)
            adjustableHandle.translationX = kX + (kWidth - moveHandleSize) / 2
            adjustableHandle.translationY = kY - moveHandleSize - dp(8)
            
            // Update layout params for floating mode
            adjustableHandle.updateLayoutParams {
                width = moveHandleSize
                height = moveHandleSize
            }
        }

        // Ensure all handles are brought to front to be above the overlay
        adjustingHeightHandle.bringToFront()
        adjustingLeftEdgeHint.bringToFront()
        adjustingRightEdgeHint.bringToFront()
        adjustingTopEdgeHint.bringToFront()
        adjustingLeftMarginHandle.bringToFront()
        adjustingRightMarginHandle.bringToFront()
        // floatingMoveHandle now also serves as the bottom padding adjuster in adjusting mode
        adjustableHandle.bringToFront()
        adjustingDefaultButton.bringToFront()
        adjustingConfirmButton.bringToFront()
    }

    private fun updateAdjustingOverlayVisibility() {
        if (isAdjustingMode) {
            adjustingOverlay.visibility = VISIBLE
            // Disable keyboard input during adjusting mode
            keyboardView.isEnabled = false
            keyboardView.isClickable = false
            keyboardView.isFocusable = false
            // Keep overlay behind handles - don't call bringToFront on it
            // Handles are added after overlay, so they should be on top by default
        } else {
            adjustingOverlay.visibility = GONE
            keyboardView.isEnabled = true
            keyboardView.isClickable = true
            keyboardView.isFocusable = true
        }
        // Bring floating move handle to front to ensure it's visible above overlay when in adjusting mode
        if (isAdjustingMode) {
            adjustableHandle.bringToFront()
            adjustingDefaultButton.bringToFront()
            adjustingConfirmButton.bringToFront()
        }
    }

    private fun updateSplitBackgroundVisibility() {
        customBackground.visibility = VISIBLE
    }

    private fun applyDockedKeyboardState() {
        val params = keyboardView.layoutParams as ConstraintLayout.LayoutParams
        params.matchConstraintMaxWidth = params.unset
        params.matchConstraintMinWidth = params.unset
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = params.unset
        params.width = matchParent
        params.startToEnd = params.unset
        params.endToStart = params.unset
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        keyboardView.layoutParams = params
        keyboardView.translationX = 0f
        keyboardView.translationY = 0f

        preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = matchParent
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = keyboardView.id
            topToBottom = unset
            topToTop = unset
            bottomToBottom = unset
        }
        preedit.ui.root.translationX = 0f
        preedit.ui.root.translationY = 0f

        syncOneHandHandleUi()
        syncKeyboardBoundsAfterLayout()
        keyboardView.invalidateOutline()
        requestLayout()
    }

    internal fun toggleFloatingMode() {
        popup.dismissAll()
        if (!isFloating && isPhysicalCandidateBarMode) {
            setPhysicalCandidateBarMode(false)
        }
        if (isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt()
            )
        }
        if (!isFloating && isOneHanded) {
            isOneHanded = false
            oneHandModeEnabledPref = false
        }
        isFloating = !isFloating
        floatingModeEnabledPref = isFloating
        kawaiiBar.setFloatingState(isEffectiveFloating)
        updateFloatingState()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        updateKeyboardSize() // Add this to refresh padding/height based on new state
        updateOneHandGapScale(force = true)
        // Rebuild the current keyboard so alt-text (punctuation) positions are
        // recomputed from a clean state at the final size. The one-hand toggle gets
        // this for free via setHorizontalGapScale -> refreshStyle, but the floating
        // toggle keeps the gap scale at 1f, so without this a top-center label can
        // get stuck in the top-right fallback after returning from floating mode.
        (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.refreshCurrentKeyboard()
        service.updateFullscreenMode()
        // Force layout update
        requestLayout()
        // Trigger insets update
        service.window.window?.decorView?.requestLayout()
    }

    private fun restoreFloatingAndOneHandState() {
        if (floatingModeEnabledPref) {
            isFloating = true
            isOneHanded = false
        } else if (oneHandModeEnabledPref) {
            isOneHanded = true
            isFloating = false
            resolveOneHandWidth()
        }
    }

    internal fun enterAdjustingMode() {
        if (!isAdjustingMode) {
            toggleAdjustingMode()
        }
    }

    internal fun exitAdjustingMode() {
        if (isAdjustingMode) {
            toggleAdjustingMode()
        }
    }

    fun toggleOneHandMode() {
        popup.dismissAll()
        if (isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt()
            )
            isFloating = false
            floatingModeEnabledPref = false
            kawaiiBar.setFloatingState(false)
        }
        isOneHanded = !isOneHanded
        oneHandModeEnabledPref = isOneHanded
        kawaiiBar.setOneHandKeyboardState(isOneHanded)
        if (isOneHanded) {
            resolveOneHandWidth()
        }
        updateFloatingState()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        updateKeyboardSize()
        updateOneHandGapScale(force = true)
        if (isOneHanded) {
            syncKeyboardBoundsAfterLayout()
        }
        service.updateFullscreenMode()
        requestLayout()
        service.window.window?.decorView?.requestLayout()
    }

    private fun toggleAdjustingMode() {
        popup.dismissAll()
        if (isAdjustingMode) {
            adjustingPendingHeightPercent = null
        }
        isAdjustingMode = !isAdjustingMode
        // In adjusting mode, force non-floating state for better UX
        if (isAdjustingMode && isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt()
            )
            isFloating = false
            kawaiiBar.setFloatingState(false)
            floatingModeEnabledPref = false
            updateFloatingState()
        }
        if (isAdjustingMode) {
            clampKeyboardSidePaddingToSafeRange()
            updateKeyboardSize()
        }
        updateAdjustingModeUi()
        requestLayout()
    }

    private fun updateAdjustingModeUi() {
        updateAdjustingHandlesVisibility()
        updateAdjustingHandleAppearance()
        // Update floating handles visibility to ensure floatingMoveHandle visibility is correct
        updateFloatingHandlesVisibility()
        // Post to ensure layout is complete before positioning handles and overlay
        keyboardView.post {
            updateAdjustingHandlePosition()
            updateAdjustingOverlayVisibility()
        }
    }

    private fun updateAdjustingHandleAppearance() {
        val handleColor = theme.accentKeyBackgroundColor
        val handleIconColor = theme.accentKeyTextColor
        val mountainBase =
            (windowManager.view.height.takeIf { it > 0 } ?: keyboardView.height.takeIf { it > 0 } ?: keyboardHeightPx) / 3f
        val mountainDepth = dp(13).toFloat()

        val overlayScrimColor = if (theme.isDark) {
            Color.argb(84, 255, 255, 255)
        } else {
            Color.argb(132, 0, 0, 0)
        }
        adjustingOverlay.setBackgroundColor(overlayScrimColor)

        adjustingHeightHandle.background = InsetTopHandleDrawable(
            handleColor = handleColor,
            arrowColor = handleIconColor,
            insetFromEdgePx = dp(33).toFloat()
        )
        adjustingTopEdgeHint.background = TopMountainHintDrawable(
            mountainColor = handleColor,
            baseLengthPx = mountainBase,
            depthPx = mountainDepth
        )

        // Side handles: outer circle + inner triangle, with a smooth bridge to screen edge.
        adjustingLeftMarginHandle.background = InsetSideHandleDrawable(
            attachToLeftEdge = true,
            handleColor = handleColor,
            arrowColor = handleIconColor
        )
        adjustingRightMarginHandle.background = InsetSideHandleDrawable(
            attachToLeftEdge = false,
            handleColor = handleColor,
            arrowColor = handleIconColor
        )
        adjustingLeftEdgeHint.background = EdgeMountainHintDrawable(
            attachToLeftEdge = true,
            mountainColor = handleColor,
            baseLengthPx = mountainBase,
            depthPx = mountainDepth
        )
        adjustingRightEdgeHint.background = EdgeMountainHintDrawable(
            attachToLeftEdge = false,
            mountainColor = handleColor,
            baseLengthPx = mountainBase,
            depthPx = mountainDepth
        )

        // floatingMoveHandle serves as bottom padding adjuster in adjusting mode
        // Use the same appearance as in floating mode
        val moveHandleSize = dp(24)
        val moveBgDrawable = createHandleDrawable(moveHandleSize / 2f)
        val moveIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_move_handle_cross)?.mutate()
        val finalDrawable = if (moveIconDrawable != null) {
            moveIconDrawable.setTint(handleIconColor)
            val inset = dp(4)
            val ld = LayerDrawable(arrayOf(moveBgDrawable, moveIconDrawable))
            ld.setLayerInset(1, inset, inset, inset, inset)
            ld
        } else {
            moveBgDrawable
        }
        adjustableHandle.background = finalDrawable

        val defaultDrawable = GradientDrawable().apply {
            setColor(theme.altKeyBackgroundColor)
            cornerRadius = dp(20).toFloat()
        }
        val confirmDrawable = GradientDrawable().apply {
            setColor(theme.accentKeyBackgroundColor)
            cornerRadius = dp(20).toFloat()
        }
        adjustingDefaultButton.background = defaultDrawable
        adjustingConfirmButton.background = confirmDrawable
        adjustingDefaultButton.setTextColor(theme.altKeyTextColor)
        adjustingConfirmButton.setTextColor(theme.accentKeyTextColor)

    }

    fun getFloatingKeyboardRegion(outRegion: Region) {
        if (!isEffectiveFloating) return
        val rect = Rect()

        keyboardView.getHitRect(rect)

        if (preedit.ui.root.visibility == View.VISIBLE) {
             val preeditRect = Rect()
             preedit.ui.root.getHitRect(preeditRect)
             rect.union(preeditRect)
        }

        if (floatingRightHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            floatingRightHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        if (floatingBottomHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            floatingBottomHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        if (adjustableHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            adjustableHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        // Include adjusting mode handles if in adjusting mode (even in floating mode)
        if (isAdjustingMode) {
            if (adjustingHeightHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustingHeightHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }

            if (adjustingLeftMarginHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustingLeftMarginHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }

            if (adjustingRightMarginHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustingRightMarginHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }

            // adjustingBottomHandle has been merged with floatingMoveHandle
            if (adjustableHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustableHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }
            if (adjustingConfirmButton.visibility == View.VISIBLE) {
                val confirmRect = Rect()
                adjustingConfirmButton.getHitRect(confirmRect)
                rect.union(confirmRect)
            }
            if (adjustingDefaultButton.visibility == View.VISIBLE) {
                val defaultRect = Rect()
                adjustingDefaultButton.getHitRect(defaultRect)
                rect.union(defaultRect)
            }
        }

        // No extra inset needed now as handles provide padding and coverage

        outRegion.set(rect)
    }

    fun getDockedKeyboardRegion(outRegion: Region) {
        val keyboardLocation = IntArray(2)
        keyboardView.getLocationInWindow(keyboardLocation)
        val rect = Rect(
            keyboardLocation[0],
            keyboardLocation[1],
            keyboardLocation[0] + keyboardView.width,
            keyboardLocation[1] + keyboardView.height
        )

        if (oneHandHandle.visibility == View.VISIBLE) {
            val handleLocation = IntArray(2)
            oneHandHandle.getLocationInWindow(handleLocation)
            val handleRect = Rect(
                handleLocation[0],
                handleLocation[1],
                handleLocation[0] + oneHandHandle.width,
                handleLocation[1] + oneHandHandle.height
            )
            rect.union(handleRect)
        }

        // Include adjusting mode handles if in adjusting mode
        if (isAdjustingMode) {
            if (adjustingHeightHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustingHeightHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustingHeightHandle.width,
                    handleLocation[1] + adjustingHeightHandle.height
                )
                rect.union(handleRect)
            }

            if (adjustingLeftMarginHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustingLeftMarginHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustingLeftMarginHandle.width,
                    handleLocation[1] + adjustingLeftMarginHandle.height
                )
                rect.union(handleRect)
            }

            if (adjustingRightMarginHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustingRightMarginHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustingRightMarginHandle.width,
                    handleLocation[1] + adjustingRightMarginHandle.height
                )
                rect.union(handleRect)
            }

            // adjustingBottomHandle has been merged with floatingMoveHandle
            if (adjustableHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustableHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustableHandle.width,
                    handleLocation[1] + adjustableHandle.height
                )
                rect.union(handleRect)
            }
            if (adjustingConfirmButton.visibility == View.VISIBLE) {
                val confirmLocation = IntArray(2)
                adjustingConfirmButton.getLocationInWindow(confirmLocation)
                val confirmRect = Rect(
                    confirmLocation[0],
                    confirmLocation[1],
                    confirmLocation[0] + adjustingConfirmButton.width,
                    confirmLocation[1] + adjustingConfirmButton.height
                )
                rect.union(confirmRect)
            }
            if (adjustingDefaultButton.visibility == View.VISIBLE) {
                val defaultLocation = IntArray(2)
                adjustingDefaultButton.getLocationInWindow(defaultLocation)
                val defaultRect = Rect(
                    defaultLocation[0],
                    defaultLocation[1],
                    defaultLocation[0] + adjustingDefaultButton.width,
                    defaultLocation[1] + adjustingDefaultButton.height
                )
                rect.union(defaultRect)
            }
        }

        outRegion.set(rect)
    }

    private fun updateFloatingState() {
        val params = keyboardView.layoutParams as ConstraintLayout.LayoutParams
        if (isEffectiveFloating) {
            // Floating mode
            params.width = resolveFloatingWidth()
            params.bottomToBottom = params.unset
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = params.unset

            // Set InputView height to match parent (screen) to allow dragging anywhere
            layoutParams?.height = matchParent

            // In floating mode, we rely on translation.
            val (storedX, storedY) = getStoredFloatingPosition()
            if (storedX != -1 && storedY != -1) {
                keyboardView.translationX = storedX.toFloat()
                keyboardView.translationY = storedY.toFloat()
            } else if (keyboardView.translationX == 0f && keyboardView.translationY == 0f) {
                keyboardView.translationX = (resources.displayMetrics.widthPixels * 0.1).toFloat()
                // Start a bit lower than center to avoid covering input field immediately if possible
                keyboardView.translationY = (resources.displayMetrics.heightPixels * 0.6).toFloat()
            }

            // Sync handles position
            updateHandlePosition()
            // Post update to ensure layout has happened
            keyboardView.post {
                clampFloatingPosition()
                updateHandlePosition()
            }

            // Update preedit constraints for floating mode
            // It should be attached to top of keyboardView
            preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                width = 0 // match constraints
                // Remove centerHorizontally (startToStart=parent, endToEnd=parent) if present from initial setup
                startToStart = keyboardView.id
                endToEnd = keyboardView.id
                bottomToTop = keyboardView.id
                // Remove other vertical constraints
                topToBottom = unset
                topToTop = unset
                bottomToBottom = unset
            }
            preedit.ui.root.translationX = keyboardView.translationX
            preedit.ui.root.translationY = keyboardView.translationY

            // Apply text scale
            (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.setTextScale(0.8f)

        } else {
            // Docked mode
            layoutParams?.height = matchParent
            applyDockedKeyboardState()
            // Reset text scale
            (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.setTextScale(1.0f)
        }
        // Always update handle position to ensure appearance is set correctly
        updateHandlePosition()
        keyboardView.layoutParams = params
        updateOneHandHandleVisibility()
        updateSplitBackgroundVisibility()
        // Request layout to apply changes to self and children
        keyboardView.invalidateOutline()
        requestLayout()
    }

    private val keyboardHeightPx: Int
        get() {
            companionKeyboardHeightPxOverride()?.let { return it }
            val effectivePercent = resolveEffectiveKeyboardHeightPercent()
            val baseHeight = (resources.displayMetrics.heightPixels * effectivePercent / 100f).toInt()
            if (isEffectiveFloating) {
                return (baseHeight * 0.8).toInt()
            }
            return baseHeight
        }

    internal fun captureCurrentKeyboardHeightPxForCompanion(): Int {
        return keyboardHeightPx
    }

    internal fun captureCurrentKeyboardHeightPercentForCompanion(): Int {
        return kotlin.math.round(resolveEffectiveKeyboardHeightPercent()).toInt().coerceIn(10, 90)
    }

    private fun companionKeyboardHeightPxOverride(): Int? {
        if (adjustingPendingHeightPercent != null) return null
        val keyboard = windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow
        return when (windowManager.currentWindowOrNull()) {
            is PickerWindow -> keyboard?.companionKeyboardHeightPxOverride()
            is KeyboardWindow -> keyboard?.companionKeyboardHeightPxOverride()
                ?.takeIf { keyboard.usesCompanionKeyboardHeightOverride() }
            else -> null
        }
    }

    private fun resolveEffectiveKeyboardHeightPercent(): Float {
        val globalPercent = (if (isLayoutLandscape) keyboardHeightPercentLandscape else keyboardHeightPercent).getValue()
        val keyboard = windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow
        val companionPercent = if (windowManager.currentWindowOrNull() is PickerWindow) {
            keyboard?.companionKeyboardHeightPercentOverride()
        } else {
            null
        }
        val overridePercent = companionPercent ?: keyboard?.currentKeyboardHeightPercentOverride()
        val heightScale = if (companionPercent != null) {
            1f
        } else {
            keyboard?.currentKeyboardHeightScaleFactor() ?: 1f
        }
        val basePercent = adjustingPendingHeightPercent ?: overridePercent ?: globalPercent
        return (basePercent.toFloat() * heightScale).coerceIn(10f, 90f)
    }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = resolveKeyboardSidePadding()
            val px = dp(value)
            if (isEffectiveFloating) {
                return (px * 0.8).toInt()
            }
            return px
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = (if (isLayoutLandscape) keyboardBottomPaddingLandscape else keyboardBottomPadding).getValue()
            val px = dp(value)
            if (isEffectiveFloating) {
                return (px * 0.8).toInt()
            }
            return px
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            // Keep keyboard height source in sync with freshly reloaded key layouts.
            if (key == keyboardPrefs.splitKeyboardEnabled.key ||
                key == keyboardPrefs.splitKeyboardThreshold.key ||
                key == keyboardPrefs.splitKeyboardGapPercent.key ||
                key == textKeyboardLayoutProfile.key) {
                (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.refreshAllKeyboards()
            }

            updateFloatingState()
            updateFloatingHandlesVisibility()
            updateOneHandHandleVisibility()
            kawaiiBar.setFloatingState(isEffectiveFloating)
            updateKeyboardSize()
            service.updateFullscreenMode()
        }
    }

    @Keep
    private val onCandidatePreferenceChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key == physicalKeyboardHorizontalCandidateBar.key) {
            service.inputDeviceManager.onPhysicalKeyboardHorizontalCandidateBarChanged()
        }
    }

    val keyboardView: View

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return super.onTouchEvent(event)
    }

    init {
        oneHandOnRight = getStoredOneHandSide()

        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)
        windowManager.onWindowChanged = {
            if (isPhysicalCandidateBarMode) {
                syncPhysicalCandidateBarLayout()
            } else {
                updateKeyboardSize()
            }
        }

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        // Apply blur effect when theme has background image and blurRadius > 0
        val hasBlur = theme is Theme.Custom && theme.shouldApplyBlur()
        Timber.d("InputView init: theme=${theme.name}, isCustom=${theme is Theme.Custom}, enableBlur=$hasBlur")
        if (theme is Theme.Custom) {
            val bg = theme.backgroundImage
            Timber.d("InputView: backgroundImage=${bg != null}, blurRadius=${bg?.blurRadius}")
        }
        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)
        updateBlurMaskThemeData()
        
        if (windowManager.view.id == View.NO_ID) {
            windowManager.view.id = View.generateViewId()
        }

        keyboardView = constraintLayout {
            id = View.generateViewId()
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams(0, 0) {
                topOfParent()
                bottomOfParent()
                startOfParent()
                endOfParent()
            })
            add(keyBlurMaskView, lParams(0, 0) {
                topOfParent()
                bottomOfParent()
                startOfParent()
                endOfParent()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(oneHandHandle, lParams(dp(16), dp(44)) {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        keyboardView.clipToOutline = true
        keyboardView.outlineProvider = keyboardOutlineProvider
        keyboardView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.invalidateOutline()
            keyBlurMaskView.markKeyRegionsDirty()
            keyBlurMaskView.invalidate()
        }
        windowManager.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            keyBlurMaskView.markKeyRegionsDirty()
            keyBlurMaskView.invalidate()
        }
        windowManager.view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                keyBlurMaskView.markKeyRegionsDirty(hierarchyChanged = true)
                keyBlurMaskView.invalidate()
            }

            override fun onChildViewRemoved(parent: View?, child: View?) {
                keyBlurMaskView.markKeyRegionsDirty(hierarchyChanged = true)
                keyBlurMaskView.invalidate()
            }
        })

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            bottomToTop = keyboardView.id
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(floatingRightHandle, lParams(dp(10), dp(10)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(floatingBottomHandle, lParams(dp(10), dp(10)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustableHandle, lParams(dp(24), dp(24)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        // Initialize adjustableHandle visibility based on current state
        updateFloatingHandlesVisibility()
        add(adjustingOverlay, lParams(matchParent, matchParent) {
            topToTop = keyboardView.id
            bottomToBottom = keyboardView.id
            startToStart = keyboardView.id
            endToEnd = keyboardView.id
        })
        // Add handles constrained to parent, positioned absolutely in updateAdjustingHandlePosition
        add(adjustingHeightHandle, lParams(dp(60), dp(30)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingLeftEdgeHint, lParams(dp(10), dp(10)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingRightEdgeHint, lParams(dp(10), dp(10)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingTopEdgeHint, lParams(dp(10), dp(10)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingLeftMarginHandle, lParams(dp(44), dp(66)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingRightMarginHandle, lParams(dp(44), dp(66)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingConfirmButton, lParams(wrapContent, wrapContent) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingDefaultButton, lParams(wrapContent, wrapContent) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })
        add(buttonsAdjustingOverlayView, lParams(matchParent, matchParent) {
            topToTop = keyboardView.id
            bottomToBottom = keyboardView.id
            startToStart = keyboardView.id
            endToEnd = keyboardView.id
        })
        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        candidatesPrefs.registerOnChangeListener(onCandidatePreferenceChangeListener)
        restoreFloatingAndOneHandState()
        updateFloatingState()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        updateSplitBackgroundVisibility()
        kawaiiBar.setFloatingState(isEffectiveFloating)
        kawaiiBar.setOneHandKeyboardState(isOneHanded)
        // Re-broadcast IME once InputView is fully initialized so layout-specific
        // keyboard height overrides are applied on first show.
        post { syncImeFromCache() }

        kawaiiBar.onFloatingToggleListener = {
            // If currently in adjusting mode, exit adjusting mode; otherwise toggle floating mode
            if (isAdjustingMode) {
                toggleAdjustingMode()
            } else {
                toggleFloatingMode()
            }
        }
        kawaiiBar.onFloatingLongPressListener = {
            // If not in floating mode and not in one-handed mode, allow entering adjusting mode
            if (!isFloating && !isOneHanded) {
                toggleAdjustingMode()
            }
        }

        kawaiiBar.view.setOnTouchListener { v, event ->
            if (!isFloating) return@setOnTouchListener false
            v.parent?.requestDisallowInterceptTouchEvent(true)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    keyboardView.translationX += dx
                    keyboardView.translationY += dy
                    clampFloatingPosition()
                    refreshKeyboardBounds()
                    // Sync preedit position
                    preedit.ui.root.translationX = keyboardView.translationX
                    preedit.ui.root.translationY = keyboardView.translationY

                    // Sync handles position
                    updateHandlePosition()
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                     v.parent?.requestDisallowInterceptTouchEvent(false)
                     refreshKeyboardBounds() // Ensure bounds are correct after drag
                     // Save position
                     saveFloatingPosition(
                        keyboardView.translationX.toInt(),
                        keyboardView.translationY.toInt()
                     )
                     true
                }
                else -> false
            }
        }
    }

    internal val isButtonsAdjustingOverlayVisible: Boolean
        get() = buttonsAdjustingOverlayView.visibility == VISIBLE

    internal fun showButtonsAdjustingOverlay() {
        if (isButtonsAdjustingOverlayVisible) return
        popup.dismissAll()
        ButtonsAdjustingWindow.updateOverlayInsets(
            keyboardSidePaddingPx,
            keyboardBottomPaddingPx,
            isPhysicalCandidateBarMode
        )
        ButtonsAdjustingWindow.onAttached()
        buttonsAdjustingOverlayView.bringToFront()
        buttonsAdjustingOverlayView.visibility = VISIBLE
        updateKeyboardSize()
    }

    internal fun hideButtonsAdjustingOverlay() {
        if (!isButtonsAdjustingOverlayVisible) return
        ButtonsAdjustingWindow.onDetached()
        buttonsAdjustingOverlayView.visibility = GONE
        updateKeyboardSize()
    }

    private fun updateKeyboardSize() {
        applyStoredOneHandSideIfNeeded()

        ButtonsAdjustingWindow.updateOverlayInsets(
            keyboardSidePaddingPx,
            keyboardBottomPaddingPx,
            isPhysicalCandidateBarMode
        )
        updateKeyboardTopBarPosition()

        val collapseKeyboardWindow =
            isPhysicalCandidateBarMode &&
                windowManager.currentWindowOrNull() is KeyboardWindow &&
                !isAdjustingMode &&
                !isButtonsAdjustingOverlayVisible
        val targetHeight = when {
            collapseKeyboardWindow -> 1
            isEffectiveFloating -> resolveFloatingHeight()
            else -> keyboardHeightPx
        }
        windowManager.view.visibility = if (collapseKeyboardWindow) INVISIBLE else VISIBLE
        windowManager.view.updateLayoutParams {
            height = targetHeight
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        if (isDockedOneHandMode) {
            val containerWidth = oneHandContainerWidth
            val oneHandWidth = resolveOneHandWidth().coerceAtMost(containerWidth)
            val remaining = (containerWidth - oneHandWidth).coerceAtLeast(0)

            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            if (oneHandOnRight) {
                leftPaddingSpace.updateLayoutParams {
                    width = remaining
                }
                rightPaddingSpace.updateLayoutParams {
                    width = 0
                }
            } else {
                leftPaddingSpace.updateLayoutParams {
                    width = 0
                }
                rightPaddingSpace.updateLayoutParams {
                    width = remaining
                }
            }

            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
            kawaiiBar.view.updateLayoutParams<LayoutParams> {
                width = 0
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
            preedit.ui.root.setPadding(
                if (oneHandOnRight) remaining else 0,
                0,
                if (oneHandOnRight) 0 else remaining,
                0
            )
            kawaiiBar.view.setPadding(0, 0, 0, 0)
            kawaiiBar.view.post { kawaiiBar.refreshButtonsLayout() }
            syncOneHandHandleUi()
            updateHandlePosition()
            syncKeyboardBoundsAfterLayout()
            return
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
            kawaiiBar.view.updateLayoutParams<LayoutParams> {
                width = LayoutParams.MATCH_PARENT
                startToEnd = unset
                endToStart = unset
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.post { kawaiiBar.refreshButtonsLayout() }
        if (isEffectiveFloating) {
            keyboardView.post {
                clampFloatingPosition()
                updateHandlePosition()
            }
        } else if (isOneHanded) {
            syncOneHandHandleUi()
        }
        // Sync handles when size changes
        updateHandlePosition()
    }

    private fun updateKeyboardTopBarPosition() {
        val placeAtBottom = isPhysicalCandidateBarMode
        if (placeAtBottom) {
            kawaiiBar.view.updateLayoutParams<LayoutParams> {
                topToTop = unset
                topToBottom = unset
                bottomToTop = bottomPaddingSpace.id
                bottomToBottom = unset
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = unset
                bottomToTop = kawaiiBar.view.id
                bottomToBottom = unset
            }
            leftPaddingSpace.updateLayoutParams<LayoutParams> {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = unset
                bottomToTop = kawaiiBar.view.id
                bottomToBottom = unset
            }
            rightPaddingSpace.updateLayoutParams<LayoutParams> {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = unset
                bottomToTop = kawaiiBar.view.id
                bottomToBottom = unset
            }
        } else {
            kawaiiBar.view.updateLayoutParams<LayoutParams> {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = unset
                bottomToTop = unset
                bottomToBottom = unset
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                topToTop = unset
                topToBottom = kawaiiBar.view.id
                bottomToTop = bottomPaddingSpace.id
                bottomToBottom = unset
            }
            leftPaddingSpace.updateLayoutParams<LayoutParams> {
                topToTop = unset
                topToBottom = kawaiiBar.view.id
                bottomToTop = unset
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            rightPaddingSpace.updateLayoutParams<LayoutParams> {
                topToTop = unset
                topToBottom = kawaiiBar.view.id
                bottomToTop = unset
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        if (isAdjustingMode) {
            exitAdjustingMode()
        }
        hideButtonsAdjustingOverlay()
        keyboardWindow.checkAndApplyFontRefresh()
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.PagedCandidateEvent -> {
                // Keep legacy candidate listeners alive even in paged mode.
                broadcaster.onCandidateUpdate(
                    FcitxEvent.CandidateListEvent.Data(total = -1, candidates = it.data.candidates)
                )
                broadcaster.onPagedCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    /**
     * Control whether the preedit component should display preedit text.
     * Set to false when preedit is displayed elsewhere (e.g., in floating CandidatesView).
     */
    fun setPreeditVisibility(shouldDisplay: Boolean) {
        preedit.shouldDisplay = shouldDisplay
    }

    internal fun setPhysicalCandidateBarMode(enabled: Boolean) {
        if (isPhysicalCandidateBarMode == enabled) return
        if (enabled && isFloating) {
            isFloating = false
        }
        isPhysicalCandidateBarMode = enabled
        syncPhysicalCandidateBarLayout()
    }

    private fun syncPhysicalCandidateBarLayout() {
        updateFloatingState()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        kawaiiBar.setFloatingState(isEffectiveFloating)
        updateKeyboardSize()
        service.updateFullscreenMode()
        requestLayout()
        service.window.window?.decorView?.requestLayout()
    }

    /**
     * Get the Y position of the input field top (where text appears)
     * This is used for positioning floating candidates window
     */
    internal fun getInputFieldTopY(): Float {
        // The input field top is approximately at the top of the keyboard view
        // In virtual keyboard mode, the text appears in the app's input field above the keyboard
        // We use the keyboard top as a reference point for the cursor Y position
        val kv = keyboardView
        val location = IntArray(2)
        kv.getLocationInWindow(location)
        return location[1].toFloat()
    }

    /**
     * Update space key label in "Always" floating mode
     * Called when InputView doesn't handle Fcitx events but still needs to update space key
     */
    internal fun updateSpaceLabelOnFloatingMode() {
        val ime = fcitx.runImmediately { inputMethodEntryCached }
        
        // Try to notify KeyboardWindow to update space label
        val keyboardWindow = windowManager.getEssentialWindow(KeyboardWindow) as? InputBroadcastReceiver
        if (keyboardWindow != null) {
            keyboardWindow.onImeUpdate(ime)
        } else {
            // Fallback: directly update TextKeyboard if KeyboardWindow is not ready
            // This can happen during initial view setup
            val kv = keyboardView
            val viewGroup = kv as? ViewGroup
            val childCount = viewGroup?.childCount ?: 0
            for (i in 0 until childCount) {
                val child = viewGroup?.getChildAt(i)
                (child as? BaseKeyboard)?.onInputMethodUpdate(ime)
            }
        }
    }

    internal fun executeButtonAction(actionId: String) {
        ButtonAction.fromId(actionId)?.execute(
            context = context,
            service = service,
            fcitx = fcitx,
            windowManager = windowManager,
            view = null,
            onActionComplete = null
        )
    }

    internal fun executeLayerSwitch(mode: KeyAction.LayerSwitchMode, target: String) {
        keyboardWindow.switchLayer(mode, target)
    }

    internal fun consumeOneShotLayer() {
        keyboardWindow.consumeOneShotLayer()
    }

    /**
     * Re-broadcast current IME state from fcitx cache.
     * Useful after InputView recreation (e.g. theme switch) to avoid waiting for next async IM event.
     */
    internal fun syncImeFromCache() {
        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })
    }

    internal fun onKeyboardHeightSourceChanged() {
        updateKeyboardSize()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    private val onButtonsLayoutChangeListener: ConfigChangeListener = {
        kawaiiBar.reloadButtonsConfig()
    }

    private val onIconChangeListener: ConfigChangeListener = {
        kawaiiBar.reloadButtonIcons()
    }

    init {
        // Register listener for buttons layout config changes
        ConfigProviders.addButtonsLayoutListener(onButtonsLayoutChangeListener)
        // Register listener for button icon file changes (hot-reload)
        ConfigProviders.addIconChangeListener(onIconChangeListener)
    }

    override fun onDetachedFromWindow() {
        windowManager.onWindowChanged = null
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        candidatesPrefs.unregisterOnChangeListener(onCandidatePreferenceChangeListener)
        ConfigProviders.removeButtonsLayoutListener(onButtonsLayoutChangeListener)
        ConfigProviders.removeIconChangeListener(onIconChangeListener)
        blurUpdateJob?.cancel()
        blurUpdateScope.cancel()
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
