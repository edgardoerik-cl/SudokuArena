package com.sudokuarena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sudokuarena.data.SocketGameClient
import com.sudokuarena.data.HttpLeaderboardRepository
import com.sudokuarena.data.local.PlayerPreferences
import com.sudokuarena.data.local.RandomSudokuGenerator
import com.sudokuarena.presentation.ArenaRoute
import com.sudokuarena.presentation.ArenaViewModel
import com.sudokuarena.presentation.WelcomeScreen
import com.sudokuarena.presentation.MultiplayerEntryScreen
import com.sudokuarena.presentation.SudokuArenaSplashScreen
import com.sudokuarena.presentation.SoloSetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00DDEB),
                    secondary = Color(0xFFFFC857),
                    background = Color(0xFF0F111A),
                    surface = Color(0xFF171B28),
                ),
            ) {
                SudokuArenaApp()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun SudokuArenaApp() {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val leaderboardRepository = remember { HttpLeaderboardRepository(BuildConfig.SOCKET_URL) }
    var screen by rememberSaveable { mutableStateOf("SPLASH") }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedRoomCode by rememberSaveable { mutableStateOf<String?>(null) }
    var soloColorMode by rememberSaveable { mutableStateOf(false) }
    var sessionId by rememberSaveable { mutableLongStateOf(0L) }

    if (screen == "SPLASH") {
        SudokuArenaSplashScreen { screen = "WELCOME" }
        return
    }

    if (screen == "WELCOME") {
        WelcomeScreen(
            initialNickname = preferences.nickname(),
            leaderboardRepository = leaderboardRepository,
            onSaveNickname = preferences::saveNickname,
            onSoloMode = {
                requestedRoomCode = null
                screen = "SOLO_SETUP"
            },
            onMultiplayerMode = { screen = "MULTIPLAYER_ENTRY" },
        )
        return
    }

    if (screen == "SOLO_SETUP") {
        SoloSetupScreen(
            isColorMode = soloColorMode,
            onColorModeChanged = { soloColorMode = it },
            onStart = {
                mode = "SOLO"
                sessionId += 1
                screen = "GAME"
            },
            onBack = { screen = "WELCOME" },
        )
        return
    }

    if (screen == "MULTIPLAYER_ENTRY") {
        MultiplayerEntryScreen(
            onCreateRoom = {
                requestedRoomCode = null
                mode = "ONLINE"
                sessionId += 1
                screen = "GAME"
            },
            onJoinRoom = { code ->
                requestedRoomCode = code
                mode = "ONLINE"
                sessionId += 1
                screen = "GAME"
            },
            onBack = { screen = "WELCOME" },
        )
        return
    }

    val sessionOwner: SessionViewModelStoreOwner = viewModel(key = "session-store")
    val isSolo = mode == "SOLO"
    val gateway = remember(sessionId) {
        if (isSolo) null else SocketGameClient(
            serverUrl = BuildConfig.SOCKET_URL,
            playerName = preferences.nickname(),
        )
    }
    val factory = remember(sessionId) {
        ArenaViewModel.factory(
            isSoloMode = isSolo,
            initialColorMode = isSolo && soloColorMode,
            gateway = gateway,
            sudokuGenerator = RandomSudokuGenerator(),
            recordStore = preferences,
            leaderboardRepository = leaderboardRepository,
            playerName = preferences.nickname(),
            requestedRoomCode = requestedRoomCode,
        )
    }
    val arenaViewModel: ArenaViewModel = viewModel(
        viewModelStoreOwner = sessionOwner,
        key = "arena-$sessionId",
        factory = factory,
    )
    ArenaRoute(arenaViewModel) {
        sessionOwner.reset()
        mode = null
        requestedRoomCode = null
        screen = "WELCOME"
    }
}

class SessionViewModelStoreOwner : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun reset() {
        viewModelStore.clear()
    }

    override fun onCleared() {
        viewModelStore.clear()
        super.onCleared()
    }
}
