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
