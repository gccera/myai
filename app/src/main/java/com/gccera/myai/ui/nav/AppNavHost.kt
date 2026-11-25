package com.gccera.myai.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gccera.myai.ui.chat.ChatScreen
import com.gccera.myai.ui.gallery.GalleryDetailScreen
import com.gccera.myai.ui.gallery.GalleryScreen
import com.gccera.myai.ui.image.ImageScreen
import com.gccera.myai.ui.models.ModelsScreen
import com.gccera.myai.ui.onboarding.OnboardingScreen
import com.gccera.myai.ui.settings.LicensesScreen
import com.gccera.myai.ui.settings.PrivacyScreen
import com.gccera.myai.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val IMAGE = "image"
    const val GALLERY = "gallery"
    const val GALLERY_DETAIL = "gallery/{id}"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"
    const val PRIVACY = "privacy"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
    Tab(Routes.IMAGE, "Image", Icons.Default.Image),
    Tab(Routes.GALLERY, "Gallery", Icons.Default.PhotoLibrary),
    Tab(Routes.MODELS, "Models", Icons.Default.Memory),
    Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
fun AppNavHost(onboardingDone: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { nav.switchTab(t.route) },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = if (onboardingDone) Routes.CHAT else Routes.ONBOARDING, modifier = Modifier.padding(pad)) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    nav.navigate(Routes.CHAT) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.CHAT) { ChatScreen(onOpenModels = { nav.switchTab(Routes.MODELS) }) }
            composable(Routes.IMAGE) { ImageScreen(onOpenModels = { nav.switchTab(Routes.MODELS) }) }
            composable(Routes.GALLERY) { GalleryScreen(onOpen = { id -> nav.navigate("gallery/$id") }) }
            composable(Routes.GALLERY_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                GalleryDetailScreen(id = it.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
            }
            composable(Routes.MODELS) { ModelsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenLicenses = { nav.navigate(Routes.LICENSES) },
                    onOpenPrivacy = { nav.navigate(Routes.PRIVACY) },
                    onDataCleared = {
                        nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
                    },
                )
            }
            composable(Routes.LICENSES) { LicensesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/** Standard bottom-tab navigation: one copy of each tab, state saved/restored when switching. */
private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
