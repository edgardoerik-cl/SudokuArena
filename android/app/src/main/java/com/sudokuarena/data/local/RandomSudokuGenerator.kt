package com.sudokuarena.data.local

import com.sudokuarena.domain.SudokuGenerator
import com.sudokuarena.domain.SudokuPuzzle
import kotlin.random.Random

/**
 * Generador local rápido. Parte de un patrón Sudoku válido y aleatoriza dígitos,
 * bandas, filas, pilas y columnas; después oculta casillas para practicar.
 * La app conserva la solución generada y valida sin conexión.
 */
class RandomSudokuGenerator(
    private val random: Random = Random.Default,
    private val emptyCells: Int = 45,
) : SudokuGenerator {
    override fun generate(): SudokuPuzzle {
        val digits = (1..9).shuffled(random)
        val rows = (0..2).shuffled(random).flatMap { band ->
            (0..2).shuffled(random).map { row -> band * 3 + row }
        }
        val columns = (0..2).shuffled(random).flatMap { stack ->
            (0..2).shuffled(random).map { column -> stack * 3 + column }
        }
        val solution = rows.map { sourceRow ->
            columns.map { sourceColumn ->
                digits[(sourceRow * 3 + sourceRow / 3 + sourceColumn) % 9]
            }
        }
        val hidden = (0 until 81).shuffled(random).take(emptyCells.coerceIn(1, 64)).toSet()
        val initialBoard = List(9) { row ->
            List<Int?>(9) { column ->
                solution[row][column].takeUnless { row * 9 + column in hidden }
            }
        }
        return SudokuPuzzle(solution = solution, initialBoard = initialBoard)
    }
}
