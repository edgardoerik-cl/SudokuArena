package com.sudokuarena.presentation

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sudokuarena.domain.TileType
import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.PuzzleDifficulty

/** Mapeo visual estable: el índice 0 representa el valor Sudoku 1 y así hasta 9. */
object SudokuTilePalette {
    val colors = listOf(
        Color(0xFFFF3B30), // rojo
        Color(0xFF003F88), // azul marino
        Color(0xFF00A86B), // verde esmeralda
        Color(0xFFFFD400), // amarillo brillante
        Color(0xFF5B2C83), // morado oscuro
        Color(0xFFFF6B00), // naranja vibrante
        Color(0xFFD100D1), // magenta
        Color(0xFFFFFFFF), // blanco puro
        Color(0xFF30343B), // gris carbón
    )

    fun colorFor(value: Int): Color = colors.getOrElse(value - 1) { Color.Gray }
}

@Composable
fun SoloSetupScreen(
    gameType: GameType,
    isColorMode: Boolean,
    difficulty: PuzzleDifficulty,
    onColorModeChanged: (Boolean) -> Unit,
    onDifficultyChanged: (PuzzleDifficulty) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (landscape) 14.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("${gameTitle(gameType).uppercase()} · SOLITARIO", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("La partida se ejecuta localmente y puede pausarse.", modifier = Modifier.padding(vertical = 14.dp))
            Text("DIFICULTAD", style = MaterialTheme.typography.labelLarge)
            val difficultyContent: @Composable (PuzzleDifficulty) -> Unit = { level ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = difficulty == level, onClick = { onDifficultyChanged(level) })
                    Text(
                        when (level) {
                            PuzzleDifficulty.EASY -> "Fácil"
                            PuzzleDifficulty.MEDIUM -> "Medio"
                            PuzzleDifficulty.HARD -> "Difícil"
                            PuzzleDifficulty.EXPERT -> "Experto"
                        },
                    )
                }
            }
            if (landscape) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) { PuzzleDifficulty.entries.forEach { difficultyContent(it) } }
            } else {
                PuzzleDifficulty.entries.forEach { difficultyContent(it) }
            }
            if (gameType == GameType.SUDOKU) {
                Card(Modifier.fillMaxWidth()) {
                    TileTypeSelector(
                        selected = if (isColorMode) TileType.COLORS else TileType.NUMBERS,
                        enabled = true,
                        onSelected = { onColorModeChanged(it == TileType.COLORS) },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Text("Comenzar")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        }
    }
}

@Composable
fun TileTypeSelector(
    selected: TileType,
    enabled: Boolean,
    onSelected: (TileType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TIPO DE FICHA", style = MaterialTheme.typography.labelLarge)
        TileType.entries.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected == type,
                    onClick = { onSelected(type) },
                    enabled = enabled,
                )
                Text(if (type == TileType.NUMBERS) "Números (1 al 9)" else "Colores (9 tonos únicos)")
            }
        }
        if (selected == TileType.COLORS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SudokuTilePalette.colors.forEach { color -> ColorTile(color, Modifier.size(22.dp)) }
            }
        }
    }
}

@Composable
fun ColorTile(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color.Black.copy(alpha = 0.45f), radius = size.minDimension * 0.49f)
        drawCircle(color, radius = size.minDimension * 0.40f)
        drawCircle(Color.White.copy(alpha = 0.70f), radius = size.minDimension * 0.40f, style = Stroke(1.5f))
    }
}
