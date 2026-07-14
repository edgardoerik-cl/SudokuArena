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

private data class TutorialStep(val icon: String, val title: String, val body: String)

@Composable
fun ArenaTutorialOverlay(
    isSoloMode: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = remember(isSoloMode) {
        buildList {
            add(TutorialStep("1–9", "Elige una casilla", "Toca una casilla vacía y luego coloca la ficha correcta. Un error bloquea el tablero durante 3 segundos."))
            add(TutorialStep("×3", "Mantén el combo", "Encadena aciertos antes de 4,5 segundos para multiplicar los puntos hasta x3."))
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
