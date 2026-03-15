package com.company.upload.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.company.upload.storage.db.UploadDatabase

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(UploadDatabase.Schema, "azurevault_upload.db")
    }
}
