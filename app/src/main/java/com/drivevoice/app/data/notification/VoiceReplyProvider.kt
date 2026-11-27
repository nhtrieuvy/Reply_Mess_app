package com.drivevoice.app.data.notification

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.drivevoice.app.domain.state.ReplyRecording
import com.drivevoice.app.service.writePipe
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Exposes a single in-memory WAV transfer to the explicitly granted recipient. */
class VoiceReplyProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = VoiceReplyStore.forCaller(uri)?.mime

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val entry = VoiceReplyStore.forCaller(uri) ?: return null
        val columns = projection?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "drivevoice.wav"
                    OpenableColumns.SIZE -> entry.size
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw FileNotFoundException()
        val entry = VoiceReplyStore.forCaller(uri)?.takeIf { mode == "r" }
            ?: throw FileNotFoundException()
        val pair = ParcelFileDescriptor.createReliablePipe()
        val data = entry.take()
        if (data == null) {
            pair.forEach { it.close() }
            throw FileNotFoundException()
        }
        val app = context?.applicationContext ?: run {
            pair.forEach { it.close() }
            data.fill(0)
            throw FileNotFoundException()
        }
        VoiceReplyStore.scope.launch {
            try {
                Os.fcntlInt(pair[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
                val transferred = withTimeoutOrNull(12_000) { writePipe(pair[1], data, data.size) } == true
                if (!transferred) runCatching { pair[1].closeWithError("Audio transfer did not finish") }
            } catch (_: Exception) {
                runCatching { pair[1].closeWithError("Audio transfer unavailable") }
            } finally {
                runCatching { pair[1].close() }
                data.fill(0)
                VoiceReplyStore.revoke(app, uri)
            }
        }
        return pair[0]
    }

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
}

internal object VoiceReplyStore {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val entries = ConcurrentHashMap<Uri, Entry>()

    internal class Entry(val uid: Int, val mime: String, private var data: ByteArray?) {
        val size = data?.size ?: 0
        @Synchronized fun take(): ByteArray? = data.also { data = null }
    }

    fun issue(context: Context, recipient: String, mime: String, recording: ReplyRecording): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || entries.size >= 4) return null
        val uid = try {
            context.packageManager.getPackageUid(recipient, 0)
        } catch (_: Exception) {
            return null
        }
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.voice-reply")
            .appendPath(UUID.randomUUID().toString()).build()
        entries[uri] = Entry(uid, mime, recording.wave())
        try {
            context.grantUriPermission(recipient, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: RuntimeException) {
            revoke(context, uri)
            return null
        }
        handler.postDelayed({ revoke(context.applicationContext, uri) }, 60_000)
        return uri
    }

    fun forCaller(uri: Uri): Entry? = entries[uri]?.takeIf { it.uid == Binder.getCallingUid() }

    fun revoke(context: Context, uri: Uri) {
        val entry = entries.remove(uri) ?: return
        entry.take()?.fill(0)
        runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
