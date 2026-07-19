package com.sudokuarena.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TetrisArenaScreen(state: ArenaUiState, onInput: (String) -> Unit, onPause: () -> Unit, onExit: () -> Unit) {
    val arena = state.tetrisState
    val me = arena?.players?.firstOrNull { it.id == state.playerId } ?: arena?.players?.firstOrNull()
    Column(
        Modifier.fillMaxSize().background(Color(0xFF071225)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArcadeTopBar("Tetris Arena", "Líneas ${me?.lines ?: 0} · ${me?.score ?: 0} pts", onPause, onExit)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.weight(1f).fillMaxSize(), color = Color(0xFF020617), shape = RoundedCornerShape(16.dp)) {
                Canvas(Modifier.fillMaxSize().padding(7.dp)) {
                    val board = me?.board ?: List(20) { List(10) { 0 } }
                    val cell = minOf(size.width / 10f, size.height / 20f)
                    val left = (size.width - cell * 10) / 2f
                    board.forEachIndexed { row, values -> values.forEachIndexed { col, value ->
                        val origin = Offset(left + col * cell, row * cell)
                        drawRect(Color.White.copy(alpha = .06f), origin, Size(cell - 1f, cell - 1f))
                        if (value > 0) {
                            val colors = listOf(Color.Transparent, Color.Cyan, Color(0xFF2962FF), Color(0xFFFF8F00), Color.Yellow, Color.Green, Color.Magenta, Color.Red, Color.Gray)
                            drawRoundRect(colors.getOrElse(value) { Color.White }, origin + Offset(1.5f, 1.5f), Size(cell - 4f, cell - 4f))
                            drawRoundRect(Color.White.copy(alpha = .55f), origin + Offset(2f, 2f), Size(cell - 5f, cell - 5f), style = Stroke(1.4f))
                        }
                    } }
                }
            }
            Column(Modifier.weight(.44f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Siguiente: ${me?.next ?: "—"}", color = Color.White, fontWeight = FontWeight.Black)
                arena?.players?.filter { it.id != me?.id }?.forEach { rival ->
                    Surface(color = Color.White.copy(alpha = .09f), shape = RoundedCornerShape(10.dp)) {
                        Text("${rival.name}: ${rival.lines}", color = Color.White, modifier = Modifier.padding(7.dp), fontSize = 11.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            VirtualJoystick(
                enabled = me?.gameOver != true,
                modifier = Modifier.size(112.dp),
                onDirection = { direction ->
                    when (direction) {
                        "LEFT", "RIGHT" -> onInput(direction)
                        "DOWN" -> onInput("SOFT_DROP")
                    }
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                Button(onClick = { onInput("ROTATE") }, enabled = me?.gameOver != true, modifier = Modifier.size(width = 132.dp, height = 52.dp)) {
                    Text("↻  GIRAR", fontWeight = FontWeight.Black)
                }
                Button(onClick = { onInput("HARD_DROP") }, enabled = me?.gameOver != true, modifier = Modifier.size(width = 132.dp, height = 58.dp)) {
                    Text("⇊  CAÍDA", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
fun PacmanArenaScreen(state: ArenaUiState, onInput: (String) -> Unit, onPause: () -> Unit, onExit: () -> Unit) {
    val arena = state.pacmanState
    val me = arena?.players?.firstOrNull { it.id == state.playerId } ?: arena?.players?.firstOrNull()
    val hitShake = remember(me?.id) { Animatable(0f) }
    val scorePulse = remember(me?.id) { Animatable(0f) }
    var previousLives by remember(me?.id) { mutableIntStateOf(me?.lives ?: 3) }
    var previousScore by remember(me?.id) { mutableIntStateOf(me?.score ?: 0) }
    LaunchedEffect(me?.lives) {
        val lives = me?.lives ?: return@LaunchedEffect
        if (lives < previousLives) { hitShake.snapTo(1f); hitShake.animateTo(0f, tween(620)) }
        previousLives = lives
    }
    LaunchedEffect(me?.score) {
        val score = me?.score ?: return@LaunchedEffect
        if (score > previousScore) { scorePulse.snapTo(1f); scorePulse.animateTo(0f, tween(430)) }
        previousScore = score
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(8.dp).graphicsLayer {
            translationX = sin((1f - hitShake.value) * Math.PI.toFloat() * 8f) * hitShake.value * 18f
            scaleX = 1f + scorePulse.value * .012f; scaleY = scaleX
        },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArcadeTopBar("Pac-Man Arena", "♥ ${me?.lives ?: 3} · ${me?.score ?: 0} pts", onPause, onExit)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF02030F), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val map = arena?.tilemap.orEmpty()
            val columns = map.firstOrNull()?.size?.coerceAtLeast(1) ?: 15
            val rows = map.size.coerceAtLeast(1)
            val tile = minOf(maxWidth / columns, maxHeight / rows)
            AndroidView(
                factory = { PacmanSurfaceView(it).apply { updateState(arena) } },
                update = { it.updateState(arena) },
                onRelease = PacmanSurfaceView::stopRenderer,
                modifier = Modifier.size(tile * columns, tile * rows),
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            VirtualJoystick(enabled = me?.lives?.let { it > 0 } == true, onDirection = onInput, modifier = Modifier.size(118.dp))
            Text("Arrastra el joystick", color = Color.White.copy(alpha = .6f), fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun DemolitionArenaScreen(state: ArenaUiState, onPaddle: (Float) -> Unit, onPause: () -> Unit, onExit: () -> Unit) {
    val arena = state.demolitionState
    val me = arena?.players?.firstOrNull { it.id == state.playerId } ?: arena?.players?.firstOrNull()
    val frame by rememberInfiniteTransition(label = "demolitionFrame").animateFloat(
        0f, 1f, infiniteRepeatable(tween(16), RepeatMode.Restart), label = "demolitionClock",
    )
    Column(Modifier.fillMaxSize().background(Color(0xFF050817)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ArcadeTopBar("Demolición Arcade", "♥ ${me?.lives ?: 3} · Nivel ${me?.level ?: 1} · ${me?.score ?: 0} pts", onPause, onExit)
        Canvas(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF090F2D), RoundedCornerShape(18.dp))
                .pointerInput(me?.id) {
                    detectDragGestures(
                        onDragStart = { onPaddle((it.x / size.width).coerceIn(0f, 1f)) },
                        onDrag = { change, _ -> change.consume(); onPaddle((change.position.x / size.width).coerceIn(0f, 1f)) },
                    )
                },
        ) {
            frame // fuerza interpolación visual entre snapshots
            val player = me ?: return@Canvas
            val elapsed = ((System.currentTimeMillis() - (arena?.serverTime ?: 0L)).coerceIn(0L, 90L)) / 1000f
            val bx = (player.ballX + player.velocityX * elapsed).coerceIn(.02f, .98f) * size.width
            val by = (player.ballY + player.velocityY * elapsed).coerceIn(.02f, 1.02f) * size.height
            player.bricks.forEach { brick ->
                val colors = listOf(Color(0xFF00E5FF), Color(0xFFFF3CAC), Color(0xFF7C4DFF), Color(0xFFFFB300), Color(0xFF00E676))
                val color = colors[abs(brick.color) % colors.size]
                drawRoundRect(color.copy(alpha = if (brick.hp > 1) 1f else .78f), Offset(brick.x * size.width, brick.y * size.height), Size(brick.width * size.width, brick.height * size.height))
                drawRoundRect(Color.White.copy(alpha = .65f), Offset(brick.x * size.width + 2f, brick.y * size.height + 2f), Size(brick.width * size.width - 4f, brick.height * size.height - 4f), style = Stroke(2f))
            }
            val paddleColor = runCatching { Color(android.graphics.Color.parseColor(player.colorHex)) }.getOrDefault(Color.Cyan)
            drawRoundRect(paddleColor, Offset((player.paddleX - .11f) * size.width, .92f * size.height), Size(.22f * size.width, .026f * size.height))
            drawCircle(Color.White, size.minDimension * .018f, Offset(bx, by))
            drawCircle(paddleColor.copy(alpha = .32f), size.minDimension * .032f, Offset(bx, by))
        }
        Text("Desliza horizontalmente para mover la plataforma", color = Color(0xFFB8C7E8), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun VirtualJoystick(enabled: Boolean, onDirection: (String) -> Unit, modifier: Modifier = Modifier) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var held by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(held, enabled) {
        while (enabled && held != null) {
            onDirection(held!!)
            delay(95)
        }
    }
    Canvas(
        modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectDragGestures(
                onDragStart = { knob = it - Offset(size.width / 2f, size.height / 2f) },
                onDragEnd = { knob = Offset.Zero; held = null },
                onDragCancel = { knob = Offset.Zero; held = null },
                onDrag = { change, _ ->
                    change.consume()
                    val raw = change.position - Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) * .31f
                    val length = raw.getDistance().coerceAtLeast(1f)
                    knob = if (length > radius) raw * (radius / length) else raw
                    if (length > radius * .28f) {
                        val angle = atan2(raw.y, raw.x)
                        held = when {
                            abs(cos(angle)) > abs(sin(angle)) -> if (raw.x > 0f) "RIGHT" else "LEFT"
                            raw.y > 0f -> "DOWN"
                            else -> "UP"
                        }
                    }
                },
            )
        },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFF152851), size.minDimension * .48f, center)
        drawCircle(Color.Cyan.copy(alpha = .72f), size.minDimension * .48f, center, style = Stroke(size.minDimension * .035f))
        drawCircle(Color(0xFF7C4DFF), size.minDimension * .23f, center + knob)
        drawCircle(Color.White.copy(alpha = .55f), size.minDimension * .18f, center + knob, style = Stroke(2f))
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
