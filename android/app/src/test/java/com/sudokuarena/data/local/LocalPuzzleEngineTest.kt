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
        GameType.entries.filterNot { it == GameType.SUDOKU }.forEach { gameType ->
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
}
