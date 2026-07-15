package com.sudokuarena.data.local

import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.GenericBoardState
import com.sudokuarena.domain.GenericCell

data class LocalPuzzleMoveResult(
    val accepted: Boolean,
    val state: GenericBoardState,
    val points: Int = 0,
    val penaltyMs: Long = 0,
    val hitMine: Boolean = false,
    val message: String = "",
)

/**
 * Motor local sin red para las nueve arenas matriciales. Comparte los mismos
 * tableros visibles y reglas esenciales del servidor, por lo que practicar no
 * requiere conexión ni expone respuestas en la UI.
 */
class LocalPuzzleEngine(val gameType: GameType) {
    private val blueprint = createBlueprint(gameType)
    private var board = blueprint.board
    private var revision = 0L
    private val foundWords = mutableSetOf<String>()

    fun snapshot(): GenericBoardState = GenericBoardState(
        gameId = "local-${gameType.name.lowercase()}",
        gameType = gameType,
        revision = revision,
        serverTime = System.currentTimeMillis(),
        rows = board.size,
        columns = board.firstOrNull()?.size ?: 0,
        board = board,
        completed = isComplete(),
        meta = blueprint.meta,
    )

    fun move(row: Int, col: Int, value: Any?): LocalPuzzleMoveResult {
        val cell = board.getOrNull(row)?.getOrNull(col)
            ?: return reject("Movimiento fuera del tablero")
        if (cell.isBlocked || cell.ownerId != null) return reject("Casilla no disponible")

        if (gameType == GameType.MINESWEEPER) {
            val mine = blueprint.answers[row][col] == true
            return if (mine) {
                replace(row, col, cell.copy(value = "MINE", isRevealed = true, isBlocked = true))
                revision++
                LocalPuzzleMoveResult(false, snapshot(), penaltyMs = 5_000, hitMine = true, message = "¡Mina! Pausa de 5 segundos")
            } else {
                replace(row, col, cell.copy(value = adjacentMines(row, col), isRevealed = true, ownerId = SOLO_OWNER))
                accept(10)
            }
        }

        if (gameType == GameType.WORD_SEARCH) {
            val word = ((value as? Map<*, *>)?.get("word") ?: value).toString().uppercase()
            val words = blueprint.meta["words"] as List<*>
            val index = words.indexOf(word)
            if (index < 0 || row != index || col != 0 || word in foundWords) return incorrect()
            word.indices.forEach { x -> replace(index, x, board[index][x].copy(ownerId = SOLO_OWNER)) }
            foundWords += word
            return accept(word.length * 10)
        }

        if (gameType == GameType.DOTS_AND_BOXES) {
            val side = value?.toString()?.lowercase().orEmpty()
            if (side !in SIDES || cell.meta[side] == true) return incorrect()
            val ownMeta = cell.meta + (side to true)
            replace(row, col, cell.copy(meta = ownMeta))
            mirrorEdge(row, col, side)
            var boxes = 0
            listOf(row to col, neighbour(row, col, side)).filterNotNull().distinct().forEach { (y, x) ->
                val target = board[y][x]
                if (SIDES.all { target.meta[it] == true } && target.ownerId == null) {
                    replace(y, x, target.copy(ownerId = SOLO_OWNER, isRevealed = true))
                    boxes++
                }
            }
            return accept(if (boxes > 0) boxes * 50 else 5)
        }

        val expected = blueprint.answers[row][col]
        val normalized = when (expected) {
            is Number -> value.toString().toIntOrNull()
            is Boolean -> value == true || value == "FILL" || value == "BLOCK"
            is String -> value?.toString()?.uppercase()
            else -> null
        }
        if (normalized != expected) return incorrect()
        replace(
            row,
            col,
            cell.copy(
                value = if (gameType == GameType.HITORI) cell.value else expected,
                isRevealed = true,
                isBlocked = gameType == GameType.HITORI,
                ownerId = SOLO_OWNER,
            ),
        )
        return accept(if (gameType == GameType.RUMMIKUB) 15 else 10)
    }

    private fun accept(points: Int): LocalPuzzleMoveResult {
        revision++
        return LocalPuzzleMoveResult(true, snapshot(), points = points, message = if (isComplete()) "Puzzle completado" else "¡Correcto!")
    }

