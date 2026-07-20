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
        TutorialStep("♟", "Movimiento o habilidad", "En tu turno eliges una sola acción: mover una pieza con las reglas clásicas o activar su habilidad contextual."),
        TutorialStep("◉", "Apunta con claridad", "Toca una pieza: las casillas azules son movimientos. Pulsa su habilidad y aparecerán en rojo o morado únicamente sus objetivos válidos."),
        TutorialStep("⏳", "Respeta la recarga", "El número sobre el botón indica rondas de cooldown. Peón y Rey solo pueden usar su habilidad una vez por partida."),
        TutorialStep("♜", "Defensas primero", "El Peón bloquea ataques frontales y la Torre protege a sus aliados adyacentes. Las pasivas defensivas tienen prioridad."),
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
        TutorialStep("↔", "Arrastra con una mano", "Desliza horizontalmente para llevar la pieza a otra columna. Toca rápidamente para rotarla con ajuste automático junto a paredes."),
        TutorialStep("↓", "Controla la caída", "Arrastra hacia abajo para caída suave. Un flick rápido hacia arriba ejecuta la caída dura."),
        TutorialStep("⚡", "Impacto cinético", "La caída dura vibra y puede acomodar bloques sueltos en huecos. Al tocar fondo tienes 0,6 segundos para el último ajuste."),
    )
    GameType.HANGMAN -> listOf(
        TutorialStep("A _ _", "Toca una letra", "Usa el teclado integrado: verde significa acierto y rojo significa error. Cada acierto revela todas sus apariciones."),
        TutorialStep("6×", "Cuida las seis vidas", "Cada error dibuja una parte del personaje. El sexto normalmente termina la ronda."),
        TutorialStep("✨", "Tres ayudas", "Revelación descubre una letra, Descarte elimina tres letras falsas y Último Aliento evita una derrota fatal una sola vez."),
    )
    GameType.ARROWS_ESCAPE -> listOf(
        TutorialStep("◫", "Explora la figura 3D", "Arrastra para orbitar la cámara y pellizca para acercar o alejar. Un toque corto selecciona el bloque bajo el dedo."),
        TutorialStep("➜", "Busca una salida", "El bloque vuela solo si toda su trayectoria está libre. Si choca, rebota, parpadea y consume un intento fallido."),
        TutorialStep("↻ 🚀", "Cambia el puzzle", "Rotar gira una flecha bloqueada 90°. El misil atraviesa obstáculos. Las bombas liberadas eliminan dos bloques cercanos."),
    )
    GameType.PACMAN_ARENA -> listOf(
        TutorialStep("↕", "Programa el próximo giro", "Desliza en cualquier zona del laberinto. La dirección queda en memoria y Pac-Man gira automáticamente al llegar a una intersección válida."),
        TutorialStep("●", "Limpia el laberinto", "Come todos los puntos para ganar. Pac-Man avanza continuamente y solo se detiene frente a una pared."),
        TutorialStep("👻", "Reconoce a los fantasmas", "Rojo persigue, Rosa embosca, Azul flanquea y Naranja huye de cerca. La píldora los vuelve azules y 20% más lentos."),
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
        TutorialStep("↔", "Desliza toda la matriz", "Haz swipe arriba, abajo, izquierda o derecha. Todas las fichas avanzan hacia ese borde sin atravesarse."),
        TutorialStep("+4 −4", "Crea el Nexo", "Solo cargas exactamente opuestas pueden fusionarse. Al encontrarse suman cero y ambas desaparecen."),
        TutorialStep("⊘", "Una fusión por movimiento", "Una ficha ya fusionada no vuelve a fusionarse durante el mismo swipe; las cargas incompatibles quedan en casillas separadas."),
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
    GameType.MEMORY_NEON -> listOf(
        TutorialStep("Encuentra parejas", "Toca una carta para verla y luego busca su símbolo gemelo.", "◇  →  ◆"),
        TutorialStep("Conquista", "Una pareja correcta queda teñida con tu color y suma 30 puntos.", "◆ + ◆ = 30"),
        TutorialStep("Carrera visual", "Las cartas fallidas vuelven a ocultarse. Memoriza su posición antes que tus rivales.", "👁  🧠  ⚡"),
    )
    GameType.MERGE_2048 -> listOf(
        TutorialStep("Desliza", "Mueve todas las fichas con un gesto o con las cuatro flechas inferiores.", "←  ↑  ↓  →"),
        TutorialStep("Combina", "Dos valores iguales se fusionan: 2+2=4, 4+4=8 y así sucesivamente.", "2 + 2 = 4"),
        TutorialStep("Alcanza la meta", "La meta depende de la dificultad. Cada fusión suma puntos inmediatamente.", "128  →  256  →  512"),
    )
}
