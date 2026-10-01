package com.hatake.cookiechecker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.hatake.cookiechecker.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val vm: CookieViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(vm = vm)
                }
            }
        }
    }
}
