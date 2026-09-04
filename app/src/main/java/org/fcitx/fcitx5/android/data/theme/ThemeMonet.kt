/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import android.os.Build
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.appContext

// Ref:
// https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md
// https://www.figma.com/community/file/809865700885504168/material-3-android-15
// https://material-foundation.github.io/material-theme-builder/

// FIXME: SDK < 34 can only have approximate color values, maybe we can implement our own color algorithm.
// See: https://github.com/XayahSuSuSu/Android-DataBackup/blob/e8b087fb55519c659bebdc46c0217731fe80a0d7/source/core/ui/src/main/kotlin/com/xayah/core/ui/material3/DynamicTonalPalette.kt#L185

object ThemeMonet {
    fun supportsCustomMappingEditor(context: Context = appContext): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return SystemColorResourceId.getAvailableForSdk(Build.VERSION.SDK_INT).any { resourceId ->
            context.resources.getIdentifier(resourceId.resourceId, "color", "android") != 0
        }
    }

    fun getLight(): Theme.Monet =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) // Real Monet colors
            Theme.Monet(
                isDark = false,
                surfaceContainer = appContext.getColor(android.R.color.system_surface_container_light),
                surfaceContainerHigh = appContext.getColor(android.R.color.system_surface_container_highest_light),
                surfaceBright = appContext.getColor(android.R.color.system_surface_bright_light),
                onSurface = appContext.getColor(android.R.color.system_on_surface_light),
                primary = appContext.getColor(android.R.color.system_primary_light),
                onPrimary = appContext.getColor(android.R.color.system_on_primary_light),
                secondaryContainer = appContext.getColor(android.R.color.system_secondary_container_light),
                onSurfaceVariant = appContext.getColor(android.R.color.system_on_surface_variant_light)
            )
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) // Approximate color values
            Theme.Monet(
                isDark = false,
                surfaceContainer = appContext.getColor(android.R.color.system_neutral1_50), // neutral94
                surfaceContainerHigh = appContext.getColor(android.R.color.system_neutral2_100), // neutral92
                surfaceBright = appContext.getColor(android.R.color.system_neutral1_10), // neutral98
                onSurface = appContext.getColor(android.R.color.system_neutral1_900),
                primary = appContext.getColor(android.R.color.system_accent1_600),
                onPrimary = appContext.getColor(android.R.color.system_accent1_0),
                secondaryContainer = appContext.getColor(android.R.color.system_accent2_100),
                onSurfaceVariant = appContext.getColor(android.R.color.system_accent2_700)
            )
        else // Static MD3 colors, based on #769CDF
            Theme.Monet(
                isDark = false,
                surfaceContainer = 0xffededf4.toInt(),
                surfaceContainerHigh = 0xffe7e8ee.toInt(),
                surfaceBright = 0xfff9f9ff.toInt(),
                onSurface = 0xff191c20.toInt(),
                primary = 0xff415f91.toInt(),
                onPrimary = 0xffffffff.toInt(),
                secondaryContainer = 0xffdae2f9.toInt(),
                onSurfaceVariant = 0xff44474e.toInt(),
            )

    fun getDark(): Theme.Monet =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) // Real Monet colors
            Theme.Monet(
                isDark = true,
                surfaceContainer = appContext.getColor(android.R.color.system_surface_container_dark),
                surfaceContainerHigh = appContext.getColor(android.R.color.system_surface_container_high_dark),
                surfaceBright = appContext.getColor(android.R.color.system_surface_bright_dark),
                onSurface = appContext.getColor(android.R.color.system_on_surface_dark),
                primary = appContext.getColor(android.R.color.system_primary_dark),
                onPrimary = appContext.getColor(android.R.color.system_on_primary_dark),
                secondaryContainer = appContext.getColor(android.R.color.system_secondary_container_dark),
                onSurfaceVariant = appContext.getColor(android.R.color.system_on_surface_variant_dark)
            )
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) // Approximate color values
            Theme.Monet(
                isDark = true,
                surfaceContainer = appContext.getColor(android.R.color.system_neutral1_900), // neutral12
                surfaceContainerHigh = appContext.getColor(android.R.color.system_neutral2_1000), // neutral17
                surfaceBright = appContext.getColor(android.R.color.system_neutral1_800), // neutral24
                onSurface = appContext.getColor(android.R.color.system_neutral1_100),
                primary = appContext.getColor(android.R.color.system_accent1_200),
                onPrimary = appContext.getColor(android.R.color.system_accent1_800),
                secondaryContainer = appContext.getColor(android.R.color.system_accent2_700),
                onSurfaceVariant = appContext.getColor(android.R.color.system_accent2_200)
            )
        else // Static MD3 colors, based on #769CDF
            Theme.Monet(
                isDark = true,
                surfaceContainer = 0xff1d2024.toInt(),
                surfaceContainerHigh = 0xff282a2f.toInt(),
                surfaceBright = 0xff37393e.toInt(),
                onSurface = 0xffe2e2e9.toInt(),
                primary = 0xffaac7ff.toInt(),
                onPrimary = 0xff0a305f.toInt(),
                secondaryContainer = 0xff3e4759.toInt(),
                onSurfaceVariant = 0xffc4c6d0.toInt(),
            )
    
    /**
     * 根据自定义映射创建 Monet 主题
     * @param isDark 是否为深色模式
     * @param mapping 颜色映射配置
     * @param context Android 上下文用于获取颜色资源
     */
    @Suppress("DEPRECATION")
    fun createFromMapping(
        isDark: Boolean,
        mapping: MonetThemeMapping,
        waterRippleResource: SystemColorResourceId? = null,
        context: android.content.Context = appContext
    ): Theme.Monet {
        fun getColor(resourceId: SystemColorResourceId): Int {
            return try {
                val colorResId = context.resources.getIdentifier(resourceId.resourceId, "color", "android")
                if (colorResId != 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.getColor(colorResId)
                    } else {
                        @Suppress("DEPRECATION")
                        context.resources.getColor(colorResId)
                    }
                } else {
                    // Fallback to default mapping
                    val defaultMapping = MonetThemeMapping.createDefault(isDark)
                    val defaultResource = when (resourceId) {
                        mapping.backgroundColor -> defaultMapping.backgroundColor
                        mapping.barColor -> defaultMapping.barColor
                        mapping.keyboardColor -> defaultMapping.keyboardColor
                        mapping.keyBackgroundColor -> defaultMapping.keyBackgroundColor
                        mapping.keyTextColor -> defaultMapping.keyTextColor
                        mapping.candidateTextColor -> defaultMapping.candidateTextColor
                        mapping.candidateLabelColor -> defaultMapping.candidateLabelColor
                        mapping.candidateCommentColor -> defaultMapping.candidateCommentColor
                        mapping.altKeyBackgroundColor -> defaultMapping.altKeyBackgroundColor
                        mapping.altKeyTextColor -> defaultMapping.altKeyTextColor
                        mapping.accentKeyBackgroundColor -> defaultMapping.accentKeyBackgroundColor
                        mapping.accentKeyTextColor -> defaultMapping.accentKeyTextColor
                        mapping.keyPressHighlightColor -> defaultMapping.keyPressHighlightColor
                        mapping.keyShadowColor -> defaultMapping.keyShadowColor
                        mapping.popupBackgroundColor -> defaultMapping.popupBackgroundColor
                        mapping.popupTextColor -> defaultMapping.popupTextColor
                        mapping.spaceBarColor -> defaultMapping.spaceBarColor
                        mapping.dividerColor -> defaultMapping.dividerColor
                        mapping.clipboardEntryColor -> defaultMapping.clipboardEntryColor
                        mapping.genericActiveBackgroundColor -> defaultMapping.genericActiveBackgroundColor
                        mapping.genericActiveForegroundColor -> defaultMapping.genericActiveForegroundColor
                        else -> defaultMapping.backgroundColor
                    }
                    val fallbackResId = context.resources.getIdentifier(defaultResource.resourceId, "color", "android")
                    if (fallbackResId != 0) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.getColor(fallbackResId)
                        } else {
                            @Suppress("DEPRECATION")
                            context.resources.getColor(fallbackResId)
                        }
                    } else {
                        // If the system resource is not available on this device (older OEM ROMs),
                        // fall back to the static Monet palette instead of a uniform gray.
                        android.util.Log.d("ThemeMonet", "system color missing: ${defaultResource.resourceId}, using static Monet fallback (isDark=$isDark)")
                        val staticTheme = if (isDark) getDark() else getLight()
                        when (defaultResource) {
                            defaultMapping.backgroundColor -> staticTheme.backgroundColor
                            defaultMapping.barColor -> staticTheme.barColor
                            defaultMapping.keyboardColor -> staticTheme.keyboardColor
                            defaultMapping.keyBackgroundColor -> staticTheme.keyBackgroundColor
                            defaultMapping.keyTextColor -> staticTheme.keyTextColor
                            defaultMapping.candidateTextColor -> staticTheme.candidateTextColor
                            defaultMapping.candidateLabelColor -> staticTheme.candidateLabelColor
                            defaultMapping.candidateCommentColor -> staticTheme.candidateCommentColor
                            defaultMapping.altKeyBackgroundColor -> staticTheme.altKeyBackgroundColor
                            defaultMapping.altKeyTextColor -> staticTheme.altKeyTextColor
                            defaultMapping.accentKeyBackgroundColor -> staticTheme.accentKeyBackgroundColor
                            defaultMapping.accentKeyTextColor -> staticTheme.accentKeyTextColor
                            defaultMapping.keyPressHighlightColor -> staticTheme.keyPressHighlightColor
                            defaultMapping.keyShadowColor -> staticTheme.keyShadowColor
                            defaultMapping.popupBackgroundColor -> staticTheme.popupBackgroundColor
                            defaultMapping.popupTextColor -> staticTheme.popupTextColor
                            defaultMapping.spaceBarColor -> staticTheme.spaceBarColor
                            defaultMapping.dividerColor -> staticTheme.dividerColor
                            defaultMapping.clipboardEntryColor -> staticTheme.clipboardEntryColor
                            defaultMapping.genericActiveBackgroundColor -> staticTheme.genericActiveBackgroundColor
                            defaultMapping.genericActiveForegroundColor -> staticTheme.genericActiveForegroundColor
                            else -> staticTheme.backgroundColor
                        }
                    }
                }
            } catch (e: Exception) {
                android.graphics.Color.GRAY
            }
        }
        
        return Theme.Monet(
            name = "Monet" + if (isDark) "Dark" else "Light",
            isDark = isDark,
            backgroundColor = getColor(mapping.backgroundColor),
            barColor = getColor(mapping.barColor),
            keyboardColor = getColor(mapping.keyboardColor),
            keyBackgroundColor = getColor(mapping.keyBackgroundColor),
            keyTextColor = getColor(mapping.keyTextColor),
            candidateTextColor = getColor(mapping.candidateTextColor),
            candidateLabelColor = getColor(mapping.candidateLabelColor),
            candidateCommentColor = getColor(mapping.candidateCommentColor),
            altKeyBackgroundColor = getColor(mapping.altKeyBackgroundColor),
            altKeyTextColor = getColor(mapping.altKeyTextColor),
            accentKeyBackgroundColor = getColor(mapping.accentKeyBackgroundColor),
            accentKeyTextColor = getColor(mapping.accentKeyTextColor),
            keyPressHighlightColor = getColor(mapping.keyPressHighlightColor)
                .alpha(if (isDark) 0.2f else 0.12f),
            keyShadowColor = getColor(mapping.keyShadowColor),
            popupBackgroundColor = getColor(mapping.popupBackgroundColor),
            popupTextColor = getColor(mapping.popupTextColor),
            spaceBarColor = getColor(mapping.spaceBarColor),
            dividerColor = getColor(mapping.dividerColor),
            clipboardEntryColor = getColor(mapping.clipboardEntryColor),
            genericActiveBackgroundColor = getColor(mapping.genericActiveBackgroundColor),
            genericActiveForegroundColor = getColor(mapping.genericActiveForegroundColor),
            waterRippleColor = waterRippleResource?.let { getColor(it) }
        )
    }
}
