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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val haptics = LocalHapticFeedback.current
    val impactFlash = remember(me?.id) { Animatable(0f) }
    var previousImpact by remember(me?.id) { mutableIntStateOf(me?.impact ?: 0) }
    LaunchedEffect(me?.impact) {
        val impact = me?.impact ?: return@LaunchedEffect
        if (impact > previousImpact) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            impactFlash.snapTo(1f)
            impactFlash.animateTo(0f, tween(360))
        }
        previousImpact = impact
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF071225)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ArcadeTopBar("Tetris Arena", "Líneas ${me?.lines ?: 0} · ${me?.score ?: 0} pts", onPause, onExit)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.weight(1f).fillMaxSize(), color = Color(0xFF020617), shape = RoundedCornerShape(16.dp)) {
                Canvas(
                    Modifier.fillMaxSize().padding(7.dp)
                        .pointerInput(me?.id, me?.gameOver) {
                            if (me?.gameOver == true) return@pointerInput
                            detectTapGestures(onTap = { onInput("ROTATE") })
                        }
                        .pointerInput(me?.id, me?.gameOver) {
                            if (me?.gameOver == true) return@pointerInput
                            var drag = Offset.Zero
                            var horizontalSteps = 0
                            detectDragGestures(
                                onDragStart = { drag = Offset.Zero; horizontalSteps = 0 },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += amount
                                    val cellWidth = size.width / 10f
                                    val requestedSteps = (drag.x / cellWidth).toInt()
                                    while (horizontalSteps < requestedSteps) {
                                        onInput("RIGHT"); horizontalSteps++; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    while (horizontalSteps > requestedSteps) {
                                        onInput("LEFT"); horizontalSteps--; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    if (drag.y > size.height * .08f) {
                                        onInput("SOFT_DROP")
                                        drag = drag.copy(y = 0f)
                                    }
                                },
                                onDragEnd = {
                                    if (drag.y < -size.height * .10f) onInput("HARD_DROP")
                                },
                            )
                        },
                ) {
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
                    if (impactFlash.value > 0f) {
                        drawRect(Color.White.copy(alpha = impactFlash.value * .28f))
                        drawRect(Color(0xFFFFC400).copy(alpha = impactFlash.value), style = Stroke(8f))
                    }
                }
            }
            Column(Modifier.weight(.44f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Actual: ${me?.current ?: "—"}", color = Color.White, fontWeight = FontWeight.Black)
                Text("Siguiente: ${me?.next ?: "—"}", color = Color.White, fontWeight = FontWeight.Black)
                Text(
                    "ENERGÍA ${me?.abilityEnergy ?: 0}/8",
                    color = Color(0xFFFFD54F),
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                )
                Button(
                    onClick = { onInput("HOLD") },
                    enabled = me?.canHold == true && (me.abilityEnergy >= 1) && me.gameOver != true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("HOLD · 1 línea\n${me?.hold ?: "—"}", fontSize = 10.sp, fontWeight = FontWeight.Black) }
                Button(
                    onClick = { onInput("CLEAN_BOMB") },
                    enabled = (me?.abilityEnergy ?: 0) >= 4 && me?.gameOver != true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("💣 Limpiar 3 · 4 líneas", fontSize = 9.sp) }
                arena?.players?.filter { it.id != me?.id }?.forEach { rival ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White.copy(alpha = .09f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${rival.name} · ${rival.lines} líneas",
                                color = Color.White,
                                fontSize = 9.sp,
                                maxLines = 1,
                                fontWeight = FontWeight.Bold,
                            )
                            MiniTetrisBoard(rival.board, Modifier.size(64.dp, 104.dp))
                        }
                    }
                }
            }
        }
        Text(
            "Arrastra ↔ para mover · toca para girar · mantén ↓ para bajar · flick ↑ para impacto",
            color = Color.White.copy(alpha = .76f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun MiniTetrisBoard(board: List<List<Int>>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF020617), RoundedCornerShape(6.dp))) {
        val rows = board.size.coerceAtLeast(20)
        val columns = board.firstOrNull()?.size?.coerceAtLeast(10) ?: 10
        val cell = minOf(size.width / columns, size.height / rows)
        val left = (size.width - cell * columns) / 2f
        val palette = listOf(
            Color.Transparent, Color.Cyan, Color(0xFF2962FF), Color(0xFFFF8F00),
            Color.Yellow, Color.Green, Color.Magenta, Color.Red, Color.Gray,
        )
        board.forEachIndexed { row, cells ->
            cells.forEachIndexed { col, value ->
                if (value > 0) {
                    drawRect(
                        palette.getOrElse(value) { Color.White },
                        Offset(left + col * cell + .5f, row * cell + .5f),
                        Size((cell - 1f).coerceAtLeast(.5f), (cell - 1f).coerceAtLeast(.5f)),
                    )
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
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF02030F), RoundedCornerShape(16.dp))
                .pointerInput(me?.id, me?.lives) {
                    var drag = Offset.Zero
                    detectDragGestures(
                        onDragStart = { drag = Offset.Zero },
                        onDrag = { change, amount -> change.consume(); drag += amount },
                        onDragEnd = {
                            if (drag.getDistance() >= 24f && (me?.lives ?: 0) > 0) {
                                onInput(
                                    if (abs(drag.x) > abs(drag.y)) {
                                        if (drag.x > 0) "RIGHT" else "LEFT"
                                    } else if (drag.y > 0) "DOWN" else "UP",
                                )
                            }
                            drag = Offset.Zero
                        },
                    )
                },
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
            if (arena?.ghosts?.any { it.mode == "FRIGHTENED" } == true) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0x332196F3))
                    drawRect(Color.White.copy(alpha = .18f), style = Stroke(7f))
                }
            }
            if (arena?.status == "WAITING") {
                Surface(
                    color = Color.Black.copy(alpha = .78f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        "DESLIZA PARA COMENZAR",
                        Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        color = Color.Yellow,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
        Text(
            "Desliza en cualquier parte del laberinto · el giro queda preparado para la próxima esquina",
            color = Color.White.copy(alpha = .72f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
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
            player.bricks.forEach { brick ->
                val colors = listOf(Color(0xFF00E5FF), Color(0xFFFF3CAC), Color(0xFF7C4DFF), Color(0xFFFFB300), Color(0xFF00E676))
                val color = colors[abs(brick.color) % colors.size]
                drawRoundRect(color.copy(alpha = if (brick.hp > 1) 1f else .78f), Offset(brick.x * size.width, brick.y * size.height), Size(brick.width * size.width, brick.height * size.height))
                drawRoundRect(Color.White.copy(alpha = .65f), Offset(brick.x * size.width + 2f, brick.y * size.height + 2f), Size(brick.width * size.width - 4f, brick.height * size.height - 4f), style = Stroke(2f))
            }
            val paddleColor = runCatching { Color(android.graphics.Color.parseColor(player.colorHex)) }.getOrDefault(Color.Cyan)
            drawRoundRect(paddleColor, Offset((player.paddleX - .11f) * size.width, .92f * size.height), Size(.22f * size.width, .026f * size.height))
            val balls = player.balls.ifEmpty {
                listOf(com.sudokuarena.domain.DemolitionBallState(
                    "legacy", player.ballX, player.ballY, player.velocityX, player.velocityY,
                ))
            }
            balls.forEach { ball ->
                val bx = (ball.x + ball.vx * elapsed).coerceIn(.02f, .98f) * size.width
                val by = (ball.y + ball.vy * elapsed).coerceIn(.02f, 1.02f) * size.height
                drawCircle(Color.White, size.minDimension * .018f, Offset(bx, by))
                drawCircle(paddleColor.copy(alpha = .32f), size.minDimension * .032f, Offset(bx, by))
            }
            player.drops.forEach { drop ->
                val color = when (drop.type) {
                    "MULTIBALL" -> Color.Cyan
                    "LASER" -> Color.Red
                    else -> Color.Yellow
                }
                drawCircle(color, size.minDimension * .025f, Offset(drop.x * size.width, drop.y * size.height))
                drawCircle(Color.White, size.minDimension * .029f, Offset(drop.x * size.width, drop.y * size.height), style = Stroke(2f))
            }
            if (player.laserUntil > (arena?.serverTime ?: 0L)) {
                drawLine(Color.Red, Offset((player.paddleX - .06f) * size.width, .91f * size.height), Offset((player.paddleX - .06f) * size.width, 0f), 3f)
                drawLine(Color.Red, Offset((player.paddleX + .06f) * size.width, .91f * size.height), Offset((player.paddleX + .06f) * size.width, 0f), 3f)
            }
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
