package com.currensee.app.data

import androidx.room.*

@Database(entities = [Riwayat::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun riwayatDao(): RiwayatDao
}