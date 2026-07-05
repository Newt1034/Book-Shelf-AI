package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.data.FirebaseManager
import com.example.ui.components.BookShelfUI
import com.example.ui.theme.BookShelfAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Firebase manager
        FirebaseManager.initialize(applicationContext)
        
        // Supports full edge-to-edge drawing under status and navigation bars
        enableEdgeToEdge()
        
        setContent {
            BookShelfAITheme {
                BookShelfUI()
            }
        }
    }
}
