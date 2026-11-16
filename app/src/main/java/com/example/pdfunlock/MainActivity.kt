@file:OptIn(ExperimentalTextApi::class)

package com.example.pdfunlock

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

// ---- Artefact look: Candy accents, Plus Jakarta Sans, wallpaper + glass-style surfaces ----

internal val Sky = Color(0xFF0A84FF)       // Candy.Sky: the primary action
private val Tomato = Color(0xFFFF5B4F)    // Candy.Tomato: errors
private val InkLight = Color(0xFF172033)
private val InkDark = Color(0xFFEEF1FF)

internal val Jakarta = FontFamily(
    Font(R.font.jakarta, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jakarta, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.jakarta, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun type(size: Int, weight: FontWeight) =
    TextStyle(fontFamily = Jakarta, fontSize = size.sp, fontWeight = weight)

private val Headline = type(32, FontWeight.Bold)
private val Title = type(18, FontWeight.Bold)
private val BodyStrong = type(15, FontWeight.SemiBold)
private val Body = type(15, FontWeight.Normal)
private val Caption = type(12, FontWeight.Normal)

internal class Palette(val dark: Boolean) {
    val ink = if (dark) InkDark else InkLight
    val inkSoft = ink.copy(alpha = 0.66f)
    val line = ink.copy(alpha = 0.10f)
    val glass = if (dark) Color(0xFFC9D2FF).copy(alpha = 0.11f) else Color.White.copy(alpha = 0.26f)
    val rim = if (dark) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.70f)
    val frost = if (dark) Color(0xFF1A1B3C).copy(alpha = 0.90f) else Color(0xFFF3F6FC).copy(alpha = 0.86f)
    val wall = if (dark) listOf(Color(0xFF0A0B1E), Color(0xFF14123A), Color(0xFF0B1030))
    else listOf(Color(0xFF9AB4DC), Color(0xFFB3C5E8), Color(0xFFA8BEE3))
    val bloomA = if (dark) Color(0xFF1E6FD9).copy(alpha = 0.46f) else Color(0xFFCDF2F3).copy(alpha = 0.62f)
    val bloomB = if (dark) Color(0xFF7C3AED).copy(alpha = 0.52f) else Color(0xFFD9C3F4).copy(alpha = 0.58f)
}

private fun Modifier.wallpaper(p: Palette): Modifier =
    this.background(Brush.verticalGradient(p.wall)).drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(p.bloomA, Color.Transparent),
                center = Offset(size.width * 0.15f, size.height * 0.10f),
                radius = size.width * 0.9f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(p.bloomB, Color.Transparent),
                center = Offset(size.width * 0.90f, size.height * 0.80f),
                radius = size.width * 0.9f
            )
        )
    }

