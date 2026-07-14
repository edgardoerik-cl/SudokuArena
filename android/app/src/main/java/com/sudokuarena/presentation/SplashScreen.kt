package com.sudokuarena.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.72f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { alpha.animateTo(1f, tween(900)) }
            launch { scale.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing)) }
        }
        delay(900)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF283593), Color(0xFF10142E), Color(0xFF070918)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFF42A5F5).copy(alpha = 0.12f), size.minDimension * 0.42f, center)
            drawCircle(Color(0xFFFFB300).copy(alpha = 0.08f), size.minDimension * 0.3f, center, style = Stroke(8f))
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
            Text("SUDOKU", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text("ARENA", color = Color(0xFFFFCA28), fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 7.sp)
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