    private fun incorrect(): LocalPuzzleMoveResult = LocalPuzzleMoveResult(
        accepted = false,
        state = snapshot(),
        penaltyMs = 3_000,
        message = "Movimiento incorrecto",
    )

    private fun reject(message: String) = LocalPuzzleMoveResult(false, snapshot(), message = message)

    private fun replace(row: Int, col: Int, value: GenericCell) {
        board = board.mapIndexed { y, cells -> if (y == row) cells.mapIndexed { x, old -> if (x == col) value else old } else cells }
    }

    private fun adjacentMines(row: Int, col: Int): Int = (row - 1..row + 1).sumOf { y ->
        (col - 1..col + 1).count { x -> blueprint.answers.getOrNull(y)?.getOrNull(x) == true }
    }

    private fun mirrorEdge(row: Int, col: Int, side: String) {
        val neighbour = neighbour(row, col, side) ?: return
        val opposite = mapOf("top" to "bottom", "right" to "left", "bottom" to "top", "left" to "right").getValue(side)
        val target = board[neighbour.first][neighbour.second]
        replace(neighbour.first, neighbour.second, target.copy(meta = target.meta + (opposite to true)))
    }

    private fun neighbour(row: Int, col: Int, side: String): Pair<Int, Int>? {
        val result = when (side) {
            "top" -> row - 1 to col
            "right" -> row to col + 1
            "bottom" -> row + 1 to col
            else -> row to col - 1
        }
        return result.takeIf { (y, x) -> board.getOrNull(y)?.getOrNull(x) != null }
    }

