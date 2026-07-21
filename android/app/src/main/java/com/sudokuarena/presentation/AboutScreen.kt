package com.sudokuarena.presentation

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.sin

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pixelDance")
    val beat by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse), label = "beat")
    Column(
        Modifier.fillMaxSize().background(Color(0xFF07152F)).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ACERCA DE MULTI ARENA", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Volver") }
        }
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            drawRect(Color(0xFF101A3C)); val floor = size.height * .82f
            repeat(12) { i -> drawRect(if (i % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF2D95), Offset(i * size.width / 12f, floor), Size(size.width / 12f, size.height - floor), alpha = .35f) }
            fun pixel(x: Float, y: Float, w: Float, h: Float, color: Color) = drawRect(color, Offset(x, y), Size(w, h))
            val bounce = sin(beat * Math.PI.toFloat()) * 10f
            // Sulley: gran silueta azul con manchas violetas y cuernos.
            val sx = size.width * .20f; val sy = floor - 112f - bounce
            pixel(sx, sy, 76f, 96f, Color(0xFF27B7D8)); pixel(sx + 12, sy - 18, 52f, 30f, Color(0xFF27B7D8));
            pixel(sx + 5, sy - 25, 12f, 16f, Color(0xFFE9E1C2)); pixel(sx + 59, sy - 25, 12f, 16f, Color(0xFFE9E1C2))
            repeat(5) { i -> pixel(sx + 10f + (i * 13f) % 50f, sy + 20f + (i % 3) * 22f, 10f, 10f, Color(0xFF7C3AED)) }
            // Sulley: rostro pixel art completo.
            pixel(sx + 17, sy - 11, 15f, 6f, Color(0xFF173B55)); pixel(sx + 44, sy - 11, 15f, 6f, Color(0xFF173B55))
            pixel(sx + 18, sy - 5, 14f, 15f, Color.White); pixel(sx + 44, sy - 5, 14f, 15f, Color.White)
            pixel(sx + 23, sy, 6f, 8f, Color(0xFF2878B8)); pixel(sx + 47, sy, 6f, 8f, Color(0xFF2878B8))
            pixel(sx + 24, sy + 9, 28f, 17f, Color(0xFF8DE1DF)); pixel(sx + 32, sy + 8, 12f, 8f, Color(0xFF26364B))
            pixel(sx + 29, sy + 23, 18f, 5f, Color(0xFF3A1830)); pixel(sx + 29, sy + 23, 5f, 7f, Color.White); pixel(sx + 42, sy + 23, 5f, 7f, Color.White)
            pixel(sx - 12, sy + 18 + beat * 12f, 16f, 58f, Color(0xFF27B7D8)); pixel(sx + 72, sy + 28 - beat * 12f, 16f, 58f, Color(0xFF27B7D8))
            // Mike: esfera verde, ojo grande y extremidades danzantes.
            val mx = size.width * .49f; val my = floor - 72f + bounce
            drawCircle(Color(0xFF8BCF2F), 42f, Offset(mx, my)); drawCircle(Color.White, 17f, Offset(mx, my - 8)); drawCircle(Color(0xFF172033), 7f, Offset(mx, my - 8))
            // Mike: ceja expresiva, sonrisa, diente y cuernos.
            pixel(mx - 19, my - 34, 38f, 5f, Color(0xFF315B24))
            pixel(mx - 16, my + 13, 32f, 10f, Color(0xFF173020)); pixel(mx - 7, my + 13, 14f, 6f, Color.White)
            pixel(mx - 31, my - 39, 8f, 14f, Color(0xFFE9E1C2)); pixel(mx + 23, my - 39, 8f, 14f, Color(0xFFE9E1C2))
            pixel(mx - 48, my - 8 + beat * 10f, 30f, 8f, Color(0xFF8BCF2F)); pixel(mx + 18, my - 8 - beat * 10f, 30f, 8f, Color(0xFF8BCF2F))
            pixel(mx - 23, my + 34, 9f, 38f, Color(0xFF8BCF2F)); pixel(mx + 14, my + 34, 9f, 38f, Color(0xFF8BCF2F))
            // Boo: pequeña figura rosa con coletas.
            val bx = size.width * .73f; val by = floor - 76f - bounce
            pixel(bx, by, 44f, 62f, Color(0xFFFF78B5)); pixel(bx + 7, by - 28, 30f, 30f, Color(0xFFF1C6A8))
            pixel(bx - 4, by - 32, 12f, 12f, Color(0xFF3A241F)); pixel(bx + 36, by - 32, 12f, 12f, Color(0xFF3A241F))
            // Boo: flequillo, ojos, nariz y sonrisa pixel art.
            pixel(bx + 7, by - 30, 30f, 7f, Color(0xFF3A241F)); pixel(bx + 12, by - 17, 5f, 7f, Color(0xFF2E2525)); pixel(bx + 27, by - 17, 5f, 7f, Color(0xFF2E2525))
            pixel(bx + 20, by - 9, 4f, 4f, Color(0xFFD18E7D)); pixel(bx + 17, by - 3, 11f, 4f, Color(0xFF9B4E62))
            pixel(bx - 20, by + 8 - beat * 10f, 22f, 8f, Color(0xFFF1C6A8)); pixel(bx + 42, by + 8 + beat * 10f, 22f, 8f, Color(0xFFF1C6A8))
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
            "8.10.6 · Pac-Man solitario fluido y rostros pixel art renovados.",
            "8.10.3 · Gato Ultimate valida y conquista mini-tableros.",
            "8.10.3 · Flechas usa geometría ortogonal 100×100.",
            "8.10.3 · Predicción visual y bots suavizados en Pac-Man.",
            "8.10 · Reactor Chain integra esferas de habilidad y 100 niveles.",
            "8.9 · Nuevas arenas, modo offline, perfiles y cuadro de honor.",
        ).forEach { Text("• $it", color = Color(0xFFE2E8F0)) }
    }
}
