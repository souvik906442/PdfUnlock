package com.example.pdfunlock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val EDIT_DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val EDIT_XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * Simple editor for .docx (edit paragraph text) and .xlsx (edit cell values).
 * Changes are written into a copy of the file; everything else in the file is kept as it was.
 */
class EditActivity : ComponentActivity() {

    private var docx: OfficeEdit.DocxEditor? = null
    private var xlsx: OfficeEdit.XlsxEditor? = null
    private val paras = mutableStateListOf<String>()

    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var dirty by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var rev by mutableStateOf(0)
    private var askDiscard by mutableStateOf(false)

    private var lastSaved: Uri? = null
    private var isDocx = true
    private var fileName = "document"

    private val saveDocx =
        registerForActivityResult(ActivityResultContracts.CreateDocument(EDIT_DOCX_MIME)) { uri -> doSave(uri) }
    private val saveXlsx =
        registerForActivityResult(ActivityResultContracts.CreateDocument(EDIT_XLSX_MIME)) { uri -> doSave(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra("path")
        fileName = intent.getStringExtra("name") ?: "document"
        isDocx = fileName.lowercase().endsWith(".docx")
        setContent { Screen() }

        if (path == null) {
            error = "No file to edit"
            loading = false
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = File(path).readBytes()
                    if (isDocx) {
                        val e = OfficeEdit.DocxEditor(bytes)
                        docx = e
                        e.texts().toList()
                    } else {
                        xlsx = OfficeEdit.XlsxEditor(bytes)
                        emptyList<String>()
                    }
                }
            }
            result.onSuccess { list ->
                paras.clear()
                paras.addAll(list)
                loading = false
            }.onFailure {
                error = it.message ?: "Couldn’t open this file for editing"
                loading = false
            }
        }
    }

    // ---------------- save / close ----------------

    private fun startSave() {
        val base = fileName.substringBeforeLast('.')
        if (isDocx) saveDocx.launch("${base}_edited.docx") else saveXlsx.launch("${base}_edited.xlsx")
    }

    private fun doSave(uri: Uri?) {
        if (uri == null) return
        val texts = paras.toList()
        saving = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = if (isDocx) docx!!.save(texts) else xlsx!!.save()
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    null
                } catch (e: Throwable) {
                    e.message ?: e.toString()
                }
            }
            saving = false
            if (ok == null) {
                lastSaved = uri
                dirty = false
                Toast.makeText(this@EditActivity, "Saved a copy", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@EditActivity, "Save failed: $ok", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishWithResult() {
        val u = lastSaved
        if (u != null) setResult(RESULT_OK, Intent().setData(u))
        finish()
    }

    private fun tryClose() {
        if (dirty) askDiscard = true else finishWithResult()
    }

    // ---------------- UI ----------------

    @Composable
    private fun Screen() {
        val dark = isSystemInDarkTheme()
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BackHandler { tryClose() }
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = { tryClose() }) { Text("Close") }
                        Text(
                            fileName,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = { startSave() },
                            enabled = !loading && error == null && !saving
                        ) { Text(if (saving) "Saving…" else "Save copy") }
                    }
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(error ?: "")
                        }
                        isDocx -> DocxList(Modifier.weight(1f))
                        else -> SheetEditor(Modifier.weight(1f))
                    }
                }

                if (askDiscard) {
                    AlertDialog(
                        onDismissRequest = { askDiscard = false },
                        title = { Text("Discard changes?") },
                        text = { Text("You have changes that are not saved yet.") },
                        confirmButton = {
                            TextButton(onClick = { askDiscard = false; finishWithResult() }) { Text("Discard") }
                        },
                        dismissButton = {
                            TextButton(onClick = { askDiscard = false }) { Text("Keep editing") }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun DocxList(modifier: Modifier) {
        LazyColumn(
            modifier,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    if (paras.isEmpty()) "No text found in this document."
                    else "Edit the text below. Pictures, tables and formatting stay as they are.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            itemsIndexed(paras) { i, t ->
                OutlinedTextField(
                    value = t,
                    onValueChange = {
                        if (i < paras.size) {
                            paras[i] = it
                            dirty = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    private fun SheetEditor(modifier: Modifier) {
        val ed = xlsx ?: return
        var sheet by remember { mutableStateOf(0) }
        var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(modifier) {
            if (ed.sheetCount() > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until ed.sheetCount()) {
                        if (i == sheet) {
                            Button(onClick = { sheet = i }) { Text(ed.sheetName(i)) }
                        } else {
                            OutlinedButton(onClick = { sheet = i }) { Text(ed.sheetName(i)) }
                        }
                    }
                }
            }

            // parse the sheet off the main thread
            val ready by produceState(false, sheet) {
                value = false
                withContext(Dispatchers.IO) { ed.rowCount(sheet) }
                value = true
            }

            if (!ready) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val rows = ed.rowCount(sheet)
                val cols = ed.colCount(sheet)
                Text(
                    if (ed.truncated(sheet))
                        "Tap a cell to edit. Only the first ${OfficeEdit.XlsxEditor.MAX_ROWS} rows and ${OfficeEdit.XlsxEditor.MAX_COLS} columns are shown."
                    else "Tap a cell to edit. Formula cells can’t be edited.",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    LazyColumn(Modifier.fillMaxHeight()) {
                        item {
                            Row {
                                HeaderBox("")
                                for (c in 0 until cols) HeaderBox(OfficeEdit.XlsxEditor.colName(c))
                            }
                        }
                        items(rows) { r ->
                            Row {
                                HeaderBox((r + 1).toString())
                                for (c in 0 until cols) {
                                    CellBox(sheet, r, c) { if (!saving) editing = Pair(r, c) }
                                }
                            }
                        }
                    }
                }
            }
        }

        editing?.let { (r, c) -> CellDialog(sheet, r, c) { editing = null } }
    }

    @Composable
    private fun HeaderBox(text: String) {
        Box(
            Modifier
                .width(if (text.isEmpty() || text.first().isDigit()) 48.dp else 110.dp)
                .height(40.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun CellBox(sheet: Int, r: Int, c: Int, onClick: () -> Unit) {
        val ed = xlsx ?: return
        val text = remember(rev, sheet, r, c) { ed.get(sheet, r, c) }
        val formula = remember(rev, sheet, r, c) { ed.isFormula(sheet, r, c) }
        Box(
            Modifier
                .width(110.dp)
                .height(40.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = if (formula) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
            )
        }
    }

    @Composable
    private fun CellDialog(sheet: Int, r: Int, c: Int, onDone: () -> Unit) {
        val ed = xlsx ?: return
        val ref = OfficeEdit.XlsxEditor.colName(c) + (r + 1)
        if (ed.isFormula(sheet, r, c)) {
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text(ref) },
                text = { Text("This cell has the formula =${ed.formula(sheet, r, c)}\nFormula cells can’t be edited here.") },
                confirmButton = { TextButton(onClick = onDone) { Text("OK") } }
            )
            return
        }
        val original = remember { ed.get(sheet, r, c) }
        var value by remember { mutableStateOf(original) }
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(ref) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (value != original && ed.set(sheet, r, c, value)) {
                        dirty = true
                        rev += 1
                    }
                    onDone()
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
        )
    }
}
