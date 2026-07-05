package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GoogleBookMetadata(
    val title: String,
    val author: String,
    val coverUrl: String,
    val description: String,
    val publisher: String,
    val pageCount: Int
)

object GoogleBooksClient {
    private const val TAG = "GoogleBooksClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchBookByIsbn(isbn: String): GoogleBookMetadata? = withContext(Dispatchers.IO) {
        val sanitizedIsbn = isbn.replace("-", "").replace(" ", "").trim()
        if (sanitizedIsbn.isEmpty()) return@withContext null
        val url = "https://www.googleapis.com/books/v1/volumes?q=isbn:$sanitizedIsbn"
        Log.d(TAG, "Querying Google Books API for ISBN: $sanitizedIsbn with URL: $url")
        try {
            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP call failed with code: ${response.code}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                val root = JSONObject(bodyStr)
                val totalItems = root.optInt("totalItems", 0)
                if (totalItems == 0) {
                    Log.w(TAG, "No books found for ISBN: $sanitizedIsbn")
                    return@withContext null
                }
                val items = root.optJSONArray("items") ?: return@withContext null
                val firstItem = items.optJSONObject(0) ?: return@withContext null
                val volumeInfo = firstItem.optJSONObject("volumeInfo") ?: return@withContext null

                val title = volumeInfo.optString("title", "Unknown Title")
                val authorsArray = volumeInfo.optJSONArray("authors")
                val authorsList = mutableListOf<String>()
                if (authorsArray != null) {
                    for (i in 0 until authorsArray.length()) {
                        authorsList.add(authorsArray.getString(i))
                    }
                }
                val author = if (authorsList.isEmpty()) "Unknown Author" else authorsList.joinToString(", ")
                val description = volumeInfo.optString("description", "No description available from Google Books.")
                val publisher = volumeInfo.optString("publisher", "Unknown Publisher")
                val pageCount = volumeInfo.optInt("pageCount", 0)

                val imageLinks = volumeInfo.optJSONObject("imageLinks")
                var coverUrl = ""
                if (imageLinks != null) {
                    coverUrl = imageLinks.optString("thumbnail", imageLinks.optString("smallThumbnail", ""))
                    if (coverUrl.startsWith("http://")) {
                        coverUrl = coverUrl.replace("http://", "https://")
                    }
                }

                return@withContext GoogleBookMetadata(
                    title = title,
                    author = author,
                    coverUrl = coverUrl,
                    description = description,
                    publisher = publisher,
                    pageCount = pageCount
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching book by ISBN", e)
            return@withContext null
        }
    }

    suspend fun searchBooksByQuery(query: String): List<GoogleBookMetadata> = withContext(Dispatchers.IO) {
        if (query.trim().isEmpty()) return@withContext emptyList()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/books/v1/volumes?q=$encodedQuery&maxResults=5"
            Log.d(TAG, "Searching Google Books with URL: $url")
            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val root = JSONObject(bodyStr)
                val items = root.optJSONArray("items") ?: return@withContext emptyList()
                val results = mutableListOf<GoogleBookMetadata>()
                for (idx in 0 until items.length()) {
                    val item = items.optJSONObject(idx) ?: continue
                    val volumeInfo = item.optJSONObject("volumeInfo") ?: continue
                    val title = volumeInfo.optString("title", "Unknown Title")
                    val authorsArray = volumeInfo.optJSONArray("authors")
                    val authorsList = mutableListOf<String>()
                    if (authorsArray != null) {
                        for (i in 0 until authorsArray.length()) {
                            authorsList.add(authorsArray.getString(i))
                        }
                    }
                    val author = if (authorsList.isEmpty()) "Unknown Author" else authorsList.joinToString(", ")
                    val description = volumeInfo.optString("description", "No description available from Google Books.")
                    val publisher = volumeInfo.optString("publisher", "Unknown Publisher")
                    val pageCount = volumeInfo.optInt("pageCount", 0)

                    val imageLinks = volumeInfo.optJSONObject("imageLinks")
                    var coverUrl = ""
                    if (imageLinks != null) {
                        coverUrl = imageLinks.optString("thumbnail", imageLinks.optString("smallThumbnail", ""))
                        if (coverUrl.startsWith("http://")) {
                            coverUrl = coverUrl.replace("http://", "https://")
                        }
                    }
                    results.add(
                        GoogleBookMetadata(
                            title = title,
                            author = author,
                            coverUrl = coverUrl,
                            description = description,
                            publisher = publisher,
                            pageCount = pageCount
                        )
                    )
                }
                return@withContext results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching books by query", e)
            return@withContext emptyList()
        }
    }
}
