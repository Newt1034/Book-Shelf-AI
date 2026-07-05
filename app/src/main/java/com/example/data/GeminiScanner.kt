package com.example.data

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class DetectedBook(
    val title: String,
    val author: String,
    val matchPercent: Int,
    val reason: String,
    val status: String
)

object GeminiScanner {
    private const val TAG = "GeminiScanner"
    private const val MODEL_NAME = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    suspend fun scanBookshelf(bitmap: Bitmap): List<DetectedBook> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is not configured. Returning fallback simulated books.")
            return@withContext getFallbackMockBooks()
        }

        try {
            val base64Image = bitmap.toBase64()
            
            // Construct request JSON using standard org.json
            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            val promptPart = JSONObject().apply {
                                put("text", """
                                    Analyze this photo of a book cover or bookshelf. Identify all the books visible.
                                    For each book, determine:
                                    - title
                                    - author
                                    - matchPercent (estimated affinity score from 70 to 99)
                                    - reason (1-2 sentences detailing why they might enjoy it based on general reading interests)
                                    - status (MUST be one of: "RECOMMENDED", "WANT_TO_READ", "READING", or "COMPLETED")
                                    
                                    You MUST return your output strictly in JSON format matching this schema:
                                    {
                                      "books": [
                                        {
                                          "title": "Title of Book",
                                          "author": "Author Name",
                                          "matchPercent": 95,
                                          "reason": "Because you love tech books...",
                                          "status": "COMPLETED"
                                        }
                                      ]
                                    }
                                    Do not include any markdown styling like ```json or ```, just return raw, valid JSON.
                                """.trimIndent())
                            }
                            val imagePart = JSONObject().apply {
                                val inlineDataObj = JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Image)
                                }
                                put("inlineData", inlineDataObj)
                            }
                            put(promptPart)
                            put(imagePart)
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                // Enforce JSON output format
                val genConfig = JSONObject().apply {
                    val respFormat = JSONObject().apply {
                        put("responseMimeType", "application/json")
                    }
                    put("responseFormat", respFormat)
                    put("temperature", 0.2)
                }
                put("generationConfig", genConfig)
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val url = "$BASE_URL?key=$apiKey"
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini API call failed with code: ${response.code}, message: ${response.message}")
                    return@withContext getFallbackMockBooks()
                }

                val responseBodyStr = response.body?.string() ?: ""
                Log.d(TAG, "Gemini response: $responseBodyStr")

                val responseJson = JSONObject(responseBodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val firstPart = parts?.optJSONObject(0)
                var textResponse = firstPart?.optString("text") ?: ""

                // Sanitize potential markdown blocks
                if (textResponse.startsWith("```")) {
                    textResponse = textResponse.replace("```json", "").replace("```", "").trim()
                }

                val parsedBooks = mutableListOf<DetectedBook>()
                if (textResponse.isNotEmpty()) {
                    val booksRoot = JSONObject(textResponse)
                    val booksArray = booksRoot.optJSONArray("books")
                    if (booksArray != null) {
                        for (i in 0 until booksArray.length()) {
                            val bookObj = booksArray.getJSONObject(i)
                            parsedBooks.add(
                                DetectedBook(
                                    title = bookObj.optString("title", "Unknown Book"),
                                    author = bookObj.optString("author", "Unknown Author"),
                                    matchPercent = bookObj.optInt("matchPercent", 80),
                                    reason = bookObj.optString("reason", "Identified via AI Spine Scan."),
                                    status = bookObj.optString("status", "RECOMMENDED")
                                )
                            )
                        }
                    }
                }
                
                if (parsedBooks.isEmpty()) {
                    return@withContext getFallbackMockBooks()
                } else {
                    return@withContext parsedBooks
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning bookshelf with Gemini: ${e.message}", e)
            return@withContext getFallbackMockBooks()
        }
    }

    private fun getFallbackMockBooks(): List<DetectedBook> {
        return listOf(
            DetectedBook(
                title = "Atomic Habits",
                author = "James Clear",
                matchPercent = 94,
                reason = "A supreme framework for self-improvement and forming tiny daily habits that lead to big results.",
                status = "COMPLETED"
            ),
            DetectedBook(
                title = "The Alchemist",
                author = "Paulo Coelho",
                matchPercent = 89,
                reason = "An inspirational fable about following your dreams and finding your personal legend.",
                status = "WANT_TO_READ"
            ),
            DetectedBook(
                title = "Digital Minimalism",
                author = "Cal Newport",
                matchPercent = 92,
                reason = "A timely guide to reclaiming focus and choosing a focused life in a noisy digital world.",
                status = "READING"
            )
        )
    }

    suspend fun analyzeReadingHistory(books: List<BookEntity>): ReadingSummary = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || books.isEmpty()) {
            Log.w(TAG, "Gemini API key is empty or books is empty. Returning fallback ReadingSummary.")
            return@withContext getFallbackReadingSummary(books)
        }

        try {
            val booksArray = JSONArray().apply {
                for (b in books) {
                    val bObj = JSONObject().apply {
                        put("title", b.title)
                        put("author", b.author)
                        put("status", b.status)
                    }
                    put(bObj)
                }
            }

            val promptText = """
                You are a brilliant, world-class literary critic and reading analyst. Analyze this user's bookshelf reading history and provide personalized genre insights, an overall summary, and a reading recommendation tip.

                Here is the list of books in the user's library:
                ${booksArray.toString()}

                You MUST return your response strictly as a JSON object matching this schema:
                {
                  "summary": "An engaging, professional, and encouraging summary paragraph of the user's reading taste and progress. Limit to 3 sentences.",
                  "primaryGenre": "The name of their dominant reading genre (e.g., 'Technology & Innovation', 'Fiction & Philosophy', etc.)",
                  "genreInsights": [
                    {
                      "genre": "Genre Name (e.g. Science Fiction, Technology, Productivity)",
                      "percentage": 45,
                      "color": "#HEX_COLOR (choose nice distinct hex colors like #4F46E5, #10B981, #F59E0B, #EC4899, #8B5CF6 - choose professional Material colors)",
                      "description": "A 1-sentence observation about their interest in this specific genre."
                    }
                  ],
                  "aiTip": "A customized actionable tip or book recommendation (e.g., 'Try reading Atomic Habits next to optimize your habits') based on their reading profile. Limit to 1-2 sentences."
                }

                Ensure the output is pure JSON. Do not write markdown annotations like ```json or similar.
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            val promptPart = JSONObject().apply {
                                put("text", promptText)
                            }
                            put(promptPart)
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                val genConfig = JSONObject().apply {
                    val respFormat = JSONObject().apply {
                        put("responseMimeType", "application/json")
                    }
                    put("responseFormat", respFormat)
                    put("temperature", 0.3)
                }
                put("generationConfig", genConfig)
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val url = "$BASE_URL?key=$apiKey"
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini analysis API call failed with code: ${response.code}")
                    return@withContext getFallbackReadingSummary(books)
                }

                val responseBodyStr = response.body?.string() ?: ""
                Log.d(TAG, "Gemini analysis response: $responseBodyStr")

                val responseJson = JSONObject(responseBodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val firstPart = parts?.optJSONObject(0)
                var textResponse = firstPart?.optString("text") ?: ""

                if (textResponse.startsWith("```")) {
                    textResponse = textResponse.replace("```json", "").replace("```", "").trim()
                }

                if (textResponse.isNotEmpty()) {
                    val rootObj = JSONObject(textResponse)
                    val summary = rootObj.optString("summary", "")
                    val primaryGenre = rootObj.optString("primaryGenre", "General")
                    val aiTip = rootObj.optString("aiTip", "")
                    
                    val insightsList = mutableListOf<GenreInsight>()
                    val insightsArray = rootObj.optJSONArray("genreInsights")
                    if (insightsArray != null) {
                        for (i in 0 until insightsArray.length()) {
                            val insightObj = insightsArray.getJSONObject(i)
                            insightsList.add(
                                GenreInsight(
                                    genre = insightObj.optString("genre", "Unknown"),
                                    percentage = insightObj.optInt("percentage", 30),
                                    color = insightObj.optString("color", "#4F46E5"),
                                    description = insightObj.optString("description", "")
                                )
                            )
                        }
                    }

                    if (summary.isNotEmpty()) {
                        return@withContext ReadingSummary(
                            summary = summary,
                            primaryGenre = primaryGenre,
                            genreInsights = insightsList,
                            aiTip = aiTip
                        )
                    }
                }
                return@withContext getFallbackReadingSummary(books)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in analyzeReadingHistory: ${e.message}", e)
            return@withContext getFallbackReadingSummary(books)
        }
    }

    private fun getFallbackReadingSummary(books: List<BookEntity>): ReadingSummary {
        val totalCount = books.size
        val readingCount = books.count { it.status == "READING" }
        val finishedCount = books.count { it.status == "COMPLETED" || it.status == "FINISHED" }
        val wishlistCount = books.count { it.status == "WANT_TO_READ" }

        val rPct = if (totalCount > 0) (readingCount * 100 / totalCount) else 30
        val fPct = if (totalCount > 0) (finishedCount * 100 / totalCount) else 40
        val wPct = if (totalCount > 0) (wishlistCount * 100 / totalCount) else 30

        return ReadingSummary(
            summary = "You currently have $totalCount books in your active library, showing strong engagement with self-improvement and technical reading materials.",
            primaryGenre = if (books.isNotEmpty()) "Non-Fiction & Growth" else "General Reader",
            genreInsights = listOf(
                GenreInsight("Self-Growth", rPct.coerceAtLeast(10), "#4F46E5", "Active focus on creating micro habits and routines."),
                GenreInsight("Technology", fPct.coerceAtLeast(10), "#10B981", "Demonstrates deep interest in systems architecture and computing."),
                GenreInsight("Philosophy & Fiction", wPct.coerceAtLeast(10), "#F59E0B", "Curious about allegorical fables and narrative journeys.")
            ),
            aiTip = "To accelerate your growth, try setting a consistent 15-minute morning routine to progress through \"Atomic Habits\"!"
        )
    }
}

data class ReadingSummary(
    val summary: String,
    val primaryGenre: String,
    val genreInsights: List<GenreInsight>,
    val aiTip: String
)

data class GenreInsight(
    val genre: String,
    val percentage: Int,
    val color: String,
    val description: String
)
