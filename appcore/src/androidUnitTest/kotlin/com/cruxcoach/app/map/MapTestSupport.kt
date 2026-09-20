package com.cruxcoach.app.map

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.FramesBinaryCodec

/** A board-location row, with the same defaults the dataset uses. */
fun location(
    id: String,
    name: String = id,
    lat: Double = 52.5,
    lng: Double = 13.4,
    city: String? = "Berlin",
    country: String = "DE",
    layoutName: String? = null,
    layoutId: Int? = null,
    sizeLabel: String? = null,
    productSizeId: Int? = null,
    access: AccessType = AccessType.UNKNOWN,
    adjustability: Adjustability = Adjustability.UNKNOWN,
    fixedAngle: Int? = null,
    brand: BoardBrand = BoardBrand.KILTER,
    wellpass: Boolean? = null,
    hasLed: Boolean? = null,
    address: String? = null,
    url: String? = null,
    phone: String? = null,
    email: String? = null,
    instagram: String? = null,
    frameMaker: String? = null,
) = BoardLocation(
    id = id, name = name, lat = lat, lng = lng, address = address, city = city,
    countryCode = country, phone = phone, email = email, url = url, instagram = instagram,
    layoutName = layoutName, layoutId = layoutId, sizeLabel = sizeLabel,
    productSizeId = productSizeId, accessType = access, adjustability = adjustability,
    fixedAngle = fixedAngle, frameMaker = frameMaker, boardBrand = brand, wellpass = wellpass,
    hasLed = hasLed,
)

/** Real in-memory BoardDB with the `:shared` location repository on top. */
class MapTestDb {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }
    val database: BoardDatabase
    val repository: BoardLocationRepositoryImpl

    init {
        BoardDatabase.Schema.create(driver)
        database = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repository = BoardLocationRepositoryImpl(database)
    }

    fun insert(vararg locations: BoardLocation) {
        for (loc in locations) {
            database.kilterBoardLocationQueries.upsertLocation(
                gym_uuid = loc.id,
                name = loc.name,
                lat = loc.lat,
                lng = loc.lng,
                address = loc.address,
                city = loc.city,
                country_code = loc.countryCode,
                phone = loc.phone,
                email = loc.email,
                url = loc.url,
                instagram = loc.instagram,
                layout_name = loc.layoutName,
                layout_id = loc.layoutId?.toLong(),
                size_label = loc.sizeLabel,
                product_size_id = loc.productSizeId?.toLong(),
                access_type = loc.accessType.name,
                adjustability = loc.adjustability.name,
                fixed_angle = loc.fixedAngle?.toLong(),
                frame_maker = loc.frameMaker,
                board_brand = loc.boardBrand.wireValue,
                wellpass = loc.wellpass?.let { if (it) 1L else 0L },
            )
        }
    }

    fun close() = driver.close()
}
