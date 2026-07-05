package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BookViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BookRepository
    private val prefs = application.getSharedPreferences("bookshelf_prefs", Context.MODE_PRIVATE)
    
    // UI state flows backing Room
    val allBooks: StateFlow<List<BookEntity>>
    val allActivities: StateFlow<List<ActivityEntity>>

    // General UI states
    private val _selectedTab = MutableStateFlow("Home")
    val selectedTab: StateFlow<String> = _selectedTab.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _spineScanProgress = MutableStateFlow(100)
    val spineScanProgress: StateFlow<Int> = _spineScanProgress.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _scannedBooks = MutableStateFlow<List<ScannedBook>>(emptyList())
    val scannedBooks: StateFlow<List<ScannedBook>> = _scannedBooks.asStateFlow()

    // --- User Profile & Authentication State Flows ---
    private val _userLoggedIn = MutableStateFlow(prefs.getBoolean("user_logged_in", false))
    val userLoggedIn: StateFlow<Boolean> = _userLoggedIn.asStateFlow()

    private val _userName = MutableStateFlow(prefs.getString("user_name", "Julian") ?: "Julian")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userEmail = MutableStateFlow(prefs.getString("user_email", "julian@bookshelf.ai") ?: "julian@bookshelf.ai")
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _userProfilePic = MutableStateFlow(prefs.getString("user_profile_pic", "") ?: "")
    val userProfilePic: StateFlow<String> = _userProfilePic.asStateFlow()

    private val _userReadingGoal = MutableStateFlow(prefs.getInt("user_reading_goal", 24))
    val userReadingGoal: StateFlow<Int> = _userReadingGoal.asStateFlow()

    private val _userTheme = MutableStateFlow(prefs.getString("user_theme", "System") ?: "System")
    val userTheme: StateFlow<String> = _userTheme.asStateFlow()

    private val _userPreferredGenres = MutableStateFlow(
        prefs.getStringSet("user_preferred_genres", setOf("Fiction", "Sci-Fi", "Mystery"))?.toList() ?: listOf("Fiction", "Sci-Fi", "Mystery")
    )
    val userPreferredGenres: StateFlow<List<String>> = _userPreferredGenres.asStateFlow()

    private val _autoScanCovers = MutableStateFlow(prefs.getBoolean("auto_scan_covers", true))
    val autoScanCovers: StateFlow<Boolean> = _autoScanCovers.asStateFlow()

    private val _emailDigests = MutableStateFlow(prefs.getBoolean("email_digests", false))
    val emailDigests: StateFlow<Boolean> = _emailDigests.asStateFlow()

    private val _syncStatus = MutableStateFlow("Local persistence active")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _readingSummaryState = MutableStateFlow<ReadingSummaryUiState>(ReadingSummaryUiState.Loading)
    val readingSummaryState: StateFlow<ReadingSummaryUiState> = _readingSummaryState.asStateFlow()

    init {
        val bookDao = BookDatabase.getDatabase(application).bookDao()
        repository = BookRepository(bookDao)

        // Read flows from database
        allBooks = repository.allBooks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        allActivities = repository.allActivities.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Seed initial data if empty
        viewModelScope.launch {
            allBooks.first { true } // Wait for first list
            if (allBooks.value.isEmpty()) {
                seedInitialData()
            }
            updateFirebaseSyncState()
            if (_userLoggedIn.value) {
                loadPreferencesFromFirestore()
                loadBooksFromFirestore()
            }
        }

        // Trigger Gemini analysis when books load or change
        viewModelScope.launch {
            allBooks.collect { books ->
                if (books.isNotEmpty()) {
                    try {
                        val summary = GeminiScanner.analyzeReadingHistory(books)
                        _readingSummaryState.value = ReadingSummaryUiState.Success(summary)
                    } catch (e: Exception) {
                        _readingSummaryState.value = ReadingSummaryUiState.Error(e.localizedMessage ?: "Unknown error")
                    }
                }
            }
        }
    }

    private suspend fun seedInitialData() {
        // Dummy data initialization disabled as requested by the user.
        // This gives a clean slate to test actual database features.
    }

    private fun updateFirebaseSyncState() {
        val isFirebaseActive = FirebaseManager.isFirebaseInitialized
        val isLoggedIn = _userLoggedIn.value
        _syncStatus.value = when {
            isFirebaseActive && isLoggedIn -> "Cloud Synced (Firebase Connected)"
            isFirebaseActive -> "Cloud Sync Ready (Login to Sync)"
            else -> "Local persistence active"
        }
    }

    fun selectTab(tab: String) {
        _selectedTab.value = tab
    }

    fun setToast(message: String?) {
        _toastMessage.value = message
    }

    // --- Profile & Authentication Management ---
    fun loginWithEmail(email: String, password: CharSequence, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            FirebaseManager.signInWithEmail(email, password, { session ->
                saveUserSession(session.uid, session.displayName, session.email, session.photoUrl)
                setToast("Welcome back, ${session.displayName}!")
                onDone(true)
            }, { err ->
                setToast("Login failed: $err")
                onDone(false)
            })
        }
    }

    fun registerWithEmail(email: String, password: CharSequence, name: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            FirebaseManager.signUpWithEmail(email, password, name, { session ->
                saveUserSession(session.uid, session.displayName, session.email, session.photoUrl)
                setToast("Successfully registered, $name!")
                onDone(true)
            }, { err ->
                setToast("Registration failed: $err")
                onDone(false)
            })
        }
    }

    fun loginWithGoogle(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            FirebaseManager.signInWithGoogle({ session ->
                saveUserSession(session.uid, session.displayName, session.email, session.photoUrl)
                setToast("Signed in as ${session.displayName} via Google")
                onDone(true)
            }, { err ->
                setToast("Google Sign-In failed: $err")
                onDone(false)
            })
        }
    }

    private fun saveUserSession(uid: String, name: String, email: String, photo: String) {
        prefs.edit().apply {
            putBoolean("user_logged_in", true)
            putString("user_uid", uid)
            putString("user_name", name)
            putString("user_email", email)
            putString("user_profile_pic", photo)
            apply()
        }
        _userLoggedIn.value = true
        _userName.value = name
        _userEmail.value = email
        _userProfilePic.value = photo
        updateFirebaseSyncState()
        triggerFirebaseSync()
        loadPreferencesFromFirestore()
        loadBooksFromFirestore()
    }

    fun loadBooksFromFirestore() {
        val uid = prefs.getString("user_uid", null) ?: return
        if (_userLoggedIn.value && FirebaseManager.isFirebaseInitialized) {
            FirebaseManager.fetchBooksFromFirestore(uid, { fetchedBooks ->
                if (fetchedBooks.isNotEmpty()) {
                    viewModelScope.launch {
                        // Insert them into local Room database
                        repository.insertBooks(fetchedBooks)
                        setToast("Loaded ${fetchedBooks.size} books from your Firestore shelf.")
                    }
                }
            }, { err ->
                Log.w("BookViewModel", "Failed to fetch books from Firestore: $err")
            })
        }
    }

    fun addBookManually(title: String, author: String, status: String) {
        viewModelScope.launch {
            val bookId = (System.currentTimeMillis() % 10000000).toInt()
            val newBook = BookEntity(
                id = bookId,
                title = title,
                author = author,
                coverUrl = "https://images.unsplash.com/photo-1543002588-bfa74002ed7e?auto=format&fit=crop&q=80&w=256", // default beautiful book placeholder
                matchPercent = 100,
                reason = "Manually added by user",
                status = status,
                timestamp = System.currentTimeMillis()
            )
            repository.insertBook(newBook)
            repository.insertActivity(
                ActivityEntity(
                    title = "Manual Book Added",
                    subtitle = "Successfully added \"$title\" to your ${status.lowercase().replace("_", " ")} shelf.",
                    progress = 100,
                    type = "SCAN"
                )
            )
            setToast("Added \"$title\" successfully!")
            triggerFirebaseSync()
        }
    }

    fun logout() {
        FirebaseManager.getAuth()?.signOut()
        prefs.edit().apply {
            putBoolean("user_logged_in", false)
            putString("user_uid", null)
            putString("user_name", "Julian")
            putString("user_email", "julian@bookshelf.ai")
            putString("user_profile_pic", "")
            // Also reset preferences to defaults on logout
            putString("user_theme", "System")
            putStringSet("user_preferred_genres", setOf("Fiction", "Sci-Fi", "Mystery"))
            putBoolean("auto_scan_covers", true)
            putBoolean("email_digests", false)
            apply()
        }
        _userLoggedIn.value = false
        _userName.value = "Julian"
        _userEmail.value = "julian@bookshelf.ai"
        _userProfilePic.value = ""
        _userTheme.value = "System"
        _userPreferredGenres.value = listOf("Fiction", "Sci-Fi", "Mystery")
        _autoScanCovers.value = true
        _emailDigests.value = false
        setToast("Logged out successfully.")
        updateFirebaseSyncState()
    }

    fun loadPreferencesFromFirestore() {
        val uid = prefs.getString("user_uid", null) ?: return
        if (_userLoggedIn.value && FirebaseManager.isFirebaseInitialized) {
            FirebaseManager.fetchUserProfileFirestore(uid, { data ->
                val name = data["name"] as? String ?: _userName.value
                val goal = (data["readingGoal"] as? Long)?.toInt() ?: _userReadingGoal.value
                val theme = data["theme"] as? String ?: _userTheme.value
                val genres = (data["preferredGenres"] as? List<*>)?.mapNotNull { it as? String } ?: _userPreferredGenres.value
                val autoScan = data["autoScanCovers"] as? Boolean ?: _autoScanCovers.value
                val digests = data["emailDigests"] as? Boolean ?: _emailDigests.value

                prefs.edit().apply {
                    putString("user_name", name)
                    putInt("user_reading_goal", goal)
                    putString("user_theme", theme)
                    putStringSet("user_preferred_genres", genres.toSet())
                    putBoolean("auto_scan_covers", autoScan)
                    putBoolean("email_digests", digests)
                    apply()
                }

                _userName.value = name
                _userReadingGoal.value = goal
                _userTheme.value = theme
                _userPreferredGenres.value = genres
                _autoScanCovers.value = autoScan
                _emailDigests.value = digests
            }, { err ->
                Log.w("BookViewModel", "Failed to fetch from Firestore: $err")
            })
        }
    }

    fun updateProfile(
        name: String,
        readingGoal: Int,
        theme: String = _userTheme.value,
        preferredGenres: List<String> = _userPreferredGenres.value,
        autoScan: Boolean = _autoScanCovers.value,
        digests: Boolean = _emailDigests.value
    ) {
        prefs.edit().apply {
            putString("user_name", name)
            putInt("user_reading_goal", readingGoal)
            putString("user_theme", theme)
            putStringSet("user_preferred_genres", preferredGenres.toSet())
            putBoolean("auto_scan_covers", autoScan)
            putBoolean("email_digests", digests)
            apply()
        }
        _userName.value = name
        _userReadingGoal.value = readingGoal
        _userTheme.value = theme
        _userPreferredGenres.value = preferredGenres
        _autoScanCovers.value = autoScan
        _emailDigests.value = digests
        setToast("Profile and settings updated successfully")

        if (_userLoggedIn.value) {
            val uid = prefs.getString("user_uid", "local_user") ?: "local_user"
            val email = _userEmail.value
            FirebaseManager.saveUserProfile(uid, name, email, readingGoal)
            FirebaseManager.saveUserProfileFirestore(
                uid = uid,
                name = name,
                email = email,
                readingGoal = readingGoal,
                theme = theme,
                preferredGenres = preferredGenres,
                autoScanCovers = autoScan,
                emailDigests = digests
            )
        }
    }

    private fun triggerFirebaseSync() {
        val uid = prefs.getString("user_uid", null) ?: return
        if (_userLoggedIn.value && FirebaseManager.isFirebaseInitialized) {
            viewModelScope.launch {
                FirebaseManager.syncBooksToFirebase(uid, allBooks.value)
                FirebaseManager.syncBooksToFirestore(uid, allBooks.value)
                FirebaseManager.syncActivitiesToFirebase(uid, allActivities.value)
            }
        }
    }

    fun updateBookStatus(bookId: Int, status: String) {
        viewModelScope.launch {
            repository.updateBookStatus(bookId, status)
            if (status == "WANT_TO_READ") {
                setToast("Added to Want to Read list")
                val book = allBooks.value.find { it.id == bookId }
                if (book != null) {
                    repository.insertActivity(
                        ActivityEntity(
                            title = "Added to Wishlist",
                            subtitle = "\"${book.title}\" is now saved to your reading list",
                            progress = 100,
                            type = "IMPORT"
                        )
                    )
                }
            }
            triggerFirebaseSync()
        }
    }

    fun deleteBookById(bookId: Int) {
        viewModelScope.launch {
            repository.deleteBookById(bookId)
            triggerFirebaseSync()
        }
    }

    // --- High-Fidelity Camera & AI Bookshelf Scanner Integration ---
    fun scanBookshelfImage(bitmap: Bitmap) {
        _isScanning.value = true
        _scannedBooks.value = listOf(
            ScannedBook("Processing image...", "Sending to Gemini AI...", ScannedStatus.SCANNING, null)
        )
        viewModelScope.launch {
            try {
                setToast("Analyzing bookshelf image via Gemini...")
                val detected = GeminiScanner.scanBookshelf(bitmap)
                
                _scannedBooks.value = detected.map {
                    ScannedBook(
                        title = it.title,
                        statusText = "Identified by AI",
                        status = ScannedStatus.VERIFIED,
                        coverUrl = null
                    )
                }
                
                // Save detected books to database
                val booksToSave = detected.map {
                    BookEntity(
                        title = it.title,
                        author = it.author,
                        coverUrl = "",
                        matchPercent = it.matchPercent,
                        reason = it.reason,
                        status = it.status
                    )
                }
                repository.insertBooks(booksToSave)
                
                // Log scan activity
                repository.insertActivity(
                    ActivityEntity(
                        title = "AI Bookshelf Scan",
                        subtitle = "Identified ${detected.size} books via Gemini API",
                        progress = 100,
                        type = "SCAN"
                    )
                )

                setToast("Scan complete! Found ${detected.size} books.")
                triggerFirebaseSync()
                delay(2000)
                _isScanning.value = false
                _selectedTab.value = "Home"
            } catch (e: Exception) {
                Log.e("BookViewModel", "Scan failed: ${e.message}", e)
                setToast("Scan failed: ${e.localizedMessage}")
                _isScanning.value = false
            }
        }
    }

    // --- Goodreads CSV Import Engine ---
    fun importGoodreadsCsvText(csvContent: String) {
        viewModelScope.launch {
            setToast("Parsing Goodreads CSV data...")
            try {
                val parsedBooks = parseGoodreadsCsv(csvContent)
                if (parsedBooks.isEmpty()) {
                    setToast("No valid books parsed. Ensure Title and Author columns exist!")
                    return@launch
                }

                repository.insertBooks(parsedBooks)

                // Log import activity
                repository.insertActivity(
                    ActivityEntity(
                        title = "Goodreads Import",
                        subtitle = "Successfully imported ${parsedBooks.size} books from CSV",
                        progress = 100,
                        type = "IMPORT"
                    )
                )

                setToast("Goodreads imported! Added ${parsedBooks.size} books.")
                triggerFirebaseSync()
            } catch (e: Exception) {
                Log.e("BookViewModel", "CSV parse error: ${e.message}", e)
                setToast("Failed to import CSV: ${e.localizedMessage}")
            }
        }
    }

    private fun parseGoodreadsCsv(csvContent: String): List<BookEntity> {
        val lines = csvContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        // CSV line splitter handling commas inside quotes
        fun splitCsvLine(line: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    inQuotes = !inQuotes
                } else if (c == ',' && !inQuotes) {
                    result.add(current.toString().trim())
                    current.setLength(0)
                } else {
                    current.append(c)
                }
                i++
            }
            result.add(current.toString().trim())
            return result
        }

        // Parse headers
        val headers = splitCsvLine(lines[0]).map { it.replace("\"", "").trim().lowercase() }
        val titleIndex = headers.indexOfFirst { it == "title" }
        val authorIndex = headers.indexOfFirst { it == "author" }
        val shelfIndex = headers.indexOfFirst { it == "exclusive shelf" || it == "bookshelves" || it == "shelf" }

        if (titleIndex == -1 || authorIndex == -1) {
            Log.e("BookViewModel", "Required headers (Title, Author) not found in CSV. Found headers: $headers")
            return emptyList()
        }

        val books = mutableListOf<BookEntity>()
        for (idx in 1 until lines.size) {
            try {
                val row = splitCsvLine(lines[idx])
                if (row.size <= maxOf(titleIndex, authorIndex)) continue

                val rawTitle = row[titleIndex].replace("\"", "").trim()
                val rawAuthor = row[authorIndex].replace("\"", "").trim()
                if (rawTitle.isEmpty() || rawAuthor.isEmpty()) continue

                val shelf = if (shelfIndex != -1 && shelfIndex < row.size) {
                    row[shelfIndex].replace("\"", "").trim().lowercase()
                } else "to-read"

                val status = when {
                    shelf.contains("currently") || shelf.contains("reading") -> "READING"
                    shelf.contains("read") && !shelf.contains("to") -> "COMPLETED"
                    else -> "WANT_TO_READ"
                }

                books.add(
                    BookEntity(
                        title = rawTitle,
                        author = rawAuthor,
                        coverUrl = "",
                        matchPercent = (82..98).random(),
                        reason = "Imported from Goodreads shelf",
                        status = status
                    )
                )
            } catch (e: Exception) {
                // Skip malformed rows gracefully
            }
        }
        return books
    }

    fun startScanningFlow() {
        _isScanning.value = true
        _scannedBooks.value = listOf(
            ScannedBook("Atomic Habits", "Verified", ScannedStatus.VERIFIED, "https://lh3.googleusercontent.com/aida-public/AB6AXuDFB8Bnt3FsLT5qWJxoqSUcOBOvAsIUcrjVEnh5t9G4RKsSQm8FnH14FkezHnUwztRcSfTKWXwD1XvLfipWcBzOqzc18t33h5itELYYXAjBaqGYiMgqoki3YqqdJ4fCpIFlbLJSdixz_cJoVt2f9GeqVDeqDz_0N1EGWrG8dQUlhEsvm75MMLLNM-Fw4PdWsU1q82uSBNbtdtugibgoh3WsgX5Spsu9sbriAViIBwMz6qwQ7rLX6Lyo"),
            ScannedBook("The Alchemist", "Scanning...", ScannedStatus.SCANNING, null),
            ScannedBook("Digital Minimalism", "Analyzing spine...", ScannedStatus.PENDING, null)
        )
    }

    fun stopScanningFlow() {
        _isScanning.value = false
        _scannedBooks.value = emptyList()
    }

    fun executeCaptureAndAnalyze() {
        viewModelScope.launch {
            setToast("Analyzing scanned bookshelf spines...")
            delay(1000)
            _scannedBooks.value = _scannedBooks.value.map {
                if (it.title == "The Alchemist") {
                    it.copy(statusText = "Verified", status = ScannedStatus.VERIFIED)
                } else it
            }
            delay(1000)
            _scannedBooks.value = _scannedBooks.value.map {
                if (it.title == "Digital Minimalism") {
                    it.copy(statusText = "Verified", status = ScannedStatus.VERIFIED)
                } else it
            }
            delay(800)

            val scannedToSave = listOf(
                BookEntity(
                    title = "Atomic Habits",
                    author = "James Clear",
                    coverUrl = "https://lh3.googleusercontent.com/aida-public/AB6AXuDFB8Bnt3FsLT5qWJxoqSUcOBOvAsIUcrjVEnh5t9G4RKsSQm8FnH14FkezHnUwztRcSfTKWXwD1XvLfipWcBzOqzc18t33h5itELYYXAjBaqGYiMgqoki3YqqdJ4fCpIFlbLJSdixz_cJoVt2f9GeqVDeqDz_0N1EGWrG8dQUlhEsvm75MMLLNM-Fw4PdWsU1q82uSBNbtdtugibgoh3WsgX5Spsu9sbriAViIBwMz6qwQ7rLX6Lyo",
                    matchPercent = 90,
                    reason = "Scanned directly via Camera Scan",
                    status = "COMPLETED"
                ),
                BookEntity(
                    title = "The Alchemist",
                    author = "Paulo Coelho",
                    coverUrl = "",
                    matchPercent = 85,
                    reason = "Scanned directly via Camera Scan",
                    status = "COMPLETED"
                ),
                BookEntity(
                    title = "Digital Minimalism",
                    author = "Cal Newport",
                    coverUrl = "",
                    matchPercent = 93,
                    reason = "Scanned directly via Camera Scan",
                    status = "WANT_TO_READ"
                )
            )
            repository.insertBooks(scannedToSave)

            repository.insertActivity(
                ActivityEntity(
                    title = "Bookshelf Scanned",
                    subtitle = "Identified Atomic Habits, The Alchemist, and Digital Minimalism",
                    progress = 100,
                    type = "SCAN"
                )
            )

            setToast("Analysis completed! Added 3 scanned books to library.")
            triggerFirebaseSync()
            delay(500)
            _isScanning.value = false
            _selectedTab.value = "Home"
        }
    }

    // --- Google Books ISBN & Search Integration ---
    private val _isbnScanResult = MutableStateFlow<GoogleBookMetadata?>(null)
    val isbnScanResult: StateFlow<GoogleBookMetadata?> = _isbnScanResult.asStateFlow()

    private val _isIsbnSearching = MutableStateFlow(false)
    val isIsbnSearching: StateFlow<Boolean> = _isIsbnSearching.asStateFlow()

    private val _isbnSearchResults = MutableStateFlow<List<GoogleBookMetadata>>(emptyList())
    val isbnSearchResults: StateFlow<List<GoogleBookMetadata>> = _isbnSearchResults.asStateFlow()

    fun searchBookByIsbn(isbn: String, onDone: (GoogleBookMetadata?) -> Unit = {}) {
        _isIsbnSearching.value = true
        viewModelScope.launch {
            val meta = GoogleBooksClient.fetchBookByIsbn(isbn)
            _isIsbnSearching.value = false
            if (meta != null) {
                _isbnScanResult.value = meta
                onDone(meta)
            } else {
                setToast("No book found for ISBN: $isbn on Google Books.")
                onDone(null)
            }
        }
    }

    fun searchGoogleBooks(query: String) {
        if (query.trim().isEmpty()) {
            _isbnSearchResults.value = emptyList()
            return
        }
        _isIsbnSearching.value = true
        viewModelScope.launch {
            val results = GoogleBooksClient.searchBooksByQuery(query)
            _isIsbnSearching.value = false
            _isbnSearchResults.value = results
        }
    }

    // --- Dashboard Specific Manual Search ---
    private val _dashboardSearchResults = MutableStateFlow<List<GoogleBookMetadata>>(emptyList())
    val dashboardSearchResults: StateFlow<List<GoogleBookMetadata>> = _dashboardSearchResults.asStateFlow()

    private val _isDashboardSearching = MutableStateFlow(false)
    val isDashboardSearching: StateFlow<Boolean> = _isDashboardSearching.asStateFlow()

    fun searchGoogleBooksDashboard(query: String) {
        if (query.trim().isEmpty()) {
            _dashboardSearchResults.value = emptyList()
            return
        }
        _isDashboardSearching.value = true
        viewModelScope.launch {
            val results = GoogleBooksClient.searchBooksByQuery(query)
            _isDashboardSearching.value = false
            _dashboardSearchResults.value = results
        }
    }

    fun clearDashboardSearch() {
        _dashboardSearchResults.value = emptyList()
    }

    fun clearIsbnSearch() {
        _isbnScanResult.value = null
        _isbnSearchResults.value = emptyList()
    }

    fun saveGoogleBookToLibrary(metadata: GoogleBookMetadata, selectedStatus: String = "WANT_TO_READ") {
        viewModelScope.launch {
            val newBook = BookEntity(
                title = metadata.title,
                author = metadata.author,
                coverUrl = metadata.coverUrl,
                matchPercent = (85..99).random(),
                reason = "Imported from Google Books via ISBN scan",
                status = selectedStatus
            )
            repository.insertBooks(listOf(newBook))
            repository.insertActivity(
                ActivityEntity(
                    title = "ISBN Book Sync",
                    subtitle = "Synced \"${metadata.title}\" from Google Books into library.",
                    progress = 100,
                    type = "SCAN"
                )
            )
            setToast("Added \"${metadata.title}\" to library!")
            triggerFirebaseSync()
        }
    }

    fun refreshReadingSummary() {
        viewModelScope.launch {
            _readingSummaryState.value = ReadingSummaryUiState.Loading
            try {
                val summary = GeminiScanner.analyzeReadingHistory(allBooks.value)
                _readingSummaryState.value = ReadingSummaryUiState.Success(summary)
                setToast("Reading summary updated successfully!")
            } catch (e: Exception) {
                _readingSummaryState.value = ReadingSummaryUiState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }
}

sealed interface ReadingSummaryUiState {
    object Loading : ReadingSummaryUiState
    data class Success(val summary: ReadingSummary) : ReadingSummaryUiState
    data class Error(val message: String) : ReadingSummaryUiState
}

enum class ScannedStatus {
    VERIFIED,
    SCANNING,
    PENDING
}

data class ScannedBook(
    val title: String,
    val statusText: String,
    val status: ScannedStatus,
    val coverUrl: String?
)
