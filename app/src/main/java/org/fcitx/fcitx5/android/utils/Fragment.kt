/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.annotation.SuppressLint
import androidx.fragment.app.Fragment
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavType
import androidx.navigation.fragment.findNavController
import androidx.navigation.toRoute
import kotlin.reflect.KType

fun <T : Any> Fragment.navigateWithAnim(route: T) {
    findNavController().navigateWithAnim(route)
}

/**
 * Decode the typed route from this Fragment's own arguments.
 *
 * FragmentManager may restore a Fragment before NavController has restored the matching back stack
 * entry, or after that entry has already been popped. FragmentNavigator copies route arguments to
 * the Fragment, so reading them directly avoids depending on transient back stack state.
 */
@SuppressLint("VisibleForTests")
@Suppress("DEPRECATION")
inline fun <reified T : Any> Fragment.lazyRoute(
    typeMap: Map<KType, NavType<*>> = emptyMap()
) = lazy {
    val arguments = requireArguments()
    val initialState = arguments.keySet().associateWith { key -> arguments[key] }
    SavedStateHandle(initialState).toRoute<T>(typeMap)
}
