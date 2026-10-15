package org.fosscore.spike.musicxml

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import java.io.File
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "M0"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoBench = intent.getBooleanExtra("bench", false)
        setContent {
            MaterialTheme { SpikeScreen(autoBench) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpikeScreen(autoBench: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { ScoreEngine(context.applicationContext) }
    // Bundled samples, then files copied into files/inbox (they stay on the device).
    val samples = remember {
        context.assets.list("samples").orEmpty().sorted() +
            File(context.getExternalFilesDir(null), "inbox").listFiles().orEmpty()
                .filter { it.isFile }.map { "inbox/${it.name}" }.sorted()
    }
    // Default staff height ~7 mm: Verovio draws a staff 72 px high at scale 100. densityDpi,
    // not xdpi: some ROMs report a wrong xdpi (the Galaxy Tab 3 gave a 2.5x too large scale).
    val defaultScale = remember {
        val dpi = context.resources.displayMetrics.densityDpi
        (7f / 25.4f * dpi / 72f * 100f).roundToInt().coerceIn(20, 200)
    }

    var fileName by remember { mutableStateOf(samples.firstOrNull() ?: "") }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var openedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var parts by remember { mutableStateOf(emptyList<Part>()) }
    var shownParts by remember { mutableStateOf(emptySet<String>()) }
    var partsMenuOpen by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf(Renderer.MUPDF) }
    var scale by remember { mutableIntStateOf(defaultScale) }
    var semitones by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var svgForWeb by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var benchRunning by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            fileBytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            fileName = uri.lastPathSegment ?: "file"
            page = 1
        }
    }

    LaunchedEffect(Unit) { version = engine.version() }

    LaunchedEffect(fileName) {
        if (fileBytes == null || samples.contains(fileName)) {
            fileBytes = withContext(Dispatchers.IO) {
                runCatching {
                    if (fileName.startsWith("inbox/")) {
                        File(context.getExternalFilesDir(null), fileName).readBytes()
                    } else {
                        context.assets.open("samples/$fileName").use { it.readBytes() }
                    }
                }.getOrNull()
            }
        }
    }

    LaunchedEffect(fileBytes, viewport, scale, semitones, shownParts, page, renderer, benchRunning) {
        val bytes = fileBytes ?: return@LaunchedEffect
        if (viewport.width == 0 || benchRunning) return@LaunchedEffect
        try {
            if (openedBytes !== bytes) {
                parts = engine.open(bytes)
                openedBytes = bytes
                page = 1
                if (shownParts.isNotEmpty()) {
                    shownParts = emptySet()
                    return@LaunchedEffect
                }
            }
            if (!engine.layout(LayoutSpec(viewport.width, viewport.height, scale, semitones, shownParts))) {
                status = "Could not load $fileName\n${engine.log()}"
                bitmap = null
                return@LaunchedEffect
            }
            pageCount = engine.pageCount
            if (page > pageCount) page = pageCount.coerceAtLeast(1)
            val svg = engine.svg(page)
            when (renderer) {
                Renderer.WEBVIEW -> {
                    bitmap = null
                    svgForWeb = svg
                }
                else -> {
                    val t0 = System.nanoTime()
                    val bmp = withContext(Dispatchers.Default) {
                        if (renderer == Renderer.MUPDF) Rasterizers.mupdf(svg, viewport.width, viewport.height)
                        else Rasterizers.androidSvg(svg, viewport.width, viewport.height)
                    }
                    engine.recordRaster((System.nanoTime() - t0) / 1_000_000)
                    bitmap = bmp
                    svgForWeb = ""
                }
            }
            status = engine.lastTimings.toString()
        } catch (e: Throwable) {
            Log.e(TAG, "render failed", e)
            status = "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    LaunchedEffect(autoBench) {
        if (!autoBench) return@LaunchedEffect
        // Keyed on autoBench only: the viewport may change while the bench runs.
        val size = snapshotFlow { viewport }.first { it.width > 0 }
        benchRunning = true
        Benchmark(context, engine).run(size.width, size.height, scale) { status = it }
        benchRunning = false
        openedBytes = null
    }

    DisposableEffect(Unit) { onDispose { player?.release() } }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Button(onClick = { menuOpen = true }) { Text(fileName.take(24)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open a file…") }, onClick = {
                        menuOpen = false
                        picker.launch(arrayOf("*/*"))
                    })
                    samples.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            menuOpen = false
                            fileBytes = null
                            fileName = name
                        })
                    }
                }
            }
            Box {
                Button(onClick = { partsMenuOpen = true }, enabled = parts.size > 1) {
                    Text(if (shownParts.isEmpty()) "Parts: all" else "Parts: ${shownParts.size}/${parts.size}")
                }
                DropdownMenu(expanded = partsMenuOpen, onDismissRequest = { partsMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("All parts") }, onClick = { shownParts = emptySet() })
                    parts.forEach { part ->
                        val shown = shownParts.isEmpty() || part.id in shownParts
                        DropdownMenuItem(
                            text = { Text("${if (shown) "☑" else "☐"} ${part.name.ifBlank { part.id }}") },
                            onClick = {
                                val current = if (shownParts.isEmpty()) parts.map { it.id }.toSet() else shownParts
                                val next = if (part.id in current) current - part.id else current + part.id
                                // Keep at least one part; all selected means "no filter".
                                if (next.isNotEmpty()) {
                                    shownParts = if (next.size == parts.size) emptySet() else next
                                }
                            }
                        )
                    }
                }
            }
            Renderer.entries.forEach { r ->
                Button(
                    onClick = { renderer = r },
                    colors = if (renderer == r) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                ) { Text(r.name.take(4)) }
            }
            Button(onClick = { scale = (scale - 5).coerceAtLeast(10) }) { Text("−") }
            Text("zoom $scale")
            Button(onClick = { scale = (scale + 5).coerceAtMost(300) }) { Text("+") }
            Button(onClick = { semitones = (semitones - 1).coerceAtLeast(-12) }) { Text("♭") }
            Text("transp ${if (semitones > 0) "+" else ""}$semitones")
            Button(onClick = { semitones = (semitones + 1).coerceAtMost(12) }) { Text("♯") }
            Button(onClick = { page = (page - 1).coerceAtLeast(1) }, enabled = page > 1) { Text("◀") }
            Text("$page/$pageCount")
            Button(onClick = { page = (page + 1).coerceAtMost(pageCount) }, enabled = page < pageCount) { Text("▶") }
            Button(onClick = {
                val current = player
                if (current != null) {
                    current.release()
                    player = null
                } else {
                    scope.launch {
                        try {
                            val file = engine.midiFile()
                            player = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                prepare()
                                setOnCompletionListener { it.release(); player = null }
                                start()
                            }
                        } catch (e: Exception) {
                            status = "MIDI: ${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
            }) { Text(if (player != null) "■" else "♪") }
            Button(onClick = {
                if (benchRunning || viewport.width == 0) return@Button
                scope.launch {
                    benchRunning = true
                    Benchmark(context, engine).run(viewport.width, viewport.height, scale) { status = it }
                    benchRunning = false
                    openedBytes = null
                }
            }) { Text("Bench") }
        }
        Text(
            "Verovio $version · $status",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            minLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.White).onSizeChanged { viewport = it }
        ) {
            if (renderer == Renderer.WEBVIEW && svgForWeb.isNotEmpty()) {
                val html = remember(svgForWeb) {
                    "<html><head><style>html,body{margin:0;background:#fff}" +
                        "svg{width:100vw;height:100vh}</style></head><body>" +
                        svgForWeb.substringAfter("?>") + "</body></html>"
                }
                AndroidView(
                    factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = false } },
                    update = { web ->
                        val t0 = System.nanoTime()
                        web.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                engine.recordRaster((System.nanoTime() - t0) / 1_000_000)
                                status = engine.lastTimings.toString() + " (WebView load)"
                            }
                        }
                        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
