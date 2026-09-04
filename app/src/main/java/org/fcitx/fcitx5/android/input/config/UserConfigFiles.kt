/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

object UserConfigFiles {
    const val DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE = "default"
    private const val TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME = "TextKeyboardLayout.json"
    private const val TEXT_KEYBOARD_LAYOUT_PREFIX = "TextKeyboardLayout."
    private const val JSON_SUFFIX = ".json"
    private const val KEY_SOUND_DIR_NAME = "key_sounds"
    private val AUDIO_FILE_NAME = Regex("^[A-Za-z0-9._ -]+\\.(?i:wav|mp3|ogg|m4a|flac)$")
    private val TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME = Regex(
        "^TextKeyboardLayout(?:\\..+)?_backup_\\d{8}_\\d{6}(?:_.*)?\\.json$"
    )

    private fun externalFilesRoot(): File? = appContext.getExternalFilesDir(null)

    fun configDir(): File? = externalFilesRoot()?.let { File(it, "config") }

    fun fontsDir(): File? = externalFilesRoot()?.let { File(it, "fonts") }

    fun keySoundsDir(): File? = configDir()?.let { File(it, KEY_SOUND_DIR_NAME) }

    fun keySoundFile(name: String): File? {
        if (!AUDIO_FILE_NAME.matches(name)) return null
        return keySoundsDir()?.let { File(it, name) }
    }

    fun listKeySoundFiles(): List<String> = keySoundsDir()
        ?.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && AUDIO_FILE_NAME.matches(it.name) }
        ?.map { it.name }
        ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
        ?.toList()
        .orEmpty()

    fun textKeyboardLayoutJson(): File? = textKeyboardLayoutJson(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)

    fun textKeyboardLayoutJson(profile: String): File? {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: return null
        val fileName = if (normalized == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$TEXT_KEYBOARD_LAYOUT_PREFIX$normalized$JSON_SUFFIX"
        }
        return configDir()?.let { File(it, fileName) }
    }

    fun normalizeTextKeyboardLayoutProfile(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val sanitized = trimmed
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "")
            .trim()
            .trim('.')
        if (sanitized.isEmpty()) return null
        return if (sanitized.equals(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE, ignoreCase = true)) {
            DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        } else {
            sanitized
        }
    }

    fun listTextKeyboardLayoutProfiles(): List<String> {
        val dir = configDir() ?: return listOf(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        val fileNames = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toList()
            .orEmpty()

        val profiles = mutableSetOf<String>()
        if (fileNames.any { it == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME }) {
            profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        }
        fileNames.forEach { name ->
            if (TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME.matches(name)) return@forEach
            if (name == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME) return@forEach
            if (name.startsWith(TEXT_KEYBOARD_LAYOUT_PREFIX) && name.endsWith(JSON_SUFFIX)) {
                val rawProfile = name.removePrefix(TEXT_KEYBOARD_LAYOUT_PREFIX).removeSuffix(JSON_SUFFIX)
                val profile = normalizeTextKeyboardLayoutProfile(rawProfile)
                if (profile != null && profile != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
                    profiles += profile
                }
            }
        }

        profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return profiles.toList().sortedWith(compareBy({ it != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
    }

    fun textKeyboardLayoutFileName(profile: String): String {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return if (normalized == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$TEXT_KEYBOARD_LAYOUT_PREFIX$normalized$JSON_SUFFIX"
        }
    }

    fun popupPresetJson(): File? = configDir()?.let { File(it, "PopupPreset.json") }

    fun fontsetJson(): File? = fontsDir()?.let { File(it, "fontset.json") }
    
    fun kawaiiBarButtonsConfig(): File? = configDir()?.let { File(it, "KawaiiBarButtonsLayout.json") }

    fun statusAreaButtonsConfig(): File? = configDir()?.let { File(it, "StatusAreaButtonsLayout.json") }

    /**
     * Unified buttons layout configuration file.
     * Replaces separate KawaiiBarButtonsLayout.json and StatusAreaButtonsLayout.json files.
     */
    fun buttonsLayoutConfig(): File? = configDir()?.let { File(it, "ButtonsLayout.json") }
}
