package com.sudokuarena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
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
import com.sudokuarena.data.local.PlayerPreferences
import com.sudokuarena.data.local.RandomSudokuGenerator
import com.sudokuarena.presentation.ArenaRoute
import com.sudokuarena.presentation.ArenaViewModel
import com.sudokuarena.presentation.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SudokuArenaApp()
            }
        }
    }
}

@Composable
private fun SudokuArenaApp() {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedRoomCode by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionId by rememberSaveable { mutableLongStateOf(0L) }

    if (mode == null) {
        WelcomeScreen(
            initialNickname = preferences.nickname(),
            onSaveNickname = preferences::saveNickname,
            onSoloMode = {
                requestedRoomCode = null
                mode = "SOLO"
                sessionId += 1
            },
            onCreateRoom = {
                requestedRoomCode = null
                mode = "ONLINE"
                sessionId += 1
            },
            onJoinRoom = { code ->
                requestedRoomCode = code
                mode = "ONLINE"
                sessionId += 1
            },
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
            gateway = gateway,
            sudokuGenerator = RandomSudokuGenerator(),
            recordStore = preferences,
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
