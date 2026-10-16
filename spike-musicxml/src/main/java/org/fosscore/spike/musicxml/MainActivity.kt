package org.fosscore.spike.musicxml

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebView
import java.io.File
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "M0"

class MainActivity : ComponentActivity() {
    private val pageTurns = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoBench = intent.getBooleanExtra("bench", false)
        setContent {
            MaterialTheme { SpikeScreen(autoBench, pageTurns) }
        }
    }

    // Page-turn keys are taken before the focused button sees them (Enter and Space would
    // otherwise press it). Dialogs have their own window, so typing a name still works.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val turn = pageTurnFor(event.keyCode, event.isShiftPressed)
        if (turn == 0) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) pageTurns.tryEmit(turn)
        return true
    }
}

/** A rendered page and where its notes are, kept so playback can turn pages without waiting. */
private class RenderedPage(val bitmap: Bitmap, val geometry: PageGeometry)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpikeScreen(autoBench: Boolean, pageTurns: SharedFlow<Int>) {
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
    // On a phone, 7 mm leaves one measure per line: the staff is also kept under 1/14 of the
    // shorter screen side.
    val defaultScale = remember {
        val metrics = context.resources.displayMetrics
        val staffPx = minOf(7f / 25.4f * metrics.densityDpi, minOf(metrics.widthPixels, metrics.heightPixels) / 14f)
        (staffPx / 72f * 100f).roundToInt().coerceIn(20, 200)
    }

