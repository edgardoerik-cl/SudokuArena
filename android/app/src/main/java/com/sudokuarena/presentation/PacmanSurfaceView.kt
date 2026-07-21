package com.sudokuarena.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.sudokuarena.domain.PacmanActorState
import com.sudokuarena.domain.PacmanArenaState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Renderizador independiente a 60 FPS. Socket.io sigue actualizando el modelo
 * cada 100 ms; este SurfaceView interpola las posiciones sin mutar el estado.
 */
class PacmanSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val displayPositions = mutableMapOf<String, PointF>()
    @Volatile private var arenaState: PacmanArenaState? = null
    @Volatile private var stateReceivedAt: Long = 0
    @Volatile private var running = false
    private var renderThread: Thread? = null

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        isFocusable = true
    }

    fun updateState(state: PacmanArenaState?) {
        arenaState = state
        stateReceivedAt = System.currentTimeMillis()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRenderer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderer()
    }

    fun startRenderer() {
        if (running || !holder.surface.isValid) return
        running = true
        renderThread = Thread(this, "PacmanArenaRenderer").also { it.start() }
    }

    fun stopRenderer() {
        running = false
        renderThread?.interrupt()
        runCatching { renderThread?.join(350) }
        renderThread = null
    }

    override fun run() {
        var previousNanos = System.nanoTime()
        while (running) {
            val frameStart = System.nanoTime()
            val deltaSeconds = ((frameStart - previousNanos) / 1_000_000_000f).coerceIn(0f, .05f)
            previousNanos = frameStart
            val canvas = runCatching { holder.lockCanvas() }.getOrNull()
            if (canvas != null) {
                try {
                    render(canvas, deltaSeconds, frameStart / 1_000_000_000f)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
            val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000L
            val remaining = 16L - elapsedMs
            if (remaining > 0) runCatching { Thread.sleep(remaining) }
        }
    }

    private fun render(canvas: Canvas, deltaSeconds: Float, timeSeconds: Float) {
        canvas.drawColor(Color.rgb(2, 3, 15))
        val state = arenaState ?: return
        val map = state.tilemap
        val rows = map.size.coerceAtLeast(1)
        val columns = map.firstOrNull()?.size?.coerceAtLeast(1) ?: 15
        val tile = min(canvas.width / columns.toFloat(), canvas.height / rows.toFloat())
        val left = (canvas.width - tile * columns) / 2f
        val top = (canvas.height - tile * rows) / 2f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(2f, tile * .11f)
        paint.color = Color.rgb(20, 61, 255)
        map.forEachIndexed { row, values ->
            values.forEachIndexed { col, value ->
                val cell = RectF(left + col * tile, top + row * tile, left + (col + 1) * tile, top + (row + 1) * tile)
                if (value == 0) {
                    canvas.drawRoundRect(cell, tile * .12f, tile * .12f, paint)
                } else if (value == 3) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(18, 8, 46)
                    canvas.drawRect(cell, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(1.5f, tile * .045f)
                    paint.color = Color.rgb(112, 52, 190)
                    canvas.drawLine(cell.left, cell.bottom, cell.right, cell.top, paint)
                } else {
                    val key = "$col:$row"
                    paint.style = Paint.Style.FILL
                    when {
                        key in state.powerPills -> {
                            val pulse = .15f + abs(sin(timeSeconds * 5f)) * .09f
                            paint.color = Color.argb(90, 255, 255, 255)
                            canvas.drawCircle(cell.centerX(), cell.centerY(), tile * pulse * 1.8f, paint)
                            paint.color = Color.WHITE
                            canvas.drawCircle(cell.centerX(), cell.centerY(), tile * pulse, paint)
                        }
                        key in state.pills -> {
                            paint.color = Color.rgb(255, 224, 130)
                            canvas.drawCircle(cell.centerX(), cell.centerY(), tile * .07f, paint)
                        }
                    }
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.rgb(20, 61, 255)
                }
            }
        }

        // Casa central cerrada: los fantasmas comidos esperan aquí y solo los
        // ojos permanecen visibles durante su regeneración.
        val ghostHouse = RectF(left + tile * 6f, top + tile * 7f, left + tile * 9f, top + tile * 8f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(115, 20, 8, 48)
        canvas.drawRoundRect(ghostHouse, tile * .28f, tile * .28f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = tile * .09f
        paint.color = Color.rgb(255, 45, 141)
        canvas.drawRoundRect(ghostHouse, tile * .28f, tile * .28f, paint)
        paint.color = Color.rgb(0, 229, 255)
        canvas.drawLine(left + tile * 7.08f, top + tile * 7f, left + tile * 7.92f, top + tile * 7f, paint)

        val smoothing = 1f - exp(-deltaSeconds * 13f)
        state.players.forEach { drawActor(canvas, it, true, left, top, tile, smoothing, timeSeconds, false) }
        val serverNow = state.serverTime + (System.currentTimeMillis() - stateReceivedAt).coerceAtLeast(0L)
        val warning = state.frightenedUntil > serverNow && state.frightenedUntil - serverNow <= 3_000
        state.ghosts.forEach { drawActor(canvas, it, false, left, top, tile, smoothing, timeSeconds, warning) }
    }

    private fun drawActor(
        canvas: Canvas,
        actor: PacmanActorState,
        pacman: Boolean,
        left: Float,
        top: Float,
        tile: Float,
        smoothing: Float,
        timeSeconds: Float,
        frightenedWarning: Boolean,
    ) {
        val targetX = left + (actor.x + .5f) * tile
        val targetY = top + (actor.y + .5f) * tile
        val display = displayPositions.getOrPut(actor.id) { PointF(targetX, targetY) }
        // El túnel debe teletransportar, no cruzar visualmente todo el mapa.
        if (abs(display.x - targetX) > tile * 5f) display.x = targetX
        else display.x += (targetX - display.x) * smoothing
        display.y += (targetY - display.y) * smoothing
        val radius = tile * .38f

        if (pacman) {
            paint.style = Paint.Style.FILL
            paint.color = parseColor(actor.colorHex, Color.YELLOW)
            val mouth = 8f + abs(sin(timeSeconds * 11f)) * 32f
            val facing = when (actor.direction) {
                "DOWN" -> 90f
                "LEFT" -> 180f
                "UP" -> 270f
                else -> 0f
            }
            canvas.drawArc(
                RectF(display.x - radius, display.y - radius, display.x + radius, display.y + radius),
                facing + mouth,
                360f - mouth * 2f,
                true,
                paint,
            )
            return
        }

        val identityColor = listOf(Color.RED, Color.MAGENTA, Color.CYAN, Color.rgb(255, 143, 0))[abs(actor.id.hashCode()) % 4]
        val ghostColor = when (actor.mode) {
            "FRIGHTENED" -> if (frightenedWarning && (timeSeconds * 7f).toInt() % 2 == 0) identityColor else Color.rgb(41, 98, 255)
            "EATEN" -> Color.TRANSPARENT
            else -> identityColor
        }
        if (actor.mode != "EATEN") {
            paint.color = ghostColor
            paint.style = Paint.Style.FILL
            val path = Path().apply {
                moveTo(display.x - radius, display.y + radius * .8f)
                lineTo(display.x - radius, display.y)
                cubicTo(
                    display.x - radius,
                    display.y - radius * 1.25f,
                    display.x + radius,
                    display.y - radius * 1.25f,
                    display.x + radius,
                    display.y,
                )
                lineTo(display.x + radius, display.y + radius * .8f)
                lineTo(display.x + radius * .5f, display.y + radius * .45f)
                lineTo(display.x, display.y + radius * .8f)
                lineTo(display.x - radius * .5f, display.y + radius * .45f)
                close()
            }
            canvas.drawPath(path, paint)
        }
        val lookX = when (actor.direction) { "LEFT" -> -radius * .10f; "RIGHT" -> radius * .10f; else -> 0f }
        val lookY = when (actor.direction) { "UP" -> -radius * .10f; "DOWN" -> radius * .10f; else -> 0f }
        for (offset in listOf(-radius * .38f, radius * .38f)) {
            paint.color = Color.WHITE
            canvas.drawCircle(display.x + offset, display.y - radius * .18f, radius * .25f, paint)
            paint.color = Color.rgb(13, 71, 161)
            canvas.drawCircle(display.x + offset + lookX, display.y - radius * .18f + lookY, radius * .11f, paint)
        }
    }

    private fun parseColor(value: String?, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)
}