@Composable
private fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Capsule button. With [tint] it is the vivid blue action pill, without it a frosted one. */
@Composable
internal fun Pill(
    text: String,
    p: Palette,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pressScale(source)
            .clip(shape)
            .background(tint ?: p.glass)
            .border(1.dp, p.rim, shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (tint != null) Color.White else p.ink,
            style = BodyStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Text(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) = androidx.compose.material3.Text(
    text = text, color = color, style = style, modifier = modifier,
    textAlign = textAlign, maxLines = maxLines, overflow = overflow
)

// ---- PDF pages (rendered from the unlocked copy) ----

private class PdfPages(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val widthPx: Int
) {
    val count: Int = renderer.pageCount
    private val cache = LruCache<Int, Bitmap>(3)
    private var closed = false

    fun render(i: Int): Bitmap? = synchronized(this) {
        if (closed) return@synchronized null
        val hit = cache.get(i)
        if (hit != null) return@synchronized hit
        try {
            renderer.openPage(i).use { page ->
                // 2x the screen width so zoomed-in pages stay sharp; plain width if memory runs short
                fun draw(w: Int): Bitmap {
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return b
                }
                val b = try {
                    draw(widthPx * 2)
                } catch (_: OutOfMemoryError) {
                    cache.evictAll()
                    draw(widthPx)
                }
                cache.put(i, b)
                b
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun close() = synchronized(this) {
        closed = true
        try { renderer.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}

private enum class Outcome { OK, WRONG, ERROR }
private enum class Phase { Empty, Loading, Locked, Ready, Failed }

class MainActivity : ComponentActivity() {

    private var sourceUri: Uri? = null
    private var fingerprint: String? = null
    private var lastError: String? = null
    private val unlockedFile by lazy { File(cacheDir, "unlocked.pdf") }

    private var docName by mutableStateOf<String?>(null)
    private var phase by mutableStateOf(Phase.Empty)
    private var pages by mutableStateOf<PdfPages?>(null)
    private var askPassword by mutableStateOf(false)
    private var passwordWrong by mutableStateOf(false)
    private var prefill by mutableStateOf("")
    private var kind by mutableStateOf(Kind.Pdf)
    private var textLines by mutableStateOf<List<String>?>(null)
    private var docBlocks by mutableStateOf<List<DocBlock>?>(null)
    private var sheets by mutableStateOf<List<Office.Sheet>?>(null)
    private var slideCount by mutableStateOf(0)
    private var hasSaved by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openPdf(uri)
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) saveTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PDFBoxResourceLoader.init(applicationContext)
        setContent { Screen() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------------- UI ----------------

    @Composable
    private fun Screen() {
        val dark = isSystemInDarkTheme()
        val p = remember(dark) { Palette(dark) }
        val density = LocalDensity.current
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        BoxWithConstraints(Modifier.fillMaxSize().wallpaper(p)) {
            val doc = pages
            val pageWidthPx = with(density) { (maxWidth - 24.dp).roundToPx() }

            val contentPad = PaddingValues(top = topInset + 84.dp, bottom = bottomInset + 96.dp)
            if (phase == Phase.Ready) {
                when (kind) {
                    Kind.Pdf -> if (doc != null) {
                        ZoomableColumn(doc, contentPad, 10.dp) {
                            items(doc.count) { i -> PageView(doc, i) }
                        }
                    }
                    Kind.Text -> TextViewer(textLines ?: emptyList(), p, contentPad) { message = it }
                    Kind.Word, Kind.Slides -> WordViewer(docBlocks ?: emptyList(), contentPad, p.inkSoft)
                    Kind.Sheet -> SheetViewer(sheets ?: emptyList(), p, contentPad) { message = it }
                    else -> {}
                }
            }

            if (phase == Phase.Empty || phase == Phase.Loading || phase == Phase.Locked || phase == Phase.Failed) {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (phase) {
                        Phase.Loading -> {
                            CircularProgressIndicator(color = Sky)
                            Spacer(Modifier.height(16.dp))
                            Text("Unlocking…", p.inkSoft, Body)
                        }
                        Phase.Locked -> {
                            Text("This PDF is locked", p.ink, Headline.copy(fontSize = 26.sp), textAlign = TextAlign.Center)
                            Spacer(Modifier.height(18.dp))
                            Pill("Enter password", p, tint = Sky) { passwordWrong = false; askPassword = true }
                        }
                        Phase.Failed -> {
                            Text("Couldn’t open this file", p.ink, Headline.copy(fontSize = 26.sp), textAlign = TextAlign.Center)
                            Spacer(Modifier.height(18.dp))
                            Pill("Open another file", p, tint = Sky) { openLauncher.launch(arrayOf("*/*")) }
                        }
                        else -> {
                            Text("PDF Unlock", p.ink, Headline)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Unlock a password-protected PDF and save a copy without the password. Word, Excel, PowerPoint and text files open too.",
                                p.inkSoft, Body, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(22.dp))
                            Pill("Open file", p, tint = Sky) { openLauncher.launch(arrayOf("*/*")) }
                        }
                    }
                }
            }

            if (phase != Phase.Empty) {
                // floating top bar
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topInset + 8.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(p.frost)
                        .border(1.dp, p.rim, RoundedCornerShape(26.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(docName ?: "PDF", p.ink, Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when (phase) {
                        Phase.Ready -> when (kind) {
                            Kind.Pdf -> "Unlocked · ${pages?.count ?: 0} pages"
                            Kind.Text -> "Text · ${textLines?.size ?: 0} lines · tap a line, or Words for exact text"
                            Kind.Word -> "Word document"
                            Kind.Sheet -> "Excel · ${sheets?.size ?: 0} sheet(s) · tap a cell to copy, long-press for the row"
                            Kind.Slides -> "PowerPoint · $slideCount slides · text only"
                            else -> ""
                        }
                        Phase.Loading -> "Unlocking…"
                        Phase.Locked -> "Locked"
                        else -> "Couldn’t open"
                    }
                    Text(sub, p.inkSoft, Caption)
                }

                // floating bottom bar
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomInset + 10.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(30.dp))
                        .background(p.frost)
                        .border(1.dp, p.rim, RoundedCornerShape(30.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Pill("Open", p, Modifier.weight(1f)) { openLauncher.launch(arrayOf("*/*")) }
                    if (hasSaved && kind == Kind.Pdf) {
                        Pill("Forget password", p, Modifier.weight(1.5f)) { forgetPassword() }
                    }
                    if (kind == Kind.Pdf) {
                        Pill(
                            "Save unlocked", p, Modifier.weight(1.5f),
                            tint = Sky, enabled = phase == Phase.Ready
                        ) {
                            saveLauncher.launch((docName ?: "document.pdf").substringBeforeLast('.') + "_unlocked.pdf")
                        }
                    }
                    Pill("Share", p, Modifier.weight(1f), enabled = phase == Phase.Ready) { shareFile() }
                }
            }

            // short message
            val msg = message
            if (msg != null) {
                LaunchedEffect(msg) { delay(2500); message = null }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomInset + (if (kind == Kind.Text || kind == Kind.Sheet) 176.dp else 96.dp))
                        .clip(RoundedCornerShape(50))
                        .background(p.frost)
                        .border(1.dp, p.rim, RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 11.dp)
                ) { Text(msg, p.ink, BodyStrong) }
            }

            if (askPassword) {
                PasswordDialog(
                    p = p,
                    name = docName ?: "PDF",
                    wrong = passwordWrong,
                    initial = prefill,
                    onCancel = { askPassword = false; phase = Phase.Locked },
                    onUnlock = { pw -> askPassword = false; tryUnlock(pw, fromUser = true) }
                )
            }
        }
    }

    @Composable
    private fun PageView(doc: PdfPages, index: Int) {
        val bmp by produceState<Bitmap?>(null, doc, index) {
            value = withContext(Dispatchers.Default) { doc.render(index) }
        }
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .then(if (bmp == null) Modifier.aspectRatio(0.707f) else Modifier)
        ) {
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }

    @Composable
    private fun PasswordDialog(
        p: Palette,
        name: String,
        wrong: Boolean,
        initial: String,
        onCancel: () -> Unit,
        onUnlock: (String) -> Unit
    ) {
        var pw by remember { mutableStateOf(initial) }
        var show by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val shape = RoundedCornerShape(30.dp)

        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.frost.copy(alpha = 0.97f))
                    .border(1.dp, p.rim, shape)
                    .padding(22.dp)
            ) {
                Text("Enter password", p.ink, Title)
                Spacer(Modifier.height(4.dp))
                Text(name, p.inkSoft, Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))

                val fieldShape = RoundedCornerShape(18.dp)
                BasicTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    textStyle = Body.copy(color = p.ink),
                    cursorBrush = SolidColor(Sky),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onUnlock(pw) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(fieldShape)
                                .background(p.ink.copy(alpha = 0.07f))
                                .border(1.dp, if (wrong) Tomato else p.line, fieldShape)
                                .padding(start = 16.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f).padding(vertical = 15.dp)) {
                                if (pw.isEmpty()) Text("Password", p.inkSoft, Body)
                                inner()
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { show = !show }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) { Text(if (show) "Hide" else "Show", Sky, BodyStrong) }
                        }
                    }
                )
                if (wrong) {
                    Spacer(Modifier.height(8.dp))
                    Text("The password is incorrect.", Tomato, Caption)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Cancel", p, Modifier.weight(1f)) { onCancel() }
                    Pill("Unlock", p, Modifier.weight(1f), tint = Sky) { onUnlock(pw) }
                }
            }
        }
    }

    // ---------------- Logic ----------------

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) openPdf(uri)
    }

    private fun openPdf(uri: Uri) {
        sourceUri = uri
        docName = queryName(uri) ?: "document.pdf"
        pages?.close()
        pages = null
        askPassword = false
        phase = Phase.Loading
        kind = detectKind(contentResolver, uri, docName)
        textLines = null
        docBlocks = null
        sheets = null
        slideCount = 0
        if (kind == Kind.Unsupported) {
            hasSaved = false
            phase = Phase.Failed
            message = "This file type isn’t supported yet"
            return
        }
        if (kind != Kind.Pdf) {
            hasSaved = false
            openOther(uri)
            return
        }
        lifecycleScope.launch {
            val fp = withContext(Dispatchers.IO) { fingerprintOf(uri) }
            fingerprint = fp
            val saved = fp?.let { PasswordStore.get(this@MainActivity, it) }
            hasSaved = saved != null || PasswordStore.getLast(this@MainActivity) != null
            // Saved password is tried automatically; otherwise an empty one (unprotected PDFs)
            tryUnlock(saved ?: "", fromUser = false)
        }
    }

    /** Text and Word files: no password step, just read and show. */
    private fun openOther(uri: Uri) {
        val k = kind
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        when (k) {
                            Kind.Text -> textLines = readTextLines(input)
                            Kind.Sheet -> sheets = Office.readSheets(input)
                            Kind.Slides -> {
                                val sl = Office.readSlides(input)
                                slideCount = sl.size
                                docBlocks = slidesToBlocks(sl)
                            }
                            else -> {
                                val bytes = Office.readAll(input)
                                docBlocks = if (Office.isOleBytes(bytes)) {
                                    Office.readDoc(bytes).map { line ->
                                        DocPara(if (line.isEmpty()) emptyList() else listOf(DocRun(line)), 0, false)
                                    }
                                } else {
                                    parseDocx(java.io.ByteArrayInputStream(bytes))
                                }
                            }
                        }
                        Unit
                    }
                }
            }
            if (result.isSuccess) {
                phase = Phase.Ready
            } else {
                phase = Phase.Failed
                message = "Error: ${result.exceptionOrNull()?.message ?: "unknown"}"
            }
        }
    }

    private fun tryUnlock(password: String, fromUser: Boolean) {
        val uri = sourceUri ?: return
        phase = Phase.Loading
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        PDDocument.load(input, password).use { doc ->
                            doc.setAllSecurityToBeRemoved(true)
                            doc.save(unlockedFile)
                        }
                    }
                    Outcome.OK
                } catch (e: InvalidPasswordException) {
                    Outcome.WRONG
                } catch (e: Exception) {
                    lastError = e.message
                    Outcome.ERROR
                }
            }
            when (outcome) {
                Outcome.OK -> {
                    if (fromUser && password.isNotEmpty()) {
                        fingerprint?.let { PasswordStore.save(this@MainActivity, it, password) }
                        PasswordStore.saveLast(this@MainActivity, password)
                        hasSaved = true
                    }
                    showPages()
                }
                Outcome.WRONG -> {
                    if (!fromUser) {
                        // this PDF's saved password no longer works
                        fingerprint?.let { PasswordStore.forget(this@MainActivity, it) }
                    }
                    val last = PasswordStore.getLast(this@MainActivity)
                    hasSaved = last != null
                    // First ask: pre-fill the password used for the previous PDF
                    prefill = if (fromUser) "" else (last ?: "")
                    phase = Phase.Locked
                    passwordWrong = fromUser
                    askPassword = true
                }
                Outcome.ERROR -> {
                    phase = Phase.Failed
                    message = "Error: ${lastError ?: "unknown"}"
                }
            }
        }
    }

    private fun showPages() {
        try {
            val p = ParcelFileDescriptor.open(unlockedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(p)
            pages = PdfPages(p, r, resources.displayMetrics.widthPixels)
            phase = Phase.Ready
        } catch (e: Exception) {
            phase = Phase.Failed
            message = "Error: ${e.message}"
        }
    }

    private fun forgetPassword() {
        fingerprint?.let { PasswordStore.forget(this, it) }
        PasswordStore.forgetLast(this)
        hasSaved = false
        message = "Saved passwords removed"
    }

    /** Sends the open file (the unlocked copy for a PDF) to any app: WhatsApp, Gmail, Drive, Bluetooth... */
    private fun shareFile() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val base = docName ?: "document"
                    val outName =
                        if (kind == Kind.Pdf) base.substringBeforeLast('.') + "_unlocked.pdf" else base
                    val f = File(dir, outName.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                    if (kind == Kind.Pdf) {
                        unlockedFile.copyTo(f, overwrite = true)
                    } else {
                        contentResolver.openInputStream(sourceUri!!)!!.use { input ->
                            f.outputStream().use { input.copyTo(it) }
                        }
                    }
                    f
                }
            }
            result.onSuccess { f ->
                val ext = f.name.substringAfterLast('.', "").lowercase()
                val mime = if (kind == Kind.Pdf) "application/pdf"
                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(send, "Share"))
                } catch (e: Exception) {
                    message = "No app to share with"
                }
            }.onFailure { message = "Share failed: ${it.message}" }
        }
    }

    private fun saveTo(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    unlockedFile.inputStream().use { it.copyTo(out) }
                }
                "Saved"
            } catch (e: Exception) {
                "Save failed: ${e.message}"
            }
            withContext(Dispatchers.Main) { message = result }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
    } catch (_: Exception) { null }

    private fun querySize(uri: Uri): Long = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && i >= 0) c.getLong(i) else 0L
        } ?: 0L
    } catch (_: Exception) { 0L }

    /** Stable id for the same PDF file: SHA-256 of first 64 KB + file size. */
    private fun fingerprintOf(uri: Uri): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(65536)
            var read = 0
            while (read < buf.size) {
                val n = s.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            md.update(buf, 0, read)
        }
        md.update(querySize(uri).toString().toByteArray())
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    override fun onDestroy() {
        pages?.close()
        unlockedFile.delete()
        File(cacheDir, "share").listFiles()?.forEach { it.delete() }
        super.onDestroy()
    }
}
