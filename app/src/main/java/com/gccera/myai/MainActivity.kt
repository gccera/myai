package com.gccera.myai

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    @Inject lateinit var hfAccount: com.gccera.myai.data.hf.HfAccount

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* progress is also shown in-app */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Download progress notifications (Android 13+). Downloads work without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        lifecycleScope.launch { hfAccount.migrateLegacyToken() }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Completes "Sign in with Hugging Face" when the browser redirects back to com.gccera.myai://oauth/callback. */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!hfAccount.isRedirect(uri)) return
        setIntent(Intent(this, MainActivity::class.java)) // don't re-handle on rotation
        lifecycleScope.launch {
            val msg = runCatching { hfAccount.handleRedirect(uri) }
                .fold({ "Signed in to Hugging Face" }, { it.message ?: "Sign-in failed" })
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
