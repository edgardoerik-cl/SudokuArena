package com.sudokuarena.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Estructura común para todos los juegos.
 *
 * Horizontal: tablero amplio a la izquierda y controles compactos a la derecha.
 * Vertical: tablero arriba y panel desplazable abajo. El cálculo se repite al
 * girar el dispositivo, sin reiniciar el ViewModel ni perder la partida.
 */
@Composable
fun AdaptiveArenaLayout(
    modifier: Modifier = Modifier,
    board: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val landscape = maxWidth > maxHeight * 1.12f
        val gap = if (landscape) 8.dp else 6.dp

        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                Box(
                    Modifier.weight(.74f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) { board() }
                ControlPanel(
                    Modifier.weight(.26f).fillMaxHeight(),
                    controls,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Box(
                    Modifier.weight(.64f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { board() }
                ControlPanel(
                    Modifier.weight(.36f).fillMaxWidth(),
                    controls,
                )
            }
        }
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = Color(0xF7F8FAFF),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 5.dp,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}
