package com.sudokuarena.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sudokuarena.domain.GameType

private data class TutorialStep(val icon: String, val title: String, val body: String)

@Composable
fun ArenaTutorialOverlay(
    isSoloMode: Boolean,
    gameType: GameType,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = remember(isSoloMode, gameType) {
        buildList {
            addAll(gameTutorialSteps(gameType))
            if (!isSoloMode) {
                add(TutorialStep("⚡", "Carga tus poderes", "Cada acierto entrega energía. Usa Ojo de Lince, Escudo o Niebla en el momento justo."))
                add(TutorialStep("🏁", "Conquista la arena", "Completa filas, columnas o cuadrantes. Si hay empate al terminar, la próxima jugada correcta gana."))
            } else {
                add(TutorialStep("⏱", "Supera tu récord", "Completa el tablero con pocos errores. El reto diario entrega XP adicional una vez al día."))
            }
        }
    }
    var page by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xB8001028)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("ENTRENAMIENTO DE ARENA", color = ArenaColors.ElectricBlue, fontWeight = FontWeight.Black)
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (slideInHorizontally(tween(240)) { it / 2 } + fadeIn()) togetherWith
                            (slideOutHorizontally(tween(200)) { -it / 2 } + fadeOut())
                    },
                    label = "tutorialPage",
                ) { index ->
                    val step = steps[index]
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(step.icon, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                        Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(step.body, style = MaterialTheme.typography.bodyLarge)
                        Text("${index + 1} / ${steps.size}", color = Color.Gray)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onFinished, modifier = Modifier.weight(1f)) { Text("Omitir") }
                    Button(
                        onClick = { if (page == steps.lastIndex) onFinished() else page += 1 },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (page == steps.lastIndex) "Jugar" else "Siguiente") }
                }
            }
        }
    }
}

private fun gameTutorialSteps(gameType: GameType): List<TutorialStep> = when (gameType) {
    GameType.SUDOKU -> listOf(
        TutorialStep("1–9", "Completa sin repetir", "Coloca del 1 al 9 sin repetir en fila, columna o bloque 3×3."),
        TutorialStep("×3", "Conquista secciones", "El último acierto de una fila, columna o bloque conquista y limpia esa zona."),
    )
    GameType.MINESWEEPER -> listOf(
        TutorialStep("✹", "Evita las minas", "Toca casillas seguras. El número indica minas en las ocho casillas vecinas."),
        TutorialStep("⏳", "Explosión", "Pisar una mina te congela cinco segundos, pero la partida continúa."),
    )
    GameType.WORD_SEARCH -> listOf(
        TutorialStep("A↗", "Arrastra palabras", "Desliza desde la primera hasta la última letra en línea recta."),
        TutorialStep("🖍", "Firma tu hallazgo", "Cada palabra encontrada queda tachada con tu color."),
    )
    GameType.CROSSWORD -> listOf(
        TutorialStep("✚", "Lee las pistas", "Selecciona una casilla blanca y escribe una letra de la respuesta."),
        TutorialStep("ABC", "Resuelve primero", "Cada letra correcta suma territorio y puntos."),
    )
    GameType.NONOGRAM -> listOf(
        TutorialStep("▦", "Interpreta las pistas", "Cada número indica grupos consecutivos de píxeles en esa fila o columna."),
        TutorialStep("🎨", "Revela el mosaico", "Toca los píxeles correctos hasta completar la imagen."),
    )
    GameType.DOTS_AND_BOXES -> listOf(
        TutorialStep("□", "Traza una arista", "Toca cerca del borde de un cuadro para dibujar esa línea."),
        TutorialStep("🎯", "Cierra cuadros", "La cuarta arista conquista el cuadro y entrega una bonificación."),
    )
    GameType.KAKURO -> listOf(
        TutorialStep("Σ", "Cumple las sumas", "Las flechas muestran la suma del grupo horizontal o vertical."),
        TutorialStep("1–9", "Sin repetir", "Dentro de una suma no puede repetirse ninguna cifra."),
    )
    GameType.MATHDOKU -> listOf(
        TutorialStep("×+", "Resuelve jaulas", "Los números de cada jaula deben producir el objetivo indicado."),
        TutorialStep("1–6", "Cuadrado latino", "No repitas números en una fila ni columna."),
    )
    GameType.HITORI -> listOf(
        TutorialStep("◼", "Apaga duplicados", "Toca cifras repetidas para que cada fila y columna quede sin duplicados."),
        TutorialStep("⛓", "Mantén conectado", "Las casillas apagadas no deben tocarse por sus lados."),
    )
    GameType.RUMMIKUB -> listOf(
        TutorialStep("A∧B", "Lee la regla", "Cada casilla muestra una condición algebraica o lógica AND, OR o XOR."),
        TutorialStep("123", "Elige la ficha", "Coloca el valor que hace verdadera la regla de la casilla."),
    )
    GameType.NURIKABE -> listOf(
        TutorialStep("●≈", "Separa islas", "Los números indican el tamaño exacto de cada isla blanca."),
        TutorialStep("■", "Construye el río", "Pinta un río negro conectado sin crear bloques de 2×2."),
    )
    GameType.BRIDGES -> listOf(
        TutorialStep("●═●", "Conecta islas", "Traza puentes horizontales o verticales entre islas alineadas."),
        TutorialStep("#", "Respeta el número", "Cada isla recibe la cantidad indicada y toda la red queda conectada."),
    )
    GameType.SLITHERLINK -> listOf(
        TutorialStep("□", "Forma un lazo", "Traza una sola línea cerrada, sin cruces ni ramificaciones."),
        TutorialStep("0–3", "Sigue las pistas", "El número indica cuántos lados de la celda pertenecen al lazo."),
    )
    GameType.CRYPTARITHM -> listOf(
        TutorialStep("A=7", "Descifra letras", "Cada letra representa un dígito diferente y una inicial nunca vale cero."),
        TutorialStep("+", "Haz verdadera la suma", "Asigna los dígitos que hacen correcta toda la ecuación."),
    )
}
