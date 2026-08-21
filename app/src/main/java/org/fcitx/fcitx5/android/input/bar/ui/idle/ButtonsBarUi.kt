/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view
import kotlin.math.max

class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    private var buttons: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons
) : Ui {

    @DrawableRes
    private val floatingIcon = R.drawable.ic_floating_toggle_24

    override val root = view(::KawaiiBarRecyclerView) {
        // Set fixed height to match KawaiiBar height
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ctx.dp(KawaiiBarComponent.HEIGHT)
        )
    }

    // Map to store button references by ID
    private val buttonMap = mutableMapOf<String, ToolButton>()
    // Keep per-button active state so recycled/rebound views always restore correct tint.
    private val buttonActiveMap = mutableMapOf<String, Boolean>()

    // Click listeners for each button
    private val clickListeners = mutableMapOf<String, View.OnClickListener>()
    private val longClickListeners = mutableMapOf<String, View.OnLongClickListener>()

    init {
        buildButtons()
    }

    private fun buildButtons() {
        buttonMap.clear()
        val recyclerView = root
        // Recreate adapter to ensure clean state
        recyclerView.adapter = ButtonsBarAdapter()
    }

    fun updateConfig(newButtons: List<ConfigurableButton>) {
        if (newButtons != buttons) {
            buttons = newButtons
            buildButtons()
        }
    }

    /**
     * Reload icons from disk for all buttons that use file-based custom icons.
     * Call this when icon files have changed on disk to refresh button drawables
     * without rebuilding the entire adapter.
     */
    fun reloadIcons() {
        buttons.forEach { config ->
            val button = buttonMap[config.id] ?: return@forEach
            if (config.icon != null && config.icon.startsWith("file:")) {
                applyIconAndText(button, config)
            }
        }
    }

    fun setOnClickListener(buttonId: String, listener: View.OnClickListener?) {
        if (listener != null) {
            clickListeners[buttonId] = listener
        } else {
            clickListeners.remove(buttonId)
        }
        buttonMap[buttonId]?.setOnClickListener(listener)
    }

    fun setOnLongClickListener(buttonId: String, listener: View.OnLongClickListener?) {
        if (listener != null) {
            longClickListeners[buttonId] = listener
        } else {
            longClickListeners.remove(buttonId)
        }
        buttonMap[buttonId]?.setOnLongClickListener(listener)
    }

    @DrawableRes
    private fun getIconResForButton(buttonId: String, customIcon: String?): Int {
        // If custom icon is specified, try to find it
        if (customIcon != null && !customIcon.startsWith("file:")) {
            val resId = ctx.resources.getIdentifier(customIcon, "drawable", ctx.packageName)
            if (resId != 0) return resId
        }

        // Check icon theme for SVG icon (resource icons handled separately in applyIconAndText)
        val action = ButtonAction.fromId(buttonId)
        return action?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
    }

    private fun loadFileIcon(path: String): Drawable? {
        return ButtonIconFile.loadDrawable(path)
    }

    private fun applyIconThemeIfAvailable(button: ToolButton, buttonId: String): Boolean {
        val action = ButtonAction.fromId(buttonId) ?: return false
        val slot = action.iconSlot ?: return false
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(slot)
        if (iconInfo != null) {
            button.setIconFromDrawable(iconInfo.drawable, tintWithTheme = iconInfo.tintWithTheme)
            return true
        }
        val textValue = IconThemeManager.resolveIcon(slot)
        if (textValue != null) {
            button.setText(textValue)
            return true
        }
        return false
    }

    private fun applyConfiguredIconIfAvailable(button: ToolButton, config: ConfigurableButton): Boolean {
        if (!config.text.isNullOrEmpty()) {
            button.setText(config.text)
            return true
        }
        val customIcon = config.icon ?: return false
        if (customIcon.startsWith("file:")) {
            val drawable = loadFileIcon(customIcon) ?: return false
            val tintWithTheme = ButtonIconFile.shouldTintIcon(customIcon)
            button.setIconFromDrawable(drawable, tintWithTheme = tintWithTheme)
            return true
        }
        val resId = ctx.resources.getIdentifier(customIcon, "drawable", ctx.packageName)
        if (resId != 0) {
            button.setIcon(resId)
            return true
        }
        return false
    }

    private fun applyIconAndText(button: ToolButton, config: ConfigurableButton) {
        if (applyIconThemeIfAvailable(button, config.id)) return
        if (applyConfiguredIconIfAvailable(button, config)) return
        val fallbackIcon = ButtonAction.fromId(config.id)?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
        button.setIcon(fallbackIcon)
    }

    private fun getDefaultLabel(buttonId: String): String {
        // Return default label from ButtonAction
        return ButtonAction.fromId(buttonId)?.let { action ->
            ctx.getString(action.defaultLabelRes)
        } ?: when (buttonId) {
            "floating_toggle" -> ctx.getString(R.string.floating_keyboard)
            else -> buttonId
        }
    }

    fun getButton(buttonId: String): ToolButton? = buttonMap[buttonId]

    fun clearTransientPressState() {
        buttonMap.values.forEach { it.clearTransientPressState() }
    }

    fun setFloatingState(isFloating: Boolean) {
        buttonActiveMap["floating_toggle"] = isFloating
        buttonMap["floating_toggle"]?.setActive(isFloating)
    }

    fun setOneHandKeyboardState(isOneHanded: Boolean) {
        buttonActiveMap["one_handed_keyboard"] = isOneHanded
        buttonMap["one_handed_keyboard"]?.setActive(isOneHanded)
    }

    fun refreshLayout() {
        val recyclerView = root
        recyclerView.layoutManager?.requestLayout()
        recyclerView.adapter?.notifyDataSetChanged()
        recyclerView.requestLayout()
    }

    /**
     * Update all buttons' active state based on their ButtonAction.isActive() method.
     */
    fun updateButtonsState(service: FcitxInputMethodService) {
        ButtonAction.allConfigurableActions.forEach { action ->
            val active = action.isActive(service)
            buttonActiveMap[action.id] = active
            buttonMap[action.id]?.setActive(active)
        }
    }

    private inner class ButtonsBarAdapter : RecyclerView.Adapter<ButtonsBarAdapter.ButtonViewHolder>() {

        inner class ButtonViewHolder(val button: ToolButton) : RecyclerView.ViewHolder(button)

        override fun getItemCount(): Int = buttons.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonViewHolder {
            val config = buttons[viewType]
            val iconRes = getIconResForButton(config.id, config.icon)
            val button = ToolButton(ctx, iconRes, theme).apply {
                contentDescription = config.label ?: getDefaultLabel(config.id)
                tag = config.id
                // Ensure button always fills KawaiiBar height
                minimumHeight = ctx.dp(KawaiiBarComponent.HEIGHT)
                layoutParams = FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    // Add horizontal margin for spacing between buttons
                    marginStart = ctx.dp(2)
                    marginEnd = ctx.dp(2)
                }

                // Apply click listeners
                clickListeners[config.id]?.let { setOnClickListener(it) }
                longClickListeners[config.id]?.let { setOnLongClickListener(it) }
            }
            applyIconAndText(button, config)
            buttonMap[config.id] = button
            return ButtonViewHolder(button)
        }

        override fun onBindViewHolder(holder: ButtonViewHolder, position: Int) {
            val recyclerView = root
            val kawaiiBarLayout = recyclerView.layoutManager as KawaiiBarLayout
            val parentWidth = recyclerView.width
            val childCount = itemCount
            val button = holder.button
            val config = buttons[position]
            buttonMap[config.id] = button
            applyIconAndText(button, config)

            val params = holder.button.layoutParams as FlexboxLayoutManager.LayoutParams

            // Calculate ideal width for even distribution
            if (parentWidth > 0 && childCount > 0) {
                val idealWidth = kawaiiBarLayout.calculateEvenDistributedWidth(childCount, parentWidth)

                // Switch to scroll mode if ideal width is less than minimum
                if (idealWidth < kawaiiBarLayout.minButtonWidth) {
                    if (kawaiiBarLayout.isEvenDistributionMode) {
                        kawaiiBarLayout.setScrollMode()
                        recyclerView.isHorizontalScrollBarEnabled = true
                        // Request relayout on next frame
                        if (position == 0) {
                            recyclerView.post {
                                notifyDataSetChanged()
                            }
                            return
                        }
                    }
                    // Scroll mode: WRAP_CONTENT with minimum width ensures buttons don't shrink
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    params.minWidth = kawaiiBarLayout.minButtonWidth
                    button.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                } else {
                    // Switch to even distribution mode if not already
                    if (!kawaiiBarLayout.isEvenDistributionMode) {
                        kawaiiBarLayout.setEvenDistributionMode()
                        recyclerView.isHorizontalScrollBarEnabled = false
                        if (position == 0) {
                            recyclerView.post {
                                notifyDataSetChanged()
                            }
                            return
                        }
                    }
                    // Even distribution mode: Set fixed width for each button
                    params.width = max(idealWidth, kawaiiBarLayout.minButtonWidth)
                    params.minWidth = 0
                    button.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            } else {
                // Fallback to scroll mode
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                params.minWidth = kawaiiBarLayout.minButtonWidth
            }
            button.setActive(buttonActiveMap[config.id] == true)
        }

        override fun getItemViewType(position: Int): Int {
            // Return position as view type since we recreate adapter on config changes
            return position
        }
    }
}
