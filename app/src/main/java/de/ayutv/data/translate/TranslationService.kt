package de.ayutv.data.translate

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import de.ayutv.player.EmbeddedSubtitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Übersetzer und Texterkennung in einem eigenen Prozess (":mt"). Die Modelle brauchen viel Speicher
 * und laufen in nativem Code: Reicht der Speicher nicht oder stürzt ein Modell ab, beendet das
 * System nur diesen Prozess. Der Film läuft weiter, der Player meldet den Grund.
 */
class TranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var translator: OnDeviceTranslator
    private lateinit var ocr: SubtitleOcr

    @Volatile
    private var client: Messenger? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handle(msg)
        true
    })

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        translator = OnDeviceTranslator(filesDir, http).apply { onProgress = ::progress }
        ocr = SubtitleOcr(filesDir, http).apply { onProgress = ::progress }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        // Laufende Anfragen abbrechen und die Modelle (zusammen gut 200 MB) freigeben, ohne auf
        // eine gerade laufende Rechnung zu warten (der Hauptthread darf nicht blockieren).
        scope.coroutineContext.cancelChildren()
        scope.launch {
            runCatching { translator.release() }
            runCatching { ocr.release() }
        }
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        // Die Nachricht wird nach dem Aufruf wiederverwendet: alles Nötige jetzt herausholen.
        val replyTo = msg.replyTo ?: return
        val what = msg.what
        val id = msg.arg1
        val data = Bundle(msg.data)
        client = replyTo
        scope.launch {
            val result = try {
                when (what) {
                    RemoteTranslator.TRANSLATE -> Bundle().apply {
                        val texts = data.getStringArrayList(RemoteTranslator.TEXTS).orEmpty()
                        val target = data.getString(RemoteTranslator.TARGET).orEmpty()
                        putStringArrayList(RemoteTranslator.RESULT, ArrayList(translator.translate(texts, target, data.getString(RemoteTranslator.SOURCE))))
                    }
                    RemoteTranslator.RECOGNIZE -> Bundle().apply {
                        val image = EmbeddedSubtitles.GrayImage(
                            data.getInt(RemoteTranslator.WIDTH),
                            data.getInt(RemoteTranslator.HEIGHT),
                            data.getByteArray(RemoteTranslator.PIXELS) ?: ByteArray(0),
                        )
                        putString(RemoteTranslator.RESULT, ocr.recognize(image))
                    }
                    else -> Bundle().apply { putString(RemoteTranslator.ERROR, "Unbekannte Anfrage $what") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Bundle().apply { putString(RemoteTranslator.ERROR, e.message ?: e.javaClass.simpleName) }
            }
            runCatching { replyTo.send(Message.obtain(null, what, id, 0).apply { this.data = result }) }
        }
    }

    private fun progress(text: String) {
        val target = client ?: return
        runCatching {
            target.send(Message.obtain(null, RemoteTranslator.PROGRESS).apply { data = Bundle().apply { putString(RemoteTranslator.RESULT, text) } })
        }
    }
}
