package com.example.pdfunlock

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private enum class Outcome { OK, WRONG, ERROR }

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var btnSave: Button

    private var sourceUri: Uri? = null
    private var fingerprint: String? = null
    private var displayName = "document.pdf"
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var lastError: String? = null
    private val unlockedFile by lazy { File(cacheDir, "unlocked.pdf") }

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
        setContentView(R.layout.activity_main)
        PDFBoxResourceLoader.init(applicationContext)

        recycler = findViewById(R.id.recycler)
        status = findViewById(R.id.status)
        btnSave = findViewById(R.id.btnSave)
        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            openLauncher.launch(arrayOf("application/pdf"))
        }
        btnSave.isEnabled = false
        btnSave.setOnClickListener {
            saveLauncher.launch(displayName.substringBeforeLast('.') + "_unlocked.pdf")
        }
        findViewById<Button>(R.id.btnForget).setOnClickListener {
            val fp = fingerprint
            if (fp != null) {
                PasswordStore.forget(this, fp)
                toast("Saved password removed for this PDF")
            } else {
                toast("No PDF open")
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

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
        displayName = queryName(uri) ?: "document.pdf"
        status.text = displayName
        closeRenderer()
        btnSave.isEnabled = false
        lifecycleScope.launch {
            val fp = withContext(Dispatchers.IO) { fingerprintOf(uri) }
            fingerprint = fp
            val saved = fp?.let { PasswordStore.get(this@MainActivity, it) }
            // Saved password is tried automatically; otherwise try empty password (unprotected PDFs)
            tryUnlock(saved ?: "", fromUser = false)
        }
    }

    private fun tryUnlock(password: String, fromUser: Boolean) {
        val uri = sourceUri ?: return
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
                    }
                    showPages()
                }
                Outcome.WRONG -> {
                    if (!fromUser) fingerprint?.let { PasswordStore.forget(this@MainActivity, it) }
                    askPassword(showError = fromUser)
                }
                Outcome.ERROR -> {
                    status.text = "Could not open PDF"
                    toast("Error: ${lastError ?: "unknown"}")
                }
            }
        }
    }

    private fun askPassword(showError: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (showError) "Wrong password, try again" else "Enter PDF password")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ -> tryUnlock(input.text.toString(), fromUser = true) }
            .setNegativeButton("Cancel") { _, _ -> status.text = "$displayName (locked)" }
            .show()
    }

    private fun showPages() {
        closeRenderer()
        try {
            val p = ParcelFileDescriptor.open(unlockedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(p)
            pfd = p
            renderer = r
            recycler.adapter = PageAdapter(r, resources.displayMetrics.widthPixels, lifecycleScope)
            btnSave.isEnabled = true
            status.text = "$displayName  •  unlocked  •  ${r.pageCount} pages"
        } catch (e: Exception) {
            status.text = "Could not display PDF"
            toast("Error: ${e.message}")
        }
    }

    private fun saveTo(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    unlockedFile.inputStream().use { it.copyTo(out) }
                }
                toast("Saved")
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            }
        }
    }

    private fun closeRenderer() {
        recycler.adapter = null
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        renderer = null
        pfd = null
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

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        closeRenderer()
        unlockedFile.delete()
        super.onDestroy()
    }
}

private class PageAdapter(
    private val r: PdfRenderer,
    private val width: Int,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PageAdapter.VH>() {

    class VH(val img: ImageView) : RecyclerView.ViewHolder(img)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val iv = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
        }
        return VH(iv)
    }

    override fun getItemCount() = r.pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.img.setImageBitmap(null)
        holder.img.minimumHeight = (width * 1.41f).toInt()
        scope.launch(Dispatchers.Default) {
            val bmp = try {
                synchronized(r) {
                    r.openPage(position).use { page ->
                        val h = (width.toFloat() * page.height / page.width).toInt()
                        val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.img.minimumHeight = 0
                    holder.img.setImageBitmap(bmp)
                }
            }
        }
    }
}
