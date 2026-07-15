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

/** Motor solución-primero para las trece arenas no-Sudoku sin conexión. */
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

    fun move(row: Int, col: Int, value: Any?): LocalPuzzleMoveResult {
        val cell = board.getOrNull(row)?.getOrNull(col) ?: return reject("Movimiento fuera del tablero")
        if (cell.isBlocked || (cell.ownerId != null && gameType != GameType.SLITHERLINK)) return reject("Casilla no disponible")

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
            val word = ((value as? Map<*, *>)?.get("word") ?: value).toString().uppercase()
            val words = blueprint.meta["words"] as List<*>
            val index = words.indexOf(word)
            if (index < 0 || row != index || col != 0 || word in foundWords) return incorrect()
            word.indices.forEach { x -> replace(index, x, board[index][x].copy(ownerId = OWNER)) }
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
        GameType.NONOGRAM, GameType.HITORI, GameType.NURIKABE, GameType.BRIDGES -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] != true || board[y][x].ownerId != null } }
        GameType.SLITHERLINK -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x].toString().split('|').filter(String::isNotBlank).all { board[y][x].meta[it] == true } } }
        else -> board.indices.all { y -> board[y].indices.all { x -> blueprint.answers[y][x] == null || board[y][x].ownerId != null } }
    }

    private data class Blueprint(val board: List<List<GenericCell>>, val answers: List<List<Any?>>, val meta: Map<String, Any?>, val seed: Int)
    private fun result(board: List<List<GenericCell>>, answers: List<List<Any?>>, meta: Map<String, Any?> = emptyMap()) = Blueprint(board, answers, meta + ("difficulty" to difficulty.name), random.nextInt())
    private fun createBlueprint(type: GameType): Blueprint = when (type) {
        GameType.MINESWEEPER -> mines(); GameType.WORD_SEARCH -> words(); GameType.CROSSWORD -> crossword(); GameType.NONOGRAM -> nonogram()
        GameType.DOTS_AND_BOXES -> dots(); GameType.KAKURO -> kakuro(); GameType.MATHDOKU -> mathdoku(); GameType.HITORI -> hitori()
        GameType.RUMMIKUB -> logicTiles(); GameType.NURIKABE -> nurikabe(); GameType.BRIDGES -> bridges(); GameType.SLITHERLINK -> slitherlink()
        GameType.CRYPTARITHM -> cryptarithm(); GameType.SUDOKU -> error("Sudoku usa RandomSudokuGenerator")
    }

    private fun mines(): Blueprint { val size = size(8,10,12,14); val count=(size*size*when(difficulty){PuzzleDifficulty.EASY->.10;PuzzleDifficulty.MEDIUM->.14;PuzzleDifficulty.HARD->.18;PuzzleDifficulty.EXPERT->.22}).toInt(); val mines=(0 until size*size).shuffled(random).take(count).toSet(); return result(matrix(size,size){_,_->GenericCell()}, matrix(size,size){r,c->r*size+c in mines}, mapOf("mineCount" to count)) }
    private fun words(): Blueprint { val size=size(8,10,12,14); val selected=WORDS.filter{it.length<=size}.shuffled(random).take(size(4,5,7,9)); val filler="ABCDEFGHIJKLMNÑOPQRSTUVWXYZ"; val answers=matrix<Any?>(size,size){r,c->selected.getOrNull(r)?.getOrNull(c)?.toString()?:filler[random.nextInt(filler.length)].toString()}; return result(answers.map{row->row.map{GenericCell(value=it,isRevealed=true)}},answers,mapOf("words" to selected)) }
    private fun crossword(): Blueprint { val size=size(9,11,13,15); val selected=WORDS.filter{it.length<=size}.shuffled(random).take(size(5,7,9,11)); val answers=List(size){r->List<Any?>(size){c->selected.getOrNull(r)?.getOrNull(c)?.toString()}}; val board=answers.mapIndexed{r,row->row.mapIndexed{c,a->if(a==null)blocked()else GenericCell(meta=mapOf("clue" to if(c==0)r+1 else 0))}}; return result(board,answers,mapOf("clues" to selected.mapIndexed{i,w->"${i+1}. Palabra de ${w.length} letras"})) }
    private fun nonogram(): Blueprint { val size=size(6,8,10,12); val answers=matrix<Any?>(size,size){_,_->random.nextDouble()<.42}; return result(answers.map{row->row.map{GenericCell()}},answers,mapOf("rowClues" to answers.map{clues(it.map{v->v==true})},"columnClues" to (0 until size).map{x->clues(answers.map{it[x]==true})})) }
    private fun dots(): Blueprint { val size=size(3,5,6,7); return result(matrix(size,size){_,_->GenericCell(meta=SIDES.associateWith{false})},matrix(size,size){_,_->true},mapOf("dots" to size+1)) }
    private fun kakuro(): Blueprint { val playable=size(3,4,5,6); val digits=(1..9).shuffled(random).take(playable); val sum=digits.sum(); val answers=matrix<Any?>(playable+1,playable+1){r,c->if(r==0||c==0)null else digits[(r+c-2)%playable]}; val board=matrix(playable+1,playable+1){r,c->when{r==0&&c==0->blocked(mapOf("clueCell" to true));r==0->blocked(mapOf("clueCell" to true,"downSum" to sum));c==0->blocked(mapOf("clueCell" to true,"rightSum" to sum));else->GenericCell()}}; return result(board,answers,mapOf("instructions" to "Cada grupo suma $sum sin repetir.")) }
    private fun mathdoku(): Blueprint { val size=size(4,5,6,7); val symbols=(1..size).shuffled(random); val answers=matrix<Any?>(size,size){r,c->symbols[(r+c)%size]}; val board=matrix(size,size){r,c->val start=c-c%2;val target=(answers[r][start] as Int)+(answers[r][minOf(size-1,start+1)] as Int);GenericCell(meta=mapOf("cageId" to "$r:$start","cageLabel" to if(c==start)"$target+" else "","cageStart" to(c==start),"cageEnd" to(c==minOf(size-1,start+1))))}; return result(board,answers,mapOf("instructions" to "Usa 1 a $size y cumple las jaulas.")) }
    private fun hitori(): Blueprint { val size=size(5,6,7,8); val black=mutableSetOf<String>(); for(index in(0 until size*size).shuffled(random)){val r=index/size;val c=index%size;if(black.size>=size(4,6,9,12))break;if(listOf("${r-1}:$c","${r+1}:$c","$r:${c-1}","$r:${c+1}").none{it in black})black+="$r:$c"}; val values=matrix(size,size){r,c->((r+c)%size)+1}.map{it.toMutableList()};black.forEach{val(r,c)=it.split(':').map(String::toInt);values[r][c]=values[r][(c+1)%size]};val answers=matrix<Any?>(size,size){r,c->"$r:$c" in black};return result(values.map{row->row.map{GenericCell(value=it,isRevealed=true)}},answers,mapOf("instructions" to "Apaga duplicados sin tocar negras por sus lados.")) }
    private fun logicTiles(): Blueprint { val rows=size(4,5,6,7);val cols=size(5,6,7,8);val answers=matrix<Any?>(rows,cols){r,c->((r*3+c*2+random.nextInt(5))%9)+1};val colors=listOf("RED","BLUE","GREEN","ORANGE");val board=matrix(rows,cols){r,c->val answer=answers[r][c]as Int;GenericCell(meta=mapOf("tileColor" to colors[r%4],"rule" to "${answer-1}+1=$answer"))};return result(board,answers,mapOf("instructions" to "Cumple la regla algebraica o lógica de cada casilla.")) }
    private fun nurikabe(): Blueprint { val size=size(6,8,10,12);val answers=matrix<Any?>(size,size){r,c->if(r%2==1)c!=(if(r%4==1)size-1 else 0)else c%3==2};val board=matrix(size,size){_,_->GenericCell()}.map{it.toMutableList()};var id=0;for(r in 0 until size){var c=0;while(c<size){if(answers[r][c]==true){c++;continue};val start=c;while(c<size&&answers[r][c]!=true)c++;id++;val clue=(start until c).random(random);board[r][clue]=blocked(mapOf("islandClue" to true,"islandSize" to c-start,"islandId" to id))}};return result(board,answers,mapOf("instructions" to "Pinta el río; evita bloques negros 2×2.")) }
    private fun bridges(): Blueprint { val grid=size(3,4,5,6);val size=grid*2-1;val answers=matrix<Any?>(size,size){_,_->false}.map{it.toMutableList()};val board=matrix(size,size){_,_->GenericCell(meta=mapOf("bridge" to true))}.map{it.toMutableList()};for(r in 0 until grid)for(c in 0 until grid){board[r*2][c*2]=blocked(mapOf("island" to true,"bridgeCount" to if(r==0||c==0)1 else 2));if(c>0)answers[r*2][c*2-1]=true;if(r>0&&c==0)answers[r*2-1][0]=true};return result(board,answers,mapOf("instructions" to "Une todas las islas con puentes.")) }
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
        val board = listOf(
            letters.map {
                GenericCell(
                    value = it.toString(),
                    isRevealed = true,
                    meta = mapOf("cryptLetter" to it.toString()),
                )
            },
        )
        return result(
            board,
            answers,
            mapOf(
                "equation" to "${encode(first)} + ${encode(second)} = ${encode(total)}",
                "instructions" to "Cada letra representa un dígito diferente.",
            ),
        )
    }

    private fun blocked(meta: Map<String, Any?> = emptyMap()) = GenericCell(isRevealed = true, isBlocked = true, meta = meta)
    private fun clues(values: List<Boolean>): List<Int> { val out=mutableListOf<Int>();var run=0;values.forEach{if(it)run++ else if(run>0){out+=run;run=0}};if(run>0)out+=run;return out.ifEmpty{listOf(0)} }
    private fun size(easy:Int,medium:Int,hard:Int,expert:Int)=when(difficulty){PuzzleDifficulty.EASY->easy;PuzzleDifficulty.MEDIUM->medium;PuzzleDifficulty.HARD->hard;PuzzleDifficulty.EXPERT->expert}
    private fun <T> matrix(rows:Int,cols:Int,create:(Int,Int)->T)=List(rows){r->List(cols){c->create(r,c)}}

    private companion object {
        const val OWNER = "solo"
        val SIDES = listOf("top", "right", "bottom", "left")
        val WORDS = listOf("ARENA","LOGICA","MATRIZ","PUZZLE","MENTE","CLAVE","CIFRA","ISLA","PUENTE","LAZO","COLOR","NEON","EQUIPO","RIVAL","RETO","NIVEL","SUMA","CELDA","FICHA","PISTA","RIO","MINA","BOMBA","TRAZO","JUGADA","VICTORIA","ESCUDO","NIEBLA","ENERGIA","COMBO","CAMINO","BLOQUE")
    }
}
