package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand

/**
 * Unknown future values must not silently opt this installation into extra
 * downloads. Without a stored choice the installation keeps downloading the
 * families it always did; a family added later ([BoardBrand.downloadedByDefault]
 * false) is fetched only once the user picks it.
 */
internal fun decodeBoardDownloadBrands(stored: Set<String>?): Set<BoardBrand> =
    stored?.mapNotNull { BoardBrand.fromWireOrNull(it)?.takeIf { brand -> brand.isInteractive } }
        ?.toSet() ?: BoardBrand.entries.filter { it.downloadedByDefault }.toSet()
