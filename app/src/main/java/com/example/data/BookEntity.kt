package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val author: String,
    val coverUrl: String,
    val matchPercent: Int,
    val reason: String,
    val status: String, // "RECOMMENDED", "WANT_TO_READ", "READING", "COMPLETED", "DETECTED"
    val timestamp: Long = System.currentTimeMillis()
)
