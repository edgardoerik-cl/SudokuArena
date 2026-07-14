package com.sudokuarena.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.LeaderboardRepository
import com.sudokuarena.domain.GameType
import kotlin.math.sin

@Composable
fun WelcomeScreen(
    initialNickname: String,
    initialXp: Int,
    selectedGameType: GameType,
    onGameSelected: (GameType) -> Unit,
    leaderboardRepository: LeaderboardRepository,
    onSaveNickname: (String) -> Unit,
    onSoloMode: () -> Unit,
    onDailyChallenge: () -> Unit,
    onMultiplayerMode: () -> Unit,
) {
    var savedNickname by remember(initialNickname) { mutableStateOf(initialNickname.trim()) }
    var firstNickname by remember { mutableStateOf("") }
    var showProfile by remember { mutableStateOf(false) }
    var showHonor by remember { mutableStateOf(false) }
    val hasProfile = savedNickname.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        AnimatedArenaBackground()
        ArenaBrandHeader(Modifier.align(Alignment.TopCenter).padding(top = 28.dp))
        if (!hasProfile) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 90.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Crea tu perfil para entrar a la arena")
                OutlinedTextField(
                    value = firstNickname,
                    onValueChange = { firstNickname = it.take(20) },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                NeonArenaButton(
                    text = "Crear perfil",
                    onClick = {
                        val clean = firstNickname.trim()
                        if (clean.isNotEmpty()) {
                            onSaveNickname(clean)
                            savedNickname = clean
                        }
                    },
                    enabled = firstNickname.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            // Tras crear el perfil, el centro queda reservado únicamente a los modos.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 185.dp, bottom = 88.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("ELIGE TU ARENA", fontWeight = FontWeight.Black, color = ArenaColors.Ink, modifier = Modifier.align(Alignment.CenterHorizontally))
                GameArenaSelector(selectedGameType, onGameSelected)
                NeonArenaButton(
                    text = "Modo Solitario",
                    onClick = onSoloMode,
                    enabled = selectedGameType == GameType.SUDOKU,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                )
                NeonArenaButton(
                    text = "Modo Multijugador",
                    onClick = onMultiplayerMode,
                    secondary = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                )
                NeonArenaButton(
                    text = "Reto Diario · +350 XP",
                    onClick = onDailyChallenge,
                    secondary = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                )
                Text(
                    "NIVEL ${initialXp / 500 + 1}  ·  $initialXp XP",
                    color = ArenaColors.Ink,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(22.dp),
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
private fun ArenaBrandHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArenaLogo(Modifier.size(132.dp))
        Text(
            "SUDOKU ARENA",
            style = MaterialTheme.typography.headlineLarge.copy(
                shadow = Shadow(Color(0x990057D9), blurRadius = 18f),
            ),
            fontWeight = FontWeight.Black,
            color = ArenaColors.Ink,
        )
    }
}

@Composable
private fun AnimatedArenaBackground() {
    val transition = rememberInfiniteTransition(label = "welcomeMotion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "welcomePhase",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFF9FBFF), Color(0xFFE8F0FF), Color(0xFFF1E9FF), Color(0xFFFFFFFF)),
                    start = Offset(phase * 900f, 0f),
                    end = Offset((1f - phase) * 900f, 1_700f),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(24) { index ->
                val depth = 0.35f + (index % 5) * 0.13f
                val baseX = ((index * 73) % 101) / 101f * size.width
                val drift = sin((phase * 6.283f + index) * depth) * (18f + index % 4 * 8f)
                val progress = (((index * 37) % 100) / 100f + phase * (0.10f + depth * 0.10f)) % 1f
                val center = Offset(baseX + drift, size.height * (1f - progress))
                val alpha = 0.05f + depth * 0.09f
                val color = if (index % 2 == 0) ArenaColors.ElectricBlue else ArenaColors.Violet
                if (index % 4 == 0) {
                    val side = 18f + depth * 24f
                    drawRoundRect(
                        color.copy(alpha = alpha),
                        topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                        size = Size(side, side),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
                        style = Stroke(2f),
                    )
                } else if (index % 4 == 1) {
                    drawCircle(color.copy(alpha = alpha), radius = 5f + depth * 10f, center = center)
                    drawLine(color.copy(alpha = alpha), center + Offset(5f, -8f), center + Offset(13f, -17f), 2f)
                } else if (index % 4 == 2) {
                    drawLine(color.copy(alpha = alpha), center + Offset(-10f, 0f), center + Offset(10f, 0f), 3f)
                    drawLine(color.copy(alpha = alpha), center + Offset(0f, -10f), center + Offset(0f, 10f), 3f)
                } else {
                    drawCircle(color.copy(alpha = alpha), radius = 10f + depth * 5f, center = center, style = Stroke(2f))
                    drawCircle(color.copy(alpha = alpha), radius = 2.5f, center = center)
                }
            }
        }
    }
}

@Composable
private fun NeonArenaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = if (secondary) ArenaColors.Violet else ArenaColors.ElectricBlue
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (pressed) 3.dp else 2.dp, accent.copy(alpha = if (pressed) 1f else 0.72f)),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
        modifier = modifier.graphicsLayer {
            scaleX = if (pressed) 0.975f else 1f
            scaleY = scaleX
            shadowElevation = if (pressed) 24f else 12f
        },
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun GameArenaSelector(selected: GameType, onSelected: (GameType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GameType.entries.chunked(2).forEach { rowGames ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowGames.forEach { game ->
                    val active = selected == game
                    Surface(
                        color = if (active) ArenaColors.ElectricBlue.copy(alpha = .16f) else Color.White.copy(alpha = .86f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .border(if (active) 2.dp else 1.dp, if (active) ArenaColors.ElectricBlue else Color(0xFFCBD5E1), RoundedCornerShape(16.dp))
                            .clickable { onSelected(game) },
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(gameGlyph(game), fontSize = 20.sp)
                            Text(gameMenuName(game), fontWeight = if (active) FontWeight.Black else FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun gameGlyph(game: GameType): String = when (game) {
    GameType.SUDOKU -> "9"; GameType.MINESWEEPER -> "✹"; GameType.WORD_SEARCH -> "A↗"; GameType.CROSSWORD -> "✚"
    GameType.NONOGRAM -> "▦"; GameType.DOTS_AND_BOXES -> "□"; GameType.KAKURO -> "Σ"; GameType.MATHDOKU -> "×"
    GameType.HITORI -> "◼"; GameType.RUMMIKUB -> "123"
}

private fun gameMenuName(game: GameType): String = when (game) {
    GameType.SUDOKU -> "Sudoku"; GameType.MINESWEEPER -> "Buscaminas"; GameType.WORD_SEARCH -> "Sopa Letras"
    GameType.CROSSWORD -> "Crucigrama"; GameType.NONOGRAM -> "Nonogram"; GameType.DOTS_AND_BOXES -> "Timbiriche"
    GameType.KAKURO -> "Kakuro"; GameType.MATHDOKU -> "Mathdoku"; GameType.HITORI -> "Hitori"; GameType.RUMMIKUB -> "Rummikub"
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
