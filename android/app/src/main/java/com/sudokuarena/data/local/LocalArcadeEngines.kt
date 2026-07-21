package com.sudokuarena.data.local

import com.sudokuarena.domain.DemolitionArenaState
import com.sudokuarena.domain.DemolitionBrickState
import com.sudokuarena.domain.DemolitionBallState
import com.sudokuarena.domain.DemolitionDropState
import com.sudokuarena.domain.DemolitionPlayerState
import com.sudokuarena.domain.PacmanActorState
import com.sudokuarena.domain.PacmanArenaState
import com.sudokuarena.domain.PacmanFruitState
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
    private var bag = mutableListOf<String>()
    private var current = drawPiece()
    private var next = drawPiece()
    private var hold: String? = null
    private var canHold = true
    private var cleanBombUsed = false
    private var abilityEnergy = 0
    private var bombsUsed = 0
    private var rotation = 0
    private var x = 3
    private var y = -1
    private var score = 0
    private var lines = 0
    private var tick = 0L
    private var gameOver = false
    private var impact = 0

    override fun input(value: String) {
        if (gameOver) return
        when (value) {
            "LEFT" -> if (canPlace(x - 1, y, rotation)) x--
            "RIGHT" -> if (canPlace(x + 1, y, rotation)) x++
            "SOFT_DROP" -> step()
            "HARD_DROP" -> {
                while (canPlace(x, y + 1, rotation)) y++
                impact++
                lock()
            }
            "ROTATE" -> {
                val target = (rotation + 1) % rotations().size
                listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1).firstOrNull { (dx, dy) ->
                    canPlace(x + dx, y + dy, target)
                }?.let { (dx, dy) -> x += dx; y += dy; rotation = target }
            }
            "HOLD" -> if (canHold && abilityEnergy >= 1) {
                val previous = hold
                hold = current
                current = previous ?: next.also { next = drawPiece() }
                rotation = 0; x = 3; y = -1; canHold = false
                abilityEnergy--
            }
            "CLEAN_BOMB" -> if (abilityEnergy >= 4) {
                repeat(3) { board.removeAt(board.lastIndex); board.add(0, MutableList(10) { 0 }) }
                cleanBombUsed = true
                bombsUsed++
                abilityEnergy -= 4
                score += 150
            }
        }
    }

    override fun tick() {
        if (!gameOver) step()
        tick++
    }

    override fun snapshot(): TetrisArenaState {
        val visible = board.map { it.toMutableList() }.toMutableList()
        if (!gameOver) cells(x, y, rotation).forEach { (row, col) ->
            visible.getOrNull(row)?.let { target -> if (col in target.indices) target[col] = pieceColor(current) }
        }
        return TetrisArenaState(
            serverTime = System.currentTimeMillis(),
            tick = tick,
            completed = gameOver,
            players = listOf(TetrisPlayerState(
                "solo", playerName, "#00E5FF", visible, next, score, lines, gameOver,
                impact, current, hold, canHold, cleanBombUsed,
                abilityEnergy, bombsUsed, 0,
            )),
        )
    }

    private fun step() {
        if (canPlace(x, y + 1, rotation)) y++ else lock()
    }

    private fun rotations() = PIECES.getValue(current)
    private fun cells(targetX: Int, targetY: Int, targetRotation: Int) =
        PIECES.getValue(current)[targetRotation].map { (row, col) -> targetY + row to targetX + col }
    private fun canPlace(targetX: Int, targetY: Int, targetRotation: Int): Boolean =
        cells(targetX, targetY, targetRotation).all { (row, col) ->
            col in 0 until 10 && row < 20 && (row < 0 || board[row][col] == 0)
        }

    private fun lock() {
        cells(x, y, rotation).forEach { (row, col) ->
            if (row < 0) { gameOver = true; return }
            board[row][col] = pieceColor(current)
        }
        val remaining = board.filterNot { row -> row.all { it != 0 } }
        val removed = 20 - remaining.size
        if (removed > 0) {
            lines += removed
            abilityEnergy = (abilityEnergy + removed).coerceAtMost(8)
            score += removed * removed * 100
            board = (MutableList(removed) { MutableList(10) { 0 } } + remaining.map { it.toMutableList() }).toMutableList()
        }
        current = next
        next = drawPiece()
        rotation = 0
        x = 3
        y = -1
        canHold = true
        if (!canPlace(x, y, rotation)) gameOver = true
    }

    private fun drawPiece(): String {
        if (bag.isEmpty()) bag = PIECES.keys.shuffled(random).toMutableList()
        return bag.removeAt(bag.lastIndex)
    }

    private fun pieceColor(piece: String) = PIECES.keys.indexOf(piece) + 1

    companion object {
        private val PIECES = linkedMapOf(
            "I" to listOf(listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3), listOf(0 to 2, 1 to 2, 2 to 2, 3 to 2)),
            "J" to listOf(listOf(0 to 0, 1 to 0, 1 to 1, 1 to 2), listOf(0 to 1, 0 to 2, 1 to 1, 2 to 1), listOf(1 to 0, 1 to 1, 1 to 2, 2 to 2), listOf(0 to 1, 1 to 1, 2 to 0, 2 to 1)),
            "L" to listOf(listOf(0 to 2, 1 to 0, 1 to 1, 1 to 2), listOf(0 to 1, 1 to 1, 2 to 1, 2 to 2), listOf(1 to 0, 1 to 1, 1 to 2, 2 to 0), listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1)),
            "O" to listOf(listOf(0 to 1, 0 to 2, 1 to 1, 1 to 2)),
            "S" to listOf(listOf(0 to 1, 0 to 2, 1 to 0, 1 to 1), listOf(0 to 1, 1 to 1, 1 to 2, 2 to 2)),
            "T" to listOf(listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2), listOf(0 to 1, 1 to 1, 1 to 2, 2 to 1), listOf(1 to 0, 1 to 1, 1 to 2, 2 to 1), listOf(0 to 1, 1 to 0, 1 to 1, 2 to 1)),
            "Z" to listOf(listOf(0 to 0, 0 to 1, 1 to 1, 1 to 2), listOf(0 to 2, 1 to 1, 1 to 2, 2 to 1)),
        )
    }
}

