/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fcitx.fcitx5.android.ui.main.settings.theme.MonetThemePrefs
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.isDarkMode
import org.fcitx.fcitx5.android.utils.userManager

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    fun interface OnThemeListChangeListener {
        fun onThemeListChange(themes: List<Theme>)
    }

    val BuiltinThemes = listOf(
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.DeepBlue,
        ThemePreset.Monokai,
        ThemePreset.AMOLEDBlack,
    )

    val DefaultTheme = ThemePreset.PixelDark

    private var monetThemes = defaultMonetThemes()

    private fun defaultMonetThemes(): List<Theme.Monet> {
        return listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
    }

    private fun loadMonetThemes(): List<Theme.Monet> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !appContext.userManager.isUserUnlocked) {
            return defaultMonetThemes()
        }
        // 检查是否存在自定义映射配置
        val lightMapping = MonetThemePrefs.getMapping("MonetLight")
        val darkMapping = MonetThemePrefs.getMapping("MonetDark")
        val lightWaterRippleResource = MonetThemePrefs.getWaterRippleResource("MonetLight")
        val darkWaterRippleResource = MonetThemePrefs.getWaterRippleResource("MonetDark")
        
        val lightTheme = if (lightMapping != null) {
            ThemeMonet.createFromMapping(
                isDark = false,
                mapping = lightMapping,
                waterRippleResource = lightWaterRippleResource
            )
        } else {
            ThemeMonet.getLight()
        }
        
        val darkTheme = if (darkMapping != null) {
            ThemeMonet.createFromMapping(
                isDark = true,
                mapping = darkMapping,
                waterRippleResource = darkWaterRippleResource
            )
        } else {
            ThemeMonet.getDark()
        }
        
        return listOf(lightTheme, darkTheme)
    }

    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes()

    fun getTheme(name: String) =
        customThemes.find { it.name == name }
            ?: monetThemes.find { it.name == name }
            ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + monetThemes + BuiltinThemes

    fun refreshThemes() {
        refreshThemes(ThemeFilesManager.listThemes())
    }

    fun refreshThemes(refreshedCustomThemes: List<Theme.Custom>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshThemes(refreshedCustomThemes) }
            return
        }
        applyRefreshedThemes(refreshedCustomThemes)
    }

    @MainThread
    private fun applyRefreshedThemes(refreshedCustomThemes: List<Theme.Custom>) {
        customThemes.clear()
        customThemes.addAll(refreshedCustomThemes)
        monetThemes = loadMonetThemes()
        activeTheme = evaluateActiveTheme()
        fireThemeListChange()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()
    private val onThemeListChangeListeners = WeakHashSet<OnThemeListChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    fun addOnThemeListChangedListener(listener: OnThemeListChangeListener) {
        onThemeListChangeListeners.add(listener)
    }

    fun removeOnThemeListChangedListener(listener: OnThemeListChangeListener) {
        onThemeListChangeListeners.remove(listener)
    }

    private fun fireChange() {
        val theme = _activeTheme
        dispatchOnMain {
            onChangeListeners.toList().forEach { it.onThemeChange(theme) }
        }
    }

    private fun fireThemeListChange() {
        val themes = getAllThemes().toList()
        dispatchOnMain {
            onThemeListChangeListeners.toList().forEach { it.onThemeListChange(themes) }
        }
    }

    private inline fun dispatchOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        ThemeFilesManager.saveThemeFiles(theme)
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        if (activeTheme.name == theme.name) {
            activeTheme = theme
        }
        fireThemeListChange()
    }

    fun nonActiveImportName(name: String): String {
        if (activeTheme.name != name || getTheme(name) !is Theme.Custom) return name
        val base = "$name (imported)"
        if (getTheme(base) == null) return base
        var index = 2
        while (true) {
            val candidate = "$base $index"
            if (getTheme(candidate) == null) return candidate
            index++
        }
    }

    fun deleteTheme(name: String) {
        customThemes.find { it.name == name }?.also {
            // Pass all themes except the one being deleted, so we can clean up unused directories
            val otherThemes = customThemes.filter { it.name != name }
            ThemeFilesManager.deleteThemeFiles(it, otherThemes)
            customThemes.remove(it)
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
        fireThemeListChange()
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        _activeTheme = theme
        prefs.normalModeTheme.setValue(theme)
    }

    fun isUsingConfiguredDarkTheme(): Boolean =
        activeTheme.name == prefs.darkModeTheme.getValue().name

    /**
     * Switch to the opposite configured day/night theme and disable system following so the
     * selection is immediately visible and remains active after the IME is recreated.
     */
    fun toggleConfiguredDayNightTheme(): Theme {
        val nextTheme = if (isUsingConfiguredDarkTheme()) {
            prefs.lightModeTheme.getValue()
        } else {
            prefs.darkModeTheme.getValue()
        }
        if (prefs.followSystemDayNightTheme.getValue()) {
            prefs.followSystemDayNightTheme.setValue(false)
        }
        setNormalModeTheme(nextTheme)
        return nextTheme
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }.getValue()
    }

    @Keep
    private val onThemePrefsChange = ManagedPreferenceProvider.OnChangeListener { key ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            activeTheme = evaluateActiveTheme()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        monetThemes = loadMonetThemes()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.registerOnChangeListener(onThemePrefsChange)
        _activeTheme = evaluateActiveTheme()
    }

    fun onSystemPlatteChange(newConfig: Configuration) {
        isDarkMode = newConfig.isDarkMode()
        // 重新加载 Monet 主题（包括自定义映射）
        monetThemes = loadMonetThemes()
        // `ManagedThemePreference` finds a theme with same name in `getAllThemes()`
        // thus `evaluateActiveTheme()` should be called after updating `monetThemes`
        activeTheme = evaluateActiveTheme()
        fireThemeListChange()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }

}
