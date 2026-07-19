package com.sudokuarena.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import com.sudokuarena.domain.AbyssActor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun AbyssRaycastScreen(
    state: ArenaUiState,
    onInput: (Float, Float, Float, Float, Boolean) -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    val snapshot = state.abyssState
    val local = snapshot?.actors?.firstOrNull { it.id == state.playerId }
    var movement by remember { mutableStateOf(Offset.Zero) }
    var turning by remember { mutableStateOf(0f) }
    var casting by remember { mutableStateOf(false) }
    fun send() = onInput(movement.x, movement.y, turning, 0f, casting)

    Scaffold(topBar = {
        PinnedGameHeader(
            title = "Abismo Arena 3D",
            subtitle = "RPG cooperativo · derrota al Guardián y encuentra la salida",
            state = state, onTutorial = {}, onPause = onPause, onExit = { confirmExit = true },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF091126), Color(0xFF19113A))), size = Size(size.width, size.height / 2f))
                drawRect(Brush.verticalGradient(listOf(Color(0xFF21182A), Color(0xFF050508))), topLeft = Offset(0f, size.height / 2f))
                if (snapshot != null && local != null && snapshot.maze.isNotEmpty()) {
                    renderRaycast(snapshot.maze, local, snapshot.actors.filter { it.id != local.id }, snapshot.exitX, snapshot.exitY)
                }
                drawCircle(Color(0xAA00E5FF), 4f, center)
                drawLine(Color(0xAA00E5FF), center - Offset(10f, 0f), center + Offset(10f, 0f), 2f)
                drawLine(Color(0xAA00E5FF), center - Offset(0f, 10f), center + Offset(0f, 10f), 2f)
            }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            val length = hypot(delta.x, delta.y).coerceAtLeast(1f)
                            movement = Offset(delta.x / length, delta.y / length)
                            send()
                        },
                        onDragEnd = { movement = Offset.Zero; send() },
                    )
                })
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { casting = true; send() },
                        onDrag = { change, delta ->
                            change.consume(); turning = (delta.x / 80f).coerceIn(-1f, 1f); casting = true; send()
                        },
                        onDragEnd = { turning = 0f; casting = false; send() },
                    )
                })
            }
            ConfirmExitDialog(confirmExit, { confirmExit = false }, onExit)
        }
    }
}

private fun DrawScope.renderRaycast(
    maze: List<List<Int>>,
    player: AbyssActor,
    actors: List<AbyssActor>,
    exitX: Float,
    exitY: Float,
) {
    val rotation = atan2(player.facingY, player.facingX)
    val fov = (PI / 3.0).toFloat()
    val rays = (size.width / 3f).toInt().coerceAtLeast(120)
    val depths = FloatArray(rays)
    for (ray in 0 until rays) {
        val angle = rotation - fov / 2f + fov * ray / rays
        val distance = castDda(maze, player.x, player.y, angle)
        depths[ray] = distance
        val corrected = distance * cos(angle - rotation)
        val wallHeight = (size.height / corrected.coerceAtLeast(.08f)).coerceAtMost(size.height * 1.4f)
        val shade = (1f - corrected / 18f).coerceIn(.12f, 1f)
        val columnWidth = size.width / rays + 1f
        drawRect(
            Color(0xFF6D5DFB).copy(alpha = shade),
            Offset(ray * size.width / rays, size.height / 2f - wallHeight / 2f),
            Size(columnWidth, wallHeight),
        )
    }
    val sprites = actors + AbyssActor(
        id = "EXIT", kind = "BOSS", x = exitX, y = exitY, hp = 1f, maxHp = 1f,
        colorHex = "#00FF99", name = "SALIDA", weapon = "SWORD",
    )
    sprites.map { actor ->
        val dx = actor.x - player.x; val dy = actor.y - player.y
        val distance = hypot(dx, dy)
        var relative = atan2(dy, dx) - rotation
        while (relative > PI) relative -= (PI * 2).toFloat()
        while (relative < -PI) relative += (PI * 2).toFloat()
        Triple(actor, distance, relative)
    }.filter { abs(it.third) < fov * .65f }.sortedByDescending { it.second }.forEach { (actor, distance, relative) ->
        val screenX = size.width * (.5f + relative / fov)
        val spriteSize = (size.height / distance.coerceAtLeast(.4f) * if (actor.id == "EXIT") .55f else .8f).coerceAtMost(size.height)
        val rayIndex = (screenX / size.width * rays).toInt().coerceIn(0, rays - 1)
        if (distance < depths[rayIndex] + .3f) {
            val color = if (actor.id == "EXIT") Color(0xFF00FF99) else if (actor.kind == "BOSS") Color(0xFFFF3D71) else Color(0xFF00E5FF)
            drawCircle(color.copy(alpha = .22f), spriteSize * .52f, Offset(screenX, size.height / 2f))
            drawCircle(color, spriteSize * .24f, Offset(screenX, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
        }
    }
}

private fun castDda(maze: List<List<Int>>, x: Float, y: Float, angle: Float): Float {
    val step = .035f
    val dx = cos(angle) * step; val dy = sin(angle) * step
    var px = x; var py = y; var distance = 0f
    while (distance < 24f) {
        px += dx; py += dy; distance += step
        val row = floor(py).toInt(); val col = floor(px).toInt()
        if (row !in maze.indices || col !in maze[row].indices || maze[row][col] != 0) return distance
    }
    return 24f
}
