package com.sudokuarena.data.local

import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.GenericBoardState
import com.sudokuarena.domain.GenericCell
import com.sudokuarena.domain.PuzzleDifficulty
import kotlin.random.Random

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

    fun snapshot() = GenericBoardState(
        gameId = "local-${gameType.name.lowercase()}-${blueprint.seed}", gameType = gameType,
        revision = revision, serverTime = System.currentTimeMillis(), rows = board.size,
        columns = board.firstOrNull()?.size ?: 0, board = board, completed = isComplete(), meta = blueprint.meta,
    )

    fun letterRack(): List<String> = (blueprint.meta["rack"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()

    fun move(row: Int, col: Int, value: Any?): LocalPuzzleMoveResult {
        val cell = board.getOrNull(row)?.getOrNull(col) ?: return reject("Movimiento fuera del tablero")
        if (cell.isBlocked || (cell.ownerId != null && gameType !in setOf(GameType.SLITHERLINK, GameType.NURIKABE, GameType.CROSS_LETTERS, GameType.WORD_SEARCH))) return reject("Casilla no disponible")

        if (gameType == GameType.CROSS_LETTERS) {
            val payload = value as? Map<*, *> ?: return incorrect()
            val word = payload["word"]?.toString()?.uppercase().orEmpty()
            val direction = payload["direction"]?.toString()?.uppercase().orEmpty()
            if (word != "ARENA" || direction != "H" || row != 7 || col != 5) return incorrect()
            word.forEachIndexed { index, letter -> replace(7, 5 + index, board[7][5 + index].copy(value = letter.toString(), isRevealed = true, ownerId = OWNER)) }
            return accept(50)
        }
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
        if (gameType == GameType.DOTS_AND_BOXES) return dotsMove(row, col, value, cell)
        if (gameType == GameType.SLITHERLINK) return slitherMove(row, col, value, cell)

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
        return accept(if (gameType == GameType.RUMMIKUB) 15 else 10)
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

    private fun slitherMove(row: Int, col: Int, value: Any?, cell: GenericCell): LocalPuzzleMoveResult {
        var targetRow = row
        var targetCol = col
        var side = value?.toString()?.lowercase().orEmpty()
        var target = cell
        var expected = blueprint.answers[targetRow][targetCol].toString().split('|').filter(String::isNotBlank)
        if (side !in expected) {
            val neighbour = neighbour(targetRow, targetCol, side) ?: return incorrect()
            val opposite = mapOf("top" to "bottom", "right" to "left", "bottom" to "top", "left" to "right")[side] ?: return incorrect()
            val neighbourExpected = blueprint.answers[neighbour.first][neighbour.second].toString().split('|').filter(String::isNotBlank)
            if (opposite !in neighbourExpected) return incorrect()
            targetRow = neighbour.first; targetCol = neighbour.second; side = opposite
            target = board[targetRow][targetCol]; expected = neighbourExpected
        }
        if (target.meta[side] == true) return incorrect()
        val meta = target.meta + (side to true)
        replace(targetRow, targetCol, target.copy(meta = meta, ownerId = OWNER.takeIf { expected.all { edge -> meta[edge] == true } })); mirrorEdge(targetRow, targetCol, side)
        return accept(8)
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
        GameType.NONOGRAM, GameType.HITORI, GameType.BRIDGES -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] != true || board[y][x].ownerId != null } }
        GameType.SLITHERLINK -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x].toString().split('|').filter(String::isNotBlank).all { board[y][x].meta[it] == true } } }
        GameType.CROSS_LETTERS -> board[7].slice(5..9).all { it.value != null }
        else -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] == null || board[y][x].ownerId != null } }
    }

    private data class Blueprint(val board: List<List<GenericCell>>, val answers: List<List<Any?>>, val meta: Map<String, Any?>, val seed: Int)
    private fun result(board: List<List<GenericCell>>, answers: List<List<Any?>>, meta: Map<String, Any?> = emptyMap()) = Blueprint(board, answers, meta + ("difficulty" to difficulty.name), random.nextInt())
    private fun createBlueprint(type: GameType): Blueprint = when (type) {
        GameType.MINESWEEPER -> mines(); GameType.WORD_SEARCH -> words(); GameType.CROSSWORD -> crossword(); GameType.NONOGRAM -> nonogram()
        GameType.DOTS_AND_BOXES -> dots(); GameType.KAKURO -> kakuro(); GameType.MATHDOKU -> mathdoku(); GameType.HITORI -> hitori()
        GameType.RUMMIKUB -> logicTiles(); GameType.NURIKABE -> nurikabe(); GameType.BRIDGES -> bridges(); GameType.SLITHERLINK -> slitherlink()
        GameType.CRYPTARITHM -> cryptarithm(); GameType.CROSS_LETTERS -> crossLetters(); GameType.SUDOKU -> error("Sudoku usa RandomSudokuGenerator")
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
    private fun nonogram(): Blueprint { val size=size(6,8,10,12); val answers=matrix<Any?>(size,size){_,_->random.nextDouble()<.42}; return result(answers.map{row->row.map{GenericCell()}},answers,mapOf("rowClues" to answers.map{clues(it.map{v->v==true})},"columnClues" to (0 until size).map{x->clues(answers.map{it[x]==true})})) }
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
    private fun logicTiles(): Blueprint {
        val rows = size(4, 5, 6, 7); val cols = size(5, 6, 7, 8)
        val operations = if (difficulty == PuzzleDifficulty.EASY) listOf("SUM") else listOf("SUM", "AND", "OR", "XOR")
        val challenges = matrix(rows, cols) { _, _ -> logicChallenge(operations) }
        val answers = challenges.map { row -> row.map { it.first as Any? } }
        val colors = listOf("RED", "BLUE", "GREEN", "ORANGE")
        val board = challenges.mapIndexed { row, cells -> cells.map { (_, rule) ->
            GenericCell(meta = mapOf("tileColor" to colors[row % colors.size], "rule" to rule))
        } }
        return result(board, answers, mapOf("instructions" to "Deduce la ficha que completa cada operación; la respuesta permanece oculta."))
    }

    private fun logicChallenge(operations: List<String>): Pair<Int, String> {
        repeat(100) {
            val operation = operations.random(random)
            val left = random.nextInt(1, 10); val right = random.nextInt(1, 10)
            val answer = when (operation) { "SUM" -> left + right; "AND" -> left and right; "OR" -> left or right; else -> left xor right }
            if (answer in 1..9) return answer to "$left ${if (operation == "SUM") "+" else operation} $right = ?"
        }
        return 2 to "1 + 1 = ?"
    }
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
    private fun slitherlink(): Blueprint {
        val size = size(5, 7, 9, 11)
        val maxInset = minOf(2, size / 4)
        val top = random.nextInt(0, maxInset + 1)
        val left = random.nextInt(0, maxInset + 1)
        val bottom = random.nextInt(size - 1 - maxInset, size)
        val right = random.nextInt(size - 1 - maxInset, size)
        val answers = matrix<Any?>(size, size) { row, col ->
            buildList {
                if (row == top && col in left..right) add("top")
                if (row == bottom && col in left..right) add("bottom")
                if (col == left && row in top..bottom) add("left")
                if (col == right && row in top..bottom) add("right")
            }.joinToString("|")
        }
        val board = matrix(size, size) { row, col ->
            GenericCell(
                meta = SIDES.associateWith { false } + mapOf(
                    "clue" to answers[row][col].toString().split('|').count(String::isNotBlank),
                ),
            )
        }
        return result(board, answers, mapOf("instructions" to "Traza un único lazo según las pistas."))
    }
    private fun cryptarithm(): Blueprint {
        val first = random.nextInt(12, 90)
        val second = random.nextInt(12, 100)
        val total = first + second
        val digits = ("$first$second$total").map(Char::digitToInt).distinct()
        val letters = "ABCDEFGHIJKLMNPQRSTUVWXYZ".toList().shuffled(random).take(digits.size)
        val digitLetters = digits.zip(letters).toMap()

        fun encode(value: Int): String = value.toString()
            .map { digitLetters.getValue(it.digitToInt()) }
            .joinToString("")

        val answers: List<List<Any?>> = listOf(digits.map { it })
        val revealed = letters.indices.shuffled(random).take(if (difficulty == PuzzleDifficulty.EASY) 3 else 2).toSet()
        val board = listOf(
            letters.mapIndexed { index, letter ->
                GenericCell(
                    value = if (index in revealed) digits[index] else letter.toString(),
                    isRevealed = true,
                    isBlocked = index in revealed,
                    meta = mapOf("cryptLetter" to letter.toString(), "given" to (index in revealed)),
                )
            },
        )
        return result(
            board,
            answers,
            mapOf(
                "equation" to "${encode(first)} + ${encode(second)} = ${encode(total)}",
                "instructions" to "Cada letra representa un dígito diferente. Las equivalencias doradas son pistas iniciales.",
            ),
        )
    }

    private fun crossLetters(): Blueprint {
        val board = matrix(15, 15) { row, col ->
            val bonus = when {
                row == 7 && col == 7 -> "DW"
                (row + col) % 11 == 0 -> "DL"
                else -> "NONE"
            }
            GenericCell(meta = mapOf("bonus" to bonus, "center" to (row == 7 && col == 7)))
        }
        val answers = matrix<Any?>(15, 15) { row, col -> if (row == 7 && col in 5..9) "ARENA"[col - 5].toString() else null }
        return result(board, answers, mapOf("rack" to listOf("A", "R", "E", "N", "A", "L", "S"), "instructions" to "Forma ARENA horizontalmente pasando por la estrella central."))
    }

    private fun blocked(meta: Map<String, Any?> = emptyMap()) = GenericCell(isRevealed = true, isBlocked = true, meta = meta)
    private fun clues(values: List<Boolean>): List<Int> { val out=mutableListOf<Int>();var run=0;values.forEach{if(it)run++ else if(run>0){out+=run;run=0}};if(run>0)out+=run;return out.ifEmpty{listOf(0)} }
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
