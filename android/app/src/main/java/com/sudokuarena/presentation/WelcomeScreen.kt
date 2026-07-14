package com.sudokuarena.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
    onMultiplayerMode: () -> Unit,
) {
    var nickname by remember(initialNickname) { mutableStateOf(initialNickname.ifBlank { "Jugador" }) }
    var editing by remember(initialNickname) { mutableStateOf(initialNickname.isBlank()) }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArenaLogo(Modifier.size(135.dp))
            Text("Sudoku Arena", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("PERFIL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (editing) {
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it.take(20) },
                            label = { Text("Nickname") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val clean = nickname.trim()
                                if (clean.isNotEmpty()) {
                                    nickname = clean
                                    onSaveNickname(clean)
                                    editing = false
                                }
                            },
                            enabled = nickname.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Guardar perfil") }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(nickname, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { editing = true }) { Text("Editar") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("MODOS DE JUEGO", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSoloMode,
                enabled = !editing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) { Text("Partida Solitario", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onMultiplayerMode,
                enabled = !editing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) { Text("Partida Multijugador", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
fun MultiplayerEntryScreen(
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onBack: () -> Unit,
) {
    var roomCode by remember { mutableStateOf("") }
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ArenaLogo(Modifier.size(120.dp))
            Text("Multijugador", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Button(onClick = onCreateRoom, modifier = Modifier.fillMaxWidth()) { Text("Crear sala") }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = roomCode,
                onValueChange = { roomCode = it.filter(Char::isDigit).take(4) },
                label = { Text("Código de 4 dígitos") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onJoinRoom(roomCode) },
                enabled = roomCode.length == 4,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unirse") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        }
    }
}
