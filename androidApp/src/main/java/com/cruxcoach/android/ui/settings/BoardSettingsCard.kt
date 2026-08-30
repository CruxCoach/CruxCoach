package com.cruxcoach.android.ui.settings

import com.cruxcoach.domain.board.BoardBrand

data class BoardSettingsCard(val brand: BoardBrand, val isActive: Boolean)

fun boardSettingsCards(activeBrand: BoardBrand): List<BoardSettingsCard> =
    BoardBrand.entries.filter { it.isInteractive }.map { BoardSettingsCard(it, it == activeBrand) }
