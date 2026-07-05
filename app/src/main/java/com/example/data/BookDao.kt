package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY timestamp DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<BookEntity>)

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateBookStatus(id: Int, status: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: Int)

    @Query("SELECT * FROM activities ORDER BY timestamp DESC")
    fun getAllActivities(): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(activity: ActivityEntity)

    @Query("UPDATE activities SET progress = :progress WHERE id = :id")
    suspend fun updateActivityProgress(id: Int, progress: Int)

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteActivityById(id: Int)
}