    var fileName by remember { mutableStateOf(samples.firstOrNull() ?: "") }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var openedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var settingsFor by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<ScoreConfigs?>(null) }
    var configMenuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var baseTempo by remember { mutableDoubleStateOf(120.0) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    var menusVisible by remember { mutableStateOf(true) }
    // True after a manual page turn while playing: the music no longer turns pages until it
    // reaches the page on screen again.
    var followPaused by remember { mutableStateOf(false) }
    var parts by remember { mutableStateOf(emptyList<Part>()) }
    var shownParts by remember { mutableStateOf(emptySet<String>()) }
    var lyrics by remember { mutableStateOf(LyricsPlacement.FILE) }
    var audible by remember { mutableStateOf<Set<String>?>(null) }
    var programs by remember { mutableStateOf(emptyMap<String, Int>()) }
    var tempo by remember { mutableDoubleStateOf(1.0) }
    var renderer by remember { mutableStateOf(Renderer.MUPDF) }
    var scale by remember { mutableIntStateOf(defaultScale) }
    var semitones by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var renderedPage by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var geometry by remember { mutableStateOf<PageGeometry?>(null) }
    val pageCache = remember { mutableMapOf<Int, RenderedPage>() }
    var cacheKey by remember { mutableStateOf<Any?>(null) }
    var svgForWeb by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var partsMenuOpen by remember { mutableStateOf(false) }
    var soundMenuOpen by remember { mutableStateOf(false) }
    var instrumentFor by remember { mutableStateOf<Part?>(null) }
    var lyricsMenuOpen by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    var playing by remember { mutableStateOf(false) }
    var cursorNotes by remember { mutableStateOf(emptyList<String>()) }
    var benchRunning by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf("") }

    fun stopPlayback() {
        startJob?.cancel()
        startJob = null
        player?.release()
        player = null
        playback = null
        playing = false
        cursorNotes = emptyList()
    }

    /** Starts playing at [fromQuarters], or at the first note of the page on screen. */
    fun startPlayback(fromQuarters: Double? = null) {
        // Only one start at a time: a second tap or a tempo change cancels the one being prepared,
        // otherwise two players could end up playing over each other.
        stopPlayback()
        startJob = scope.launch {
            try {
                status = "Preparing playback…"
                val t0 = System.nanoTime()
                val pb = engine.playback(SoundSpec(audible, programs, tempo))
                val onPage = geometry?.noteIds.orEmpty()
                val start = fromQuarters
                    ?: pb.onsets.firstOrNull { o -> o.notes.any { it in onPage } }?.quarters ?: 0.0
                ensureActive()
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(pb.midi.absolutePath)
                    prepare()
                    setOnCompletionListener { stopPlayback() }
                    if (start > 0) seekTo(pb.msAt(start).toInt())
                    start()
                }
                playback = pb
                playing = true
                followPaused = false
                status = "Playing · prepared in ${(System.nanoTime() - t0) / 1_000_000} ms · ♩ = ${(baseTempo * tempo).roundToInt()}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "playback failed", e)
                status = "MIDI: ${e.javaClass.simpleName}: ${e.message}"
                stopPlayback()
            }
        }
    }

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
        stopPlayback()
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

    fun applySettings(saved: ScoreSettings) {
        scale = saved.scale
        semitones = saved.semitones
        shownParts = saved.parts
        lyrics = saved.lyrics
        audible = saved.audible
        programs = saved.programs
        tempo = saved.tempo
    }

    // Save the settings into the current configuration of the score as soon as they change.
    LaunchedEffect(settingsFor, scale, semitones, shownParts, lyrics, audible, programs, tempo) {
        val file = settingsFor ?: return@LaunchedEffect
        val updated = configs?.with(ScoreSettings(scale, semitones, shownParts, lyrics, audible, programs, tempo))
            ?: return@LaunchedEffect
        configs = updated
        ScoreConfigs.save(context, file, updated)
    }

    /** Switches to another configuration (or a changed set of them) and saves it. */
    fun useConfigs(next: ScoreConfigs) {
        stopPlayback()
        configs = next
        applySettings(next.settings)
        settingsFor?.let { ScoreConfigs.save(context, it, next) }
    }

    // A reloaded score has new note ids: playback must start again.
    LaunchedEffect(semitones, shownParts, lyrics, audible, programs) { if (player != null) stopPlayback() }

    LaunchedEffect(tempo) {
        // Let repeated taps settle, then go on at the same place with the new tempo.
        delay(400)
        val pb = playback
        val p = player
        if (pb != null && p != null) startPlayback(pb.quartersAt(p.currentPosition.toLong()))
    }

    LaunchedEffect(fileBytes, viewport, scale, semitones, shownParts, lyrics, page, renderer, benchRunning) {
        val bytes = fileBytes ?: return@LaunchedEffect
        if (viewport.width == 0 || benchRunning) return@LaunchedEffect
        try {
            if (openedBytes !== bytes) {
                parts = engine.open(bytes)
                openedBytes = bytes
                page = 1
                renderedPage = 0
                cacheKey = null
                // Restore the settings saved with this score, or start with the parts the author
                // left visible (MuseScore exports hidden staves too).
                val visible = parts.filter { it.visible }.map { it.id }.toSet()
                val initial = if (visible.isEmpty() || visible.size == parts.size) emptySet() else visible
                baseTempo = engine.baseTempo
                val loaded = ScoreConfigs.load(context, fileName)
                    ?: ScoreConfigs.new(ScoreSettings(scale = defaultScale, parts = initial))
                val saved = loaded.settings
                val changed = scale != saved.scale || semitones != saved.semitones ||
                    shownParts != saved.parts || lyrics != saved.lyrics
                configs = loaded
                applySettings(saved)
                settingsFor = fileName
                if (changed) return@LaunchedEffect
            } else if (page == renderedPage) {
                // Let repeated taps on zoom or transpose settle before the slow layout starts.
                delay(300)
            }
            val spec = LayoutSpec(viewport.width, viewport.height, scale, semitones, shownParts, lyrics)
            if (cacheKey != spec || renderer != Renderer.MUPDF) {
                status = "…"
                if (!engine.layout(spec)) {
                    status = "Could not load $fileName\n${engine.log()}"
                    bitmap = null
                    return@LaunchedEffect
                }
                pageCache.clear()
                cacheKey = spec
            }
            pageCount = engine.pageCount
            if (page > pageCount) page = pageCount.coerceAtLeast(1)
            val cached = pageCache[page]
            if (cached != null && renderer == Renderer.MUPDF) {
                bitmap = cached.bitmap
                geometry = cached.geometry
                svgForWeb = ""
                renderedPage = page
                return@LaunchedEffect
            }
            val svg = engine.svg(page)
            when (renderer) {
                Renderer.WEBVIEW -> {
                    bitmap = null
                    geometry = PageGeometry.parse(svg)
                    svgForWeb = svg
                }
                else -> {
                    val t0 = System.nanoTime()
                    val bmp = withContext(Dispatchers.Default) {
                        if (renderer == Renderer.MUPDF) Rasterizers.mupdf(svg, viewport.width, viewport.height)
                        else Rasterizers.androidSvg(svg, viewport.width, viewport.height)
                    }
                    engine.recordRaster((System.nanoTime() - t0) / 1_000_000)
                    val geo = withContext(Dispatchers.Default) { PageGeometry.parse(svg) }
                    if (renderer == Renderer.MUPDF) pageCache[page] = RenderedPage(bmp, geo)
                    bitmap = bmp
                    geometry = geo
                    svgForWeb = ""
                }
            }
            renderedPage = page
            if (player == null) status = engine.lastTimings.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "render failed", e)
            status = "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // While playing, render the next page ahead so the page turn is immediate. Keep three pages.
    LaunchedEffect(playing, renderedPage, cacheKey) {
        val spec = cacheKey as? LayoutSpec ?: return@LaunchedEffect
        if (!playing || renderer != Renderer.MUPDF) return@LaunchedEffect
        pageCache.keys.filter { it < renderedPage - 1 || it > renderedPage + 1 }.forEach { pageCache.remove(it) }
        val next = renderedPage + 1
        if (next > pageCount || pageCache.containsKey(next)) return@LaunchedEffect
        try {
            val svg = engine.svg(next)
            val rendered = withContext(Dispatchers.Default) {
                RenderedPage(Rasterizers.mupdf(svg, spec.width, spec.height), PageGeometry.parse(svg))
            }
            if (cacheKey == spec) pageCache[next] = rendered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "prefetch failed", e)
        }
    }

    fun turnPage(delta: Int) {
        val target = (page + delta).coerceIn(1, pageCount.coerceAtLeast(1))
        if (target == page) return
        page = target
        if (player != null) followPaused = true
    }

    LaunchedEffect(Unit) { pageTurns.collect { turnPage(it) } }

    // Follow the music: move the cursor to the notes being played and turn the page when needed.
    LaunchedEffect(playing, playback) {
        val pb = playback ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        var last = -2
        var asked = 0
        while (true) {
            val p = player ?: break
            val index = pb.onsetIndexAt(pb.quartersAt(p.currentPosition.toLong()))
            if (index != last && index >= 0) {
                last = index
                val notes = pb.onsets[index].notes
                // Onsets of hidden notes only (a vocal line kept for its lyrics) keep the last cursor.
                if (notes.any { geometry?.head(it) != null }) cursorNotes = notes
                val shown = geometry?.noteIds.orEmpty()
                if (followPaused && renderedPage == page && notes.any { it in shown }) followPaused = false
                if (!followPaused && renderedPage == page && notes.none { it in shown }) {
                    val target = engine.pageOf(notes.first())
                    if (target > 0 && target != page && target != asked) {
                        asked = target
                        page = target
                    }
                }
            }
            delay(40)
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

    renaming?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Configuration name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    configs?.let { useConfigs(it.rename(name)) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    // Android 15+ draws apps edge to edge: keep the score and menus out of the system bars.
    Box(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // The score fills the screen and the menus float over it, so hiding them does not change
        // the viewport (which would lay the score out again). Tap zones as in FOSScore's PDF
        // viewer: left third previous page, right third next page, centre shows or hides the menus.
        Box(
            Modifier.fillMaxSize().onSizeChanged { viewport = it }.pointerInput(Unit) {
                detectTapGestures { pos ->
                    when {
                        pos.x > size.width * 2f / 3f -> turnPage(1)
                        pos.x < size.width / 3f -> turnPage(-1)
                        else -> menusVisible = !menusVisible
                    }
                }
            }
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
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    val geo = geometry
                    if (geo != null && cursorNotes.isNotEmpty()) {
                        Canvas(Modifier.fillMaxSize()) {
                            // Same transform as ContentScale.Fit with centred alignment.
                            val fit = minOf(size.width / bmp.width, size.height / bmp.height)
                            val dx = (size.width - bmp.width * fit) / 2
                            val dy = (size.height - bmp.height * fit) / 2
                            val k = bmp.width / geo.width * fit
                            val heads = cursorNotes.mapNotNull { geo.head(it) }
                            if (heads.isEmpty()) return@Canvas
                            val space = heads.first().space * k
                            val left = heads.minOf { it.x } * k + dx - space
                            val right = heads.maxOf { it.x } * k + dx + space
                            val top = heads.minOf { it.top } * k + dy - 2 * space
                            val bottom = heads.maxOf { it.bottom } * k + dy + 2 * space
                            drawRect(Color(0x3300A0FF), Offset(left, top), Size(right - left, bottom - top))
                            heads.forEach { h ->
                                drawCircle(Color(0x8800A0FF), radius = space * 0.8f, center = Offset(h.x * k + dx, h.y * k + dy))
                            }
                        }
                    }
                }
            }
        }
        if (menusVisible) {
            Column(Modifier.fillMaxWidth().background(Color(0xF2FFFFFF))) {
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
                                settingsFor = null
                                configs = null
                                fileBytes = null
                                fileName = name
                            })
                        }
                    }
                }
                Box {
                    Button(onClick = { configMenuOpen = true }, enabled = configs != null) {
                        Text("Config: ${configs?.current ?: "–"}")
                    }
                    DropdownMenu(expanded = configMenuOpen, onDismissRequest = { configMenuOpen = false }) {
                        val c = configs
                        if (c != null) {
                            c.configs.keys.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text("${if (name == c.current) "●" else "○"} $name") },
                                    onClick = {
                                        configMenuOpen = false
                                        if (name != c.current) useConfigs(c.select(name))
                                    }
                                )
                            }
                            DropdownMenuItem(text = { Text("New (copy of this one)") }, onClick = {
                                configMenuOpen = false
                                useConfigs(c.duplicate())
                            })
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = {
                                configMenuOpen = false
                                renaming = c.current
                            })
                            DropdownMenuItem(text = { Text("Delete this one") }, enabled = c.configs.size > 1, onClick = {
                                configMenuOpen = false
                                useConfigs(c.delete())
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
                Box {
                    Button(onClick = { lyricsMenuOpen = true }) { Text("Lyrics: ${lyrics.label}") }
                    DropdownMenu(expanded = lyricsMenuOpen, onDismissRequest = { lyricsMenuOpen = false }) {
                        LyricsPlacement.entries.forEach { placement ->
                            DropdownMenuItem(
                                text = { Text("${if (placement == lyrics) "●" else "○"} ${placement.label}") },
                                onClick = {
                                    lyricsMenuOpen = false
                                    lyrics = placement
                                }
                            )
                        }
                    }
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
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    val p = player
                    when {
                        p == null -> startPlayback()
                        playing -> { p.pause(); playing = false }
                        else -> { p.start(); playing = true }
                    }
                }) { Text(if (playing) "⏸" else "♪ ▶") }
                Button(onClick = { stopPlayback() }, enabled = player != null) { Text("■") }
                // Tempo in quarter notes per minute, 2 at a time; stored as a factor of the written tempo.
                val bpm = (baseTempo * tempo).roundToInt()
                fun setBpm(value: Int) {
                    tempo = (value / baseTempo).coerceIn(0.2, 4.0)
                }
                Button(onClick = { setBpm(bpm - 2) }, enabled = tempo > 0.21) { Text("−") }
                Text(if (bpm == baseTempo.roundToInt()) "♩ = $bpm" else "♩ = $bpm (${baseTempo.roundToInt()})")
                Button(onClick = { setBpm(bpm + 2) }, enabled = tempo < 3.99) { Text("+") }
                Button(onClick = { tempo = 1.0 }, enabled = tempo != 1.0) { Text("↺") }
                Box {
                    Button(onClick = { soundMenuOpen = true }) {
                        Text(if (audible == null) "Sound: shown parts" else "Sound: ${audible!!.size}/${parts.size}")
                    }
                    DropdownMenu(expanded = soundMenuOpen, onDismissRequest = { soundMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("${if (audible == null) "●" else "○"} Hear the parts shown") },
                            onClick = { audible = null }
                        )
                        parts.forEach { part ->
                            val heard = (audible ?: shownParts.ifEmpty { parts.map { it.id }.toSet() }).contains(part.id)
                            DropdownMenuItem(
                                text = { Text("${if (heard) "🔊" else "🔇"} ${part.name.ifBlank { part.id }}") },
                                onClick = {
                                    val current = audible ?: shownParts.ifEmpty { parts.map { it.id }.toSet() }
                                    val next = if (part.id in current) current - part.id else current + part.id
                                    if (next.isNotEmpty()) audible = next
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "      ↳ " + if (part.drums) "drums" else programName(programs[part.id] ?: part.program),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                enabled = !part.drums,
                                onClick = { instrumentFor = part }
                            )
                        }
                    }
                    DropdownMenu(expanded = instrumentFor != null, onDismissRequest = { instrumentFor = null }) {
                        val part = instrumentFor
                        if (part != null) {
                            DropdownMenuItem(text = { Text("As in file (${programName(part.program)})") }, onClick = {
                                programs = programs - part.id
                                instrumentFor = null
                            })
                            GM_PROGRAMS.forEach { (program, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    programs = programs + (part.id to program)
                                    instrumentFor = null
                                })
                            }
                        }
                    }
                }
                Renderer.entries.forEach { r ->
                    Button(
                        onClick = { renderer = r },
                        colors = if (renderer == r) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                    ) { Text(r.name.take(4)) }
                }
                Button(onClick = {
                    if (benchRunning || viewport.width == 0) return@Button
                    stopPlayback()
                    scope.launch {
                        benchRunning = true
                        Benchmark(context, engine).run(viewport.width, viewport.height, scale) { status = it }
                        benchRunning = false
                        openedBytes = null
                        cacheKey = null
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
            }
        }
    }
}
