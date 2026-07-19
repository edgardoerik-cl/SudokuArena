package com.sudokuarena.presentation

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.GameType

private data class TutorialStep(val icon: String, val title: String, val body: String)

@Composable
fun ArenaTutorialOverlay(
    isSoloMode: Boolean,
    gameType: GameType,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
            modifier = Modifier
                .fillMaxWidth(if (landscape) .76f else 1f)
                .heightIn(max = (configuration.screenHeightDp - 20).dp)
                .padding(if (landscape) 10.dp else 24.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(if (landscape) 16.dp else 24.dp),
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
    GameType.TIC_TAC_TOE -> listOf(
        TutorialStep("X O", "Marca una casilla", "En tu turno toca un espacio libre para colocar tu símbolo."),
        TutorialStep("3×", "Crea una línea", "Gana formando tres símbolos iguales en horizontal, vertical o diagonal."),
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
    GameType.CHESS_TACTICS -> listOf(
        TutorialStep("AP", "Administra acciones", "Mover y atacar consume AP. Al tocar una unidad verás movimiento azul y ataque rojo."),
        TutorialStep("♟", "Falange", "El Peón puede gastar AP para duplicar su defensa durante el turno."),
        TutorialStep("♞♜", "Habilidades tácticas", "El Caballo golpea un área con Terremoto y la Torre atraviesa dos objetivos con Rayo Perforante."),
    )
    GameType.NURIKABE -> listOf(
        TutorialStep("●≈", "Separa islas", "Los números indican el tamaño exacto de cada isla blanca."),
        TutorialStep("■", "Construye el río", "Pinta un río negro conectado sin crear bloques de 2×2."),
    )
    GameType.BRIDGES -> listOf(
        TutorialStep("●═●", "Conecta islas", "Traza puentes horizontales o verticales entre islas alineadas."),
        TutorialStep("#", "Respeta el número", "Cada isla recibe la cantidad indicada y toda la red queda conectada."),
    )
    GameType.TETRIS_ARENA -> listOf(
        TutorialStep("▟", "Completa líneas", "Mueve y rota los tetrominós. Una fila llena desaparece y suma puntos."),
        TutorialStep("▰", "Ataca con basura", "Dobles, triples y Tetris envían líneas grises a los tableros rivales."),
    )
    GameType.HANGMAN -> listOf(
        TutorialStep("A _ _", "Adivina la palabra", "Usa la pista y prueba letras. Cada acierto revela todas sus posiciones."),
        TutorialStep("6×", "Conserva tus intentos", "Con seis errores quedas eliminado; completa la palabra antes que tus rivales."),
    )
    GameType.ARROWS_ESCAPE -> listOf(
        TutorialStep("➜", "Libera los bloques", "Una flecha sale volando si no existe otro bloque frente a ella."),
        TutorialStep("↩", "Evita rebotes", "Si la trayectoria está ocupada, la ficha rebota y permanece en el tablero."),
    )
    GameType.PACMAN_ARENA -> listOf(
        TutorialStep("●", "Come las píldoras", "Recorre el laberinto y suma puntos antes que los demás jugadores."),
        TutorialStep("👻", "Lee a los fantasmas", "Alternan entre persecución y dispersión. Una píldora de poder los vuelve vulnerables."),
    )
    GameType.CROSS_LETTERS -> listOf(
        TutorialStep("AÑ", "Usa tu atril", "En tu turno forma una palabra española con las siete letras disponibles."),
        TutorialStep("DL·TW", "Aprovecha premios", "Las casillas DL/TL multiplican una letra; DW/TW multiplican toda la palabra."),
        TutorialStep("★", "Conecta palabras", "La primera palabra cruza la estrella central y las siguientes deben enlazarse al tablero."),
    )
    GameType.SECRET_CODE -> listOf(
        TutorialStep("5×5", "Dos equipos, una clave", "Cada palabra oculta un agente rojo, azul, neutral o al asesino."),
        TutorialStep("💡 3", "El capitán conecta ideas", "Solo el capitán ve los colores y entrega una pista de una palabra más un número."),
        TutorialStep("⚠", "Operativos: elijan", "Toca palabras relacionadas. Una neutral termina el turno; el asesino pierde la partida al instante."),
    )
    GameType.CAPITAL_ARENA -> listOf(
        TutorialStep("🎲", "Lanza dos dados", "En tu turno avanza por la arena y resuelve la casilla donde aterrizas."),
        TutorialStep("🏙", "Compra y mejora", "Adquiere propiedades libres y aumenta su nivel para cobrar rentas mayores."),
        TutorialStep("⚡", "Administra tu capital", "Cobra al pasar por Salida, evita la quiebra y termina con el patrimonio más alto."),
    )
    GameType.NEXUS_ZERO -> listOf(
        TutorialStep("+4 −4", "Neutraliza cargas", "Encuentra dos nodos dispersos vinculados cuyos valores sumen exactamente cero."),
        TutorialStep("⚡", "Encadena enlaces", "Resuelve pares rápidamente para multiplicar tu dominio y energía."),
    )
    GameType.CHECKERS -> listOf(
        TutorialStep("⛀", "Mueve en diagonal", "Las fichas normales avanzan una casilla diagonal hacia el lado rival."),
        TutorialStep("×", "Captura obligatoria", "Si puedes saltar una ficha rival debes hacerlo. Tras una captura puede haber otra encadenada."),
        TutorialStep("♛", "Corona una reina", "Al alcanzar la última fila, la ficha se convierte en Reina y recorre diagonales completas."),
    )
    GameType.DEMOLITION_ARCADE -> listOf(
        TutorialStep("Mueve la plataforma", "Arrastra el dedo horizontalmente en la arena para seguir la bola.", "↔"),
        TutorialStep("Rompe la estructura", "Cada impacto daña un bloque. Algunos necesitan más de un golpe.", "●▰"),
        TutorialStep("Conserva tus vidas", "Si la bola cae por abajo pierdes una vida. Vacía el nivel para avanzar.", "♥"),
    )
}
