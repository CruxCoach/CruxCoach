package com.cruxcoach.app.logbook

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase

/** Real in-memory SecureDatabase with the production `:shared` repository. */
fun newPersonalRepo(): PersonalBoardRepositoryImpl {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    SecureDatabase.Schema.create(driver)
    return PersonalBoardRepositoryImpl(SecureDatabase(driver))
}

class MapKeyValueStore(initial: Map<String, String> = emptyMap()) : com.cruxcoach.app.platform.KeyValueStore {
    val map = java.util.concurrent.ConcurrentHashMap(initial)
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun keys(): Set<String> = map.keys.toSet()
}
