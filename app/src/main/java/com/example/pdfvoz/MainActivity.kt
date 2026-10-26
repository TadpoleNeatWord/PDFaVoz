package com.example.pdfvoz

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) open(u)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        lm = LinearLayoutManager(this)
        list.layoutManager = lm

        findViewById<Button>(R.id.btnOpen).setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { play() }
        findViewById<Button>(R.id.btnPause).setOnClickListener { send(TtsService.PAUSE) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { send(TtsService.STOP) }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // El servicio avisa de la página que está leyendo y la pantalla la sigue.
        TtsService.onPage = { p -> runOnUiThread { lm.scrollToPositionWithOffset(p, 0) } }
    }

    private fun open(u: Uri) {
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        renderer?.close(); pfd?.close()
        pfd = contentResolver.openFileDescriptor(u, "r")
        renderer = PdfRenderer(pfd!!)
        uri = u
        list.post { list.adapter = PageAdapter(renderer!!) }
    }

    private fun play() {
        val u = uri ?: return
        val page = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val i = Intent(this, TtsService::class.java).setAction(TtsService.PLAY)
            .putExtra("uri", u.toString()).putExtra("page", page)
        ContextCompat.startForegroundService(this, i)
    }

    private fun send(action: String) {
        startService(Intent(this, TtsService::class.java).setAction(action))
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close(); pfd?.close()
    }

    inner class PageAdapter(private val r: PdfRenderer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = r.pageCount
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val iv = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                adjustViewBounds = true
                setPadding(0, 0, 0, 8)
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val iv = holder.itemView as ImageView
            val w = list.width
            iv.setImageBitmap(null)
            iv.minimumHeight = (w * 1.41).toInt()
            iv.tag = position
            io.execute {
                try {
                    val bmp = synchronized(r) {
                        val page = r.openPage(position)
                        val h = (w.toFloat() * page.height / page.width).toInt()
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        b
                    }
                    iv.post { if (iv.tag == position) { iv.minimumHeight = 0; iv.setImageBitmap(bmp) } }
                } catch (_: Exception) {}
            }
        }
    }
}
