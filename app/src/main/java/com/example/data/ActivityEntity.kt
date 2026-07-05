package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val subtitle: String,
    val progress: Int, // 0 to 100
    val type: String,  // "SCAN", "NEW_MATCH", "IMPORT"
    val timestamp: Long = System.currentTimeMillis()
)
