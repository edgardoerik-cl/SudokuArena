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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.rotate
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
fun MultiArenaSplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1.08f) }
    val pulseTransition = rememberInfiniteTransition(label = "neonPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "neonPulseValue",
    )
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { alpha.animateTo(1f, tween(1_500)) }
            launch { scale.animateTo(1f, tween(2_800, easing = FastOutSlowInEasing)) }
        }
        delay(200)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.multi_arena_splash_art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
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
        Text(
            "CONECTANDO MULTI ARENA${if (pulse > 0.67f) "…" else ""}",
            color = Color.White.copy(alpha = 0.72f + pulse * 0.28f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp)
                .background(Color(0xB8070918), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** Emblema vivo de las diez arenas conectadas. */
@Composable
fun ArenaLogo(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "arenaEmblem")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emblemPulse",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8_000)),
        label = "emblemOrbit",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cyan = Color(0xFF00A8FF)
            val violet = Color(0xFF7C3AED)
            val radius = size.minDimension * 0.35f
            drawCircle(cyan.copy(alpha = 0.08f + pulse * 0.08f), radius * (1.18f + pulse * 0.06f))
            rotate(orbit) {
                drawArc(cyan, 8f, 102f, false, style = Stroke(5f), topLeft = Offset(center.x - radius * 1.18f, center.y - radius * 1.18f), size = Size(radius * 2.36f, radius * 2.36f))
                drawArc(violet, 188f, 108f, false, style = Stroke(5f), topLeft = Offset(center.x - radius * 1.18f, center.y - radius * 1.18f), size = Size(radius * 2.36f, radius * 2.36f))
                repeat(10) { index ->
                    val angle = index * 6.283185f / 10f
                    drawCircle(
                        if (index % 2 == 0) cyan else violet,
                        radius = 5f + pulse * 2f,
                        center = center + Offset(kotlin.math.cos(angle), kotlin.math.sin(angle)) * radius * 1.18f,
                    )
                }
            }
            val shield = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius * 0.82f, center.y - radius * 0.5f)
                lineTo(center.x + radius * 0.72f, center.y + radius * 0.55f)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius * 0.72f, center.y + radius * 0.55f)
                lineTo(center.x - radius * 0.82f, center.y - radius * 0.5f)
                close()
            }
            drawPath(shield, Brush.linearGradient(listOf(Color(0xFFF8FBFF), Color(0xFFDCEBFF), Color(0xFFEDE4FF))))
            drawPath(shield, Brush.linearGradient(listOf(cyan, violet)), style = Stroke(7f + pulse * 2f))
            val grid = radius * 0.45f
            for (line in -1..1) {
                val p = line * grid / 2f
                drawLine(cyan.copy(alpha = 0.20f), Offset(center.x - grid, center.y + p), Offset(center.x + grid, center.y + p), 2f)
                drawLine(violet.copy(alpha = 0.18f), Offset(center.x + p, center.y - grid), Offset(center.x + p, center.y + grid), 2f)
            }
        }
        Text(
            "M",
            color = Color(0xFF102A56),
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.graphicsLayer {
                scaleX = 1f + pulse * 0.045f
                scaleY = scaleX
                shadowElevation = 10f + pulse * 12f
            },
        )
    }
}
