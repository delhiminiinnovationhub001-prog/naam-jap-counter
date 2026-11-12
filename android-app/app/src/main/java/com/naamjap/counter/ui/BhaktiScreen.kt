package com.naamjap.counter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naamjap.counter.bhakti.Aarti
import com.naamjap.counter.bhakti.Chapter
import com.naamjap.counter.bhakti.Devotional
import com.naamjap.counter.bhakti.Speaker
import java.net.URLEncoder

private enum class Part(val label: String) { Aarti("🪔 Aarti"), Gita("📖 Bhagavad Gita") }

/** Aartis and the Bhagavad Gita, with read-aloud; works offline. */
@Composable
fun BhaktiScreen() {
    val context = LocalContext.current
    var part by rememberSaveable { mutableStateOf(Part.Aarti) }
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Part.entries.forEach { p ->
                FilterChip(selected = part == p, onClick = { speaker.stop(); part = p }, label = { Text(p.label) })
            }
        }
        VoiceNotice(speaker)
        when (part) {
            Part.Aarti -> AartiPart(speaker)
            Part.Gita -> GitaPart(speaker)
        }
    }
}

@Composable
private fun VoiceNotice(speaker: Speaker) {
    val context = LocalContext.current
    val st by speaker.state.collectAsStateWithLifecycle()
    if (st == Speaker.State.NoHindi || st == Speaker.State.Failed) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Read-aloud needs the Hindi voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Install it once in the phone's Text-to-speech settings (Google: Language > हिन्दी). Reading the text works without it.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { runCatching { context.startActivity(Speaker.installVoiceIntent()) } }) { Text("Open Text-to-speech settings") }
            }
        }
    }
}

/** Opens YouTube Music (the app if installed, else the website) with a search. */
private fun openYouTubeMusic(context: Context, query: String) {
    val url = "https://music.youtube.com/search?q=" + URLEncoder.encode(query, "UTF-8")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ------------------------------------------------------------------- aarti

@Composable
private fun AartiPart(speaker: Speaker) {
    val context = LocalContext.current
    val all = remember { runCatching { Devotional.aartis(context) }.getOrDefault(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val open = all.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { speaker.stop(); openId = null }
        AartiView(open, speaker) { speaker.stop(); openId = null }
        return
    }
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter {
        it.titleHi.contains(q, true) || it.titleEn.contains(q, true) || it.deity.contains(q, true)
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(60) },
                label = { Text("Search aarti or song") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotEmpty() && shown.isEmpty()) openYouTubeMusic(context, q) }),
            )
            if (q.isNotEmpty()) {
                OutlinedButton(onClick = { openYouTubeMusic(context, q) }, modifier = Modifier.padding(top = 6.dp)) {
                    Text("▶ Search “$q” on YouTube Music")
                }
            }
        }
        items(shown, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth().clickable { openId = a.id }) {
                Column(Modifier.padding(14.dp)) {
                    Text(a.titleHi, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(a.titleEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (shown.isEmpty()) item { Text("No aarti with that name in the app. Search it on YouTube Music above.") }
    }
}

@Composable
private fun AartiView(a: Aarti, speaker: Speaker, onBack: () -> Unit) {
    val context = LocalContext.current
    var hindi by rememberSaveable { mutableStateOf(true) }
    val speaking by speaker.speaking.collectAsStateWithLifecycle()
    val voice by speaker.state.collectAsStateWithLifecycle()
    val lines = if (hindi || a.en.isEmpty()) a.hi.ifEmpty { a.en } else a.en
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ All aartis") }
        Text(a.titleHi, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Saffron)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openYouTubeMusic(context, a.titleHi) }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                Text("▶ YouTube Music")
            }
            if (speaking == a.id) OutlinedButton(onClick = speaker::stop) { Text("■ Stop") }
            else OutlinedButton(enabled = voice == Speaker.State.Ready, onClick = { speaker.speak(a.id, a.hi.ifEmpty { a.en }) }) { Text("🔊 Read aloud") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = hindi, onClick = { hindi = true }, label = { Text("हिन्दी") })
            if (a.en.isNotEmpty()) FilterChip(selected = !hindi, onClick = { hindi = false }, label = { Text("English letters") })
        }
        lines.forEach { l ->
            Text(l.replace(";", "\n"), style = MaterialTheme.typography.bodyLarge, fontSize = 19.sp, lineHeight = 30.sp)
            HorizontalDivider()
        }
    }
}

// -------------------------------------------------------------------- gita

