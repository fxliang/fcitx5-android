/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import androidx.annotation.RequiresApi
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.NavbarBackground
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry
import org.fcitx.fcitx5.android.utils.BitmapBlurUtil
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.views.imageDrawable

class KeyboardPreviewUi(override val ctx: Context, val theme: Theme) : Ui {

    var intrinsicWidth: Int = -1
        private set

    var intrinsicHeight: Int = -1
        private set

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val keyboardHeightPercent by keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape by keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding by keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape by keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding by keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape by keyboardPrefs.keyboardBottomPaddingLandscape

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (ctx.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }
            return ctx.dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (ctx.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }
            return ctx.dp(value)
        }

    private val navbarBackground = ThemeManager.prefs.navbarBackground
    private val keyBorder by ThemeManager.prefs.keyBorder

    private val navbarBkgChangeListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        recalculateSize()
    }

    private val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val blurMaskView = PreviewBlurMaskView().apply {
        visibility = View.GONE
    }

    private val barHeight = ctx.dp(40)
    private val fakeKawaiiBar = view(::View)

    private var keyboardWidth = -1
    private var keyboardHeight = -1
    private var sizeScale = 1f
    private lateinit var fakeKeyboardWindow: TextKeyboard
    private var currentTheme: Theme? = null
    private var isUpdatingTheme = false
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var backgroundJob: Job? = null
    private var backgroundGeneration = 0L

    private inner class PreviewBlurMaskView : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = Rect()
        private val clipRect = Rect()
        private val clipRectF = RectF()
        private val clipPath = Path()
        private val keyViews = ArrayList<KeyView>(64)
        private val keyClipRects = ArrayList<Rect>(64)
        private val keyClipRadii = ArrayList<Float>(64)
        private var blurBitmap: Bitmap? = null
        private var redrawRetryCount = 0
        private var keyRegionsDirty = true
        private var keyHierarchyDirty = true
        private var hasVisibleKey = false
        /**
         * RenderNode carrying the GPU blur RenderEffect. The bitmap is recorded into the node;
         * replaying the node under a canvas clip blurs the full image first and then hard-clips,
         * matching the CPU `loadBlurredBitmapForRendering + clipPath + drawBitmap` semantics.
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
                // View itself never carries the RenderEffect — blur lives on the offscreen node,
                // so canvas clipping in onDraw applies after the blur.
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
            visibility = if (bitmap == null) View.GONE else View.VISIBLE
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

        override fun onDraw(canvas: Canvas) {
            val bitmap = blurBitmap ?: return
            if (width <= 0 || height <= 0) return
            calculateCenterCropSource(bitmap.width, bitmap.height, width, height, srcRect)
            dstRect.set(0, 0, width, height)

            if (!keyBorder) {
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
                redrawRetryCount = 0
                return
            }

            if (!this@KeyboardPreviewUi::fakeKeyboardWindow.isInitialized) return
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

            if (fakeKawaiiBar.isShown && fakeKawaiiBar.width > 0 && fakeKawaiiBar.height > 0) {
                val barSaveId = canvas.save()
                clipRect.set(
                    fakeKawaiiBar.left,
                    fakeKawaiiBar.top,
                    fakeKawaiiBar.right,
                    fakeKawaiiBar.bottom
                )
                if (clipRect.intersect(0, 0, width, height)) {
                    canvas.clipRect(clipRect)
                    drawFullScreenBlur(canvas, bitmap)
                }
                canvas.restoreToCount(barSaveId)
            }

            if (hasVisibleKey && !drewKeyRegion) {
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
         * Draws the source bitmap onto [canvas]. On API ≥ S the bitmap is replayed through a
         * RenderNode carrying the blur RenderEffect, so the GPU blur runs once over the full
         * image and any canvas clip the caller set applies AFTER the blur — visually equivalent
         * to `loadBlurredBitmapForRendering + clipPath + drawBitmap` on the CPU path.
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
                keyViews.clear()
                collectVisibleKeys(fakeKeyboardWindow, keyViews)
                keyHierarchyDirty = false
            }
            fun buildClipRects() {
                hasVisibleKey = false
                keyClipRects.clear()
                keyClipRadii.clear()
                keyViews.forEach { key ->
                    if (!key.isShown) return@forEach
                    hasVisibleKey = true
                    if (key.width <= 0 || key.height <= 0) return@forEach
                    clipRect.set(0, 0, key.width, key.height)
                    fakeInputView.offsetDescendantRectToMyCoords(key, clipRect)
                    clipRect.offset(-left, -top)
                    clipRect.set(
                        clipRect.left + key.hMargin,
                        clipRect.top + key.vMargin,
                        clipRect.right - key.hMargin,
                        clipRect.bottom - key.vMargin
                    )
                    if (clipRect.width() <= 0 || clipRect.height() <= 0) return@forEach
                    if (!clipRect.intersect(0, 0, width, height)) return@forEach
                    val maxRadius = minOf(clipRect.width(), clipRect.height()) * 0.5f
                    val radius = key.radius.coerceIn(0f, maxRadius)
                    keyClipRects.add(Rect(clipRect))
                    keyClipRadii.add(radius)
                }
            }
            buildClipRects()
            if (!hasVisibleKey && keyViews.isNotEmpty()) {
                keyViews.clear()
                collectVisibleKeys(fakeKeyboardWindow, keyViews)
                buildClipRects()
            }
        }

        private fun collectVisibleKeys(view: View, out: MutableList<KeyView>) {
            if (view is KeyView) {
                out.add(view)
                return
            }
            val group = view as? ViewGroup ?: return
            for (i in 0 until group.childCount) {
                collectVisibleKeys(group.getChildAt(i), out)
            }
        }
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

    private fun applyBlurMaskFromBitmap(sourceBitmap: Bitmap?, blurRadius: Float, brightness: Int) {
        backgroundJob?.cancel()
        val generation = ++backgroundGeneration
        if (sourceBitmap == null || blurRadius <= 0f) {
            blurMaskView.setBlurBitmap(null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurMaskView.setBlurBitmap(
                bitmap = sourceBitmap,
                brightness = brightness,
                blurRadius = blurRadius,
                useRenderEffect = true
            )
        } else {
            backgroundJob = backgroundScope.launch {
                val blurred = withContext(Dispatchers.IO) {
                    BitmapBlurUtil.blur(sourceBitmap, blurRadius)
                }
                if (generation != backgroundGeneration) return@launch
                blurMaskView.setBlurBitmap(blurred, brightness)
            }
        }
    }

    private fun fallbackBackground(theme: Theme): Drawable =
        if (theme is Theme.Custom && theme.backgroundImage != null) {
            ColorDrawable(if (keyBorder) theme.backgroundColor else theme.keyboardColor)
        } else {
            theme.backgroundDrawable(keyBorder)
        }

    private fun loadBackgroundFromThemeAsync(theme: Theme) {
        backgroundJob?.cancel()
        val generation = ++backgroundGeneration
        val custom = theme as? Theme.Custom
        val bg = custom?.backgroundImage
        if (bg == null) {
            setBackground(theme.backgroundDrawable(keyBorder))
            blurMaskView.setBlurBitmap(null)
            return
        }
        setBackground(fallbackBackground(theme))
        blurMaskView.setBlurBitmap(null)
        backgroundJob = backgroundScope.launch {
            try {
                val sourceBitmap = withContext(Dispatchers.IO) {
                    bg.loadBitmapForRendering()
                }
                if (generation != backgroundGeneration) return@launch
                if (sourceBitmap == null) {
                    setBackground(fallbackBackground(theme))
                    blurMaskView.setBlurBitmap(null)
                    return@launch
                }
                setBackground(
                    BitmapDrawable(ctx.resources, sourceBitmap).apply {
                        colorFilter = DarkenColorFilter(100 - bg.brightness)
                    }
                )
                if (bg.blurRadius <= 0f) {
                    blurMaskView.setBlurBitmap(null)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurMaskView.setBlurBitmap(
                        bitmap = sourceBitmap,
                        brightness = bg.brightness,
                        blurRadius = bg.blurRadius,
                        useRenderEffect = true
                    )
                } else {
                    val blurred = withContext(Dispatchers.IO) {
                        BitmapBlurUtil.blur(sourceBitmap, bg.blurRadius)
                    }
                    if (generation != backgroundGeneration) return@launch
                    blurMaskView.setBlurBitmap(blurred, bg.brightness)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation != backgroundGeneration) return@launch
                setBackground(fallbackBackground(theme))
                blurMaskView.setBlurBitmap(null)
            }
        }
    }

    private val fakeInputView = constraintLayout {
        add(bkg, lParams(matchConstraints, matchConstraints) {
            topOfParent()
            bottomOfParent()
            startOfParent()
            endOfParent()
        })
        add(blurMaskView, lParams(matchConstraints, matchConstraints) {
            topOfParent()
            bottomOfParent()
            startOfParent()
            endOfParent()
        })
        add(fakeKawaiiBar, lParams(matchConstraints, dp(40)) {
            topOfParent()
            centerHorizontally()
        })
    }

    override val root = object : FrameLayout(ctx) {
        init {
            add(fakeInputView, lParams())
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            recalculateSize()
            onSizeMeasured?.invoke(intrinsicWidth, intrinsicHeight)
            navbarBackground.registerOnChangeListener(navbarBkgChangeListener)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            recalculateSize()
        }

        override fun onDetachedFromWindow() {
            backgroundJob?.cancel()
            backgroundGeneration++
            navbarBackground.unregisterOnChangeListener(navbarBkgChangeListener)
            super.onDetachedFromWindow()
        }
    }

    var onSizeMeasured: ((Int, Int) -> Unit)? = null

    private fun keyboardWindowAspectRatio(): Pair<Int, Int> {
        val resources = ctx.resources
        val displayMetrics = resources.displayMetrics
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels
        val hPercent = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
            else -> keyboardHeightPercent
        }
        return w to (h * hPercent / 100)
    }

    init {
        val (w, h) = keyboardWindowAspectRatio()
        keyboardWidth = w
        keyboardHeight = h
        setTheme(theme)
        // Apply initial size scale
        recalculateSize()
    }

    fun recalculateSize() {
        val (baseW, baseH) = keyboardWindowAspectRatio()
        val scale = sizeScale.coerceIn(0.35f, 1f)
        keyboardWidth = (baseW * scale).toInt().coerceAtLeast(1)
        keyboardHeight = (baseH * scale).toInt().coerceAtLeast(1)
        fakeKeyboardWindow.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = keyboardHeight
            horizontalMargin = keyboardSidePaddingPx
        }
        intrinsicWidth = keyboardWidth
        // KawaiiBar height + WindowManager view height
        intrinsicHeight = barHeight + keyboardHeight
        // extra bottom padding
        intrinsicHeight += keyboardBottomPaddingPx
        // windowInsets navbar padding
        if (navbarBackground.getValue() == NavbarBackground.Full) {
            ViewCompat.getRootWindowInsets(root)?.also {
                // IME window has different navbar height when system navigation in "gesture navigation" mode
                // thus the inset from Activity root window is unreliable
                if (it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom > 0 ||
                    // in case navigation hint was hidden ...
                    it.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom > 0
                ) {
                    intrinsicHeight += ctx.navbarFrameHeight()
                }
            }
        }
        // fakeInputView size should match the calculated intrinsic size
        fakeInputView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = intrinsicWidth
            height = intrinsicHeight
        }
        blurMaskView.markKeyRegionsDirty()
        blurMaskView.invalidate()
    }

    fun setSizeScale(scale: Float) {
        val clamped = scale.coerceIn(0.35f, 1f)
        if (sizeScale == clamped) return
        sizeScale = clamped
        recalculateSize()
        // Also adjust text scale to match the keyboard size scale
        // This ensures text doesn't look too large when keyboard is scaled down
        if (this::fakeKeyboardWindow.isInitialized) {
            fakeKeyboardWindow.setTextScale(sizeScale)
        }
    }

    fun setBackground(drawable: Drawable) {
        bkg.imageDrawable = drawable
    }

    fun setBackgroundWithBlur(drawable: Drawable, sourceBitmap: Bitmap?, blurRadius: Float, brightness: Int) {
        setBackground(drawable)
        applyBlurMaskFromBitmap(sourceBitmap, blurRadius, brightness)
    }

    fun setTheme(theme: Theme, background: Drawable? = null, forceRefresh: Boolean = false) {
        // Prevent re-entrant calls that could cause infinite loops
        if (isUpdatingTheme) return
        
        val sameTheme = currentTheme != null && currentTheme == theme

        if (background != null) {
            backgroundJob?.cancel()
            backgroundGeneration++
            setBackground(background)
        } else {
            loadBackgroundFromThemeAsync(theme)
        }

        // First-time setup: create new keyboard view
        if (!this::fakeKeyboardWindow.isInitialized) {
            isUpdatingTheme = true
            // The preview keyboard owns its layout state, so its preview IME never reaches the
            // real keyboard. Pass it at construction time to build the right layout at once.
            val previewIme = PreviewInputMethodEntry.create()
            fakeKeyboardWindow = TextKeyboard(ctx, theme, previewIme, isPreview = true)
            currentTheme = theme

            // Match KawaiiBar behavior: use barColor for Builtin themes without border
            fakeKawaiiBar.backgroundColor = if (keyBorder) Color.TRANSPARENT else theme.barColor

            fakeInputView.apply {
                add(fakeKeyboardWindow, lParams(matchConstraints, keyboardHeight) {
                    below(fakeKawaiiBar)
                    centerHorizontally(keyboardSidePaddingPx)
                })
            }

            fakeKeyboardWindow.post {
                fakeKeyboardWindow.onAttach()
                fakeKeyboardWindow.onInputMethodUpdate(previewIme)
                fakeKeyboardWindow.setTextScale(sizeScale)
                fakeKeyboardWindow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    blurMaskView.markKeyRegionsDirty()
                    blurMaskView.invalidate()
                }
                fakeKeyboardWindow.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        blurMaskView.markKeyRegionsDirty(hierarchyChanged = true)
                        blurMaskView.invalidate()
                    }

                    override fun onChildViewRemoved(parent: View?, child: View?) {
                        blurMaskView.markKeyRegionsDirty(hierarchyChanged = true)
                        blurMaskView.invalidate()
                    }
                })
                fakeKeyboardWindow.requestLayout()
                fakeKeyboardWindow.invalidate()
                blurMaskView.markKeyRegionsDirty()
                blurMaskView.invalidate()
                isUpdatingTheme = false
            }
        } else {
            // Update KawaiiBar background color
            fakeKawaiiBar.backgroundColor = if (keyBorder) Color.TRANSPARENT else theme.barColor

            fakeKeyboardWindow.post {
                try {
                    isUpdatingTheme = true
                    if (forceRefresh || sameTheme) {
                        // Config changed: rebuild layout
                        // refreshStyle() reads latest config from ThemeManager.prefs.
                        // The keyboard still holds its own preview IME, so the rebuilt layout
                        // stays the previewed one without touching global state.
                        fakeKeyboardWindow.refreshStyle()
                    } else {
                        // Theme changed: update colors without rebuilding
                        currentTheme = theme
                        fakeKeyboardWindow.updateTheme(theme)
                    }
                    blurMaskView.markKeyRegionsDirty()
                    blurMaskView.invalidate()
                } finally {
                    isUpdatingTheme = false
                }
            }
        }
    }
}
