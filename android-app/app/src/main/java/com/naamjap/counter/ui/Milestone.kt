package com.naamjap.counter.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.naamjap.counter.phone.Milestone
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Celebration over everything: every 100 japs a burst of flowers and the count;
 * every 1000 a bigger one with "Radha" flashing and "Radha ji blesses us".
 */
@Composable
fun MilestoneOverlay(m: Milestone, onDone: () -> Unit) {
    val view = LocalView.current
    val duration = if (m.big) 6000 else 2800
    val progress = remember(m) { Animatable(0f) }
    LaunchedEffect(m) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        progress.animateTo(1f, tween(duration, easing = LinearEasing))
        onDone()
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.85f, 1.15f, infiniteRepeatable(tween(if (m.big) 350 else 600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s",
    )
    val p = progress.value
    val fade = if (p < 0.1f) p / 0.1f else if (p > 0.85f) (1f - p) / 0.15f else 1f
    val petals = remember(m) {
        List(if (m.big) 90 else 40) {
            Petal(Random.nextFloat() * 360f, 0.3f + Random.nextFloat() * 0.7f, Random.nextFloat(), petalColors.random())
        }
    }
    Box(
        Modifier.fillMaxSize().alpha(fade)
            .background(if (m.big) Color(0xCC3A0A00) else Color(0x88000000))
            .clickable(onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val reach = size.minDimension * 0.75f
            for (pt in petals) {
                val t = ((p * (if (m.big) 2.5f else 1.4f)) + pt.delay) % 1f
                val r = reach * t * pt.speed
                val a = Math.toRadians(pt.angle.toDouble() + t * 60)
                val pos = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat() + t * t * 200f)
                drawCircle(pt.color.copy(alpha = 1f - t), radius = (if (m.big) 14f else 10f) * (1f - t * 0.5f), center = pos)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (m.big) {
                // "RADHA" flashing, like the counter's screen at every 1000
                val flash = ((p * 12).toInt() % 2 == 0)
                Text("राधा", color = if (flash) Color(0xFFFFD54F) else Color(0xFFFF8F00), fontSize = 72.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.scale(pulse))
                Text("RADHA", color = if (flash) Color(0xFFFF8F00) else Color(0xFFFFD54F), fontSize = 44.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.scale(pulse))
            }
            Text(m.count.indian(), color = Color.White, fontSize = if (m.big) 64.sp else 56.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.scale(if (m.big) 1f else pulse))
            Text(
                if (m.big) "Radha ji blesses us 🙏" else "japs today${m.name?.let { " · $it" } ?: ""}",
                color = Color(0xFFFFE0B2), fontSize = if (m.big) 26.sp else 18.sp, textAlign = TextAlign.Center,
                fontWeight = if (m.big) FontWeight.Bold else FontWeight.Normal,
            )
            if (m.big) Text("${m.count / 1000} thousand naam today${m.name?.let { ", $it" } ?: ""}", color = Color.White, fontSize = 16.sp)
        }
    }
    LaunchedEffect(m) {
        if (m.big) repeat(5) { delay(1000); view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }
}

private data class Petal(val angle: Float, val speed: Float, val delay: Float, val color: Color)

private val petalColors = listOf(
    Color(0xFFFF8F00), Color(0xFFFFD54F), Color(0xFFE91E63), Color(0xFFFF7043), Color(0xFFFFF176), Color(0xFFF48FB1),
)
