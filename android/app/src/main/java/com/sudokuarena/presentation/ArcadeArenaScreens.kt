package com.sudokuarena.presentation

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.sudokuarena.domain.PacmanActorState
import kotlin.math.roundToInt
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
    val motionPhase by rememberInfiniteTransition(label = "pacmanMotion").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Restart),
        label = "chomp",
    )
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
                                key in (arena?.powerPills ?: emptySet()) -> {
                                    val radius = px * (.13f + abs(sin(motionPhase * Math.PI.toFloat() * 2f)) * .10f)
                                    drawCircle(Color.White.copy(alpha = .42f), radius * 1.8f, Offset((col + .5f) * px, (row + .5f) * py))
                                    drawCircle(Color.White, radius, Offset((col + .5f) * px, (row + .5f) * py))
                                }
                                key in (arena?.pills ?: emptySet()) -> drawCircle(Color(0xFFFFE082), px * .07f, Offset((col + .5f) * px, (row + .5f) * py))
                            }
                        }
                    } }
                }
                arena?.players?.forEach { actor -> AnimatedPacActor(actor, tilePx, tile, true, motionPhase) }
                arena?.ghosts?.forEach { actor -> AnimatedPacActor(actor, tilePx, tile, false, motionPhase) }
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
private fun AnimatedPacActor(
    actor: PacmanActorState,
    tilePx: Float,
    tileDp: androidx.compose.ui.unit.Dp,
    pacman: Boolean,
    phase: Float,
) {
    val target = Offset(actor.x * tilePx, actor.y * tilePx)
    val animated by animateOffsetAsState(target, spring(stiffness = 520f, dampingRatio = .82f), label = "actor-${actor.id}")
    val color = if (pacman) parseArcadeColor(actor.colorHex, Color.Yellow)
    else when (actor.mode) {
        "FRIGHTENED" -> Color(0xFF2962FF)
        "EATEN" -> Color.White
        else -> listOf(Color.Red, Color.Magenta, Color.Cyan, Color(0xFFFF8F00))[kotlin.math.abs(actor.id.hashCode()) % 4]
    }
    Canvas(
        Modifier
            .size(tileDp * .82f)
            .offset { IntOffset((animated.x + tilePx * .09f).roundToInt(), (animated.y + tilePx * .09f).roundToInt()) }
            .graphicsLayer {
                val bounce = sin((phase + (kotlin.math.abs(actor.id.hashCode()) % 5) * .11f) * Math.PI.toFloat() * 2f)
                translationY = bounce * if (pacman) 1.5f else 3f
                scaleX = if (actor.mode == "FRIGHTENED") .92f + abs(bounce) * .08f else 1f
                scaleY = scaleX
            },
    ) {
        if (pacman) {
            val mouth = 8f + abs(sin(phase * Math.PI.toFloat() * 2f)) * 31f
            val facing = when (actor.direction) {
                "DOWN" -> 90f
                "LEFT" -> 180f
                "UP" -> 270f
                else -> 0f
            }
            drawArc(color, facing + mouth, 360f - mouth * 2f, true)
            drawCircle(Color.Black, size.minDimension * .045f, Offset(size.width * .57f, size.height * .27f))
        } else {
            val bodyTop = size.height * .08f
            val bodyBottom = size.height * .84f
            val path = Path().apply {
                moveTo(size.width * .08f, bodyBottom)
                lineTo(size.width * .08f, size.height * .45f)
                cubicTo(size.width * .08f, bodyTop, size.width * .92f, bodyTop, size.width * .92f, size.height * .45f)
                lineTo(size.width * .92f, bodyBottom)
                val wave = size.width / 4f
                for (index in 4 downTo 0) {
                    val x = index * wave
                    val y = if ((index + (phase * 4).toInt()) % 2 == 0) bodyBottom else size.height * .96f
                    lineTo(x, y)
                }
                close()
            }
            if (actor.mode != "EATEN") drawPath(path, color)
            val eyeY = size.height * .43f
            for (eyeX in listOf(size.width * .36f, size.width * .66f)) {
                drawCircle(Color.White, size.minDimension * .12f, Offset(eyeX, eyeY))
                val look = when (actor.direction) {
                    "LEFT" -> Offset(-size.width * .035f, 0f)
                    "RIGHT" -> Offset(size.width * .035f, 0f)
                    "UP" -> Offset(0f, -size.height * .035f)
                    else -> Offset(0f, size.height * .035f)
                }
                drawCircle(Color(0xFF0D47A1), size.minDimension * .055f, Offset(eyeX, eyeY) + look)
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

private fun parseArcadeColor(value: String?, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(fallback)
