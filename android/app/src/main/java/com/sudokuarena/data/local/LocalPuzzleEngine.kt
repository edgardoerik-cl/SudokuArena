package com.sudokuarena.data.local

import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.GenericBoardState
import com.sudokuarena.domain.GenericCell
import com.sudokuarena.domain.PuzzleDifficulty
import kotlin.random.Random
import kotlin.math.abs

data class LocalPuzzleMoveResult(
    val accepted: Boolean,
    val state: GenericBoardState,
    val points: Int = 0,
    val penaltyMs: Long = 0,
    val hitMine: Boolean = false,
    val message: String = "",
)

/** Motor solución-primero para las arenas no-Sudoku sin conexión. */
class LocalPuzzleEngine(
    val gameType: GameType,
    private val difficulty: PuzzleDifficulty = PuzzleDifficulty.MEDIUM,
    seed: Long = System.nanoTime(),
) {
    private val random = Random(seed)
    private val blueprint = createBlueprint(gameType)
    private var board = blueprint.board
    private var revision = 0L
    private val foundWords = mutableSetOf<String>()
    private val guessedLetters = mutableSetOf<String>()
    private var hangmanErrors = 0
    private var capitalPosition = 0
    private var capitalBalance = 1_500
    private var capitalStage = "ROLL"
    private var capitalPending: Int? = null
    private var capitalDice = listOf(1, 1)
    private var capitalEvent = "Construye tu imperio neón"
    private var capitalCard: Map<String, Any?>? = null
    private val capitalOwners = mutableMapOf<Int, String>()
    private val capitalLevels = mutableMapOf<Int, Int>()
    private var localTurnTeam = "BLUE"

    fun snapshot() = GenericBoardState(
        gameId = "local-${gameType.name.lowercase()}-${blueprint.seed}", gameType = gameType,
        revision = revision, serverTime = System.currentTimeMillis(), rows = board.size,
        columns = board.firstOrNull()?.size ?: 0, board = board, completed = isComplete(),
        meta = blueprint.meta +
            if (gameType == GameType.HANGMAN) mapOf(
                "guessedLetters" to guessedLetters.sorted(),
                "wrongGuesses" to guessedLetters.filter { guess ->
                    blueprint.answers.flatten().none { it?.toString() == guess }
                },
                "mistakesMade" to hangmanErrors,
                "hiddenWord" to board.firstOrNull().orEmpty().map { it.value?.toString() ?: "_" },
            ) else emptyMap<String, Any?>() +
            if (gameType in setOf(GameType.CHECKERS, GameType.CHESS_TACTICS)) mapOf(
                "localTurnTeam" to localTurnTeam,
                "instructions" to "Modo Hotseat: entrega el teléfono al equipo ${if (localTurnTeam == "BLUE") "Azul" else "Rojo"}.",
            ) else emptyMap<String, Any?>() +
            if (gameType == GameType.CAPITAL_ARENA) mapOf(
            "currentPlayerTurn" to OWNER, "stage" to capitalStage, "pendingProperty" to capitalPending,
            "dice" to capitalDice, "lastEvent" to capitalEvent, "surpriseCard" to capitalCard,
            "balances" to mapOf(OWNER to capitalBalance), "positions" to mapOf(OWNER to capitalPosition),
            "propertyOwners" to capitalOwners.mapKeys { it.key.toString() },
            "propertyLevels" to capitalLevels.mapKeys { it.key.toString() },
        ) else emptyMap<String, Any?>(),
    )

    fun letterRack(): List<String> = (blueprint.meta["rack"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()

    fun move(row: Int, col: Int, value: Any?): LocalPuzzleMoveResult {
        val cell = board.getOrNull(row)?.getOrNull(col) ?: return reject("Movimiento fuera del tablero")
        if (gameType == GameType.CAPITAL_ARENA) return capitalMove(value)
        if (cell.isBlocked || (cell.ownerId != null && gameType !in setOf(GameType.TETRIS_ARENA, GameType.NURIKABE, GameType.CROSS_LETTERS, GameType.WORD_SEARCH))) return reject("Casilla no disponible")

        if (gameType == GameType.CROSS_LETTERS) {
            val payload = value as? Map<*, *> ?: return incorrect()
            val word = payload["word"]?.toString()?.uppercase().orEmpty()
            val direction = payload["direction"]?.toString()?.uppercase().orEmpty()
            if (word != "MENTE" || direction != "V" || row != 6 || col != 7) return incorrect()
            word.forEachIndexed { index, letter ->
                val target = board[6 + index][7]
                if (target.value == null) replace(6 + index, 7, target.copy(value = letter.toString(), isRevealed = true, ownerId = OWNER))
            }
            return accept(50)
        }
        if (gameType == GameType.SECRET_CODE) {
            if (cell.ownerId != null) return reject("Palabra ya revelada")
            val identity = blueprint.answers[row][col].toString()
            replace(row, col, cell.copy(ownerId = OWNER, meta = cell.meta + ("revealedColor" to identity)))
            return accept(if (identity == "RED") 20 else 0)
        }
        if (gameType == GameType.HANGMAN) {
            val letter = value?.toString()?.uppercase()?.takeIf { it.length == 1 && it[0] in "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ" }
                ?: return reject("Selecciona una letra")
            if (!guessedLetters.add(letter)) return reject("Letra ya utilizada")
            var hits = 0
            blueprint.answers.first().forEachIndexed { index, answer ->
                if (answer?.toString() == letter) {
                    replace(0, index, board[0][index].copy(value = letter, isRevealed = true, ownerId = OWNER))
                    hits += 1
                }
            }
            if (hits == 0) hangmanErrors += 1
            revision += 1
            return LocalPuzzleMoveResult(
                accepted = true,
                state = snapshot(),
                points = hits * 12,
                message = if (hits > 0) "¡Letra correcta!" else "Esa letra no aparece",
            )
        }
        if (gameType == GameType.ARROWS_ESCAPE) {
            val direction = cell.value?.toString() ?: return reject("Flecha inválida")
            val (dy, dx) = when (direction) {
                "UP" -> -1 to 0; "RIGHT" -> 0 to 1; "DOWN" -> 1 to 0; else -> 0 to -1
            }
            var y = row + dy
            var x = col + dx
            while (board.getOrNull(y)?.getOrNull(x) != null) {
                if (board[y][x].ownerId == null) return reject("La trayectoria está bloqueada")
                y += dy
                x += dx
            }
            replace(row, col, cell.copy(ownerId = OWNER, isRevealed = true))
            return accept(10)
        }
        if (gameType == GameType.CHECKERS) return checkersMove(row, col, value, cell)
        if (gameType == GameType.CHESS_TACTICS) return chessHotseatMove(row, col, value, cell)
        if (gameType == GameType.NURIKABE) {
            val action = value?.toString()?.uppercase().orEmpty()
            if (action !in setOf("RIVER", "ISLAND", "CLEAR") || cell.meta["islandClue"] == true) return reject("Casilla no disponible")
            val next = if (action == "CLEAR") cell.copy(value = null, ownerId = null, isRevealed = false)
            else cell.copy(value = action, ownerId = OWNER, isRevealed = true)
            replace(row, col, next)
            return accept(if ((action == "RIVER") == (blueprint.answers[row][col] == true)) 10 else 0)
        }

        if (gameType == GameType.MINESWEEPER) {
            val mine = blueprint.answers[row][col] == true
            return if (mine) {
                replace(row, col, cell.copy(value = "MINE", isRevealed = true, isBlocked = true)); revision++
                LocalPuzzleMoveResult(false, snapshot(), penaltyMs = 5_000, hitMine = true, message = "¡Mina! Pausa de 5 segundos")
            } else {
                replace(row, col, cell.copy(value = adjacentMines(row, col), isRevealed = true, ownerId = OWNER)); accept(10)
            }
        }
        if (gameType == GameType.WORD_SEARCH) {
            val payload = value as? Map<*, *>
            val word = (payload?.get("word") ?: value).toString().uppercase()
            @Suppress("UNCHECKED_CAST")
            val placements = blueprint.meta["placements"] as List<Map<String, Any>>
            val placement = placements.firstOrNull {
                it["word"] == word && it["startRow"] == row && it["startCol"] == col &&
                    (payload?.get("endRow") == null || payload["endRow"].toString().toIntOrNull() == it["endRow"]) &&
                    (payload?.get("endCol") == null || payload["endCol"].toString().toIntOrNull() == it["endCol"])
            }
            if (placement == null || word in foundWords) return incorrect()
            word.indices.forEach { offset ->
                val y = row + (placement.getValue("rowStep") as Int) * offset
                val x = col + (placement.getValue("colStep") as Int) * offset
                replace(y, x, board[y][x].copy(ownerId = OWNER))
            }
            foundWords += word; return accept(word.length * 10)
        }
        if (gameType == GameType.TIC_TAC_TOE) {
            if (cell.value != null) return reject("Casilla ocupada")
            replace(row, col, cell.copy(value = "X", isRevealed = true, ownerId = OWNER))
            if (!ticWinner("X")) {
                val empty = board.flatMapIndexed { y, cells -> cells.mapIndexedNotNull { x, target -> if (target.value == null) y to x else null } }
                val bot = empty.randomOrNull(random)
                if (bot != null) {
                    val target = board[bot.first][bot.second]
                    replace(bot.first, bot.second, target.copy(value = "O", isRevealed = true, ownerId = "LOCAL_BOT"))
                }
            }
            return accept(if (ticWinner("X")) 100 else 10)
        }
        if (gameType == GameType.DOTS_AND_BOXES) return dotsMove(row, col, value, cell)
        if (gameType == GameType.NEXUS_ZERO) {
            val payload = value as? Map<*, *> ?: return incorrect()
            val targetRow = payload["targetRow"]?.toString()?.toIntOrNull() ?: return incorrect()
            val targetCol = payload["targetCol"]?.toString()?.toIntOrNull() ?: return incorrect()
            val target = board.getOrNull(targetRow)?.getOrNull(targetCol) ?: return incorrect()
            if (target.ownerId != null || blueprint.answers[row][col] != "$targetRow:$targetCol") return incorrect()
            if ((cell.value as? Number)?.toInt()?.plus((target.value as? Number)?.toInt() ?: 99) != 0) return incorrect()
            replace(row, col, cell.copy(ownerId = OWNER, isRevealed = true))
            replace(targetRow, targetCol, target.copy(ownerId = OWNER, isRevealed = true))
            return accept(24)
        }

        val expected = blueprint.answers[row][col]
        val normalized = when (expected) {
            is Number -> value.toString().toIntOrNull()
            is Boolean -> value == true || value == "FILL" || value == "BLOCK" || value == "RIVER" || value == "BRIDGE"
            is String -> value?.toString()?.uppercase()
            else -> null
        }
        if (normalized != expected) return incorrect()
        replace(row, col, cell.copy(
            value = if (gameType == GameType.HITORI) cell.value else expected,
            isRevealed = true, isBlocked = gameType == GameType.HITORI, ownerId = OWNER,
        ))
        return accept(10)
    }

    private fun dotsMove(row: Int, col: Int, value: Any?, cell: GenericCell): LocalPuzzleMoveResult {
        val side = value?.toString()?.lowercase().orEmpty()
        if (side !in SIDES || cell.meta[side] == true) return incorrect()
        replace(row, col, cell.copy(meta = cell.meta + (side to true))); mirrorEdge(row, col, side)
        var boxes = 0
        listOfNotNull(row to col, neighbour(row, col, side)).distinct().forEach { (y, x) ->
            val target = board[y][x]
            if (SIDES.all { target.meta[it] == true } && target.ownerId == null) { replace(y, x, target.copy(ownerId = OWNER, isRevealed = true)); boxes++ }
        }
        return accept(if (boxes > 0) boxes * 50 else 5)
    }

    private fun accept(points: Int): LocalPuzzleMoveResult { revision++; return LocalPuzzleMoveResult(true, snapshot(), points, message = if (isComplete()) "Puzzle completado" else "¡Correcto!") }
    private fun incorrect() = LocalPuzzleMoveResult(false, snapshot(), penaltyMs = 3_000, message = "Movimiento incorrecto")
    private fun reject(message: String) = LocalPuzzleMoveResult(false, snapshot(), message = message)
    private fun replace(row: Int, col: Int, value: GenericCell) { board = board.mapIndexed { y, cells -> if (y == row) cells.mapIndexed { x, old -> if (x == col) value else old } else cells } }
    private fun adjacentMines(row: Int, col: Int) = (row - 1..row + 1).sumOf { y -> (col - 1..col + 1).count { x -> blueprint.answers.getOrNull(y)?.getOrNull(x) == true } }
    private fun mirrorEdge(row: Int, col: Int, side: String) { val point = neighbour(row, col, side) ?: return; val opposite = mapOf("top" to "bottom", "right" to "left", "bottom" to "top", "left" to "right").getValue(side); val target = board[point.first][point.second]; replace(point.first, point.second, target.copy(meta = target.meta + (opposite to true))) }
    private fun neighbour(row: Int, col: Int, side: String): Pair<Int, Int>? { val p = when (side) { "top" -> row - 1 to col; "right" -> row to col + 1; "bottom" -> row + 1 to col; else -> row to col - 1 }; return p.takeIf { board.getOrNull(it.first)?.getOrNull(it.second) != null } }

    private fun isComplete(): Boolean = when (gameType) {
        GameType.MINESWEEPER -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] == true || board[y][x].ownerId != null } }
        GameType.WORD_SEARCH -> foundWords.size == (blueprint.meta["words"] as List<*>).size
        GameType.DOTS_AND_BOXES -> board.flatten().all { it.ownerId != null }
        GameType.NURIKABE -> board.indices.all { y -> board[y].indices.all { x -> board[y][x].meta["islandClue"] == true || board[y][x].value == if (blueprint.answers[y][x] == true) "RIVER" else "ISLAND" } }
        GameType.TIC_TAC_TOE -> ticWinner("X") || ticWinner("O") || board.flatten().all { it.value != null }
        GameType.HITORI, GameType.BRIDGES -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] != true || board[y][x].ownerId != null } }
        GameType.CROSS_LETTERS -> listOf(6, 8, 9, 10).all { board[it][7].ownerId != null }
        GameType.SECRET_CODE -> board.flatten().count { it.ownerId != null && it.meta["revealedColor"] == "RED" } == 8
        GameType.CAPITAL_ARENA -> false
        GameType.CHECKERS -> board.flatten().count { it.meta["team"] == "BLUE" } == 0 ||
            board.flatten().count { it.meta["team"] == "RED" } == 0
        GameType.CHESS_TACTICS -> board.flatten().count { it.value == "KING" } < 2
        GameType.HANGMAN -> board.first().all { it.value != null } || hangmanErrors >= 6
        else -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] == null || board[y][x].ownerId != null } }
    }

    private data class Blueprint(val board: List<List<GenericCell>>, val answers: List<List<Any?>>, val meta: Map<String, Any?>, val seed: Int)
    private fun result(board: List<List<GenericCell>>, answers: List<List<Any?>>, meta: Map<String, Any?> = emptyMap()) = Blueprint(board, answers, meta + ("difficulty" to difficulty.name), random.nextInt())
    private fun createBlueprint(type: GameType): Blueprint = when (type) {
        GameType.MINESWEEPER -> mines(); GameType.WORD_SEARCH -> words(); GameType.CROSSWORD -> crossword(); GameType.TIC_TAC_TOE -> ticTacToe()
        GameType.DOTS_AND_BOXES -> dots(); GameType.KAKURO -> kakuro(); GameType.MATHDOKU -> mathdoku(); GameType.HITORI -> hitori()
        GameType.NURIKABE -> nurikabe(); GameType.BRIDGES -> bridges()
        GameType.HANGMAN -> hangman(); GameType.ARROWS_ESCAPE -> arrowsEscape()
        GameType.CROSS_LETTERS -> crossLetters(); GameType.SECRET_CODE -> secretCode()
        GameType.CAPITAL_ARENA -> capitalArena(); GameType.NEXUS_ZERO -> nexusZero()
        GameType.CHECKERS -> checkers()
        GameType.CHESS_TACTICS -> chessHotseat()
        GameType.TETRIS_ARENA, GameType.PACMAN_ARENA, GameType.DEMOLITION_ARCADE ->
            result(listOf(listOf(GenericCell(isBlocked = true))), listOf(listOf(null)), mapOf("actionMode" to true))
        GameType.SUDOKU -> error("Sudoku usa RandomSudokuGenerator")
    }

    private fun ticTacToe(): Blueprint = result(
        matrix(3, 3) { _, _ -> GenericCell() },
        matrix(3, 3) { _, _ -> null },
        mapOf("turnBased" to true),
    )

    private fun ticWinner(mark: String): Boolean {
        val lines = listOf(
            listOf(0 to 0, 0 to 1, 0 to 2), listOf(1 to 0, 1 to 1, 1 to 2), listOf(2 to 0, 2 to 1, 2 to 2),
            listOf(0 to 0, 1 to 0, 2 to 0), listOf(0 to 1, 1 to 1, 2 to 1), listOf(0 to 2, 1 to 2, 2 to 2),
            listOf(0 to 0, 1 to 1, 2 to 2), listOf(0 to 2, 1 to 1, 2 to 0),
        )
        return lines.any { line -> line.all { (row, col) -> board[row][col].value == mark } }
    }

    private fun checkers(): Blueprint = result(
        matrix(8, 8) { row, col ->
            val playable = (row + col) % 2 == 1
            val team = when {
                playable && row <= 2 -> "BLUE"
                playable && row >= 5 -> "RED"
                else -> null
            }
            GenericCell(
                value = team?.let { "${it}_MAN" },
                isRevealed = team != null,
                isBlocked = !playable,
                meta = mapOf("playable" to playable, "team" to team, "king" to false),
            )
        },
        matrix(8, 8) { _, _ -> null },
        mapOf("hotseat" to true),
    )

    private fun checkersMove(row: Int, col: Int, value: Any?, source: GenericCell): LocalPuzzleMoveResult {
        val payload = value as? Map<*, *> ?: return reject("Selecciona origen y destino")
        val targetRow = payload["targetRow"]?.toString()?.toIntOrNull() ?: return reject("Destino inválido")
        val targetCol = payload["targetCol"]?.toString()?.toIntOrNull() ?: return reject("Destino inválido")
        val target = board.getOrNull(targetRow)?.getOrNull(targetCol) ?: return reject("Destino inválido")
        if (source.meta["team"] != localTurnTeam || source.value == null || target.isBlocked || target.value != null) {
            return reject("Movimiento inválido; el turno se conserva")
        }
        val dy = targetRow - row
        val dx = targetCol - col
        val king = source.meta["king"] == true
        val forward = if (localTurnTeam == "BLUE") 1 else -1
        var capture: Pair<Int, Int>? = null
        if (abs(dx) == 1 && (dy == forward || king && abs(dy) == 1)) {
            // Paso simple.
        } else if (abs(dx) == 2 && abs(dy) == 2) {
            val middle = board[row + dy / 2][col + dx / 2]
            if (middle.meta["team"] == null || middle.meta["team"] == localTurnTeam) return reject("No hay rival para capturar")
            capture = row + dy / 2 to col + dx / 2
        } else return reject("Movimiento diagonal no permitido")
        val crowned = king || (localTurnTeam == "BLUE" && targetRow == 7) || (localTurnTeam == "RED" && targetRow == 0)
        replace(targetRow, targetCol, source.copy(
            value = if (crowned) "${localTurnTeam}_KING" else "${localTurnTeam}_MAN",
            meta = source.meta + ("king" to crowned),
        ))
        replace(row, col, source.copy(value = null, ownerId = null, isRevealed = false, meta = source.meta + ("team" to null) + ("king" to false)))
        capture?.let { (captureRow, captureCol) ->
            val captured = board[captureRow][captureCol]
            replace(captureRow, captureCol, captured.copy(value = null, ownerId = null, isRevealed = false, meta = captured.meta + ("team" to null) + ("king" to false)))
        }
        localTurnTeam = if (localTurnTeam == "BLUE") "RED" else "BLUE"
        return accept(if (capture != null) 35 else 5)
    }

    private fun chessHotseat(): Blueprint {
        val back = listOf("ROOK", "KNIGHT", "BISHOP", "QUEEN", "KING", "BISHOP", "KNIGHT", "ROOK")
        return result(
            matrix(8, 8) { row, col ->
                val team = when (row) { 0, 1 -> "BLUE"; 6, 7 -> "RED"; else -> null }
                val type = when (row) { 0, 7 -> back[col]; 1, 6 -> "PAWN"; else -> null }
                GenericCell(
                    value = type,
                    isRevealed = type != null,
                    meta = mapOf(
                        "team" to team, "type" to type, "hp" to 3, "maxHp" to 3,
                        "ap" to 2, "maxAp" to 2, "statusEffects" to emptyList<String>(),
                    ),
                )
            },
            matrix(8, 8) { _, _ -> null },
            mapOf("hotseat" to true),
        )
    }

    private fun chessHotseatMove(row: Int, col: Int, value: Any?, source: GenericCell): LocalPuzzleMoveResult {
        val payload = value as? Map<*, *> ?: return reject("Selecciona una pieza y su destino")
        val targetRow = payload["targetRow"]?.toString()?.toIntOrNull() ?: return reject("Destino inválido")
        val targetCol = payload["targetCol"]?.toString()?.toIntOrNull() ?: return reject("Destino inválido")
        val target = board.getOrNull(targetRow)?.getOrNull(targetCol) ?: return reject("Destino inválido")
        if (source.meta["team"] != localTurnTeam || source.value == null || target.meta["team"] == localTurnTeam) {
            return reject("Movimiento inválido; el turno se conserva")
        }
        val dy = targetRow - row
        val dx = targetCol - col
        val valid = when (source.value) {
            "PAWN" -> abs(dx) <= 1 && dy == if (localTurnTeam == "BLUE") 1 else -1
            "KNIGHT" -> setOf(abs(dy), abs(dx)) == setOf(1, 2)
            "BISHOP" -> abs(dy) == abs(dx)
            "ROOK" -> dy == 0 || dx == 0
            "QUEEN" -> dy == 0 || dx == 0 || abs(dy) == abs(dx)
            "KING" -> maxOf(abs(dy), abs(dx)) == 1
            else -> false
        }
        if (!valid || !chessPathClear(row, col, targetRow, targetCol, source.value.toString())) {
            return reject("La pieza no puede llegar allí")
        }
        replace(targetRow, targetCol, source.copy(ownerId = OWNER, isRevealed = true))
        replace(row, col, GenericCell(meta = mapOf("team" to null, "type" to null)))
        localTurnTeam = if (localTurnTeam == "BLUE") "RED" else "BLUE"
        return accept(if (target.value != null) 30 else 8)
    }

    private fun chessPathClear(row: Int, col: Int, targetRow: Int, targetCol: Int, type: String): Boolean {
        if (type in setOf("PAWN", "KNIGHT", "KING")) return true
        val dy = (targetRow - row).coerceIn(-1, 1)
        val dx = (targetCol - col).coerceIn(-1, 1)
        var y = row + dy
        var x = col + dx
        while (y != targetRow || x != targetCol) {
            if (board[y][x].value != null) return false
            y += dy; x += dx
        }
        return true
    }

    private fun hangman(): Blueprint {
        val word = listOf("ARENA", "LABERINTO", "VERTICAL", "FLECHAS").random(random)
        return result(
            listOf(word.mapIndexed { index, _ -> GenericCell(meta = mapOf("letterIndex" to index)) }),
            listOf(word.map { it.toString() }),
            mapOf("clue" to "Palabra relacionada con la Multi Arena", "maxErrors" to 6),
        )
    }

    private fun arrowsEscape(): Blueprint {
        val size = size(5, 6, 7, 8)
        val values = matrix<Any?>(size, size) { row, col ->
            val distances = listOf(row to "UP", size - 1 - col to "RIGHT", size - 1 - row to "DOWN", col to "LEFT")
            distances.minBy { it.first }.second
        }
        return result(
            values.map { row -> row.map { value -> GenericCell(value = value, isRevealed = true, meta = mapOf("arrow" to value)) } },
            values,
            mapOf("totalBlocks" to size * size),
        )
    }

    private fun mines(): Blueprint { val size = size(8,10,12,14); val count=(size*size*when(difficulty){PuzzleDifficulty.EASY->.10;PuzzleDifficulty.MEDIUM->.14;PuzzleDifficulty.HARD->.18;PuzzleDifficulty.EXPERT->.22}).toInt(); val mines=(0 until size*size).shuffled(random).take(count).toSet(); return result(matrix(size,size){_,_->GenericCell()}, matrix(size,size){r,c->r*size+c in mines}, mapOf("mineCount" to count)) }
    private fun words(): Blueprint {
        val boardSize = size(8, 10, 12, 14)
        val targetCount = size(4, 5, 7, 9)
        val grid = MutableList(boardSize) { MutableList<Any?>(boardSize) { null } }
        val directions = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1).shuffled(random)
        val placements = mutableListOf<Map<String, Any>>()
        for ((word) in WORDS.filter { it.first.length <= boardSize }.shuffled(random)) {
            if (placements.size >= targetCount) break
            val preferred = directions[placements.size % directions.size]
            val directionAttempts = listOf(preferred) + directions.filter { it != preferred }.shuffled(random)
            var placed = false
            for ((rowStep, colStep) in directionAttempts) {
                repeat(40) {
                    if (placed) return@repeat
                    val last = word.lastIndex
                    val minRow = if (rowStep < 0) last else 0
                    val maxRow = if (rowStep > 0) boardSize - 1 - last else boardSize - 1
                    val minCol = if (colStep < 0) last else 0
                    val maxCol = if (colStep > 0) boardSize - 1 - last else boardSize - 1
                    if (minRow > maxRow || minCol > maxCol) return@repeat
                    val startRow = random.nextInt(minRow, maxRow + 1)
                    val startCol = random.nextInt(minCol, maxCol + 1)
                    val fits = word.indices.all { offset ->
                        val current = grid[startRow + rowStep * offset][startCol + colStep * offset]
                        current == null || current == word[offset].toString()
                    }
                    if (!fits) return@repeat
                    word.indices.forEach { offset -> grid[startRow + rowStep * offset][startCol + colStep * offset] = word[offset].toString() }
                    placements += mapOf(
                        "word" to word, "startRow" to startRow, "startCol" to startCol,
                        "endRow" to startRow + rowStep * last, "endCol" to startCol + colStep * last,
                        "rowStep" to rowStep, "colStep" to colStep,
                    )
                    placed = true
                }
                if (placed) break
            }
        }
        val filler = "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ"
        grid.forEach { row -> row.indices.forEach { col -> if (row[col] == null) row[col] = filler[random.nextInt(filler.length)].toString() } }
        val answers = grid.map { it.toList() }
        return result(
            answers.map { row -> row.map { GenericCell(value = it, isRevealed = true) } },
            answers,
            mapOf("words" to placements.map { it.getValue("word") }, "placements" to placements),
        )
    }

    private fun crossword(): Blueprint {
        val boardSize = size(9, 11, 13, 15)
        val selected = WORDS.filter { it.first.length <= boardSize }.shuffled(random).take(size(5, 7, 9, 11))
        val answers = List(boardSize) { row -> List<Any?>(boardSize) { col -> selected.getOrNull(row)?.first?.getOrNull(col)?.toString() } }
        val board = answers.mapIndexed { row, cells -> cells.mapIndexed { col, answer ->
            if (answer == null) blocked() else GenericCell(meta = mapOf("clue" to if (col == 0) row + 1 else 0))
        } }
        return result(board, answers, mapOf("clues" to selected.mapIndexed { index, entry -> "${index + 1}H. ${entry.second}" }))
    }
    private fun dots(): Blueprint { val size=size(3,5,6,7); return result(matrix(size,size){_,_->GenericCell(meta=SIDES.associateWith{false})},matrix(size,size){_,_->true},mapOf("dots" to size+1)) }
    private fun kakuro(): Blueprint {
        val playable = size(3, 4, 5, 6); val digits = (1..9).shuffled(random).take(playable); val sum = digits.sum()
        val answers = matrix<Any?>(playable + 1, playable + 1) { row, col -> if (row == 0 || col == 0) null else digits[(row + col - 2) % playable] }
        val board = matrix(playable + 1, playable + 1) { row, col -> when {
            row == 0 && col == 0 -> blocked(mapOf("clueCell" to true))
            row == 0 -> blocked(mapOf("clueCell" to true, "downSum" to sum))
            col == 0 -> blocked(mapOf("clueCell" to true, "rightSum" to sum))
            else -> GenericCell()
        } }.map { it.toMutableList() }
        val anchors = if (difficulty == PuzzleDifficulty.EASY) 4 else if (difficulty == PuzzleDifficulty.MEDIUM) 3 else if (difficulty == PuzzleDifficulty.HARD) 2 else 1
        (1..playable).flatMap { row -> (1..playable).map { col -> row to col } }.shuffled(random).take(anchors).forEach { (row, col) ->
            board[row][col] = GenericCell(value = answers[row][col], isRevealed = true, isBlocked = true, meta = mapOf("given" to true))
        }
        return result(board, answers, mapOf("instructions" to "Cada grupo suma $sum sin repetir. Los números dorados son pistas."))
    }
    private fun mathdoku(): Blueprint { val size=size(4,5,6,7); val symbols=(1..size).shuffled(random); val answers=matrix<Any?>(size,size){r,c->symbols[(r+c)%size]}; val board=matrix(size,size){r,c->val start=c-c%2;val target=(answers[r][start] as Int)+(answers[r][minOf(size-1,start+1)] as Int);GenericCell(meta=mapOf("cageId" to "$r:$start","cageLabel" to if(c==start)"$target+" else "","cageStart" to(c==start),"cageEnd" to(c==minOf(size-1,start+1))))}; return result(board,answers,mapOf("instructions" to "Usa 1 a $size y cumple las jaulas.")) }
    private fun hitori(): Blueprint { val size=size(5,6,7,8); val black=mutableSetOf<String>(); for(index in(0 until size*size).shuffled(random)){val r=index/size;val c=index%size;if(black.size>=size(4,6,9,12))break;if(listOf("${r-1}:$c","${r+1}:$c","$r:${c-1}","$r:${c+1}").none{it in black})black+="$r:$c"}; val values=matrix(size,size){r,c->((r+c)%size)+1}.map{it.toMutableList()};black.forEach{val(r,c)=it.split(':').map(String::toInt);values[r][c]=values[r][(c+1)%size]};val answers=matrix<Any?>(size,size){r,c->"$r:$c" in black};return result(values.map{row->row.map{GenericCell(value=it,isRevealed=true)}},answers,mapOf("instructions" to "Apaga duplicados sin tocar negras por sus lados.")) }
    private fun nurikabe(): Blueprint { val size=size(6,8,10,12);val answers=matrix<Any?>(size,size){r,c->if(r%2==1)c!=(if(r%4==1)size-1 else 0)else c%3==2};val board=matrix(size,size){_,_->GenericCell()}.map{it.toMutableList()};var id=0;for(r in 0 until size){var c=0;while(c<size){if(answers[r][c]==true){c++;continue};val start=c;while(c<size&&answers[r][c]!=true)c++;id++;val clue=(start until c).random(random);board[r][clue]=blocked(mapOf("islandClue" to true,"islandSize" to c-start,"islandId" to id))}};return result(board,answers,mapOf("instructions" to "Pinta el río; evita bloques negros 2×2.")) }
    private fun bridges(): Blueprint {
        val grid = size(3, 4, 5, 6); val boardSize = grid * 2 - 1
        val answers = matrix<Any?>(boardSize, boardSize) { _, _ -> false }.map { it.toMutableList() }
        val board = matrix(boardSize, boardSize) { _, _ -> GenericCell(meta = mapOf("bridge" to true)) }.map { it.toMutableList() }
        for (row in 0 until grid) for (col in 0 until grid) {
            if (col > 0) answers[row * 2][col * 2 - 1] = true
            if (row > 0 && col == 0) answers[row * 2 - 1][0] = true
        }
        for (row in 0 until grid) for (col in 0 until grid) {
            val y = row * 2; val x = col * 2
            val targets = buildList {
                if (x > 0 && answers[y][x - 1] == true) add("$y:${x - 2}")
                if (x + 1 < boardSize && answers[y][x + 1] == true) add("$y:${x + 2}")
                if (y > 0 && answers[y - 1][x] == true) add("${y - 2}:$x")
                if (y + 1 < boardSize && answers[y + 1][x] == true) add("${y + 2}:$x")
            }
            board[y][x] = blocked(mapOf("island" to true, "bridgeCount" to targets.size, "validTargets" to targets))
        }
        return result(board, answers, mapOf("instructions" to "Toca una isla y luego un vecino resaltado."))
    }
    private fun nexusZero(): Blueprint {
        val rows = size(4, 6, 6, 8)
        val cols = 6
        val total = rows * cols
        val shuffled = (0 until total).shuffled(random)
        val partners = IntArray(total)
        shuffled.chunked(2).forEach { pair -> partners[pair[0]] = pair[1]; partners[pair[1]] = pair[0] }
        val positions = (0 until total).map { index ->
            val baseX = 24 + (index % cols) * 158
            val baseY = 24 + (index / cols) * (650 / rows)
            mapOf(
                "x" to (baseX + random.nextInt(-18, 19)).coerceIn(6, 920),
                "y" to (baseY + random.nextInt(-12, 13)).coerceIn(6, 630),
                "width" to 74,
                "height" to 58,
            )
        }
        val board = MutableList(rows) { row -> MutableList(cols) { col ->
            GenericCell(isRevealed = true, meta = mapOf("charge" to true) + positions[row * cols + col])
        } }
        val answers = MutableList(rows) { MutableList<Any?>(cols) { null } }
        val assigned = mutableSetOf<Int>()
        repeat(total) { index ->
            if (index in assigned) return@repeat
            val partner = partners[index]
            val charge = random.nextInt(1, 10) * if (random.nextBoolean()) 1 else -1
            val row = index / cols; val col = index % cols
            val otherRow = partner / cols; val otherCol = partner % cols
            board[row][col] = board[row][col].copy(value = charge)
            board[otherRow][otherCol] = board[otherRow][otherCol].copy(value = -charge)
            answers[row][col] = "$otherRow:$otherCol"
            answers[otherRow][otherCol] = "$row:$col"
            assigned += index; assigned += partner
        }
        return result(
            board.map { it.toList() },
            answers.map { it.toList() },
            mapOf("instructions" to "Nexo Cero: enlaza cargas dispersas que sumen 0.", "spatialLayout" to true, "arenaWidth" to 1000, "arenaHeight" to 700),
        )
    }

    private fun crossLetters(): Blueprint {
        val board = matrix(15, 15) { row, col ->
            val bonus = when {
                row == 7 && col == 7 -> "DW"
                (row + col) % 11 == 0 -> "DL"
                else -> "NONE"
            }
            val anchor = if (row == 7 && col in 5..9) "ARENA"[col - 5].toString() else null
            GenericCell(value = anchor, isRevealed = anchor != null, meta = mapOf("bonus" to bonus, "center" to (row == 7 && col == 7), "given" to (anchor != null)))
        }
        val answers = matrix<Any?>(15, 15) { _, _ -> null }
        return result(board, answers, mapOf("rack" to listOf("M", "E", "N", "T", "E", "S", "O"), "instructions" to "El tablero comienza con ARENA. Cruza una palabra válida usando tu atril."))
    }

    private fun secretCode(): Blueprint {
        val words = WORDS.map { it.first }.shuffled(random).take(25).toMutableList()
        while (words.size < 25) words += "CLAVE${words.size}"
        val identities = (List(8) { "RED" } + List(8) { "BLUE" } + List(8) { "NEUTRAL" } + "ASSASSIN").shuffled(random)
        return result(
            matrix(5, 5) { row, col -> GenericCell(value = words[row * 5 + col], isRevealed = true) },
            matrix<Any?>(5, 5) { row, col -> identities[row * 5 + col] },
            mapOf("currentTeam" to "RED", "instructions" to "Encuentra las ocho palabras rojas y evita al asesino."),
        )
    }

    private fun capitalMove(value: Any?): LocalPuzzleMoveResult {
        val action = (value as? Map<*, *>)?.get("action")?.toString().orEmpty()
        when (action) {
            "ROLL" -> {
                if (capitalStage != "ROLL") return reject("Primero termina tu turno")
                capitalDice = listOf(random.nextInt(1, 7), random.nextInt(1, 7))
                val raw = capitalPosition + capitalDice.sum()
                if (raw >= 40) capitalBalance += 200
                capitalPosition = raw % 40
                val space = (blueprint.meta["spaces"] as List<Map<String, Any?>>)[capitalPosition]
                val price = space["price"] as Int
                when (space["type"]) {
                    "CHANCE" -> drawLocalCapitalCard()
                    "GO_TO_JAIL" -> {
                        capitalPosition = 10; capitalStage = "END"; capitalEvent = "Vas directamente a la cárcel"
                    }
                    "TAX" -> {
                        capitalBalance = (capitalBalance - 120).coerceAtLeast(0)
                        capitalStage = "END"; capitalEvent = "Impuesto de infraestructura: -120"
                    }
                    else -> {
                        capitalPending = capitalPosition.takeIf { price > 0 && capitalOwners[it] == null }
                        capitalStage = if (capitalPending != null) "BUY_OR_END" else "END"
                        capitalEvent = if (capitalPending != null) "${space["name"]} disponible por $price" else "Caíste en ${space["name"]}"
                    }
                }
            }
            "BUY" -> {
                val index = capitalPending ?: return reject("No hay una propiedad disponible")
                @Suppress("UNCHECKED_CAST")
                val space = (blueprint.meta["spaces"] as List<Map<String, Any?>>)[index]
                val price = space["price"] as Int
                if (capitalBalance < price) return reject("Capital insuficiente")
                capitalBalance -= price; capitalOwners[index] = OWNER; capitalLevels[index] = 0
                board.flatten().firstOrNull { (it.meta["index"] as? Number)?.toInt() == index }?.let { target ->
                    val position = board.indices.firstNotNullOf { r -> board[r].indices.firstOrNull { c -> board[r][c] === target }?.let { r to it } }
                    replace(position.first, position.second, target.copy(ownerId = OWNER))
                }
                capitalPending = null; capitalStage = "END"; capitalEvent = "Propiedad conquistada"
            }
            "BUILD" -> {
                if (capitalOwners[capitalPosition] != OWNER) return reject("Debes estar en una propiedad propia")
                if (capitalBalance < 50) return reject("Capital insuficiente")
                capitalBalance -= 50; capitalLevels[capitalPosition] = (capitalLevels[capitalPosition] ?: 0) + 1
                capitalEvent = "Mejora de hackeo construida"
            }
            "END_TURN" -> {
                capitalStage = "ROLL"; capitalPending = null; capitalEvent = "Lanza los dados"
            }
            else -> return reject("Acción económica no válida")
        }
        revision += 1
        return LocalPuzzleMoveResult(true, snapshot(), points = 0, message = capitalEvent)
    }

    private fun drawLocalCapitalCard() {
        val cards = listOf(
            Triple("Hackathon Maestro", "Recibes 200 créditos", "BONUS"),
            Triple("Inversión Relámpago", "Recibes 120 créditos", "BONUS"),
            Triple("Fallo de Servidor", "Pagas una multa de 100 créditos", "PENALTY"),
            Triple("Auditoría de la Arena", "Pagas una multa de 160 créditos", "PENALTY"),
            Triple("Atajo Quantum", "Avanzas 3 casillas", "MOVE"),
            Triple("Firewall Policial", "Vas directamente a la cárcel", "PENALTY"),
        )
        val index = random.nextInt(cards.size)
        val selected = cards[index]
        when (index) {
            0 -> capitalBalance += 200
            1 -> capitalBalance += 120
            2 -> capitalBalance = (capitalBalance - 100).coerceAtLeast(0)
            3 -> capitalBalance = (capitalBalance - 160).coerceAtLeast(0)
            4 -> {
                val raw = capitalPosition + 3
                if (raw >= 40) capitalBalance += 200
                capitalPosition = raw % 40
            }
            5 -> capitalPosition = 10
        }
        capitalCard = mapOf(
            "id" to "local-$revision-${random.nextInt()}",
            "playerId" to OWNER,
            "title" to selected.first,
            "description" to selected.second,
            "kind" to selected.third,
        )
        capitalPending = null
        capitalStage = "END"
        capitalEvent = "🎴 ${selected.first}: ${selected.second}"
    }

    private fun capitalArena(): Blueprint {
        val names = listOf("SALIDA") + (1 until 40).map { "Distrito $it" }
        val coordinates = buildList {
            for (col in 10 downTo 0) add(10 to col)
            for (row in 9 downTo 0) add(row to 0)
            for (col in 1..10) add(0 to col)
            for (row in 1..9) add(row to 10)
        }
        val spaces = names.mapIndexed { index, name ->
            val special = index in setOf(0, 2, 4, 7, 10, 17, 20, 22, 30, 33, 37)
            mapOf<String, Any?>(
                "index" to index, "name" to name,
                "type" to if (special) if (index == 0) "START" else "CHANCE" else "PROPERTY",
                "price" to if (special) 0 else 100 + index * 5, "rent" to if (special) 0 else 15 + index,
            )
        }
        val lookup = coordinates.mapIndexed { index, coordinate -> coordinate to spaces[index] }.toMap()
        return result(
            matrix(11, 11) { row, col ->
                lookup[row to col]?.let { space ->
                    GenericCell(isRevealed = true, meta = space)
                } ?: blocked(mapOf("capitalCenter" to true))
            },
            matrix<Any?>(11, 11) { _, _ -> null },
            mapOf("spaces" to spaces, "instructions" to "Lanza dados, compra propiedades y construye mejoras."),
        )
    }

    private fun blocked(meta: Map<String, Any?> = emptyMap()) = GenericCell(isRevealed = true, isBlocked = true, meta = meta)
    private fun size(easy:Int,medium:Int,hard:Int,expert:Int)=when(difficulty){PuzzleDifficulty.EASY->easy;PuzzleDifficulty.MEDIUM->medium;PuzzleDifficulty.HARD->hard;PuzzleDifficulty.EXPERT->expert}
    private fun <T> matrix(rows:Int,cols:Int,create:(Int,Int)->T)=List(rows){r->List(cols){c->create(r,c)}}

    private companion object {
        const val OWNER = "solo"
        val SIDES = listOf("top", "right", "bottom", "left")
        val WORDS = listOf(
            "SOL" to "Estrella luminosa en el centro de nuestro sistema",
            "ARENA" to "Recinto donde se enfrentan los competidores", "LOGICA" to "Disciplina que estudia el razonamiento válido",
            "MATRIZ" to "Conjunto rectangular organizado en filas y columnas", "PUZZLE" to "Juego que exige resolver un problema",
            "MENTE" to "Facultad de pensar, recordar e imaginar", "CLAVE" to "Dato indispensable para descifrar un mensaje",
            "CIFRA" to "Símbolo utilizado para representar un número", "ISLA" to "Tierra rodeada completamente por agua",
            "PUENTE" to "Construcción que une dos orillas", "LAZO" to "Vuelta cerrada hecha con una línea",
            "COLOR" to "Sensación visual producida por la luz", "NEON" to "Gas noble usado en letreros luminosos",
            "EQUIPO" to "Grupo que coopera para lograr una meta", "RIVAL" to "Persona que compite por el mismo objetivo",
            "RETO" to "Objetivo difícil que pone a prueba una habilidad", "NIVEL" to "Grado de dificultad",
            "SUMA" to "Operación que reúne dos o más cantidades", "CELDA" to "Espacio individual de una cuadrícula",
            "FICHA" to "Pieza pequeña utilizada para jugar", "PISTA" to "Indicio que ayuda a descubrir una respuesta",
            "RIO" to "Corriente natural de agua", "MINA" to "Carga explosiva oculta bajo una superficie",
            "BOMBA" to "Artefacto diseñado para producir una explosión", "TRAZO" to "Línea realizada al dibujar",
            "JUGADA" to "Acción ejecutada durante una partida", "VICTORIA" to "Resultado favorable de quien gana",
            "ESCUDO" to "Objeto defensivo que protege de un ataque", "NIEBLA" to "Nube baja que dificulta la visibilidad",
            "ENERGIA" to "Capacidad para realizar trabajo", "COMBO" to "Cadena de aciertos consecutivos",
            "CAMINO" to "Ruta que conduce de un lugar a otro", "BLOQUE" to "Pieza compacta de material",
        )
    }
}