class LocalPacmanEngine(private val playerName: String) : LocalRealtimeGameEngine<String, PacmanArenaState> {
    private val map = List(15) { row -> List(15) { col ->
        if (row == 7 && (col == 0 || col == 14)) 1
        else if (row == 0 || col == 0 || row == 14 || col == 14 || (row % 4 == 0 && col in 3..11 && col != 7)) 0 else 1
    } }
    private val pills = buildSet {
        map.forEachIndexed { row, cells -> cells.forEachIndexed { col, value ->
            if (value == 1 && !(row == 1 && col == 1)) add("$col:$row")
        } }
    }.toMutableSet()
    private val powerPills = mutableSetOf("1:1", "13:1", "1:13", "13:13")
    private var x = 1
    private var y = 1
    private var direction = "STOP"
    private var queuedDirection: String? = null
    private var lives = 3
    private var score = 0
    private var tick = 0L
    private var started = false
    private var frightenedUntil = 0L
    private var level = 1
    private var fruit: PacmanFruitState? = null
    private val fruitMilestones = mutableSetOf<Int>()
    private val initialPelletCount = pills.size + powerPills.size
    private val ghosts = mutableListOf(13 to 13, 13 to 1, 1 to 13, 7 to 7)
    private val eatenUntil = MutableList(4) { 0L }

    override fun input(value: String) {
        if (value in setOf("UP", "RIGHT", "DOWN", "LEFT")) {
            queuedDirection = value
            started = true
        }
    }

