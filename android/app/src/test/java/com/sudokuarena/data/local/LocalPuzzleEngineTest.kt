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
        GameType.entries.filterNot { it in setOf(GameType.SUDOKU, GameType.ABYSS_ARENA) }.forEach { gameType ->
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
    fun `Crucigrama usa pistas reales y Rummikub oculta resultados`() {
        val crossword = LocalPuzzleEngine(GameType.CROSSWORD, seed = 12L).snapshot()
        val clues = crossword.meta["clues"] as List<*>
        assertTrue(clues.all { !it.toString().contains("Palabra de") })

        val rummikub = LocalPuzzleEngine(GameType.RUMMIKUB, seed = 12L).snapshot()
        assertTrue(rummikub.board.flatten().all { it.value == null })
        assertTrue(rummikub.board.flatten().filterNot { it.isBlocked }.all { it.meta["meldType"] in setOf("RUN", "GROUP") })
        assertTrue(rummikub.board.flatten().none { it.meta.containsKey("rule") })
    }

    @Test
    fun `Nexo Cero conquista dos cargas opuestas vecinas`() {
        val engine = LocalPuzzleEngine(GameType.NEXUS_ZERO, seed = 4L)
        val initial = engine.snapshot()
        val result = engine.move(0, 0, mapOf("targetRow" to 0, "targetCol" to 1))
        assertTrue(result.accepted)
        assertEquals(24, result.points)
        assertEquals("solo", result.state.board[0][0].ownerId)
        assertEquals("solo", result.state.board[0][1].ownerId)
        assertEquals(0, (initial.board[0][0].value as Number).toInt() + (initial.board[0][1].value as Number).toInt())
    }
}
