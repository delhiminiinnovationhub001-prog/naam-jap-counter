package com.naamjap.counter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

private val indianFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))

/** 1234567 -> "12,34,567" */
fun Long.indian(): String = indianFormat.format(this)
fun Int.indian(): String = indianFormat.format(this.toLong())

/** Microphone level from -80 to -20 dBFS, with the trigger level as a red line. */
@Composable
fun LevelBar(levelDb: Float, thresholdDb: Float, voice: Boolean, paused: Boolean, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = when {
        paused -> Color.Gray
        voice -> VoiceGreen
        else -> LevelBlue
    }
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        fun frac(db: Float) = ((db + 80f) / 60f).coerceIn(0f, 1f)
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(fill, size = Size(size.width * frac(levelDb), size.height), cornerRadius = r)
        val x = size.width * frac(thresholdDb)
        drawLine(Danger, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}

/** Closeness of the last sound to the taught word; the red line is the threshold (at 50%). */
@Composable
fun MatchBar(score: Float, threshold: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (score >= 0 && threshold > 0) {
            val frac = (score / (threshold * 2)).coerceIn(0f, 1f)
            drawRoundRect(
                if (score <= threshold) VoiceGreen else LevelBlue,
                size = Size(size.width * frac, size.height),
                cornerRadius = r,
            )
        }
        val x = size.width / 2
        drawLine(Danger, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) Danger else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InputDialog(
    title: String,
    label: String,
    initial: String = "",
    numeric: Boolean = false,
    maxLength: Int = 16,
    confirmLabel: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.isNotBlank() && (!numeric || value.trim().toLongOrNull()?.let { it >= 0 } == true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> value = if (numeric) v.filter(Char::isDigit).take(9) else v.take(maxLength) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(value.trim()); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
