/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.audioManager
import org.fcitx.fcitx5.android.utils.getSystemSettings
import org.fcitx.fcitx5.android.utils.vibrator

object InputFeedbacks {

    enum class InputFeedbackMode(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowingSystem(R.string.following_system_settings),
        Enabled(R.string.enabled),
        Disabled(R.string.disabled);
    }

    private var systemSoundEffects = false
    private var systemHapticFeedback = false

    fun syncSystemPrefs() {
        systemSoundEffects = getSystemSettings<Int>(Settings.System.SOUND_EFFECTS_ENABLED) == 1
        // it says "Replaced by using android.os.VibrationAttributes.USAGE_TOUCH"
        // but gives no clue about how to use it, and this one still works
        @Suppress("DEPRECATION")
        systemHapticFeedback = getSystemSettings<Int>(Settings.System.HAPTIC_FEEDBACK_ENABLED) == 1
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val soundOnKeyPress by keyboardPrefs.soundOnKeyPress
    private val soundOnKeyPressVolume by keyboardPrefs.soundOnKeyPressVolume
    private val hapticOnKeyPress by keyboardPrefs.hapticOnKeyPress
    private val hapticOnKeyUp by keyboardPrefs.hapticOnKeyUp
    private val buttonPressVibrationMilliseconds by keyboardPrefs.buttonPressVibrationMilliseconds
    private val buttonLongPressVibrationMilliseconds by keyboardPrefs.buttonLongPressVibrationMilliseconds
    private val buttonPressVibrationAmplitude by keyboardPrefs.buttonPressVibrationAmplitude
    private val buttonLongPressVibrationAmplitude by keyboardPrefs.buttonLongPressVibrationAmplitude

    private val vibrator = appContext.vibrator
    private var cachedPressDuration: Long = -1L
    private var cachedPressAmplitude: Int = Int.MIN_VALUE
    private var cachedPressEffect: VibrationEffect? = null
    private var cachedLongPressDuration: Long = -1L
    private var cachedLongPressAmplitude: Int = Int.MIN_VALUE
    private var cachedLongPressEffect: VibrationEffect? = null

    private val hasAmplitudeControl =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) && vibrator.hasAmplitudeControl()

    private fun vibrationEffect(duration: Long, amplitude: Int, longPress: Boolean): VibrationEffect {
        if (longPress) {
            if (
                cachedLongPressEffect == null ||
                cachedLongPressDuration != duration ||
                cachedLongPressAmplitude != amplitude
            ) {
                cachedLongPressEffect = VibrationEffect.createOneShot(duration, amplitude)
                cachedLongPressDuration = duration
                cachedLongPressAmplitude = amplitude
            }
            return cachedLongPressEffect!!
        }
        if (
            cachedPressEffect == null ||
            cachedPressDuration != duration ||
            cachedPressAmplitude != amplitude
        ) {
            cachedPressEffect = VibrationEffect.createOneShot(duration, amplitude)
            cachedPressDuration = duration
            cachedPressAmplitude = amplitude
        }
        return cachedPressEffect!!
    }

    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false) {
        when (hapticOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemHapticFeedback) return
        }
        if (keyUp && !hapticOnKeyUp) return
        val duration: Long
        val amplitude: Int
        val hfc: Int
        if (longPress) {
            duration = buttonLongPressVibrationMilliseconds.toLong()
            amplitude = buttonLongPressVibrationAmplitude
            hfc = HapticFeedbackConstants.LONG_PRESS
        } else {
            duration = buttonPressVibrationMilliseconds.toLong()
            amplitude = buttonPressVibrationAmplitude
            hfc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && keyUp) {
                HapticFeedbackConstants.KEYBOARD_RELEASE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }

        // there is `VibrationEffect.DEFAULT_AMPLITUDE` but no default duration;
        // also `VibrationEffect.createOneShot()` only accepts positive duration.
        // so changing amplitude without changing duration makes no sense
        if (duration != 0L) {
            // on Android 13, if system haptic feedback was disabled, `vibrator.vibrate()` won't work
            // but `view.performHapticFeedback()` with `FLAG_IGNORE_GLOBAL_SETTING` still works
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(vibrationEffect(duration, amplitude, longPress))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ve = vibrationEffect(duration, VibrationEffect.DEFAULT_AMPLITUDE, longPress)
                vibrator.vibrate(ve)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (hapticOnKeyPress == InputFeedbackMode.Enabled) {
                // it says "Starting TIRAMISU only privileged apps can ignore user settings for touch feedback"
                // but we still seem to be able to use `FLAG_IGNORE_GLOBAL_SETTING`
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            view.performHapticFeedback(hfc, flags)
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    private val audioManager = appContext.audioManager
    private val customKeySound by keyboardPrefs.customKeySound
    @Volatile
    private var customSoundPool: SoundPool? = null
    @Volatile
    private var customSoundId = 0
    @Volatile
    private var customSoundReady = false
    @Volatile
    private var loadedCustomKeySound = ""

    init {
        keyboardPrefs.customKeySound.registerOnChangeListener { _, _ -> reloadCustomSound() }
        reloadCustomSound()
    }

    private fun reloadCustomSound() {
        customSoundPool?.release()
        customSoundPool = null
        customSoundId = 0
        customSoundReady = false
        loadedCustomKeySound = customKeySound
        val file = UserConfigFiles.keySoundFile(customKeySound) ?: return
        if (!file.isFile) return

        val pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        customSoundPool = pool
        pool.setOnLoadCompleteListener { loadedPool, sampleId, status ->
            if (loadedPool === customSoundPool && sampleId == customSoundId) {
                customSoundReady = status == 0
                if (!customSoundReady) {
                    customSoundPool = null
                    customSoundId = 0
                    loadedPool.release()
                }
            }
        }
        val soundId = runCatching { pool.load(file.path, 1) }.getOrElse {
            pool.release()
            customSoundPool = null
            0
        }
        customSoundId = soundId
    }

    private fun playCustomSound(volume: Int): Boolean {
        if (customKeySound.isBlank()) return false
        if (loadedCustomKeySound != customKeySound) reloadCustomSound()
        val pool = customSoundPool ?: return false
        if (customSoundId == 0 || !customSoundReady) return false
        val gain = if (volume == 0) 1f else volume / 100f
        return pool.play(customSoundId, gain, gain, 1, 0, 1f) != 0
    }

    fun soundEffect(effect: SoundEffect) {
        when (soundOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemSoundEffects) return
        }
        val volume = soundOnKeyPressVolume
        if (playCustomSound(volume)) return
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        if (volume == 0) {
            audioManager.playSoundEffect(fx, -1f)
        } else {
            audioManager.playSoundEffect(fx, volume / 100f)
        }
    }

}
