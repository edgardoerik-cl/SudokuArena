package com.sudokuarena.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sudokuarena.R

@Composable
fun SudokuArenaSplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.72f) }
    val pulseTransition = rememberInfiniteTransition(label = "neonPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "neonPulseValue",
    )
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { alpha.animateTo(1f, tween(900)) }
            launch { scale.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing)) }
        }
        delay(1_900)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Sustituir el recurso XML provisional por el WEBP/PNG final con el mismo nombre.
        Image(
            painter = painterResource(R.drawable.sudoku_arena_splash_art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = 1.06f - alpha.value * 0.06f
                    scaleY = 1.06f - alpha.value * 0.06f
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent, Color(0xDD070918)),
                    ),
                ),
        )
        Canvas(Modifier.fillMaxSize()) {
            val horizon = size.height * 0.43f
            val vanishingPoint = Offset(size.width / 2f, horizon)
            // Líneas que convergen hacia el horizonte: coliseo digital en perspectiva.
            for (lane in -8..8) {
                val bottomX = size.width / 2f + lane * size.width / 8f
                drawLine(
                    Color(0xFF00F0FF).copy(alpha = 0.16f + pulse * 0.16f),
                    vanishingPoint,
                    Offset(bottomX, size.height),
                    if (lane % 3 == 0) 2.4f else 1.2f,
                )
            }
            for (line in 1..13) {
                val progress = line / 13f
                val y = horizon + progress * progress * (size.height - horizon)
                drawLine(
                    Color(0xFF8B5CF6).copy(alpha = 0.12f + progress * 0.28f),
                    Offset(0f, y),
                    Offset(size.width, y),
                    1.4f,
                )
            }
            drawCircle(Color(0xFF00F0FF).copy(alpha = 0.08f + pulse * 0.08f), size.minDimension * 0.42f, center)
            drawCircle(Color(0xFFFFC857).copy(alpha = 0.08f + pulse * 0.05f), size.minDimension * 0.3f, center, style = Stroke(8f))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            },
        ) {
            ArenaLogo(Modifier.size(210.dp))
            Text("SUDOKU", color = Color.White.copy(alpha = 0.82f + pulse * 0.18f), fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text("ARENA", color = Color(0xFFFFCA28).copy(alpha = 0.72f + pulse * 0.28f), fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 7.sp)
            Text(
                "SINCRONIZANDO ARENA${if (pulse > 0.67f) "…" else ""}",
                color = Color.White.copy(alpha = 0.58f + pulse * 0.24f),
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

/** Tablero 3x3 fusionado con gradas, columnas y arco de coliseo. */
@Composable
fun ArenaLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val glow = Color(0xFF64B5F6)
        val gold = Color(0xFFFFCA28)
        val boardSize = size.minDimension * 0.58f
        val left = (size.width - boardSize) / 2f
        val top = size.height * 0.24f
        drawRoundRect(glow.copy(alpha = 0.18f), Offset(left - 10f, top - 10f), Size(boardSize + 20f, boardSize + 20f), CornerRadius(24f))
        drawRoundRect(Color(0xFF111A45), Offset(left, top), Size(boardSize, boardSize), CornerRadius(18f))
        drawRoundRect(gold, Offset(left, top), Size(boardSize, boardSize), CornerRadius(18f), style = Stroke(7f))
        for (index in 1..2) {
            val position = index * boardSize / 3f
            drawLine(glow, Offset(left + position, top), Offset(left + position, top + boardSize), 5f)
            drawLine(glow, Offset(left, top + position), Offset(left + boardSize, top + position), 5f)
        }
        listOf(1, 4, 6, 8, 3).forEachIndexed { index, number ->
            val column = index % 3
            val row = index / 3
            drawCircle(
                gold.copy(alpha = 0.85f),
                radius = boardSize * 0.035f,
                center = Offset(left + (column + 0.5f) * boardSize / 3f, top + (row + 0.5f) * boardSize / 3f),
            )
        }
        val arena = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.84f)
            quadraticTo(size.width * 0.5f, size.height * 0.61f, size.width * 0.88f, size.height * 0.84f)
        }
        drawPath(arena, gold, style = Stroke(12f))
        drawLine(gold, Offset(size.width * 0.16f, size.height * 0.84f), Offset(size.width * 0.84f, size.height * 0.84f), 12f)
        for (column in 0..4) {
            val x = size.width * (0.24f + column * 0.13f)
            drawLine(glow, Offset(x, size.height * 0.73f), Offset(x, size.height * 0.84f), 7f)
        }
    }
}