    override fun tick() {
        if (!started) return
        queuedDirection?.let { queued ->
            val (qy, qx) = vector(queued)
            val targetX = if (y == 7 && x + qx < 0) 14 else if (y == 7 && x + qx > 14) 0 else x + qx
            if (map.getOrNull(y + qy)?.getOrNull(targetX) == 1) {
                direction = queued
                queuedDirection = null
            }
        }
        val (dy, dx) = vector(direction)
        val targetX = if (y == 7 && x + dx < 0) 14 else if (y == 7 && x + dx > 14) 0 else x + dx
        if (map.getOrNull(y + dy)?.getOrNull(targetX) == 1) { y += dy; x = targetX }
        if (pills.remove("$x:$y")) score += 10
        if (powerPills.remove("$x:$y")) { score += 50; frightenedUntil = tick + 100 }
        fruit?.let { bonus -> if (bonus.x.toInt() == x && bonus.y.toInt() == y) { score += bonus.points; fruit = null } }
        val remaining = pills.size + powerPills.size
        val milestone = when {
            remaining <= initialPelletCount * .30 -> 70
            remaining <= initialPelletCount * .70 -> 30
            else -> 0
        }
        if (milestone > 0 && fruit == null && fruitMilestones.add(milestone)) {
            fruit = PacmanFruitState(7f, 9f, if (level < 4) "CHERRY" else "STRAWBERRY", if (level < 4) 100 else 300)
        }
        if (tick % 4L != 3L) {
            ghosts.indices.forEach { index ->
                if (eatenUntil[index] > tick) {
                    ghosts[index] = 7 to 7
                    return@forEach
                }
                val ghost = ghosts[index]
                val candidates = listOf("UP", "RIGHT", "DOWN", "LEFT").map { vector(it) }
                    .map { (gy, gx) -> ghost.first + gx to ghost.second + gy }
                    .filter { (gx, gy) -> map.getOrNull(gy)?.getOrNull(gx) == 1 }
                ghosts[index] = if (tick < frightenedUntil) {
                    candidates.maxByOrNull { (gx, gy) -> abs(gx - x) + abs(gy - y) } ?: ghost
                } else candidates.minByOrNull { (gx, gy) -> abs(gx - x) + abs(gy - y) } ?: ghost
            }
        }
        ghosts.forEachIndexed { index, ghost ->
            if (ghost.first != x || ghost.second != y || eatenUntil[index] > tick) return@forEachIndexed
            if (tick < frightenedUntil) {
                score += 200
                ghosts[index] = 7 to 7
                eatenUntil[index] = tick + 300
            } else {
                lives--
                x = 1; y = 1; direction = "STOP"
            }
        }
        tick++
        if (pills.isEmpty() && powerPills.isEmpty() && level < 100) {
            level++; refillLevel(); x = 1; y = 1; direction = "STOP"; started = false
        }
    }

    private fun refillLevel() {
        pills.clear(); powerPills.clear(); fruit = null; fruitMilestones.clear()
        map.forEachIndexed { row, cells -> cells.forEachIndexed { col, value -> if (value == 1) pills += "$col:$row" } }
        listOf("1:1", "13:1", "1:13", "13:13").forEach { pills.remove(it); powerPills += it }
    }

    override fun snapshot() = PacmanArenaState(
        serverTime = System.currentTimeMillis(),
        tick = tick,
        completed = lives <= 0 || level >= 100,
        tilemap = map,
        pills = pills.toSet(),
        powerPills = powerPills.toSet(),
        players = listOf(PacmanActorState("solo", x.toFloat(), y.toFloat(), direction, lives, score, "#FFD600", playerName)),
        ghosts = ghosts.mapIndexed { index, point ->
            PacmanActorState(
                "ghost-$index", point.first.toFloat(), point.second.toFloat(), "LEFT",
                mode = if (eatenUntil[index] > tick) "EATEN" else if (tick < frightenedUntil) "FRIGHTENED" else "CHASE",
            )
        },
        status = if (started) "PLAYING" else "WAITING",
        frightenedUntil = if (frightenedUntil > tick) System.currentTimeMillis() + (frightenedUntil - tick) * 100 else 0,
        level = level,
        fruit = fruit,
    )

    private fun vector(value: String): Pair<Int, Int> = when (value) {
        "UP" -> -1 to 0; "RIGHT" -> 0 to 1; "DOWN" -> 1 to 0; "LEFT" -> 0 to -1; else -> 0 to 0
    }
}

class LocalDemolitionEngine(private val playerName: String, seed: Int = 73) : LocalRealtimeGameEngine<Float, DemolitionArenaState> {
    private val random = Random(seed)
    private data class Ball(val id: String, var x: Float, var y: Float, var vx: Float, var vy: Float)
    private data class Drop(val id: String, var x: Float, var y: Float, val type: String)
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
    private val balls = mutableListOf(Ball("main", ballX, ballY, vx, vy))
    private val drops = mutableListOf<Drop>()
    private var nextSkyDropTick = 480L
    private var wideUntilTick = 0L
    private var missileUntilTick = 0L
    private var missileX = .5f

    override fun input(value: Float) { paddleX = value.coerceIn(.11f, .89f) }

    override fun tick() = tick(1f / 60f)

