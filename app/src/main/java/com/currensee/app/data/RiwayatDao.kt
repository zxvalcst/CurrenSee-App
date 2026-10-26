package com.currensee.app.data

import androidx.room.*
@Dao
interface RiwayatDao {
    @Insert
    suspend fun insert(r: Riwayat)

    @Query("SELECT * FROM riwayat ORDER BY timestamp DESC")
    suspend fun getAll(): List<Riwayat>
}