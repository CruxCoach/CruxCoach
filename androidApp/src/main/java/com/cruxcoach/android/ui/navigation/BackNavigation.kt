package com.cruxcoach.android.ui.navigation

import androidx.navigation.NavController

/**
 * Back from a screen's own arrow, or once the screen has done its job.
 *
 * Never closes the first screen, which the plain [NavController.popBackStack] does: the leaving screen stays on top
 * and clickable while it fades out, every further tap on its arrow popped one more entry, in the end the first one
 * too, and NavHost draws nothing for an empty back stack. That was a black screen until the app was closed. System
 * Back never got there, it only works above the first screen.
 *
 * @return whether a screen was closed.
 */
internal fun NavController.goBack(): Boolean = previousBackStackEntry != null && popBackStack()
