package com.example.pdfvoz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.Locale
import java.util.concurrent.Executors

class TtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val PLAY = "PLAY"
        const val PAUSE = "PAUSE"
        const val STOP = "STOP"
        var onPage: ((Int) -> Unit)? = null
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var startAfterInit = false
    private var doc: PDDocument? = null
    private var docUri: String? = null
    private var page = 0
    @Volatile private var token = 0
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tts", "Lectura de PDF", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale.getDefault()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) {}
            override fun onDone(id: String?) {
                val p = id?.split(":") ?: return
                if (p.size == 3 && p[0].toInt() == token && p[2] == "L") {
                    page = p[1].toInt() + 1
                    val t = token
                    io.execute { speakFrom(t) }
                }
            }
        })
        ready = true
        if (startAfterInit) { startAfterInit = false; val t = token; io.execute { speakFrom(t) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> {
                val u = intent.getStringExtra("uri") ?: return START_NOT_STICKY
                page = intent.getIntExtra("page", 0)
                token++
                val t = token
                startForeground(1, notif("Leyendo en voz alta…", PAUSE, "Pausa", u))
                io.execute {
                    try {
                        if (docUri != u) {
                            doc?.close()
                            doc = contentResolver.openInputStream(Uri.parse(u)).use { PDDocument.load(it) }
                            docUri = u
                        }
                        if (ready) speakFrom(t) else startAfterInit = true
                    } catch (e: Exception) {
                        main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    }
                }
            }
            PAUSE -> {
                token++
                tts?.stop()
                getSystemService(NotificationManager::class.java)
                    .notify(1, notif("En pausa", PLAY, "Reanudar", docUri))
            }
            STOP -> {
                token++
                tts?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Lee desde la página actual; avanza solo página a página. */
    private fun speakFrom(t: Int) {
        val d = doc ?: return
        while (t == token) {
            if (page >= d.numberOfPages) {
                main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                return
            }
            val stripper = PDFTextStripper().apply { startPage = page + 1; endPage = page + 1 }
            val text = stripper.getText(d).replace(Regex("\\s+"), " ").trim()
            val current = page
            main.post { onPage?.invoke(current) }
            if (text.isEmpty()) { page++; continue }
            val chunks = chunk(text)
            chunks.forEachIndexed { i, c ->
                val flag = if (i == chunks.lastIndex) "L" else "$i"
                tts?.speak(c, TextToSpeech.QUEUE_ADD, null, "$t:$current:$flag")
            }
            return
        }
    }

    private fun chunk(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in text.split(Regex("(?<=[.!?])\\s+"))) {
            if (sb.length + s.length > 3000 && sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
            sb.append(s).append(' ')
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.flatMap { it.chunked(3500) }
    }

    private fun notif(text: String, action: String, label: String, uri: String?) =
        NotificationCompat.Builder(this, "tts")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PDF Voz")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, label, actionIntent(action, uri))
            .addAction(0, "Parar", actionIntent(STOP, null))
            .build()

    private fun actionIntent(action: String, uri: String?): PendingIntent {
        val i = Intent(this, TtsService::class.java).setAction(action)
        if (action == PLAY) i.putExtra("uri", uri).putExtra("page", page)
        return if (action == PLAY)
            PendingIntent.getForegroundService(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else
            PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        token++
        tts?.shutdown()
        doc?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
