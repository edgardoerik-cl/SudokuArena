package com.sudokuarena.data.local

import com.sudokuarena.domain.DemolitionArenaState
import com.sudokuarena.domain.DemolitionBrickState
import com.sudokuarena.domain.DemolitionPlayerState
import com.sudokuarena.domain.PacmanActorState
import com.sudokuarena.domain.PacmanArenaState
import com.sudokuarena.domain.TetrisArenaState
import com.sudokuarena.domain.TetrisPlayerState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/** Motores offline pequeños. No conocen Socket.IO y conservan el mismo DTO que la red. */
interface LocalRealtimeGameEngine<Input, State> {
    fun input(value: Input)
    fun tick()
    fun snapshot(): State
}

class LocalTetrisEngine(
    private val playerName: String,
    seed: Int = 17,
) : LocalRealtimeGameEngine<String, TetrisArenaState> {
    private val random = Random(seed)
    private var board = MutableList(20) { MutableList(10) { 0 } }
    private var x = 4
    private var y = 0
    private var score = 0
    private var lines = 0
    private var tick = 0L
    private var gameOver = false
    private var impact = 0

    override fun input(value: String) {
        if (gameOver) return
        when (value) {
            "LEFT" -> if (canPlace(x - 1, y)) x--
            "RIGHT" -> if (canPlace(x + 1, y)) x++
            "SOFT_DROP" -> step()
            "HARD_DROP" -> {
                while (canPlace(x, y + 1)) y++
                impact++
                lock()
            }
            "ROTATE" -> score += 1 // La ficha inicial es un bloque 2x2 simétrico.
        }
    }

    override fun tick() {
        if (!gameOver) step()
        tick++
    }

    override fun snapshot(): TetrisArenaState {
        val visible = board.map { it.toMutableList() }.toMutableList()
        if (!gameOver) for (dy in 0..1) for (dx in 0..1) {
            visible.getOrNull(y + dy)?.let { row -> if (x + dx in row.indices) row[x + dx] = 1 }
        }
        return TetrisArenaState(
            serverTime = System.currentTimeMillis(),
            tick = tick,
            completed = gameOver,
            players = listOf(TetrisPlayerState("solo", playerName, "#00E5FF", visible, "O", score, lines, gameOver, impact)),
        )
    }

    private fun step() {
        if (canPlace(x, y + 1)) y++ else lock()
    }

    private fun canPlace(targetX: Int, targetY: Int): Boolean =
        (0..1).all { dy -> (0..1).all { dx ->
            targetY + dy in board.indices && targetX + dx in 0 until 10 && board[targetY + dy][targetX + dx] == 0
        } }

    private fun lock() {
        for (dy in 0..1) for (dx in 0..1) {
            if (y + dy !in board.indices) { gameOver = true; return }
            board[y + dy][x + dx] = 1
        }
        val remaining = board.filterNot { row -> row.all { it != 0 } }
        val removed = 20 - remaining.size
        if (removed > 0) {
            lines += removed
            score += removed * removed * 100
            board = (MutableList(removed) { MutableList(10) { 0 } } + remaining.map { it.toMutableList() }).toMutableList()
        }
        x = random.nextInt(0, 9)
        y = 0
        if (!canPlace(x, y)) gameOver = true
    }
}

class LocalPacmanEngine(private val playerName: String) : LocalRealtimeGameEngine<String, PacmanArenaState> {
    private val map = List(15) { row -> List(15) { col ->
        if (row == 0 || col == 0 || row == 14 || col == 14 || (row % 4 == 0 && col in 3..11 && col != 7)) 0 else 1
    } }
    private val pills = buildSet {
        map.forEachIndexed { row, cells -> cells.forEachIndexed { col, value ->
            if (value == 1 && !(row == 1 && col == 1)) add("$col:$row")
        } }
    }.toMutableSet()
    private var x = 1
    private var y = 1
    private var direction = "STOP"
    private var queuedDirection: String? = null
    private var lives = 3
    private var score = 0
    private var tick = 0L
    private val ghosts = mutableListOf(13 to 13, 13 to 1, 1 to 13, 7 to 7)

    override fun input(value: String) {
        if (value in setOf("UP", "RIGHT", "DOWN", "LEFT")) queuedDirection = value
    }

