package com.sudokuarena.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.sudokuarena.R
import com.sudokuarena.domain.LeaderboardRepository

@Composable
fun WelcomeScreen(
    initialNickname: String,
    leaderboardRepository: LeaderboardRepository,
    onSaveNickname: (String) -> Unit,
    onSoloMode: () -> Unit,
    onMultiplayerMode: () -> Unit,
) {
    var savedNickname by remember(initialNickname) { mutableStateOf(initialNickname.trim()) }
    var firstNickname by remember { mutableStateOf("") }
    var showProfile by remember { mutableStateOf(false) }
    var showHonor by remember { mutableStateOf(false) }
    val hasProfile = savedNickname.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(22.dp),
    ) {
        BrandWatermark(Modifier.align(Alignment.Center))
        if (!hasProfile) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.sudoku_arena_icon),
                    contentDescription = "Logo de Sudoku Arena",
                    modifier = Modifier.size(150.dp),
                )
                Text("SUDOKU ARENA", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text("Crea tu perfil para entrar a la arena")
                OutlinedTextField(
                    value = firstNickname,
                    onValueChange = { firstNickname = it.take(20) },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val clean = firstNickname.trim()
                        if (clean.isNotEmpty()) {
                            onSaveNickname(clean)
                            savedNickname = clean
                        }
                    },
                    enabled = firstNickname.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Crear perfil") }
            }
        } else {
            // Tras crear el perfil, el centro queda reservado únicamente a los modos.
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Button(
                    onClick = onSoloMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                ) { Text("Modo Solitario", style = MaterialTheme.typography.titleLarge) }
                Button(
                    onClick = onMultiplayerMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                ) { Text("Modo Multijugador", style = MaterialTheme.typography.titleLarge) }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloatingActionButton(onClick = { showProfile = true }) {
                    ControlIcon(ControlIconType.PROFILE)
                }
                FloatingActionButton(onClick = { showHonor = true }) {
                    ControlIcon(ControlIconType.TROPHY)
                }
            }
        }
    }

    if (showProfile) {
        ProfileDialog(
            nickname = savedNickname,
            onDismiss = { showProfile = false },
            onSave = { updated ->
                savedNickname = updated
                onSaveNickname(updated)
                showProfile = false
            },
        )
    }
    if (showHonor) {
        LeaderboardBottomSheet(repository = leaderboardRepository, onDismiss = { showHonor = false })
    }
}

@Composable
private fun BrandWatermark(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.alpha(0.075f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.sudoku_arena_icon),
            contentDescription = null,
            modifier = Modifier.size(330.dp),
        )
        Text(
            "SUDOKU ARENA",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ProfileDialog(nickname: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var edited by remember(nickname) { mutableStateOf(nickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar perfil") },
        text = {
            OutlinedTextField(
                value = edited,
                onValueChange = { edited = it.take(20) },
                label = { Text("Nickname") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(edited.trim()) }, enabled = edited.trim().isNotEmpty()) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private enum class ControlIconType { PROFILE, TROPHY }

@Composable
private fun ControlIcon(type: ControlIconType) {
    Canvas(Modifier.size(27.dp)) {
        val color = ArenaColors.ElectricBlue
        if (type == ControlIconType.PROFILE) {
            drawCircle(color, radius = size.minDimension * 0.18f, center = Offset(size.width / 2f, size.height * 0.32f), style = Stroke(2.6f))
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(size.width * 0.19f, size.height * 0.50f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.42f),
                style = Stroke(2.6f),
            )
        } else {
            val cup = Path().apply {
                moveTo(size.width * 0.3f, size.height * 0.18f)
                lineTo(size.width * 0.7f, size.height * 0.18f)
                lineTo(size.width * 0.64f, size.height * 0.55f)
                quadraticTo(size.width * 0.5f, size.height * 0.7f, size.width * 0.36f, size.height * 0.55f)
                close()
            }
            drawPath(cup, color, style = Stroke(2.5f))
            drawLine(color, Offset(size.width * 0.5f, size.height * 0.68f), Offset(size.width * 0.5f, size.height * 0.82f), 2.5f)
            drawLine(color, Offset(size.width * 0.32f, size.height * 0.84f), Offset(size.width * 0.68f, size.height * 0.84f), 2.5f)
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
