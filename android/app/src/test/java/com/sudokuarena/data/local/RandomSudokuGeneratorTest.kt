package com.sudokuarena.data.local

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomSudokuGeneratorTest {
    @Test
    fun `genera tablero valido con 45 casillas vacias`() {
        val puzzle = RandomSudokuGenerator(random = Random(7), emptyCells = 45).generate()
        val expected = (1..9).toSet()

        assertEquals(9, puzzle.solution.size)
        puzzle.solution.forEach { assertEquals(expected, it.toSet()) }
        for (column in 0..8) {
            assertEquals(expected, puzzle.solution.map { row -> row[column] }.toSet())
        }
        for (box in 0..8) {
            val startRow = box / 3 * 3
            val startColumn = box % 3 * 3
            val values = buildSet {
                for (row in startRow until startRow + 3) {
                    for (column in startColumn until startColumn + 3) add(puzzle.solution[row][column])
                }
            }
            assertEquals(expected, values)
        }
        assertEquals(45, puzzle.initialBoard.flatten().count { it == null })
        puzzle.initialBoard.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, value ->
                if (value == null) assertNull(value) else assertTrue(value == puzzle.solution[row][column])
            }
        }
    }
}
