package com.sudokuarena.data.local

import com.sudokuarena.domain.GameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPuzzleEngineTest {
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
    fun `Nexo Cero conquista dos cargas opuestas dispersas`() {
        val engine = LocalPuzzleEngine(GameType.NEXUS_ZERO, seed = 4L)
        val initial = engine.snapshot()
        var result: LocalPuzzleMoveResult? = null
        search@ for (row in 0 until initial.rows) {
            for (col in 0 until initial.columns) {
                for (targetRow in 0 until initial.rows) {
                    for (targetCol in 0 until initial.columns) {
                        val candidate = engine.move(
                            row,
                            col,
                            mapOf("targetRow" to targetRow, "targetCol" to targetCol),
                        )
                        if (candidate.accepted) {
                            result = candidate
                            break@search
                        }
                    }
                }
            }
        }
        val accepted = requireNotNull(result)
        assertTrue(accepted.accepted)
        assertEquals(24, accepted.points)
        assertEquals(2, accepted.state.board.flatten().count { it.ownerId == "solo" })
        val conquered = accepted.state.board.flatten().filter { it.ownerId == "solo" }
        assertEquals(0, conquered.sumOf { (it.value as Number).toInt() })
        assertTrue(conquered.map { it.meta["x"] }.distinct().size > 1)
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
