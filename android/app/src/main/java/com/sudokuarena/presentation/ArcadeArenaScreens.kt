package com.sudokuarena.presentation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.sin

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
    val hitShake = remember(me?.id) { androidx.compose.animation.core.Animatable(0f) }
    val scorePulse = remember(me?.id) { androidx.compose.animation.core.Animatable(0f) }
    var previousLives by remember(me?.id) { mutableIntStateOf(me?.lives ?: 3) }
    var previousScore by remember(me?.id) { mutableIntStateOf(me?.score ?: 0) }
    LaunchedEffect(me?.lives) {
        val lives = me?.lives ?: return@LaunchedEffect
        if (lives < previousLives) {
            hitShake.snapTo(1f)
            hitShake.animateTo(0f, tween(620))
        }
        previousLives = lives
    }
    LaunchedEffect(me?.score) {
        val score = me?.score ?: return@LaunchedEffect
        if (score > previousScore) {
            scorePulse.snapTo(1f)
            scorePulse.animateTo(0f, tween(430))
        }
        previousScore = score
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
            .graphicsLayer {
                translationX = sin((1f - hitShake.value) * Math.PI.toFloat() * 8f) * hitShake.value * 18f
                scaleX = 1f + scorePulse.value * .012f
                scaleY = scaleX
            },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArcadeTopBar("Pac-Man Arena", "♥ ${me?.lives ?: 3} · ${me?.score ?: 0} pts", onPause, onExit)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF02030F), RoundedCornerShape(16.dp))
                .pointerInput(arena?.tick) {
                    var totalDrag = Offset.Zero
                    detectDragGestures(
                        onDragStart = { totalDrag = Offset.Zero },
                        onDrag = { change, amount ->
                            change.consume()
                            totalDrag += amount
                        },
                        onDragEnd = {
                            if (totalDrag.getDistance() < 24f) return@detectDragGestures
                            onInput(
                                if (abs(totalDrag.x) > abs(totalDrag.y)) {
                                    if (totalDrag.x > 0f) "RIGHT" else "LEFT"
                                } else if (totalDrag.y > 0f) "DOWN" else "UP",
                            )
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val map = arena?.tilemap.orEmpty()
            val columns = map.firstOrNull()?.size?.coerceAtLeast(1) ?: 15
            val rows = map.size.coerceAtLeast(1)
            val tile = minOf(maxWidth / columns, maxHeight / rows)
            val boardWidth = tile * columns
            val boardHeight = tile * rows
            AndroidView(
                factory = { context ->
                    PacmanSurfaceView(context).apply {
                        onDirection = onInput
                        updateState(arena)
                    }
                },
                update = { view ->
                    view.onDirection = onInput
                    view.updateState(arena)
                },
                onRelease = PacmanSurfaceView::stopRenderer,
                modifier = Modifier.size(boardWidth, boardHeight),
            )
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