    fun tick(dt: Float) {
        if (lives <= 0) return
        if (tick >= nextSkyDropTick) {
            val types = listOf("PADDLE_WIDE", "MULTIBALL_2", "MULTIBALL_5", "MULTIBALL_10", "MISSILE")
            drops += Drop("sky-$tick", random.nextFloat() * .84f + .08f, -.04f, types.random(random))
            nextSkyDropTick = tick + 1_200L
        }
        drops.forEach { it.y += dt * .22f }
        val paddleWidth = if (tick < wideUntilTick) .38f else .21f
        val caught = drops.filter { it.y in .88f..1f && abs(it.x - paddleX) <= paddleWidth / 2f + .025f }
        caught.forEach(::applyDrop)
        drops.removeAll { it.y > 1f || it in caught }
        if (tick < missileUntilTick) {
            val victims = bricks.filter { abs(it.x + it.width / 2f - missileX) <= .05f }
            if (victims.isNotEmpty()) { bricks.removeAll(victims.toSet()); score += victims.size * 14 * level }
        }
        val radius = .014f
        balls.toList().forEach { ball ->
            var nx = ball.x + ball.vx * dt; var ny = ball.y + ball.vy * dt
            if (nx - radius <= 0f || nx + radius >= 1f) { ball.vx *= -1; nx = nx.coerceIn(radius, 1f - radius) }
            if (ny - radius <= 0f) { ball.vy = abs(ball.vy); ny = radius }
            if (ball.vy > 0 && ny + radius >= .91f && ny - radius <= .935f && abs(nx - paddleX) <= paddleWidth / 2f + .01f) {
                val relative = ((nx - paddleX) / (paddleWidth / 2f)).coerceIn(-1f, 1f)
                val speed = hypot(ball.vx, ball.vy).coerceAtMost(.85f)
                ball.vx = relative * speed * .78f; ball.vy = -sqrt((speed * speed - ball.vx * ball.vx).coerceAtLeast(.08f)); ny = .895f
            }
            val hit = bricks.firstOrNull { nx + radius >= it.x && nx - radius <= it.x + it.width && ny + radius >= it.y && ny - radius <= it.y + it.height }
            if (hit != null) { ball.vy *= -1; bricks.remove(hit); score += 10 * level }
            ball.x = nx; ball.y = ny
        }
        balls.removeAll { it.y - radius > 1f }
        if (balls.isEmpty()) {
            lives--
            resetBall()
        } else if (bricks.isEmpty()) {
            level++
            score += 250
            bricks = generateLevel(level).toMutableList()
            resetBall()
        }
        balls.firstOrNull()?.let { ballX = it.x; ballY = it.y; vx = it.vx; vy = it.vy }
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
                balls = balls.map { DemolitionBallState(it.id, it.x, it.y, it.vx, it.vy) },
                drops = drops.map { DemolitionDropState(it.id, it.x, it.y, it.type) },
                wideUntil = if (tick < wideUntilTick) System.currentTimeMillis() + (wideUntilTick - tick) * 16 else 0,
                missileUntil = if (tick < missileUntilTick) System.currentTimeMillis() + (missileUntilTick - tick) * 16 else 0,
                missileX = missileX,
            ),
        ),
    )

    private fun resetBall() {
        ballX = paddleX; ballY = .82f; vx = if (level % 2 == 0) -.34f else .34f; vy = -.5f
        balls.clear(); balls += Ball("ball-$tick", ballX, ballY, vx, vy)
    }

    private fun applyDrop(drop: Drop) {
        when (drop.type) {
            "PADDLE_WIDE" -> wideUntilTick = tick + 720
            "MISSILE" -> { missileX = paddleX; missileUntilTick = tick + 75 }
            else -> if (drop.type.startsWith("MULTIBALL")) {
                val multiplier = drop.type.substringAfterLast('_').toIntOrNull() ?: 2
                val source = balls.toList().ifEmpty { listOf(Ball("seed", paddleX, .82f, .34f, -.48f)) }
                val target = (source.size * multiplier).coerceAtMost(20)
                while (balls.size < target) {
                    val base = source[balls.size % source.size]
                    val direction = if (balls.size % 2 == 0) 1f else -1f
                    balls += Ball("multi-$tick-${balls.size}", base.x, base.y, abs(base.vx).coerceAtLeast(.22f) * direction, -abs(base.vy))
                }
            }
        }
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