@Composable
private fun GitaPart(speaker: Speaker) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bhakti", Context.MODE_PRIVATE) }
    val gita = remember { runCatching { Devotional.gita(context) }.getOrDefault(emptyList()) }
    var chapter by rememberSaveable { mutableIntStateOf(-1) }   // index, -1 = chapter list
    var verse by rememberSaveable { mutableIntStateOf(0) }

    if (gita.isEmpty()) {
        Text("The Gita could not be loaded.", Modifier.padding(16.dp))
        return
    }
    if (chapter < 0) {
        val lastC = prefs.getInt("ch", -1)
        val lastV = prefs.getInt("v", 0)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("श्रीमद्भगवद्गीता", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Saffron)
                Text("18 chapters · 701 verses · Sanskrit with Hindi meaning", style = MaterialTheme.typography.bodySmall)
            }
            if (lastC >= 0 && lastC < gita.size) item {
                Card(Modifier.fillMaxWidth().clickable { chapter = lastC; verse = lastV },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text("Continue: chapter ${gita[lastC].n}, verse ${gita[lastC].verses.getOrNull(lastV)?.n ?: 1}",
                        Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
            items(gita.indices.toList()) { i ->
                val c = gita[i]
                Card(Modifier.fillMaxWidth().clickable { chapter = i; verse = 0 }) {
                    Column(Modifier.padding(14.dp)) {
                        Text("अध्याय ${c.n} · ${c.titleSa}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Chapter ${c.n}: ${c.titleEn} · ${c.verses.size} verses", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text(Devotional.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        return
    }
    BackHandler { speaker.stop(); chapter = -1 }
    GitaReader(gita, chapter, verse, speaker,
        onMove = { c, v -> chapter = c; verse = v; prefs.edit().putInt("ch", c).putInt("v", v).apply() },
        onBack = { speaker.stop(); chapter = -1 })
}

@Composable
private fun GitaReader(gita: List<Chapter>, ci: Int, vi: Int, speaker: Speaker, onMove: (Int, Int) -> Unit, onBack: () -> Unit) {
    val c = gita[ci]
    val v = c.verses[vi.coerceIn(0, c.verses.lastIndex)]
    val speaking by speaker.speaking.collectAsStateWithLifecycle()
    val voice by speaker.state.collectAsStateWithLifecycle()
    var path by rememberSaveable { mutableStateOf(false) }      // keep reading verse after verse
    var showEn by rememberSaveable { mutableStateOf(false) }
    var showIast by rememberSaveable { mutableStateOf(false) }
    var rate by remember { mutableFloatStateOf(speaker.rate) }

    fun next(): Pair<Int, Int>? = when {
        vi < c.verses.lastIndex -> ci to vi + 1
        ci < gita.lastIndex -> ci + 1 to 0
        else -> null
    }
    fun prev(): Pair<Int, Int>? = when {
        vi > 0 -> ci to vi - 1
        ci > 0 -> ci - 1 to gita[ci - 1].verses.lastIndex
        else -> null
    }
    val id = "${c.n}.${v.n}"
    fun read() = speaker.speak(id, listOf(v.sa, "अर्थ। " + v.hi)) { done ->
        // पाठ: after the meaning, go on to the next verse by itself.
        if (path && done == id) next()?.let { (nc, nv) -> onMove(nc, nv) }
    }
    // In पाठ mode a new verse on screen is read as soon as it appears.
    LaunchedEffect(id, path) { if (path && voice == Speaker.State.Ready && speaking != id) read() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Chapters") }
            Spacer(Modifier.weight(1f))
            Text("अध्याय ${c.n} · श्लोक ${v.n}", style = MaterialTheme.typography.titleSmall)
        }
        Text(c.titleSa, style = MaterialTheme.typography.titleMedium, color = Saffron, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(v.sa, Modifier.fillMaxWidth().padding(16.dp), fontSize = 21.sp, lineHeight = 34.sp, textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold)
        }
        if (showIast && v.iast.isNotBlank()) Text(v.iast, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("अर्थ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(v.hi, fontSize = 18.sp, lineHeight = 29.sp)
        if (showEn) {
            Text("Meaning (Shri Purohit Swami)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(v.en, style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = showIast, onClick = { showIast = !showIast }, label = { Text("Roman letters") })
            FilterChip(selected = showEn, onClick = { showEn = !showEn }, label = { Text("English") })
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (speaking == id) OutlinedButton(onClick = { path = false; speaker.stop() }) { Text("■ Stop") }
            else OutlinedButton(enabled = voice == Speaker.State.Ready, onClick = { read() }) { Text("🔊 Read") }
            Button(
                enabled = voice == Speaker.State.Ready,
                onClick = { path = !path; if (!path) speaker.stop() },
                colors = if (path) ButtonDefaults.buttonColors(containerColor = Danger) else ButtonDefaults.buttonColors(),
            ) { Text(if (path) "■ Stop पाठ" else "▶ पाठ (read on)") }
        }
        Text("पाठ reads each shloka in Sanskrit, then its meaning in Hindi, and goes on to the next.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text("Reading speed", style = MaterialTheme.typography.bodySmall)
            Slider(value = rate, onValueChange = { rate = it; speaker.rate = it }, valueRange = 0.5f..1.3f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = prev() != null, onClick = { speaker.stop(); prev()?.let { (a, b) -> onMove(a, b) } }) { Text("‹ Previous") }
            OutlinedButton(enabled = next() != null, onClick = { speaker.stop(); next()?.let { (a, b) -> onMove(a, b) } }) { Text("Next ›") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
