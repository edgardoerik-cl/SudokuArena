package com.sudokuarena.presentation

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.sudokuarena.domain.PacmanActorState
import kotlin.math.roundToInt

@Composable
fun TetrisArenaScreen(
    state: ArenaUiState,
    onInput: (String) -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
) {
    val arena = state.tetrisState
    val me = arena?.players?.firstOrNull { it.id == state.playerId } ?: arena?.players?.firstOrNull()
    Column(
        Modifier.fillMaxSize().background(Color(0xFF071225)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArcadeTopBar("Tetris Arena", "Líneas ${me?.lines ?: 0} · ${me?.score ?: 0} pts", onPause, onExit)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxSize(),
                color = Color(0xFF020617),
                shape = RoundedCornerShape(16.dp),
            ) {
                Canvas(Modifier.fillMaxSize().padding(7.dp)) {
                    val board = me?.board ?: List(20) { List(10) { 0 } }
                    val cell = minOf(size.width / 10f, size.height / 20f)
                    val left = (size.width - cell * 10) / 2f
                    board.forEachIndexed { row, values -> values.forEachIndexed { col, value ->
                        val origin = Offset(left + col * cell, row * cell)
                        drawRect(Color.White.copy(alpha = .06f), origin, Size(cell - 1f, cell - 1f))
                        if (value > 0) {
                            val colors = listOf(
                                Color.Transparent, Color.Cyan, Color(0xFF2962FF), Color(0xFFFF8F00),
                                Color.Yellow, Color.Green, Color.Magenta, Color.Red, Color.Gray,
                            )
                            drawRoundRect(colors.getOrElse(value) { Color.White }, origin + Offset(1.5f, 1.5f), Size(cell - 4f, cell - 4f))
                            drawRoundRect(Color.White.copy(alpha = .55f), origin + Offset(2f, 2f), Size(cell - 5f, cell - 5f), style = Stroke(1.4f))
                        }
                    } }
                }
            }
            Column(
                Modifier.weight(.48f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Siguiente: ${me?.next ?: "—"}", color = Color.White, fontWeight = FontWeight.Black)
                arena?.players?.filter { it.id != me?.id }?.forEach { rival ->
                    Surface(color = Color.White.copy(alpha = .09f), shape = RoundedCornerShape(10.dp)) {
                        Text("${rival.name}: ${rival.lines} líneas", color = Color.White, modifier = Modifier.padding(7.dp), fontSize = 12.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("LEFT" to "◀", "ROTATE" to "↻", "SOFT_DROP" to "▼", "RIGHT" to "▶", "HARD_DROP" to "⤓")
                .forEach { (action, glyph) ->
                    Button(onClick = { onInput(action) }, enabled = me?.gameOver != true) { Text(glyph, fontSize = 20.sp) }
                }
        }
    }
}

@Composable
fun PacmanArenaScreen(
    state: ArenaUiState,
    onInput: (String) -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
) {
    val arena = state.pacmanState
    val me = arena?.players?.firstOrNull { it.id == state.playerId } ?: arena?.players?.firstOrNull()
    Column(Modifier.fillMaxSize().background(Color.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        ArcadeTopBar("Pac-Man Arena", "♥ ${me?.lives ?: 3} · ${me?.score ?: 0} pts", onPause, onExit)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF02030F), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val map = arena?.tilemap.orEmpty()
            val columns = map.firstOrNull()?.size?.coerceAtLeast(1) ?: 15
            val rows = map.size.coerceAtLeast(1)
            val tile = minOf(maxWidth / columns, maxHeight / rows)
            val tilePx = with(LocalDensity.current) { tile.toPx() }
            val boardWidth = tile * columns
            val boardHeight = tile * rows
            Box(Modifier.size(boardWidth, boardHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    val px = size.width / columns
                    val py = size.height / rows
                    map.forEachIndexed { row, values -> values.forEachIndexed { col, value ->
                        if (value == 0) {
                            drawRoundRect(Color(0xFF143DFF), Offset(col * px, row * py), Size(px, py), style = Stroke(maxOf(1.5f, px * .12f)))
                        } else {
                            val key = "$col:$row"
                            when {
                                key in (arena?.powerPills ?: emptySet()) -> drawCircle(Color.White, px * .18f, Offset((col + .5f) * px, (row + .5f) * py))
                                key in (arena?.pills ?: emptySet()) -> drawCircle(Color(0xFFFFE082), px * .07f, Offset((col + .5f) * px, (row + .5f) * py))
                            }
                        }
                    } }
                }
                arena?.players?.forEach { actor -> AnimatedPacActor(actor, tilePx, true) }
                arena?.ghosts?.forEach { actor -> AnimatedPacActor(actor, tilePx, false) }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onInput("UP") }) { Text("▲") }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Button(onClick = { onInput("LEFT") }) { Text("◀") }
                Button(onClick = { onInput("DOWN") }) { Text("▼") }
                Button(onClick = { onInput("RIGHT") }) { Text("▶") }
            }
        }
    }
}

@Composable
private fun AnimatedPacActor(actor: PacmanActorState, tilePx: Float, pacman: Boolean) {
    val target = Offset(actor.x * tilePx, actor.y * tilePx)
    val animated by animateOffsetAsState(target, spring(stiffness = 520f, dampingRatio = .82f), label = "actor-${actor.id}")
    val color = if (pacman) parseArcadeColor(actor.colorHex, Color.Yellow)
    else when (actor.mode) {
        "FRIGHTENED" -> Color(0xFF2962FF)
        else -> listOf(Color.Red, Color.Magenta, Color.Cyan, Color(0xFFFF8F00))[kotlin.math.abs(actor.id.hashCode()) % 4]
    }
    Box(
        Modifier
            .size((tilePx * .82f).dp)
            .offset { IntOffset((animated.x + tilePx * .09f).roundToInt(), (animated.y + tilePx * .09f).roundToInt()) }
            .background(color, if (pacman) CircleShape else RoundedCornerShape(45)),
    )
}

@Composable
private fun ArcadeTopBar(title: String, subtitle: String, onPause: () -> Unit, onExit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(subtitle, color = Color(0xFFB8C7E8), fontSize = 12.sp)
        }
        IconButton(onClick = onPause) { Text("⏸", color = Color.Cyan, fontSize = 20.sp) }
        IconButton(onClick = onExit) { Text("✕", color = Color(0xFFFF5577), fontSize = 20.sp) }
    }
}

private fun parseArcadeColor(value: String?, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(fallback)
