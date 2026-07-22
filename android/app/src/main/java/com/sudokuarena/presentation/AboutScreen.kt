package com.sudokuarena.presentation

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.sin

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pixelDance")
    val beat by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse), label = "beat")
    var selectedCharacter by remember { mutableStateOf<Int?>(null) }
    var jumpTrigger by remember { mutableIntStateOf(0) }
    val jump = remember { Animatable(0f) }
    LaunchedEffect(jumpTrigger) {
        if (jumpTrigger == 0) return@LaunchedEffect
        jump.snapTo(0f)
        jump.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
        selectedCharacter = null
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF07152F)).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ACERCA DE MULTI ARENA", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Volver") }
        }
        Canvas(
            Modifier.fillMaxWidth().height(280.dp).pointerInput(Unit) {
                detectTapGestures { tap ->
                    if (tap.y < size.height * .18f || tap.y > size.height * .88f) return@detectTapGestures
                    selectedCharacter = when {
                        tap.x < size.width * .34f -> 0
                        tap.x < size.width * .66f -> 2
                        else -> 1
                    }
                    jumpTrigger++
                }
            },
        ) {
            drawRect(Color(0xFF101A3C)); val floor = size.height * .82f
            repeat(12) { i -> drawRect(if (i % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF2D95), Offset(i * size.width / 12f, floor), Size(size.width / 12f, size.height - floor), alpha = .35f) }
            fun pixel(x: Float, y: Float, w: Float, h: Float, color: Color) = drawRect(color, Offset(x, y), Size(w, h))
            val bounce = sin(beat * Math.PI.toFloat()) * 10f
            fun characterJump(index: Int) = if (selectedCharacter == index) sin(jump.value * Math.PI.toFloat()) * 34f else 0f
            fun pixelHeart(centerX: Float, top: Float, visible: Boolean) {
                if (!visible) return
                val alpha = (.45f + sin(jump.value * Math.PI.toFloat()) * .55f).coerceIn(0f, 1f)
                val red = Color(0xFFFF315C).copy(alpha = alpha)
                pixel(centerX - 14, top + 6, 10f, 10f, red); pixel(centerX + 4, top + 6, 10f, 10f, red)
                pixel(centerX - 20, top + 12, 40f, 12f, red); pixel(centerX - 14, top + 24, 28f, 10f, red)
                pixel(centerX - 7, top + 34, 14f, 8f, red)
                pixel(centerX - 8, top + 13, 8f, 7f, Color.White.copy(alpha = alpha * .75f))
            }
            // Sulley: gran silueta azul con manchas violetas y cuernos.
            val sx = size.width * .12f; val sy = floor - 112f - bounce - characterJump(0)
            pixel(sx, sy, 76f, 96f, Color(0xFF27B7D8)); pixel(sx + 12, sy - 18, 52f, 30f, Color(0xFF27B7D8));
            pixel(sx + 5, sy - 25, 12f, 16f, Color(0xFFE9E1C2)); pixel(sx + 59, sy - 25, 12f, 16f, Color(0xFFE9E1C2))
            repeat(5) { i -> pixel(sx + 10f + (i * 13f) % 50f, sy + 20f + (i % 3) * 22f, 10f, 10f, Color(0xFF7C3AED)) }
            // Sulley: rostro pixel art completo.
            pixel(sx + 17, sy - 11, 15f, 6f, Color(0xFF173B55)); pixel(sx + 44, sy - 11, 15f, 6f, Color(0xFF173B55))
            pixel(sx + 18, sy - 5, 14f, 15f, Color.White); pixel(sx + 44, sy - 5, 14f, 15f, Color.White)
            pixel(sx + 23, sy, 6f, 8f, Color(0xFF2878B8)); pixel(sx + 47, sy, 6f, 8f, Color(0xFF2878B8))
            pixel(sx + 24, sy + 9, 28f, 17f, Color(0xFF8DE1DF)); pixel(sx + 32, sy + 8, 12f, 8f, Color(0xFF26364B))
            // Sonrisa abierta en tres niveles para que nunca parezca un gesto neutro.
            pixel(sx + 25, sy + 20, 26f, 5f, Color(0xFF3A1830)); pixel(sx + 29, sy + 25, 18f, 6f, Color(0xFF3A1830))
            pixel(sx + 34, sy + 28, 8f, 4f, Color(0xFFFF7A9D)); pixel(sx + 26, sy + 20, 5f, 6f, Color.White); pixel(sx + 45, sy + 20, 5f, 6f, Color.White)
            pixel(sx + 14, sy + 38, 8f, 8f, Color(0xFF79D9E8)); pixel(sx + 52, sy + 48, 8f, 8f, Color(0xFF79D9E8))
            pixel(sx + 9, sy + 82, 18f, 9f, Color(0xFF1688B5)); pixel(sx + 49, sy + 82, 18f, 9f, Color(0xFF1688B5))
            repeat(3) { claw -> pixel(sx + 8 + claw * 6f, sy + 91, 4f, 7f, Color(0xFFE9E1C2)); pixel(sx + 49 + claw * 6f, sy + 91, 4f, 7f, Color(0xFFE9E1C2)) }
            pixel(sx - 12, sy + 18 + beat * 12f, 16f, 58f, Color(0xFF27B7D8)); pixel(sx + 72, sy + 28 - beat * 12f, 16f, 58f, Color(0xFF27B7D8))
            pixelHeart(sx + 38, sy - 70, selectedCharacter == 0)
            // Mike: esfera verde, ojo grande y extremidades danzantes.
            val mx = size.width * .78f; val my = floor - 72f + bounce - characterJump(1)
            drawCircle(Color(0xFF8BCF2F), 42f, Offset(mx, my)); drawCircle(Color.White, 17f, Offset(mx, my - 8)); drawCircle(Color(0xFF172033), 7f, Offset(mx, my - 8))
            // Mike: ceja expresiva, sonrisa, diente y cuernos.
            pixel(mx - 19, my - 34, 38f, 5f, Color(0xFF315B24))
            pixel(mx - 17, my + 12, 34f, 6f, Color(0xFF173020)); pixel(mx - 13, my + 18, 26f, 7f, Color(0xFF173020))
            pixel(mx - 7, my + 22, 14f, 5f, Color(0xFFFF7A9D)); pixel(mx - 8, my + 12, 16f, 5f, Color.White)
            pixel(mx - 31, my - 39, 8f, 14f, Color(0xFFE9E1C2)); pixel(mx + 23, my - 39, 8f, 14f, Color(0xFFE9E1C2))
            pixel(mx - 9, my - 12, 5f, 5f, Color.White); pixel(mx + 8, my + 4, 7f, 5f, Color(0xFFAEEB63))
            pixel(mx - 36, my + 4, 7f, 7f, Color(0xFF5A9824)); pixel(mx + 29, my + 4, 7f, 7f, Color(0xFF5A9824))
            pixel(mx - 48, my - 8 + beat * 10f, 30f, 8f, Color(0xFF8BCF2F)); pixel(mx + 18, my - 8 - beat * 10f, 30f, 8f, Color(0xFF8BCF2F))
            pixel(mx - 23, my + 34, 9f, 38f, Color(0xFF8BCF2F)); pixel(mx + 14, my + 34, 9f, 38f, Color(0xFF8BCF2F))
            pixelHeart(mx, my - 92, selectedCharacter == 1)
            // Boo: pequeña figura rosa con coletas.
            val bx = size.width * .45f; val by = floor - 76f - bounce - characterJump(2)
            pixel(bx, by, 44f, 62f, Color(0xFFFF78B5)); pixel(bx + 7, by - 28, 30f, 30f, Color(0xFFF1C6A8))
            pixel(bx - 4, by - 32, 12f, 12f, Color(0xFF3A241F)); pixel(bx + 36, by - 32, 12f, 12f, Color(0xFF3A241F))
            // Boo: flequillo, ojos, nariz y sonrisa pixel art.
            pixel(bx + 7, by - 30, 30f, 7f, Color(0xFF3A241F)); pixel(bx + 12, by - 17, 5f, 7f, Color(0xFF2E2525)); pixel(bx + 27, by - 17, 5f, 7f, Color(0xFF2E2525))
            pixel(bx + 20, by - 9, 4f, 4f, Color(0xFFD18E7D))
            pixel(bx + 14, by - 4, 16f, 4f, Color(0xFF9B4E62)); pixel(bx + 18, by, 8f, 4f, Color(0xFF9B4E62)); pixel(bx + 19, by, 6f, 2f, Color(0xFFFFB0C8))
            pixel(bx + 9, by + 8, 26f, 5f, Color(0xFFFFB0D1)); pixel(bx + 7, by + 24, 30f, 5f, Color(0xFFD94B8C))
            pixel(bx + 5, by + 48, 12f, 12f, Color(0xFF8E5CD9)); pixel(bx + 27, by + 48, 12f, 12f, Color(0xFF8E5CD9))
            pixel(bx + 8, by - 21, 4f, 4f, Color(0xFFFFE5D1)); pixel(bx + 32, by - 21, 4f, 4f, Color(0xFFFFE5D1))
            pixel(bx - 20, by + 8 - beat * 10f, 22f, 8f, Color(0xFFF1C6A8)); pixel(bx + 42, by + 8 + beat * 10f, 22f, 8f, Color(0xFFF1C6A8))
            pixelHeart(bx + 22, by - 82, selectedCharacter == 2)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(42.dp)) {
                val scale = .82f + beat * .16f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2f, size.height * .84f)
                    cubicTo(size.width * .08f, size.height * .58f, size.width * .05f, size.height * .18f, size.width * .29f, size.height * .18f)
                    cubicTo(size.width * .43f, size.height * .18f, size.width / 2f, size.height * .32f, size.width / 2f, size.height * .32f)
                    cubicTo(size.width / 2f, size.height * .32f, size.width * .57f, size.height * .18f, size.width * .71f, size.height * .18f)
                    cubicTo(size.width * .95f, size.height * .18f, size.width * .92f, size.height * .58f, size.width / 2f, size.height * .84f)
                }
                scale(scale, scale, pivot = center) { drawPath(path, Color(0xFFFF2D55)) }
            }
            Spacer(Modifier.width(10.dp))
            Text("Hecho con amor para mi esposa Lía", color = Color(0xFFFFB3C7), fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 10.dp))
        }
        Text("CHANGELOG", color = Color(0xFF00E5FF), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        listOf(
            "8.11.5 · Flechas ocupa visualmente cada celda interior de la figura.",
            "8.11.4 · Flechas rellena cada celda de la figura; Boo baila al centro.",
            "8.11.3 · Flechas densifica las figuras sin aumentar su grosor.",
            "8.11.2 · Flechas recupera trazos finos y encadena 100 etapas.",
            "8.11.1 · Flechas: lienzos 5×5, 7×7, 8×8 y 20×20.",
            "8.11.0 · Personajes pixel art interactivos con salto y corazones.",
            "8.10.6 · Pac-Man solitario fluido y rostros pixel art renovados.",
            "8.10.3 · Gato Ultimate valida y conquista mini-tableros.",
            "8.10.3 · Flechas usa geometría ortogonal 100×100.",
            "8.10.3 · Predicción visual y bots suavizados en Pac-Man.",
            "8.10 · Reactor Chain integra esferas de habilidad y 100 niveles.",
            "8.9 · Nuevas arenas, modo offline, perfiles y cuadro de honor.",
        ).forEach { Text("• $it", color = Color(0xFFE2E8F0)) }
    }
}
