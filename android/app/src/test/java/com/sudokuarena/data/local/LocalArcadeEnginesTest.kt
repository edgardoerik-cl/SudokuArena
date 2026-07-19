package com.sudokuarena.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArcadeEnginesTest {
    @Test
    fun `Tetris local procesa controles sin red`() {
        val engine = LocalTetrisEngine("QA")
        engine.input("LEFT")
        engine.input("HARD_DROP")
        val state = engine.snapshot()
        assertEquals(20, state.players.first().board.size)
        assertTrue(state.players.first().score >= 0)
    }

    @Test
    fun `Pacman local conserva fantasmas al ochenta por ciento`() {
        val engine = LocalPacmanEngine("QA")
        engine.input("RIGHT")
        repeat(5) { engine.tick() }
        val state = engine.snapshot()
        assertEquals(4, state.ghosts.size)
        assertTrue(state.players.first().score >= 0)
    }

    @Test
    fun `Demolicion local mantiene bola y plataforma normalizadas`() {
        val engine = LocalDemolitionEngine("QA")
        engine.input(.8f)
        repeat(120) { engine.tick() }
        val player = engine.snapshot().players.first()
        assertEquals(.8f, player.paddleX)
        assertTrue(player.ballX in 0f..1f)
        assertTrue(player.bricks.isNotEmpty())
    }
}
