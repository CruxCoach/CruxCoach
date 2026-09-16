package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand

/** Unknown future values must not silently opt this installation into extra downloads. */
internal fun decodeBoardDownloadBrands(stored: Set<String>?): Set<BoardBrand> =
    stored?.mapNotNull { BoardBrand.fromWireOrNull(it)?.takeIf { brand -> brand.isInteractive } }
        ?.toSet() ?: BoardBrand.entries.filter { it.isInteractive }.toSet()
