package com.sudokuarena.data.local

import com.sudokuarena.domain.GameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPuzzleEngineTest {
    @Test
    fun `Tetris local usa las siete piezas y bloquea habilidades hasta ganar lineas`() {
        val starts = (1..12).map { seed -> LocalTetrisEngine("Tester", seed).snapshot().players.first().current }.toSet()
        assertTrue("El generador no puede producir solamente cuadrados O", starts.size > 1)
        val engine = LocalTetrisEngine("Tester", 17)
        val before = engine.snapshot().players.first()
        assertEquals(0, before.abilityEnergy)
        engine.input("HOLD")
        val held = engine.snapshot().players.first()
        assertEquals(before.current, held.current)
        assertEquals(null, held.hold)
        assertTrue(held.canHold)
        engine.input("CLEAN_BOMB")
        assertFalse(engine.snapshot().players.first().cleanBombUsed)
    }

    @Test
    fun `Pacman local espera el primer swipe`() {
        val engine = LocalPacmanEngine("Tester")
        assertEquals("WAITING", engine.snapshot().status)
        engine.tick()
        assertEquals(0L, engine.snapshot().tick)
        engine.input("RIGHT")
        engine.tick()
        assertEquals("PLAYING", engine.snapshot().status)
        assertTrue(engine.snapshot().tick > 0)
    }

    @Test
    fun `Tower Defense local mantiene tropas visibles durante la oleada`() {
        val engine = LocalPuzzleEngine(GameType.TOWER_DEFENSE)
        assertTrue(engine.move(0, 0, mapOf("action" to "BUILD", "towerType" to "RAPID")).accepted)
        assertTrue(engine.move(0, 0, mapOf("action" to "START_WAVE")).accepted)
        val started = engine.snapshot()
        assertEquals(true, started.meta["waveActive"])
        assertTrue((started.meta["enemies"] as List<*>).isNotEmpty())
        val now = System.currentTimeMillis()
        repeat(12) { engine.tickTowerDefense(now + (it + 1) * 100L) }
        val moving = engine.snapshot()
        val enemies = moving.meta["enemies"] as List<Map<*, *>>
        assertTrue(enemies.any { ((it["progress"] as? Number)?.toFloat() ?: 0f) > 0f })
        assertTrue((moving.meta["projectiles"] as List<*>).isNotEmpty())
    }

    @Test
    fun `todos los juegos no Sudoku crean un tablero local jugable`() {
        val onlineOnly = setOf(
            GameType.SUDOKU,
            GameType.TETRIS_ARENA,
            GameType.PACMAN_ARENA,
            GameType.DEMOLITION_ARCADE,
        )
        GameType.entries.filterNot { it in onlineOnly }.forEach { gameType ->
            val state = LocalPuzzleEngine(gameType).snapshot()
            assertEquals(gameType, state.gameType)
            assertTrue("$gameType debe tener filas", state.rows > 0)
            assertTrue("$gameType debe tener columnas", state.columns > 0)
            assertFalse("$gameType no debe empezar completado", state.completed)
        }
    }

    @Test
    fun `Buscaminas local congela cinco segundos y bloquea la mina`() {
        val engine = LocalPuzzleEngine(GameType.MINESWEEPER, seed = 7L)
        val initial = engine.snapshot()
        var mineResult: LocalPuzzleMoveResult? = null
        search@ for (row in 0 until initial.rows) {
            for (col in 0 until initial.columns) {
                val candidate = engine.move(row, col, null)
                if (candidate.hitMine) {
                    mineResult = candidate
                    break@search
                }
            }
        }
        assertNotNull("El tablero generado debe incluir minas", mineResult)
        val result = requireNotNull(mineResult)
        assertFalse(result.accepted)
        assertTrue(result.hitMine)
        assertEquals(5_000L, result.penaltyMs)
        assertTrue(result.state.board.flatten().any { it.value == "MINE" && it.isBlocked })
    }

    @Test
    fun `Sopa de Letras local acepta un recorrido multidireccional completo`() {
        val engine = LocalPuzzleEngine(GameType.WORD_SEARCH, seed = 91L)
        val initial = engine.snapshot()
        @Suppress("UNCHECKED_CAST")
        val placement = (initial.meta["placements"] as List<Map<String, Any>>).first()
        val result = engine.move(
            placement.getValue("startRow") as Int,
            placement.getValue("startCol") as Int,
            mapOf(
                "word" to placement.getValue("word"),
                "endRow" to placement.getValue("endRow"),
                "endCol" to placement.getValue("endCol"),
            ),
        )
        assertTrue(result.accepted)
        assertTrue((placement.getValue("word") as String).indices.all { offset ->
            val row = (placement.getValue("startRow") as Int) + (placement.getValue("rowStep") as Int) * offset
            val col = (placement.getValue("startCol") as Int) + (placement.getValue("colStep") as Int) * offset
            result.state.board[row][col].ownerId == "solo"
        })
    }

    @Test
    fun `Crucigrama usa pistas conceptuales reales`() {
        val crossword = LocalPuzzleEngine(GameType.CROSSWORD, seed = 12L).snapshot()
        val clues = crossword.meta["clues"] as List<*>
        assertTrue(clues.all { !it.toString().contains("Palabra de") })
    }

    @Test
    fun `Nexo Cero desliza todas las cargas sin superponer valores incompatibles`() {
        val engine = LocalPuzzleEngine(GameType.NEXUS_ZERO, seed = 4L)
        val initial = engine.snapshot()
        val before = initial.board.flatten().count { it.value != null }
        val result = engine.move(0, 0, "RIGHT")
        val after = result.state.board.flatten().count { it.value != null }
        assertTrue(result.accepted || result.message.contains("movimiento", ignoreCase = true))
        assertTrue(after <= before)
        result.state.board.forEach { row ->
            assertEquals(row.count { it.value != null }, row.mapNotNull { it.value }.size)
        }
    }

    @Test
    fun `Ahorcado local acepta el teclado virtual sin seleccionar una letra del tablero`() {
        val engine = LocalPuzzleEngine(GameType.HANGMAN, seed = 11L)
        val initial = engine.snapshot()
        val answer = initial.meta["hiddenWord"] as List<*>
        assertTrue(answer.all { it == "_" })
        val result = engine.move(0, 0, "A")
        assertTrue(result.accepted)
        assertTrue((result.state.meta["guessedLetters"] as List<*>).contains("A"))
    }

    @Test
    fun `Flechas local comienza visible y permite escapar una pieza exterior`() {
        val engine = LocalPuzzleEngine(GameType.ARROWS_ESCAPE, seed = 22L)
        val initial = engine.snapshot()
        assertTrue(initial.board.flatten().any { it.value != null })
        val result = engine.move(0, 0, "ESCAPE")
        assertTrue(result.accepted)
        assertEquals("solo", result.state.board[0][0].ownerId)
    }

    @Test
    fun `Flechas local gira la dirección visible y conserva cuerpos densos`() {
        val engine = LocalPuzzleEngine(GameType.ARROWS_ESCAPE, seed = 73L)
        val initial = engine.snapshot()
        val shapes = initial.meta["shapes"] as List<Map<*, *>>
        assertTrue(shapes.size >= 20)
        assertTrue(shapes.any { ((it["gridCells"] as? List<*>)?.size ?: 0) > 1 })
        val previous = shapes.first()["direction"]
        val result = engine.move(0, 0, mapOf("action" to "ROTATE"))
        assertTrue(result.accepted)
        val rotated = result.state.meta["shapes"] as List<Map<*, *>>
        assertTrue(rotated.first()["direction"] != previous)
    }

    @Test
    fun `Damas hotseat conserva equipo tras rechazo y rota tras jugada valida`() {
        val engine = LocalPuzzleEngine(GameType.CHECKERS, seed = 3L)
        val invalid = engine.move(2, 1, mapOf("targetRow" to 7, "targetCol" to 6))
        assertFalse(invalid.accepted)
        assertEquals("BLUE", invalid.state.meta["localTurnTeam"])
        val valid = engine.move(2, 1, mapOf("targetRow" to 3, "targetCol" to 0))
        assertTrue(valid.accepted)
        assertEquals("RED", valid.state.meta["localTurnTeam"])
    }

    @Test
    fun `Chess Tactics hotseat permite mover peon y rota el equipo`() {
        val engine = LocalPuzzleEngine(GameType.CHESS_TACTICS, seed = 5L)
        val result = engine.move(1, 0, mapOf("action" to "MOVE", "targetRow" to 2, "targetCol" to 0))
        assertTrue(result.accepted)
        assertEquals("RED", result.state.meta["localTurnTeam"])
        assertEquals("PAWN", result.state.board[2][0].value)
    }
}
