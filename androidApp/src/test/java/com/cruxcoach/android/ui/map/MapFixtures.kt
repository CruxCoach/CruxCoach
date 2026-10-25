package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand

/** Compact board rows for map tests. */
internal fun mapBoard(
    id: String,
    lat: Double,
    lng: Double,
    name: String = "Gym $id",
    brand: BoardBrand = BoardBrand.KILTER,
    layoutId: Int? = if (brand == BoardBrand.KILTER) 1 else null,
    layoutName: String? = null,
    city: String? = "Munich",
    country: String = "DE",
    url: String? = null,
    phone: String? = null,
    address: String? = null,
    sizeLabel: String? = null,
    productSizeId: Int? = null,
    access: AccessType = AccessType.UNKNOWN,
    adjustability: Adjustability = Adjustability.UNKNOWN,
    fixedAngle: Int? = null,
    hasLed: Boolean? = null,
    wellpass: Boolean? = null,
) = BoardLocation(
    id = id, name = name, lat = lat, lng = lng,
    address = address, city = city, countryCode = country,
    phone = phone, email = null, url = url, instagram = null,
    layoutName = layoutName, layoutId = layoutId,
    sizeLabel = sizeLabel, productSizeId = productSizeId,
    accessType = access, adjustability = adjustability,
    fixedAngle = fixedAngle, frameMaker = null, boardBrand = brand,
    wellpass = wellpass, hasLed = hasLed,
)
