package com.gccera.myai

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.ui.nav.AppNavHost
import com.gccera.myai.ui.theme.MyAiTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* progress is also shown in-app */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Download progress notifications (Android 13+). Downloads work without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize()) // DataStore loads in a few ms; avoid flashing onboarding.
            } else {
                MyAiTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        // Keyed so "Clear all data" (which resets onboardingDone) rebuilds the graph cleanly.
                        androidx.compose.runtime.key(s.onboardingDone) { AppNavHost(onboardingDone = s.onboardingDone) }
                    }
                }
            }
        }
    }
}
