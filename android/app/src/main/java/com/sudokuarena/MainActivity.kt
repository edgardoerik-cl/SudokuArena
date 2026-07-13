package com.sudokuarena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sudokuarena.data.SocketGameClient
import com.sudokuarena.presentation.ArenaRoute
import com.sudokuarena.presentation.ArenaViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val arenaViewModel: ArenaViewModel = viewModel(
                    factory = ArenaViewModel.factory(
                        SocketGameClient(
                            serverUrl = BuildConfig.SOCKET_URL,
                            playerName = "Jugador-${android.os.Build.MODEL.take(8)}",
                        ),
                    ),
                )
                ArenaRoute(arenaViewModel)
            }
        }
    }
}