    private fun isComplete(): Boolean = when (gameType) {
        GameType.MINESWEEPER -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] == true || board[y][x].ownerId != null } }
        GameType.WORD_SEARCH -> foundWords.size == (blueprint.meta["words"] as List<*>).size
        GameType.DOTS_AND_BOXES -> board.flatten().all { it.ownerId != null }
        GameType.NONOGRAM, GameType.HITORI -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] != true || board[y][x].ownerId != null } }
        else -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] == null || board[y][x].ownerId != null } }
    }

    private data class Blueprint(
        val board: List<List<GenericCell>>,
        val answers: List<List<Any?>>,
        val meta: Map<String, Any?> = emptyMap(),
    )

    private fun createBlueprint(type: GameType): Blueprint = when (type) {
        GameType.MINESWEEPER -> mines()
        GameType.WORD_SEARCH -> words()
        GameType.CROSSWORD -> crossword()
        GameType.NONOGRAM -> nonogram()
        GameType.DOTS_AND_BOXES -> dots()
        GameType.KAKURO -> kakuro()
        GameType.MATHDOKU -> mathdoku()
        GameType.HITORI -> hitori()
        GameType.RUMMIKUB -> rummikub()
        GameType.SUDOKU -> error("Sudoku usa RandomSudokuGenerator")
    }

    private fun mines(): Blueprint {
        val mines = setOf(3, 9, 14, 27, 31, 45, 52, 68, 74, 91, 96)
        return Blueprint(matrix(10, 10) { _, _ -> GenericCell() }, matrix(10, 10) { r, c -> r * 10 + c in mines }, mapOf("mineCount" to mines.size))
    }

    private fun words(): Blueprint {
        val words = listOf("ARENA", "LOGICA", "NEON", "MATRIZ", "PUZZLE")
        val filler = "QWERTYUIOPASDFGHJKLZXCVBNM"
        val answers = matrix<Any?>(10, 10) { r, c -> words.getOrNull(r)?.getOrNull(c)?.toString() ?: filler[(r * 7 + c * 11) % filler.length].toString() }
        return Blueprint(answers.map { row -> row.map { GenericCell(value = it, isRevealed = true) } }, answers, mapOf("words" to words))
    }

    private fun crossword(): Blueprint {
        val rows = listOf("ARENA#RED", "LOGICA###", "NEON#BOT#", "MATRIZ###", "PUZZLE###", "JUEGO####", "COLOR####", "PODER####", "MENTE####")
        val answers = rows.map { it.padEnd(9, '#').take(9).map { char -> char.takeUnless { it == '#' }?.toString() } }
        val board = answers.mapIndexed { r, row -> row.mapIndexed { c, answer -> if (answer == null) blocked() else GenericCell(meta = mapOf("clue" to if (c == 0) r + 1 else 0)) } }
        return Blueprint(board, answers, mapOf("clues" to listOf("Arena de competencia", "Razonamiento", "Luz brillante", "Datos rectangulares", "Rompecabezas", "Actividad con reglas", "Identidad visual", "Habilidad especial", "Capacidad de razonar")))
    }

    private fun nonogram(): Blueprint {
        val pattern = listOf("00111100", "01111110", "11011011", "11111111", "01111110", "00111100", "00011000", "00111100")
        val answers = pattern.map { row -> row.map { it == '1' } }
        return Blueprint(answers.map { row -> row.map { GenericCell() } }, answers, mapOf("rowClues" to answers.map(::clues), "columnClues" to answers[0].indices.map { x -> clues(answers.map { it[x] }) }))
    }

    private fun dots() = Blueprint(matrix(5, 5) { _, _ -> GenericCell(meta = SIDES.associateWith { false }) }, matrix(5, 5) { _, _ -> true }, mapOf("dots" to 6))

    private fun kakuro(): Blueprint {
        val answers = matrix<Any?>(5, 5) { r, c -> if (r == 0 || c == 0) null else ((r + c - 2) % 4) + 1 }
        val board = matrix(5, 5) { r, c -> when {
            r == 0 && c == 0 -> blocked(mapOf("clueCell" to true))
            r == 0 -> blocked(mapOf("clueCell" to true, "downSum" to 10))
            c == 0 -> blocked(mapOf("clueCell" to true, "rightSum" to 10))
            else -> GenericCell()
        } }
        return Blueprint(board, answers, mapOf("instructions" to "Cada fila y columna suma 10."))
    }

    private fun mathdoku(): Blueprint {
        val answers = matrix<Any?>(6, 6) { r, c -> ((r + c) % 6) + 1 }
        val board = matrix(6, 6) { r, c ->
            val start = c - c % 2
            val target = answers[r][start] as Int + answers[r][start + 1] as Int
            GenericCell(meta = mapOf("cageId" to r * 3 + c / 2, "cageLabel" to if (c % 2 == 0) "$target+" else "", "cageStart" to (c % 2 == 0), "cageEnd" to (c % 2 == 1)))
        }
        return Blueprint(board, answers, mapOf("instructions" to "Usa 1 a 6 y cumple las jaulas."))
    }

    private fun hitori(): Blueprint {
        val black = setOf("0:0", "0:3", "2:1", "2:4", "4:0", "4:3")
        val values = matrix(6, 6) { r, c -> ((r + c) % 6) + 1 }.map { it.toMutableList() }
        black.forEach { key -> val (r, c) = key.split(':').map(String::toInt); values[r][c] = values[r][(c + 1) % 6] }
        val answers = matrix<Any?>(6, 6) { r, c -> "$r:$c" in black }
        return Blueprint(values.map { row -> row.map { GenericCell(value = it, isRevealed = true) } }, answers, mapOf("instructions" to "Apaga los duplicados."))
    }

    private fun rummikub(): Blueprint {
        val colors = listOf("RED", "BLUE", "GREEN", "ORANGE")
        val starts = listOf(1, 3, 5, 7)
        val answers = matrix<Any?>(4, 7) { r, c -> starts[r] + c }
        return Blueprint(matrix(4, 7) { r, _ -> GenericCell(meta = mapOf("tileColor" to colors[r], "meld" to "RUN")) }, answers, mapOf("colors" to colors, "instructions" to "Completa las escaleras."))
    }

    private fun blocked(meta: Map<String, Any?> = emptyMap()) = GenericCell(isRevealed = true, isBlocked = true, meta = meta)

    private fun clues(values: List<Boolean>): List<Int> {
        val result = mutableListOf<Int>(); var run = 0
        values.forEach { if (it) run++ else if (run > 0) { result += run; run = 0 } }
        if (run > 0) result += run
        return result.ifEmpty { listOf(0) }
    }

    private fun <T> matrix(rows: Int, cols: Int, create: (Int, Int) -> T): List<List<T>> =
        List(rows) { r -> List(cols) { c -> create(r, c) } }

    private companion object {
        const val SOLO_OWNER = "solo"
        val SIDES = listOf("top", "right", "bottom", "left")
    }
}
