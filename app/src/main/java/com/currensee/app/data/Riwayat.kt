package com.currensee.app.data

import androidx.room.*

@Entity(tableName = "riwayat")
data class Riwayat(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nominal: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)