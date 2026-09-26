package com.cruxcoach.android.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.Climb_lists

/**
 * A list's name as shown. The two built-in lists are stored under fixed German
 * names ("Favoriten", "Ignoriert"), which the English app showed verbatim.
 */
@Composable
internal fun builtinListName(name: String, isBuiltin: Boolean, isIgnored: Boolean): String = when {
    isIgnored -> stringResource(R.string.board_list_builtin_ignored)
    isBuiltin -> stringResource(R.string.board_list_builtin_favorites)
    else -> name
}

@Composable
internal fun Climb_lists.displayName(): String = builtinListName(name, isBuiltin, isIgnored)
