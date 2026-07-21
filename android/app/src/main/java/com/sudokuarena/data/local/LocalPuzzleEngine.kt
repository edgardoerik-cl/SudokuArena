package com.sudokuarena.data.local

import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.GenericBoardState
import com.sudokuarena.domain.GenericCell
import com.sudokuarena.domain.PuzzleDifficulty
import kotlin.random.Random
import kotlin.math.abs
import kotlin.math.hypot

private data class LocalTowerEnemy(
    val id: String,
    val kind: String,
    var hp: Float,
    val maxHp: Float,
    var progress: Float,
    val speed: Float,
    val spawnAt: Long,
    var slowUntil: Long = 0L,
    var status: String = "WAITING",
)

private data class LocalTowerProjectile(
    val id: String,
    val towerRow: Int,
    val towerCol: Int,
    val targetId: String,
    val color: String,
    val damage: Float,
    val towerType: String,
    val firedAt: Long,
    val arrivesAt: Long,
)

private fun localTowerPosition(progress: Float, path: List<Pair<Int, Int>>): Pair<Double, Double> {
    val start = progress.toInt().coerceIn(0, path.lastIndex)
    val end = (start + 1).coerceAtMost(path.lastIndex)
    val fraction = (progress - start).coerceIn(0f, 1f)
    return (path[start].first + (path[end].first - path[start].first) * fraction).toDouble() to
        (path[start].second + (path[end].second - path[start].second) * fraction).toDouble()
}

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
    private var hangmanRevealUsed = false
    private var hangmanDiscardUsed = false
    private var hangmanLastBreathUsed = false
    private val hangmanDiscarded = mutableSetOf<String>()
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
    private var arrowRotateUses = 0
    private var arrowMissileUses = 0
    private var nurikabeSonarUses = 0
    private var memoryFirstPick: Pair<Int, Int>? = null
    private var nexusRound = 1
    private var towerCredits = 400
    private var towerWave = 0
    private var towerBaseHealth = 20
    private var towerWaveActive = false
    private var towerLastTickAt = 0L
    private val towerEnemies = mutableListOf<LocalTowerEnemy>()
    private val towerProjectiles = mutableListOf<LocalTowerProjectile>()
    private val towerNextShotAt = mutableMapOf<String, Long>()
    private var reactorRemoved = 0

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
                "correctGuesses" to guessedLetters.filter { guess -> blueprint.answers.flatten().any { it?.toString() == guess } },
                "revealUsed" to if (hangmanRevealUsed) listOf(OWNER) else emptyList<String>(),
                "discardUsed" to if (hangmanDiscardUsed) listOf(OWNER) else emptyList<String>(),
                "lastBreathUsed" to if (hangmanLastBreathUsed) listOf(OWNER) else emptyList<String>(),
                "discardedByPlayer" to mapOf(OWNER to hangmanDiscarded.toList()),
                "hiddenWord" to board.firstOrNull().orEmpty().map { it.value?.toString() ?: "_" },
            ) else emptyMap<String, Any?>() +
            if (gameType in setOf(GameType.CHECKERS, GameType.CHESS_TACTICS)) mapOf(
                "localTurnTeam" to localTurnTeam,
                "instructions" to "Modo Hotseat: entrega el teléfono al equipo ${if (localTurnTeam == "BLUE") "Azul" else "Rojo"}.",
            ) else emptyMap<String, Any?>() +
            if (gameType == GameType.ARROWS_ESCAPE) mapOf(
                "rotateUses" to mapOf(OWNER to arrowRotateUses),
                "missileUses" to mapOf(OWNER to arrowMissileUses),
                "removedByPlayer" to mapOf(OWNER to board.firstOrNull().orEmpty().mapIndexedNotNull { index, cell ->
                    if (cell.ownerId == OWNER) "0:$index" else null
                }),
                "progress" to mapOf(OWNER to board.firstOrNull().orEmpty().count { it.ownerId == OWNER }),
            ) else emptyMap<String, Any?>() +
            if (gameType == GameType.NURIKABE) mapOf(
                "sonarUses" to mapOf(OWNER to nurikabeSonarUses),
            ) else emptyMap<String, Any?>() +
            if (gameType == GameType.NEXUS_ZERO) mapOf(
                "nexusRound" to nexusRound,
                "nexusTargetRounds" to (blueprint.meta["nexusTargetRounds"] ?: 3),
            ) else emptyMap<String, Any?>() +
            if (gameType == GameType.TOWER_DEFENSE) mapOf(
                "wave" to towerWave,
                "baseHealth" to towerBaseHealth,
                "credits" to mapOf(OWNER to towerCredits),
                "maxWaves" to 20,
                "waveActive" to towerWaveActive,
                "remainingEnemies" to towerEnemies.count { it.status == "WAITING" || it.status == "MOVING" },
                "enemies" to towerEnemies.map { enemy -> mapOf(
                    "id" to enemy.id, "kind" to enemy.kind, "hp" to enemy.hp, "maxHp" to enemy.maxHp,
                    "progress" to enemy.progress, "speed" to enemy.speed, "spawnAt" to enemy.spawnAt,
                    "slowUntil" to enemy.slowUntil, "status" to enemy.status,
                ) },
                "projectiles" to towerProjectiles.map { projectile -> mapOf(
                    "id" to projectile.id, "towerRow" to projectile.towerRow, "towerCol" to projectile.towerCol,
                    "targetId" to projectile.targetId, "color" to projectile.color,
                    "damage" to projectile.damage, "towerType" to projectile.towerType,
                    "firedAt" to projectile.firedAt, "arrivesAt" to projectile.arrivesAt,
                ) },
            ) else emptyMap<String, Any?>() +
            if (gameType == GameType.REACTOR_CHAIN) mapOf(
                "removed" to reactorRemoved,
                "targetRemoved" to (blueprint.meta["targetRemoved"] ?: 100),
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
        if (gameType == GameType.MERGE_2048) return merge2048Move(value)
        if (gameType == GameType.TOWER_DEFENSE) return towerDefenseMove(row, col, value)
        if (gameType == GameType.REACTOR_CHAIN) return reactorChainMove(row, col)
        if (gameType == GameType.MEMORY_NEON) {
            board.forEachIndexed { y, cells -> cells.forEachIndexed { x, candidate ->
                if (candidate.meta["mismatch"] == true) {
                    replace(y, x, candidate.copy(value = null, isRevealed = false, meta = candidate.meta + ("mismatch" to false)))
                }
            } }
            val activeCell = board[row][col]
            if (activeCell.ownerId != null || activeCell.value != null) return reject("Carta no disponible")
            replace(row, col, activeCell.copy(value = blueprint.answers[row][col], isRevealed = true))
            val first = memoryFirstPick
            if (first == null) {
                memoryFirstPick = row to col
                return accept(0)
            }
            memoryFirstPick = null
            val firstCell = board[first.first][first.second]
            return if (blueprint.answers[first.first][first.second] == blueprint.answers[row][col]) {
                replace(first.first, first.second, firstCell.copy(ownerId = OWNER))
                replace(row, col, board[row][col].copy(ownerId = OWNER))
                accept(30)
            } else {
                replace(first.first, first.second, firstCell.copy(meta = firstCell.meta + ("mismatch" to true)))
                replace(row, col, board[row][col].copy(meta = board[row][col].meta + ("mismatch" to true)))
                accept(0)
            }
        }
        if (gameType == GameType.HITORI && (value as? Map<*, *>)?.get("action")?.toString()?.uppercase() == "HINT") {
            var candidate: Pair<Int, Int>? = null
            board.forEachIndexed { y, cells -> cells.forEachIndexed { x, target ->
                if (candidate == null && blueprint.answers[y][x] == true && target.ownerId == null) candidate = y to x
            } }
            if (candidate == null) board.forEachIndexed { y, cells -> cells.forEachIndexed { x, target ->
                if (candidate == null && blueprint.answers[y][x] != true && target.meta["hintColor"] == null) candidate = y to x
            } }
            val (hintRow, hintCol) = candidate ?: return reject("No quedan deducciones")
            val target = board[hintRow][hintCol]
            replace(hintRow, hintCol, target.copy(meta = target.meta + ("hintColor" to if (blueprint.answers[hintRow][hintCol] == true) "RED" else "GREEN")))
            return accept(0)
        }
        if (cell.isBlocked || (cell.ownerId != null && gameType !in setOf(GameType.TETRIS_ARENA, GameType.NURIKABE, GameType.HITORI, GameType.CROSS_LETTERS, GameType.WORD_SEARCH))) return reject("Casilla no disponible")

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
            val payload = value as? Map<*, *>
            when (payload?.get("action")?.toString()?.uppercase()) {
                "REVEAL" -> {
                    if (hangmanRevealUsed) return reject("Revelación Clara ya fue utilizada")
                    val letter = blueprint.answers.flatten().mapNotNull { it?.toString() }.firstOrNull { it !in guessedLetters }
                        ?: return reject("No quedan letras")
                    hangmanRevealUsed = true
                    return revealHangmanLetter(letter, 0)
                }
                "DISCARD" -> {
                    if (hangmanDiscardUsed) return reject("Descarte Táctico ya fue utilizado")
                    hangmanDiscardUsed = true
                    hangmanDiscarded += "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ".map(Char::toString)
                        .filter { candidate -> blueprint.answers.flatten().none { it?.toString() == candidate } && candidate !in guessedLetters }
                        .shuffled(random).take(3)
                    revision += 1
                    return LocalPuzzleMoveResult(true, snapshot(), message = "Tres letras incorrectas descartadas")
                }
            }
            val letter = (payload?.get("letter") ?: value)?.toString()?.uppercase()?.takeIf { it.length == 1 && it[0] in "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ" }
                ?: return reject("Selecciona una letra")
            if (!guessedLetters.add(letter)) return reject("Letra ya utilizada")
            var hits = 0
            blueprint.answers.first().forEachIndexed { index, answer ->
                if (answer?.toString() == letter) {
                    replace(0, index, board[0][index].copy(value = letter, isRevealed = true, ownerId = OWNER))
                    hits += 1
                }
            }
            if (hits == 0) {
                hangmanErrors += 1
                if (hangmanErrors >= 6 && !hangmanLastBreathUsed) {
                    hangmanLastBreathUsed = true
                    hangmanErrors = 5
                }
            }
            revision += 1
            return LocalPuzzleMoveResult(
                accepted = true,
                state = snapshot(),
                points = hits * 12,
                message = if (hits > 0) "¡Letra correcta!" else "Esa letra no aparece",
            )
        }
        if (gameType == GameType.ARROWS_ESCAPE) {
            val action = (value as? Map<*, *>)?.get("action")?.toString()?.uppercase() ?: "ESCAPE"
            if (action == "ROTATE") {
                if (arrowRotateUses >= 2) return reject("No quedan rotaciones")
                val next = when (cell.value?.toString()) { "UP" -> "RIGHT"; "RIGHT" -> "DOWN"; "DOWN" -> "LEFT"; else -> "UP" }
                replace(row, col, cell.copy(value = next, meta = cell.meta + ("arrow" to next)))
                arrowRotateUses++
                return accept(0)
            }
            if (action == "MISSILE") {
                if (arrowMissileUses >= 1) return reject("Misil ya utilizado")
                arrowMissileUses++
                replace(row, col, cell.copy(ownerId = OWNER, isRevealed = true))
                return accept(15)
            }
            if (!localArrowCanEscape(col)) return reject("La punta todavía está bloqueada por otra ruta")
            replace(row, col, cell.copy(ownerId = OWNER, isRevealed = true))
            return accept(10)
        }
        if (gameType == GameType.CHECKERS) return checkersMove(row, col, value, cell)
        if (gameType == GameType.CHESS_TACTICS) return chessHotseatMove(row, col, value, cell)
        if (gameType == GameType.NURIKABE) {
            if ((value as? Map<*, *>)?.get("action")?.toString()?.uppercase() == "SONAR") {
                if (nurikabeSonarUses >= 3) return reject("No quedan pulsos")
                for (y in maxOf(0, row - 1)..minOf(board.lastIndex, row + 1)) {
                    for (x in maxOf(0, col - 1)..minOf(board[y].lastIndex, col + 1)) {
                        val target = board[y][x]
                        replace(y, x, target.copy(meta = target.meta + ("sonarState" to if (blueprint.answers[y][x] == true) "RIVER" else "ISLAND")))
                    }
                }
                nurikabeSonarUses++
                return accept(0)
            }
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
            return nexusSwipe(value)
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
        GameType.NEXUS_ZERO -> board.flatten().all { it.value == null } &&
            nexusRound >= ((blueprint.meta["nexusTargetRounds"] as? Number)?.toInt() ?: 3)
        GameType.MEMORY_NEON -> board.flatten().all { it.ownerId != null }
        GameType.MERGE_2048 -> {
            val target = (blueprint.meta["target"] as? Number)?.toInt() ?: 256
            board.flatten().any { ((it.value as? Number)?.toInt() ?: 0) >= target } || mergeHasNoMoves()
        }
        GameType.TOWER_DEFENSE -> towerBaseHealth <= 0 || (towerWave >= 20 && !towerWaveActive)
        GameType.REACTOR_CHAIN -> reactorRemoved >= ((blueprint.meta["targetRemoved"] as? Number)?.toInt() ?: 100)
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
        GameType.MEMORY_NEON -> memoryNeon()
        GameType.MERGE_2048 -> merge2048()
        GameType.TOWER_DEFENSE -> towerDefense()
        GameType.REACTOR_CHAIN -> reactorChain()
        GameType.TETRIS_ARENA, GameType.PACMAN_ARENA, GameType.DEMOLITION_ARCADE ->
            result(listOf(listOf(GenericCell(isBlocked = true))), listOf(listOf(null)), mapOf("actionMode" to true))
        GameType.SUDOKU -> error("Sudoku usa RandomSudokuGenerator")
    }

    private fun ticTacToe(): Blueprint = result(
        matrix(3, 3) { _, _ -> GenericCell() },
        matrix(3, 3) { _, _ -> null },
        mapOf("turnBased" to true),
    )

    private fun memoryNeon(): Blueprint {
        val (rows, columns) = when (difficulty) {
            PuzzleDifficulty.EASY -> 4 to 4
            PuzzleDifficulty.EXPERT -> 6 to 6
            else -> 4 to 6
        }
        val symbols = listOf("◆", "●", "▲", "★", "☀", "☾", "⚡", "✦", "⬢", "♣", "♥", "♠", "♫", "☂", "✿", "☯", "☕", "∞")
        val values = symbols.take(rows * columns / 2).flatMap { listOf(it, it) }.shuffled(random)
        return result(
            matrix(rows, columns) { _, _ -> GenericCell(meta = mapOf("card" to true)) },
            matrix(rows, columns) { row, col -> values[row * columns + col] },
            mapOf(
                "pairCount" to rows * columns / 2,
                "instructions" to "Encuentra parejas. La primera carta queda visible y una pareja correcta se conquista.",
            ),
        )
    }

    private fun merge2048(): Blueprint {
        val cells = MutableList(16) { GenericCell() }
        listOf(0, 10).forEach { index -> cells[index] = GenericCell(value = 2, isRevealed = true) }
        val target = when (difficulty) {
            PuzzleDifficulty.EASY -> 128
            PuzzleDifficulty.MEDIUM -> 256
            PuzzleDifficulty.HARD -> 512
            PuzzleDifficulty.EXPERT -> 1024
        }
        return result(
            cells.chunked(4),
            matrix(4, 4) { _, _ -> null },
            mapOf("actionMode" to true, "engine" to "MERGE_2048", "target" to target, "instructions" to "Combina fichas iguales y alcanza $target."),
        )
    }

    private fun merge2048Move(rawDirection: Any?): LocalPuzzleMoveResult {
        val direction = rawDirection?.toString()?.uppercase()
        if (direction !in setOf("UP", "RIGHT", "DOWN", "LEFT")) return reject("Dirección inválida")
        val old = board.map { row -> row.map { (it.value as? Number)?.toInt() ?: 0 } }
        val next = old.map { it.toMutableList() }.toMutableList()
        var score = 0
        fun slide(input: List<Int>): List<Int> {
            val compact = input.filter { it > 0 }
            val output = mutableListOf<Int>()
            var index = 0
            while (index < compact.size) {
                if (index + 1 < compact.size && compact[index] == compact[index + 1]) {
                    output += compact[index] * 2
                    score += compact[index] * 2
                    index += 2
                } else {
                    output += compact[index]
                    index += 1
                }
            }
            while (output.size < 4) output += 0
            return output
        }
        if (direction == "LEFT" || direction == "RIGHT") {
            repeat(4) { row ->
                val source = if (direction == "RIGHT") old[row].reversed() else old[row]
                next[row] = (if (direction == "RIGHT") slide(source).reversed() else slide(source)).toMutableList()
            }
        } else repeat(4) { col ->
            val source = (0..3).map { old[it][col] }.let { if (direction == "DOWN") it.reversed() else it }
            val result = slide(source).let { if (direction == "DOWN") it.reversed() else it }
            repeat(4) { row -> next[row][col] = result[row] }
        }
        if (next == old) return reject("Ese deslizamiento no mueve fichas")
        board = next.mapIndexed { row, values ->
            values.mapIndexed { col, number ->
                GenericCell(
                    value = number.takeIf { it > 0 },
                    isRevealed = number > 0,
                    ownerId = OWNER.takeIf { number > 0 && number != old[row][col] },
                )
            }
        }
        val empty = board.flatMapIndexed { row, cells -> cells.mapIndexedNotNull { col, target -> if (target.value == null) row to col else null } }
        empty.randomOrNull(random)?.let { (row, col) -> replace(row, col, GenericCell(value = if (random.nextFloat() < .9f) 2 else 4, isRevealed = true)) }
        return accept(maxOf(2, score))
    }

    private fun mergeHasNoMoves(): Boolean {
        if (board.flatten().any { it.value == null }) return false
        repeat(4) { row -> repeat(4) { col ->
            val value = board[row][col].value
            if (board.getOrNull(row + 1)?.get(col)?.value == value ||
                board[row].getOrNull(col + 1)?.value == value
            ) return false
        } }
        return true
    }

    private fun revealHangmanLetter(letter: String, points: Int): LocalPuzzleMoveResult {
        guessedLetters += letter
        blueprint.answers.first().forEachIndexed { index, answer ->
            if (answer?.toString() == letter) {
                replace(0, index, board[0][index].copy(value = letter, isRevealed = true, ownerId = OWNER))
            }
        }
        revision += 1
        return LocalPuzzleMoveResult(true, snapshot(), points = points, message = "Revelación Clara activada")
    }

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

    private fun localArrowCanEscape(index: Int): Boolean {
        val shapes = blueprint.meta["shapes"] as? List<*> ?: return false
        val route = shapes.getOrNull(index) as? Map<*, *> ?: return false
        fun pointsOf(raw: Map<*, *>): List<Pair<Float, Float>> = (raw["points"] as? List<*>)?.mapNotNull { item ->
            val point = item as? Map<*, *> ?: return@mapNotNull null
            ((point["x"] as? Number)?.toFloat() ?: return@mapNotNull null) to
                ((point["y"] as? Number)?.toFloat() ?: return@mapNotNull null)
        }.orEmpty()
        val points = pointsOf(route)
        val head = points.lastOrNull() ?: return false
        val direction = board.firstOrNull()?.getOrNull(index)?.value?.toString() ?: route["direction"]?.toString()
        val vector = when (direction) {
            "UP" -> 0f to -1f; "RIGHT" -> 1f to 0f; "DOWN" -> 0f to 1f; else -> -1f to 0f
        }
        val distance = listOfNotNull(
            if (vector.first > 0) (1.08f - head.first) / vector.first else null,
            if (vector.first < 0) (-.08f - head.first) / vector.first else null,
            if (vector.second > 0) (1.08f - head.second) / vector.second else null,
            if (vector.second < 0) (-.08f - head.second) / vector.second else null,
        ).filter { it > 0 }.minOrNull() ?: return false
        fun pointSegmentDistance(point: Pair<Float, Float>, start: Pair<Float, Float>, end: Pair<Float, Float>): Float {
            val dx = end.first - start.first; val dy = end.second - start.second
            val square = dx * dx + dy * dy
            if (square == 0f) return kotlin.math.hypot(point.first - start.first, point.second - start.second)
            val t = (((point.first - start.first) * dx + (point.second - start.second) * dy) / square).coerceIn(0f, 1f)
            return kotlin.math.hypot(point.first - start.first - t * dx, point.second - start.second - t * dy)
        }
        return shapes.indices.none { obstacleIndex ->
            if (obstacleIndex == index || board.first()[obstacleIndex].ownerId == OWNER) return@none false
            val obstacle = shapes[obstacleIndex] as? Map<*, *> ?: return@none false
            val obstaclePoints = pointsOf(obstacle)
            val clearance = ((route["thickness"] as? Number)?.toFloat() ?: .014f) +
                ((obstacle["thickness"] as? Number)?.toFloat() ?: .014f) + .008f
            obstaclePoints.zipWithNext().any { (start, end) ->
                (1..32).any { step ->
                    val t = step / 32f
                    val sample = (head.first + vector.first * distance * t) to (head.second + vector.second * distance * t)
                    pointSegmentDistance(sample, start, end) <= clearance
                }
            }
        }
    }

    private fun arrowsEscape(): Blueprint {
        val count = size(12, 16, 20, 24)
        val columns = 4
        val shapes = (0 until count).map { index ->
            val column = index % columns
            val layer = index / columns
            val fromTop = column % 2 == 0
            val centerX = (column + .5f) / columns
            val baseY = if (fromTop) .12f + layer * .135f else .88f - layer * .135f
            val direction = if (fromTop) "UP" else "DOWN"
            val points = if (fromTop) listOf(
                mapOf("x" to centerX - .055f, "y" to baseY + .09f),
                mapOf("x" to centerX + .045f, "y" to baseY + .09f),
                mapOf("x" to centerX + .045f, "y" to baseY + .035f),
                mapOf("x" to centerX, "y" to baseY),
            ) else listOf(
                mapOf("x" to centerX + .055f, "y" to baseY - .09f),
                mapOf("x" to centerX - .045f, "y" to baseY - .09f),
                mapOf("x" to centerX - .045f, "y" to baseY - .035f),
                mapOf("x" to centerX, "y" to baseY),
            )
            mapOf(
                "id" to "route-$index", "points" to points, "direction" to direction,
                "exitVector" to mapOf("x" to 0, "y" to if (fromTop) -1 else 1),
                "thickness" to .014f, "blockType" to if (index > 0 && index % 7 == 0) "BOMB" else "NORMAL",
                "memberKeys" to listOf("0:$index"), "removalOrder" to index,
            )
        }
        return result(
            listOf(shapes.mapIndexed { index, shape ->
                val direction = shape["direction"]
                GenericCell(direction, true, meta = mapOf(
                    "arrow" to direction, "shapeId" to "route-$index", "shapeAnchor" to true,
                    "pathType" to "SERPENTINE", "blockType" to shape["blockType"],
                ))
            }),
            listOf(shapes.map { it["direction"] }),
            mapOf(
                "freeSpace" to true, "pathModel" to "SERPENTINE_V2", "worldWidth" to 1, "worldHeight" to 1,
                "totalBlocks" to count, "totalShapes" to count, "rotatePowerUses" to 2, "missilePowerUses" to 1,
                "shapes" to shapes,
                "instructions" to "Toca una ruta o su punta cuando el trayecto de salida hasta el borde esté libre.",
            ),
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
        val boardSize = size(5, 6, 7, 8)
        val pairsPerRow = maxOf(1, (boardSize * .36f).toInt())
        val pairCount = pairsPerRow * boardSize
        val cells = MutableList(boardSize) { MutableList(boardSize) { GenericCell() } }
        repeat(boardSize) { row ->
            val columns = (0 until boardSize).shuffled(random).take(pairsPerRow * 2).sorted()
            repeat(pairsPerRow) { pair ->
                val value = random.nextInt(1, 10)
                val ordered = if (random.nextBoolean()) listOf(value, -value) else listOf(-value, value)
                cells[row][columns[pair * 2]] = GenericCell(value = ordered[0], isRevealed = true, meta = mapOf("charge" to true))
                cells[row][columns[pair * 2 + 1]] = GenericCell(value = ordered[1], isRevealed = true, meta = mapOf("charge" to true))
            }
        }
        return result(
            cells,
            matrix(boardSize, boardSize) { _, _ -> null },
            mapOf(
                "actionMode" to true,
                "engine" to "NEXUS_SWIPE",
                "pairCount" to pairCount,
                "nexusTargetRounds" to when (difficulty) {
                    PuzzleDifficulty.EASY -> 3
                    PuzzleDifficulty.MEDIUM -> 4
                    PuzzleDifficulty.HARD -> 5
                    PuzzleDifficulty.EXPERT -> 6
                },
                "instructions" to "Desliza +N contra -N para crear Nexo Cero.",
            ),
        )
    }

    private fun nexusSwipe(raw: Any?): LocalPuzzleMoveResult {
        val direction = raw?.toString()?.uppercase()
        if (direction !in setOf("UP", "RIGHT", "DOWN", "LEFT")) return reject("Desliza en una dirección")
        val size = board.size
        val old = board.map { row -> row.map { (it.value as? Number)?.toInt() } }
        val next = MutableList(size) { MutableList<Int?>(size) { null } }
        var merges = 0
        fun line(values: List<Int?>): List<Int?> {
            val output = mutableListOf<Int>()
            values.filterNotNull().forEach { value ->
                if (output.lastOrNull()?.plus(value) == 0) { output.removeAt(output.lastIndex); merges++ }
                else output += value
            }
            return output.map<Int, Int?> { it } + List(size - output.size) { null }
        }
        repeat(size) { axis ->
            val horizontal = direction in setOf("LEFT", "RIGHT")
            var source = List(size) { index -> if (horizontal) old[axis][index] else old[index][axis] }
            if (direction in setOf("RIGHT", "DOWN")) source = source.reversed()
            var result = line(source)
            if (direction in setOf("RIGHT", "DOWN")) result = result.reversed()
            result.forEachIndexed { index, value -> if (horizontal) next[axis][index] = value else next[index][axis] = value }
        }
        if (next == old) return reject("Las fichas están bloqueadas en esa dirección")
        board = next.map { row -> row.map { value -> GenericCell(value = value, isRevealed = value != null, meta = if (value != null) mapOf("charge" to true) else emptyMap()) } }
        val targetRounds = (blueprint.meta["nexusTargetRounds"] as? Number)?.toInt() ?: 3
        if (board.flatten().all { it.value == null } && nexusRound < targetRounds) {
            nexusRound++
            spawnLocalNexusWave(maxOf(2, (size * .34f).toInt()))
        }
        return accept(merges * 25)
    }

    private fun spawnLocalNexusWave(pairCount: Int) {
        val size = board.size
        val positions = buildList {
            repeat(size) { row -> repeat(size) { col -> add(row to col) } }
        }.shuffled(random)
        val next = MutableList(size) { MutableList(size) { GenericCell() } }
        repeat(minOf(pairCount, positions.size / 2)) { pair ->
            val value = random.nextInt(1, 10)
            val sign = if (random.nextBoolean()) 1 else -1
            val first = positions[pair * 2]
            val second = positions[pair * 2 + 1]
            next[first.first][first.second] = GenericCell(value * sign, true, meta = mapOf("charge" to true))
            next[second.first][second.second] = GenericCell(-value * sign, true, meta = mapOf("charge" to true))
        }
        board = next
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

    private fun towerDefense(): Blueprint {
        val rows = 9
        val columns = 14
        val path = buildList {
            for (col in 0 until columns) add(1 to col)
            for (row in 2..4) add(row to columns - 1)
            for (col in columns - 2 downTo 1) add(4 to col)
            for (row in 5..7) add(row to 1)
            for (col in 2 until columns) add(7 to col)
        }
        val indexed = path.withIndex().associate { it.value to it.index }
        return result(
            matrix(rows, columns) { row, col ->
                val index = indexed[row to col]
                if (index == null) GenericCell(meta = mapOf("buildable" to true))
                else GenericCell(isRevealed = true, isBlocked = true, meta = mapOf(
                    "path" to true, "pathIndex" to index, "spawn" to (index == 0), "base" to (index == path.lastIndex),
                ))
            },
            matrix(rows, columns) { _, _ -> null },
            mapOf(
                "actionMode" to true,
                "engine" to "COOP_TOWER_DEFENSE",
                "path" to path.map { (row, col) -> mapOf("row" to row, "col" to col) },
                "maxWaves" to 20,
            ),
        )
    }

    private fun reactorChain(): Blueprint {
        val boardSize = size(7, 8, 9, 10)
        val colors = if (difficulty == PuzzleDifficulty.EASY) 4 else if (difficulty == PuzzleDifficulty.EXPERT) 6 else 5
        val guaranteed = random.nextInt(1, colors + 1)
        val reactorBoard = matrix(boardSize, boardSize) { row, col ->
            val value = if ((row == 0 && col <= 1) || (row == 1 && col == 0)) guaranteed
                else random.nextInt(1, colors + 1)
            GenericCell(value, true, meta = mapOf("reactorOrb" to true))
        }
        return result(
            reactorBoard,
            matrix(boardSize, boardSize) { _, _ -> null },
            mapOf("actionMode" to true, "engine" to "REACTOR_CHAIN", "colors" to colors, "targetRemoved" to boardSize * boardSize * 2),
        )
    }

    private fun towerDefenseMove(row: Int, col: Int, raw: Any?): LocalPuzzleMoveResult {
        val payload = raw as? Map<*, *>
        val action = payload?.get("action")?.toString()?.uppercase() ?: "BUILD"
        if (action == "START_WAVE") {
            if (towerWaveActive) return reject("La oleada actual sigue en combate")
            towerWave++
            val now = System.currentTimeMillis()
            val layers = 1 + towerWave / 5
            towerEnemies.clear()
            repeat(5 + towerWave * 2) { index ->
                val kind = when {
                    towerWave >= 12 && index % 7 == 0 -> "GOLEM"
                    towerWave >= 7 && index % 5 == 0 -> "PHANTOM"
                    index % 4 == 0 -> "BRUTE"
                    else -> "SCOUT"
                }
                val hp = (when (kind) { "GOLEM" -> 110f; "BRUTE" -> 58f; "PHANTOM" -> 38f; else -> 28f }) * layers
                val speed = when (kind) { "PHANTOM" -> 1.28f; "GOLEM" -> .46f; "BRUTE" -> .66f; else -> .9f }
                towerEnemies += LocalTowerEnemy("w$towerWave-e$index", kind, hp, hp, 0f, speed, now + index * 620L)
            }
            towerProjectiles.clear()
            towerWaveActive = true
            towerLastTickAt = now
            revision++
            return LocalPuzzleMoveResult(true, snapshot(), message = "Oleada $towerWave entrando en la arena")
        }
        val target = board.getOrNull(row)?.getOrNull(col) ?: return reject("Terreno inválido")
        if (action == "UPGRADE") {
            val level = (target.meta["level"] as? Number)?.toInt() ?: return reject("Selecciona una torre")
            if (level >= 3) return reject("Torre al nivel máximo")
            val cost = level * 100
            if (towerCredits < cost) return reject("Créditos insuficientes")
            towerCredits -= cost
            replace(row, col, target.copy(meta = target.meta + ("level" to level + 1)))
            return accept(10)
        }
        if (target.meta["buildable"] != true || target.meta["towerType"] != null) return reject("Selecciona un terreno libre")
        val type = payload?.get("towerType")?.toString()?.uppercase() ?: "RAPID"
        val cost = mapOf("RAPID" to 100, "BLAST" to 150, "SNIPER" to 180, "FROST" to 130)[type] ?: return reject("Torre inválida")
        if (towerCredits < cost) return reject("Créditos insuficientes")
        towerCredits -= cost
        replace(row, col, target.copy(value = type, isRevealed = true, ownerId = OWNER, meta = target.meta + mapOf("towerType" to type, "level" to 1)))
        return accept(10)
    }

    fun tickTowerDefense(now: Long = System.currentTimeMillis()): Boolean {
        if (gameType != GameType.TOWER_DEFENSE || !towerWaveActive) return false
        val path = (blueprint.meta["path"] as? List<*>)?.mapNotNull { raw ->
            val point = raw as? Map<*, *> ?: return@mapNotNull null
            ((point["row"] as? Number)?.toInt() ?: return@mapNotNull null) to
                ((point["col"] as? Number)?.toInt() ?: return@mapNotNull null)
        }.orEmpty()
        if (path.size < 2) return false
        val dt = if (towerLastTickAt == 0L) .05f else ((now - towerLastTickAt) / 1_000f).coerceIn(.01f, .2f)
        towerLastTickAt = now
        towerEnemies.forEach { enemy ->
            if (enemy.status == "WAITING" && now >= enemy.spawnAt) enemy.status = "MOVING"
            if (enemy.status != "MOVING") return@forEach
            enemy.progress += enemy.speed * (if (enemy.slowUntil > now) .58f else 1f) * dt
            if (enemy.progress >= path.lastIndex) {
                enemy.status = "LEAKED"
                towerBaseHealth = (towerBaseHealth - when (enemy.kind) { "GOLEM" -> 3; "BRUTE" -> 2; else -> 1 }).coerceAtLeast(0)
            }
        }
        if (towerBaseHealth <= 0) {
            towerEnemies.filter { it.status == "WAITING" || it.status == "MOVING" }.forEach { it.status = "LEAKED" }
        }
        val active = towerEnemies.filter { it.status == "MOVING" }
        board.forEachIndexed { towerRow, cells -> cells.forEachIndexed { towerCol, tower ->
            val type = tower.meta["towerType"]?.toString() ?: return@forEachIndexed
            val level = (tower.meta["level"] as? Number)?.toInt() ?: 1
            val range = when (type) { "SNIPER" -> 6.4; "BLAST" -> 3.2; "FROST" -> 3.7; else -> 3.4 }
            val key = "$towerRow:$towerCol"
            if ((towerNextShotAt[key] ?: 0L) > now) return@forEachIndexed
            val target = active.map { enemy ->
                val point = localTowerPosition(enemy.progress, path)
                enemy to hypot(point.first - towerRow.toDouble(), point.second - towerCol.toDouble())
            }.filter { it.second <= range }.maxByOrNull { it.first.progress }?.first ?: return@forEachIndexed
            val damage = (when (type) { "SNIPER" -> 22f; "BLAST" -> 13f; "FROST" -> 7f; else -> 9f }) * level
            target.hp -= damage
            if (type == "FROST") target.slowUntil = now + 1_700
            if (target.hp <= 0) { target.hp = 0f; target.status = "DEFEATED" }
            towerProjectiles += LocalTowerProjectile(
                "$now-$key", towerRow, towerCol, target.id,
                when (type) { "FROST" -> "#22D3EE"; "BLAST" -> "#FB923C"; "SNIPER" -> "#C084FC"; else -> "#60A5FA" },
                damage, type,
                now, now + if (type == "SNIPER") 120 else 240,
            )
            towerNextShotAt[key] = now + when (type) { "RAPID" -> 360L; "SNIPER" -> 1_100L; else -> 700L }
        } }
        towerProjectiles.removeAll { it.arrivesAt + 220 < now }
        if (towerEnemies.none { it.status == "WAITING" || it.status == "MOVING" }) {
            towerWaveActive = false
            val defeated = towerEnemies.count { it.status == "DEFEATED" }
            towerCredits += defeated * (8 + towerWave / 4)
        }
        revision++
        return true
    }

    private fun reactorChainMove(row: Int, col: Int): LocalPuzzleMoveResult {
        val color = (board.getOrNull(row)?.getOrNull(col)?.value as? Number)?.toInt() ?: return reject("Núcleo vacío")
        val queue = ArrayDeque<Pair<Int, Int>>()
        val group = linkedSetOf<Pair<Int, Int>>()
        queue.add(row to col)
        while (queue.isNotEmpty()) {
            val point = queue.removeFirst()
            if (point in group || (board.getOrNull(point.first)?.getOrNull(point.second)?.value as? Number)?.toInt() != color) continue
            group += point
            queue.add(point.first - 1 to point.second)
            queue.add(point.first + 1 to point.second)
            queue.add(point.first to point.second - 1)
            queue.add(point.first to point.second + 1)
        }
        if (group.size < 3) return reject("Necesitas 3 núcleos conectados")
        val colors = (blueprint.meta["colors"] as? Number)?.toInt() ?: 5
        val next = board.map { it.toMutableList() }.toMutableList()
        group.forEach { (y, x) -> next[y][x] = GenericCell() }
        for (x in next[0].indices) {
            val values = next.mapNotNull { (it[x].value as? Number)?.toInt() }.toMutableList()
            while (values.size < next.size) values.add(0, random.nextInt(1, colors + 1))
            values.forEachIndexed { y, value -> next[y][x] = GenericCell(value, true, meta = mapOf("reactorOrb" to true)) }
        }
        board = next
        reactorRemoved += group.size
        return accept(group.size * group.size)
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
