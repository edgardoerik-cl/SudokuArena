package com.sudokuarena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.sudokuarena.data.SocketGameClient
import com.sudokuarena.data.HttpLeaderboardRepository
import com.sudokuarena.data.local.PlayerPreferences
import com.sudokuarena.data.local.RandomSudokuGenerator
import com.sudokuarena.presentation.ArenaRoute
import com.sudokuarena.presentation.ArenaViewModel
import com.sudokuarena.presentation.WelcomeScreen
import com.sudokuarena.presentation.MultiplayerEntryScreen
import com.sudokuarena.presentation.MultiArenaSplashScreen
import com.sudokuarena.presentation.SoloSetupScreen
import com.sudokuarena.presentation.MultiArenaTheme
import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.PuzzleDifficulty
import com.sudokuarena.audio.GlobalAudioManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContent {
            MultiArenaTheme {
                MultiArenaApp()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onStart() {
        super.onStart()
        GlobalAudioManager.onForeground()
    }

    override fun onStop() {
        GlobalAudioManager.onBackground()
        super.onStop()
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
private fun MultiArenaApp() {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val favoriteGames by preferences.favoriteGamesFlow().collectAsState(initial = emptySet())
    val appScope = rememberCoroutineScope()
    val leaderboardRepository = remember { HttpLeaderboardRepository(BuildConfig.SOCKET_URL) }
    var screen by rememberSaveable { mutableStateOf("SPLASH") }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedRoomCode by rememberSaveable { mutableStateOf<String?>(null) }
    var soloColorMode by rememberSaveable { mutableStateOf(false) }
    var dailyChallenge by rememberSaveable { mutableStateOf(false) }
    var selectedGameType by rememberSaveable { mutableStateOf(GameType.SUDOKU) }
    var soloDifficulty by rememberSaveable { mutableStateOf(PuzzleDifficulty.MEDIUM) }
    var sessionId by rememberSaveable { mutableLongStateOf(0L) }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (fadeIn(tween(280)) + slideInHorizontally(tween(320)) { it / 10 })
                .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { -it / 12 })
        },
        label = "mainNavigation",
    ) { destination ->
        when (destination) {
            "SPLASH" -> MultiArenaSplashScreen { screen = "WELCOME" }
            "WELCOME" -> WelcomeScreen(
                initialNickname = preferences.nickname(),
                initialXp = preferences.totalXp(),
                initialAvatar = preferences.avatarId(),
                selectedGameType = selectedGameType,
                favoriteGames = favoriteGames,
                onToggleFavorite = { game -> appScope.launch { preferences.toggleFavorite(game) } },
                onGameSelected = { selectedGameType = it },
                leaderboardRepository = leaderboardRepository,
                onSaveNickname = preferences::saveNickname,
                onSaveAvatar = preferences::saveAvatarId,
                onSoloMode = {
                    dailyChallenge = false
                    requestedRoomCode = null
                    screen = "SOLO_SETUP"
                },
                onDailyChallenge = {
                    dailyChallenge = true
                    soloColorMode = false
                    requestedRoomCode = null
                    mode = "SOLO"
                    sessionId += 1
                    screen = "GAME"
                },
                onMultiplayerMode = { screen = "MULTIPLAYER_ENTRY" },
            )
            "SOLO_SETUP" -> SoloSetupScreen(
                gameType = selectedGameType,
                isColorMode = soloColorMode,
                difficulty = soloDifficulty,
                onColorModeChanged = { soloColorMode = it },
                onDifficultyChanged = { soloDifficulty = it },
                onStart = {
                    mode = "SOLO"
                    sessionId += 1
                    screen = "GAME"
                },
                onBack = { screen = "WELCOME" },
            )
            "MULTIPLAYER_ENTRY" -> MultiplayerEntryScreen(
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
            else -> {
                val sessionOwner: SessionViewModelStoreOwner = viewModel(key = "session-store")
                val isSolo = mode == "SOLO"
                val gateway = remember(sessionId) {
                    if (isSolo) null else SocketGameClient(
                        serverUrl = BuildConfig.SOCKET_URL,
                        playerName = preferences.nickname(),
                        clientId = preferences.clientId(),
                        avatarId = preferences.avatarId(),
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
                        isDailyChallenge = isSolo && dailyChallenge,
                        initialGameType = selectedGameType,
                        initialPuzzleDifficulty = soloDifficulty,
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
        }
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
