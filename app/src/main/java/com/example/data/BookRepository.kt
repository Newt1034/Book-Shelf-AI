package com.example.data

import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {
    val allBooks: Flow<List<BookEntity>> = bookDao.getAllBooks()
    val allActivities: Flow<List<ActivityEntity>> = bookDao.getAllActivities()

    suspend fun insertBook(book: BookEntity) {
        bookDao.insertBook(book)
    }

    suspend fun insertBooks(books: List<BookEntity>) {
        bookDao.insertBooks(books)
    }

    suspend fun updateBookStatus(id: Int, status: String) {
        bookDao.updateBookStatus(id, status)
    }

    suspend fun deleteBookById(id: Int) {
        bookDao.deleteBookById(id)
    }

    suspend fun insertActivity(activity: ActivityEntity) {
        bookDao.insertActivity(activity)
    }

    suspend fun updateActivityProgress(id: Int, progress: Int) {
        bookDao.updateActivityProgress(id, progress)
    }

    suspend fun deleteActivityById(id: Int) {
        bookDao.deleteActivityById(id)
    }
}
