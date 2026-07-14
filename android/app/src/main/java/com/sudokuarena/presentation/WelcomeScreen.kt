package com.sudokuarena.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    initialNickname: String,
    onSaveNickname: (String) -> Unit,
    onSoloMode: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
) {
    var nickname by remember(initialNickname) { mutableStateOf(initialNickname) }
    var nicknameSaved by remember(initialNickname) { mutableStateOf(initialNickname.isNotBlank()) }
    var showMultiplayer by remember { mutableStateOf(false) }
    var roomCode by remember { mutableStateOf("") }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Sudoku Arena", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Robo de Filas", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = {
                    nickname = it.take(20)
                    nicknameSaved = false
                    showMultiplayer = false
                },
                label = { Text("Tu nickname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val clean = nickname.trim()
                    if (clean.isNotEmpty()) {
                        onSaveNickname(clean)
                        nickname = clean
                        nicknameSaved = true
                    }
                },
                enabled = nickname.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (nicknameSaved) "Nickname guardado ✓" else "Guardar nickname") }

            AnimatedVisibility(visible = nicknameSaved) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onSoloMode, modifier = Modifier.fillMaxWidth()) {
                        Text("🧩 Modo Solitario")
                    }
                    OutlinedButton(
                        onClick = { showMultiplayer = !showMultiplayer },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🌐 Modo Multijugador") }

                    AnimatedVisibility(visible = showMultiplayer) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = onCreateRoom, modifier = Modifier.fillMaxWidth()) {
                                Text("Crear sala nueva")
                            }
                            OutlinedTextField(
                                value = roomCode,
                                onValueChange = { value -> roomCode = value.filter(Char::isDigit).take(4) },
                                label = { Text("Código de 4 dígitos") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { onJoinRoom(roomCode) },
                                enabled = roomCode.length == 4,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Unirse a sala") }
                        }
                    }
                }
            }
        }
    }
}
