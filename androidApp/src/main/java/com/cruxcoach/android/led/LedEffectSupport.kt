package com.cruxcoach.android.led

import com.cruxcoach.domain.board.BoardBrand

/**
 * Which boards show LED effects — the one place the settings, the climb menu and
 * the engine ask.
 *
 * Kilter only for now. The other Aurora boards speak the same packets and would
 * work through the same path, but none of their controllers has been watched
 * under animation-rate traffic yet; widening [boards] is the whole change once
 * one has. Shiny Wall (role codes, no colour per LED), MoonBoard (fixed role
 * colours over a slow UART) and Quantum (a controller shared between climbers)
 * would need effects of their own.
 */
object LedEffectSupport {

    val boards: Set<BoardBrand> = setOf(BoardBrand.KILTER)

    fun supports(brand: BoardBrand?): Boolean = brand != null && brand in boards
}