    override fun tick() {
        queuedDirection?.let { queued ->
            val (qy, qx) = vector(queued)
            if (map.getOrNull(y + qy)?.getOrNull(x + qx) == 1) {
                direction = queued
                queuedDirection = null
            }
        }
        val (dy, dx) = vector(direction)
        if (map.getOrNull(y + dy)?.getOrNull(x + dx) == 1) { y += dy; x += dx }
        if (pills.remove("$x:$y")) score += 10
        if (tick % 5L != 4L) {
            ghosts.indices.forEach { index ->
                val ghost = ghosts[index]
                val candidates = listOf("UP", "RIGHT", "DOWN", "LEFT").map { vector(it) }
                    .map { (gy, gx) -> ghost.first + gx to ghost.second + gy }
                    .filter { (gx, gy) -> map.getOrNull(gy)?.getOrNull(gx) == 1 }
                ghosts[index] = candidates.minByOrNull { (gx, gy) -> abs(gx - x) + abs(gy - y) } ?: ghost
            }
        }
        if (ghosts.any { it.first == x && it.second == y }) {
            lives--
            x = 1; y = 1
        }
        tick++
    }

    override fun snapshot() = PacmanArenaState(
        serverTime = System.currentTimeMillis(),
        tick = tick,
        completed = pills.isEmpty() || lives <= 0,
        tilemap = map,
        pills = pills.toSet(),
        powerPills = emptySet(),
        players = listOf(PacmanActorState("solo", x.toFloat(), y.toFloat(), direction, lives, score, "#FFD600", playerName)),
        ghosts = ghosts.mapIndexed { index, point ->
            PacmanActorState("ghost-$index", point.first.toFloat(), point.second.toFloat(), "LEFT", mode = "CHASE")
        },
    )

    private fun vector(value: String): Pair<Int, Int> = when (value) {
        "UP" -> -1 to 0; "RIGHT" -> 0 to 1; "DOWN" -> 1 to 0; "LEFT" -> 0 to -1; else -> 0 to 0
    }
}

class LocalDemolitionEngine(private val playerName: String) : LocalRealtimeGameEngine<Float, DemolitionArenaState> {
    private var paddleX = .5f
    private var ballX = .5f
    private var ballY = .78f
    private var vx = .34f
    private var vy = -.48f
    private var lives = 3
    private var score = 0
    private var level = 1
    private var tick = 0L
    private var bricks = generateLevel(level).toMutableList()

    override fun input(value: Float) { paddleX = value.coerceIn(.11f, .89f) }

    override fun tick() = tick(1f / 60f)

    fun tick(dt: Float) {
        if (lives <= 0) return
        val radius = .014f
        var nx = ballX + vx * dt
        var ny = ballY + vy * dt
        if (nx - radius <= 0f || nx + radius >= 1f) { vx *= -1; nx = nx.coerceIn(radius, 1f - radius) }
        if (ny - radius <= 0f) { vy = abs(vy); ny = radius }
        if (vy > 0 && ny + radius >= .91f && ny - radius <= .935f && abs(nx - paddleX) <= .115f) {
            val relative = ((nx - paddleX) / .105f).coerceIn(-1f, 1f)
            val speed = hypot(vx, vy).coerceAtMost(.78f)
            vx = relative * speed * .78f
            vy = -sqrt((speed * speed - vx * vx).coerceAtLeast(.08f))
            ny = .895f
        }
        val hit = bricks.firstOrNull { brick ->
            nx + radius >= brick.x && nx - radius <= brick.x + brick.width &&
                ny + radius >= brick.y && ny - radius <= brick.y + brick.height
        }
        if (hit != null) {
            vy *= -1
            bricks.remove(hit)
            score += 10 * level
        }
        ballX = nx; ballY = ny
        if (ballY - radius > 1f) {
            lives--
            resetBall()
        } else if (bricks.isEmpty()) {
            level++
            score += 250
            bricks = generateLevel(level).toMutableList()
            resetBall()
        }
        tick++
    }

    override fun snapshot() = DemolitionArenaState(
        serverTime = System.currentTimeMillis(),
        tick = tick,
        completed = lives <= 0,
        players = listOf(
            DemolitionPlayerState(
                "solo", playerName, "#00E5FF", paddleX, ballX, ballY, vx, vy,
                lives, score, level, bricks.toList(),
            ),
        ),
    )

    private fun resetBall() {
        ballX = paddleX; ballY = .82f; vx = if (level % 2 == 0) -.34f else .34f; vy = -.5f
    }

    private fun generateLevel(level: Int): List<DemolitionBrickState> {
        val rows = (4 + level / 2).coerceAtMost(8)
        return buildList {
            for (row in 0 until rows) for (col in 0 until 8) {
                if ((row * 3 + col + level) % 7 == 0) continue
                add(DemolitionBrickState("L$level-$row-$col", .06f + col * .113f, .08f + row * .058f, .101f, .045f, 1, row + level))
            }
        }
    }
}
