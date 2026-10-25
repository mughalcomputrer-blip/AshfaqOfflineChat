package com.ashfaq.offlinechat

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File

class MainActivity : AppCompatActivity(), ChatManager.Listener {

    private lateinit var chatManager: ChatManager
    private lateinit var adapter: MessageAdapter
    private lateinit var statusText: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var editMessage: EditText
    private lateinit var buttonMic: ImageButton
    private lateinit var buttonPhoto: ImageButton
    private lateinit var buttonFile: ImageButton
    private lateinit var quoteBar: View
    private lateinit var quoteBarSender: TextView
    private lateinit var quoteBarText: TextView

    private var requestDialog: AlertDialog? = null
    private var closing = false

    // The quote chosen with a long press ("Reply"), waiting to ride
    // along with the next message the user sends — text, voice, photo
    // or file alike. Both stay null when no reply is pending; sending
    // (or the ✕ on the quote bar) clears them again.
    private var pendingQuotedSender: String? = null
    private var pendingQuotedText: String? = null

    // Ids for our own sent messages, handed out one per message, so a
    // finished transfer can be matched back to its chat row (the
    // "delivered" ticks). Continued after the loaded history in
    // onCreate, so ids never repeat within the saved chat.
    private var nextLocalId = 1L

    // Voice recording state.
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt: Long = 0L
    private var isRecording = false
    private val recordHandler = Handler(Looper.getMainLooper())
    private val autoStopRecording = Runnable { stopRecordingAndSend() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            chatManager.start()
        } else {
            setStatus(getString(R.string.permissions_denied), R.color.status_error)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    // The system media picker (photos and videos): no storage
    // permission is needed, and a cancelled pick simply gives us
    // nothing (not an error). The picked item's type decides which
    // send flow it takes.
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mimeType = try {
                contentResolver.getType(uri)
            } catch (e: Exception) {
                null
            }
            if (mimeType != null && mimeType.startsWith("video/")) {
                prepareAndSendVideo(uri)
            } else {
                prepareAndSendPhoto(uri)
            }
        }
    }

    // The system document picker, for files of any kind: again no storage
    // permission is needed, and a cancelled pick stays silent.
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            prepareAndSendFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Version 1 stays in portrait so a rotation cannot drop the connection.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        editMessage = findViewById(R.id.editMessage)
        buttonMic = findViewById(R.id.buttonMic)
        buttonPhoto = findViewById(R.id.buttonPhoto)
        buttonFile = findViewById(R.id.buttonFile)
        quoteBar = findViewById(R.id.quoteBar)
        quoteBarSender = findViewById(R.id.quoteBarSender)
        quoteBarText = findViewById(R.id.quoteBarText)
        val buttonFind: MaterialButton = findViewById(R.id.buttonFind)
        val buttonDisconnect: MaterialButton = findViewById(R.id.buttonDisconnect)
        val buttonSend: MaterialButton = findViewById(R.id.buttonSend)
        val buttonName: TextView = findViewById(R.id.buttonName)
        val buttonClearChat: TextView = findViewById(R.id.buttonClearChat)
        val buttonCancelQuote: ImageButton = findViewById(R.id.buttonCancelQuote)

        adapter = MessageAdapter()
        adapter.onReplyRequested = { message -> showReplyDialog(message) }
        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.adapter = adapter

        // Show the chat that was saved on this phone, if any.
        val history = ChatHistoryStore.load(this)
        if (history.isNotEmpty()) {
            adapter.setMessages(history)
            recyclerMessages.scrollToPosition(history.size - 1)
        }
        // New messages we send get fresh local ids, continuing after
        // the highest id the loaded history already uses.
        nextLocalId = (history.maxOfOrNull { it.localId } ?: 0L) + 1L

        chatManager = ChatManager(this, this)

        buttonFind.setOnClickListener { onFindClicked() }
        buttonDisconnect.setOnClickListener { chatManager.disconnect() }
        buttonSend.setOnClickListener { sendCurrentMessage() }
        buttonMic.setOnClickListener { onMicClicked() }
        buttonPhoto.setOnClickListener { onPhotoClicked() }
        buttonFile.setOnClickListener { onFileClicked() }
        buttonName.setOnClickListener { showNameDialog() }
        buttonClearChat.setOnClickListener { confirmClearChat() }
        buttonCancelQuote.setOnClickListener { clearPendingQuote() }
    }

    override fun onDestroy() {
        closing = true
        requestDialog?.dismiss()
        discardRecording()
        chatManager.disconnect()
        super.onDestroy()
    }

    // ---------- Buttons ----------

    private fun onFindClicked() {
        if (chatManager.isConnected()) {
            Toast.makeText(this, R.string.status_connected_already, Toast.LENGTH_SHORT).show()
            return
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            chatManager.start()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun sendCurrentMessage() {
        val text = editMessage.text.toString().trim()
        if (text.isEmpty()) return
        val localId = nextLocalId++
        if (!chatManager.sendMessage(
                text, localId, pendingQuotedSender, pendingQuotedText
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = text,
                isSent = true,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText
            )
        )
        editMessage.text.clear()
        clearPendingQuote()
    }

    // ---------- Replies ----------

    /** The little menu shown when a message is long-pressed. */
    private fun showReplyDialog(message: ChatMessage) {
        if (closing) return
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.reply))) { _, _ ->
                setPendingQuote(message)
            }
            .show()
    }

    /**
     * Remembers [message] as the one being replied to and shows the
     * quote bar above the input row. The quote goes out with the next
     * message that is actually sent, whichever kind it is; a send that
     * fails (for example while not connected) leaves it in place.
     */
    private fun setPendingQuote(message: ChatMessage) {
        pendingQuotedSender = quoteAuthorOf(message)
        pendingQuotedText = quotePreviewOf(message)
        quoteBarSender.text = pendingQuotedSender
        quoteBarText.text = pendingQuotedText
        quoteBar.visibility = View.VISIBLE
    }

    /** Drops the pending reply and hides the quote bar. */
    private fun clearPendingQuote() {
        pendingQuotedSender = null
        pendingQuotedText = null
        quoteBar.visibility = View.GONE
    }

    /**
     * The name a quote of [message] carries: the original author's
     * display name. Our own messages are quoted under our own name (the
     * other phones stamp it into the quote, exactly like the sender
     * name of a normal message); a received message whose author is
     * unknown (saved by an older app version) is quoted as "Friend".
     */
    private fun quoteAuthorOf(message: ChatMessage): String = when {
        message.isSent -> chatManager.displayBaseName()
        !message.senderName.isNullOrBlank() -> message.senderName
        else -> getString(R.string.quote_friend)
    }

    /** The one-line preview a quote of [message] shows. */
    private fun quotePreviewOf(message: ChatMessage): String = when (message.type) {
        ChatMessage.Type.TEXT -> {
            val oneLine = message.text.replace('\n', ' ').trim()
            if (oneLine.length > QUOTE_PREVIEW_MAX_CHARS) {
                oneLine.substring(0, QUOTE_PREVIEW_MAX_CHARS) + "…"
            } else {
                oneLine
            }
        }
        ChatMessage.Type.VOICE ->
            getString(R.string.quote_voice_preview, formatDuration(message.durationSeconds))
        ChatMessage.Type.PHOTO -> getString(R.string.quote_photo_preview)
        ChatMessage.Type.VIDEO ->
            getString(R.string.quote_video_preview, formatDuration(message.durationSeconds))
        ChatMessage.Type.FILE -> getString(
            R.string.quote_file_preview,
            message.fileName ?: message.filePath?.let { File(it).name } ?: ""
        )
    }

    /** "0:07" style, like the voice rows in the chat show it. */
    private fun formatDuration(totalSeconds: Int): String =
        String.format(
            java.util.Locale.getDefault(), "%d:%02d",
            totalSeconds / 60, totalSeconds % 60
        )

    // ---------- Voice messages ----------

    /** First tap starts recording, second tap stops and sends it. */
    private fun onMicClicked() {
        if (isRecording) {
            stopRecordingAndSend()
            return
        }
        if (!chatManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected_voice, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val newRecorder: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(64_000)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
        } catch (e: Exception) {
            try {
                newRecorder.release()
            } catch (ignored: Exception) {
                // Recorder already gone.
            }
            file.delete()
            Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_SHORT).show()
            return
        }
        recorder = newRecorder
        recordingFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        isRecording = true
        buttonMic.setColorFilter(ContextCompat.getColor(this, R.color.status_error))
        setStatus(getString(R.string.recording_status), R.color.status_error)
        recordHandler.removeCallbacks(autoStopRecording)
        recordHandler.postDelayed(autoStopRecording, MAX_RECORDING_MS)
    }

    private fun stopRecordingAndSend() {
        val activeRecorder = recorder ?: return
        recordHandler.removeCallbacks(autoStopRecording)
        val file = recordingFile
        val durationSeconds = (
            (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000
            ).toInt().coerceIn(1, MAX_RECORDING_SECONDS)

        var stoppedOk = true
        try {
            activeRecorder.stop()
        } catch (e: RuntimeException) {
            // Stopping too early makes the recorder refuse; the file is
            // then unusable, so we throw it away below.
            stoppedOk = false
        }
        try {
            activeRecorder.release()
        } catch (ignored: Exception) {
            // Recorder already gone.
        }
        recorder = null
        recordingFile = null
        isRecording = false
        buttonMic.setColorFilter(ContextCompat.getColor(this, R.color.primary))

        if (!stoppedOk || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val localId = nextLocalId++
        if (!chatManager.sendVoiceMessage(
                file, durationSeconds, localId,
                pendingQuotedSender, pendingQuotedText
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.VOICE,
                audioPath = file.absolutePath,
                durationSeconds = durationSeconds,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText
            )
        )
        clearPendingQuote()
        setStatus(getString(R.string.voice_sent), R.color.status_connected)
    }

    /** Stops a recording without sending it (used when the screen closes). */
    private fun discardRecording() {
        recordHandler.removeCallbacks(autoStopRecording)
        val activeRecorder = recorder ?: return
        try {
            activeRecorder.stop()
        } catch (ignored: RuntimeException) {
            // Nothing worth keeping anyway.
        }
        try {
            activeRecorder.release()
        } catch (ignored: Exception) {
            // Recorder already gone.
        }
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        isRecording = false
    }

    // ---------- Photo and video messages ----------

    private fun onPhotoClicked() {
        if (!chatManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected_media, Toast.LENGTH_SHORT).show()
            return
        }
        photoPickerLauncher.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
        )
    }

    /**
     * Shrinks the picked photo (longest side at most [MAX_PHOTO_DIMENSION]
     * pixels), saves it as a JPEG in the cache folder and sends it. Working
     * on a small copy keeps the offline transfer fast.
     */
    private fun prepareAndSendPhoto(uri: Uri) {
        val file = try {
            createPreparedPhotoFile(uri)
        } catch (e: Exception) {
            null
        }
        if (file == null) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val localId = nextLocalId++
        if (!chatManager.sendPhotoMessage(
                file, localId, pendingQuotedSender, pendingQuotedText
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.PHOTO,
                imagePath = file.absolutePath,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText
            )
        )
        clearPendingQuote()
    }

    private fun createPreparedPhotoFile(uri: Uri): File? {
        // Read only the size first, without loading the whole picture.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode a sampled-down copy (about twice the final size at most),
        // then scale exactly; this keeps memory use modest.
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth, bounds.outHeight,
                MAX_PHOTO_DIMENSION * 2, MAX_PHOTO_DIMENSION * 2
            )
        }
        var bitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        // Turn the picture the right way up, using the rotation stored in
        // the photo itself (framework EXIF reader, Android 7 and newer;
        // older phones simply keep the picture as it comes).
        val rotationDegrees = readExifRotationDegrees(uri)
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated !== bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
        }

        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide > MAX_PHOTO_DIMENSION) {
            val scale = MAX_PHOTO_DIMENSION.toFloat() / largestSide
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val saved = file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, output)
        }
        bitmap.recycle()
        if (!saved || !file.exists() || file.length() == 0L) {
            file.delete()
            return null
        }
        return file
    }

    /** Rotation stored in the photo (0, 90, 180 or 270). 0 when unknown. */
    private fun readExifRotationDegrees(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (
                halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ---------- Video messages ----------

    /**
     * Copies the picked video into our cache folder (keeping its name)
     * and sends it as it is — videos are not shrunk or re-encoded
     * (that would need extra libraries). The same 100 MB cap as for
     * files applies, checked up front when the picker reports a size
     * and again during the copy.
     */
    private fun prepareAndSendVideo(uri: Uri) {
        val originalName = queryDisplayName(uri)
            ?: "video_${System.currentTimeMillis()}"
        var cleanName = ChatManager.sanitizeFileName(originalName)
            .ifEmpty { "video_${System.currentTimeMillis()}" }
        // The player on the other phone (and our own history) is
        // happier with a proper ending; add the picker's one when the
        // name has none.
        if (!cleanName.contains('.')) {
            val extension = try {
                contentResolver.getType(uri)?.let {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                }
            } catch (e: Exception) {
                null
            }
            if (!extension.isNullOrEmpty()) {
                cleanName = "$cleanName.$extension"
            }
        }

        // When the picker can tell us the size up front, refuse a video
        // that is too large without copying it first.
        val knownSize = queryFileSize(uri)
        if (knownSize != null && knownSize > MAX_FILE_SIZE_BYTES) {
            onError(getString(R.string.file_too_large))
            return
        }

        val file = when (val result = copyPickedFile(uri, cleanName)) {
            is CopyResult.Done -> result.file
            CopyResult.TooLarge -> {
                onError(getString(R.string.file_too_large))
                return
            }
            CopyResult.Failed -> {
                Toast.makeText(this, R.string.video_failed, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val durationSeconds = pickedVideoDurationSeconds(file)
        val localId = nextLocalId++
        if (!chatManager.sendVideoMessage(
                file, localId, pendingQuotedSender, pendingQuotedText
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.VIDEO,
                videoPath = file.absolutePath,
                durationSeconds = durationSeconds,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText
            )
        )
        clearPendingQuote()
    }

    /** Reads the length of a video file, in seconds. 0 when unknown. */
    private fun pickedVideoDurationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            (millis / 1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
                // Nothing to clean up then.
            }
        }
    }

    // ---------- File messages ----------

    private fun onFileClicked() {
        if (!chatManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected_file, Toast.LENGTH_SHORT).show()
            return
        }
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    /**
     * Copies the picked file into our cache folder (keeping its original
     * name) and sends it. Files over [MAX_FILE_SIZE_BYTES] are refused
     * with the normal error path, before anything is sent.
     */
    private fun prepareAndSendFile(uri: Uri) {
        val originalName = queryDisplayName(uri)
            ?: "file_${System.currentTimeMillis()}"
        val cleanName = ChatManager.sanitizeFileName(originalName)
            .ifEmpty { "file_${System.currentTimeMillis()}" }

        // When the picker can tell us the size up front, refuse a file
        // that is too large without copying it first.
        val knownSize = queryFileSize(uri)
        if (knownSize != null && knownSize > MAX_FILE_SIZE_BYTES) {
            onError(getString(R.string.file_too_large))
            return
        }

        val file = when (val result = copyPickedFile(uri, cleanName)) {
            is CopyResult.Done -> result.file
            CopyResult.TooLarge -> {
                onError(getString(R.string.file_too_large))
                return
            }
            CopyResult.Failed -> {
                Toast.makeText(this, R.string.file_failed, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val localId = nextLocalId++
        if (!chatManager.sendFileMessage(
                file, localId, pendingQuotedSender, pendingQuotedText
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.FILE,
                filePath = file.absolutePath,
                fileName = file.name,
                fileSizeBytes = file.length(),
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText
            )
        )
        clearPendingQuote()
    }

    /** How copying a picked file into the cache folder went. */
    private sealed class CopyResult {
        class Done(val file: File) : CopyResult()
        object TooLarge : CopyResult()
        object Failed : CopyResult()
    }

    /**
     * Streams the picked content into a cache file with the given name.
     * The copy stops (and the half-written file is removed) as soon as
     * the size passes [MAX_FILE_SIZE_BYTES], for pickers that do not
     * report a size up front.
     */
    private fun copyPickedFile(uri: Uri, name: String): CopyResult {
        val dest = File(cacheDir, name)
        return try {
            val input = contentResolver.openInputStream(uri) ?: return CopyResult.Failed
            var total = 0L
            var tooLarge = false
            input.use { stream ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_SIZE_BYTES) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            when {
                tooLarge -> {
                    dest.delete()
                    CopyResult.TooLarge
                }
                !dest.exists() || dest.length() == 0L -> {
                    dest.delete()
                    CopyResult.Failed
                }
                else -> CopyResult.Done(dest)
            }
        } catch (e: Exception) {
            dest.delete()
            CopyResult.Failed
        }
    }

    /** The name the picker shows for this file. Null when unknown. */
    private fun queryDisplayName(uri: Uri): String? =
        queryOpenableColumn(uri, OpenableColumns.DISPLAY_NAME) { cursor, index ->
            cursor.getString(index)
        }

    /** The size the picker reports for this file. Null when unknown. */
    private fun queryFileSize(uri: Uri): Long? =
        queryOpenableColumn(uri, OpenableColumns.SIZE) { cursor, index ->
            if (cursor.isNull(index)) null else cursor.getLong(index)
        }

    private fun <T> queryOpenableColumn(
        uri: Uri,
        column: String,
        read: (android.database.Cursor, Int) -> T?
    ): T? {
        return try {
            contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && cursor.moveToFirst()) read(cursor, index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Permissions ----------

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.toTypedArray()
    }

    // ---------- Helpers ----------

    private fun setStatus(text: String, colorRes: Int) {
        statusText.text = text
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun addMessage(message: ChatMessage) {
        adapter.addMessage(message)
        recyclerMessages.scrollToPosition(adapter.itemCount - 1)
        // Keep the on-phone history in step, so the chat survives a restart.
        ChatHistoryStore.save(this, adapter.snapshot())
    }

    // ---------- Display name & chat history ----------

    /** Lets the user change the name other phones see for this phone. */
    private fun showNameDialog() {
        val input = EditText(this).apply {
            setText(chatManager.displayBaseName())
            setSelection(text.length)
            setSingleLine(true)
            hint = getString(R.string.name_dialog_hint)
        }
        // Give the text field some room inside the dialog.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (20 * resources.displayMetrics.density).toInt()
            setPadding(side, 0, side, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.name_dialog_title)
            .setMessage(R.string.name_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                chatManager.updateDisplayName(input.text.toString())
                Toast.makeText(
                    this,
                    getString(R.string.name_saved, chatManager.displayBaseName()),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks first, then wipes the saved history and the on-screen chat. */
    private fun confirmClearChat() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_chat_title)
            .setMessage(R.string.clear_chat_message)
            .setPositiveButton(R.string.clear_chat_confirm) { _, _ ->
                adapter.clearMessages()
                ChatHistoryStore.clear(this)
                Toast.makeText(this, R.string.chat_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- ChatManager.Listener ----------

    override fun onSearching() {
        if (closing) return
        setStatus(getString(R.string.status_searching), R.color.status_idle)
    }

    override fun onConnectionRequest(
        endpointName: String,
        authDigits: String,
        accept: () -> Unit,
        reject: () -> Unit
    ) {
        if (closing) return
        requestDialog?.dismiss()
        requestDialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(getString(R.string.confirm_message, endpointName, authDigits))
            .setCancelable(false)
            .setPositiveButton(R.string.accept) { _, _ -> accept() }
            .setNegativeButton(R.string.reject) { _, _ ->
                // ChatManager reports the right status for our role
                // (searching again, or still hosting the group).
                reject()
            }
            .show()
    }

    override fun onConnecting(endpointName: String) {
        if (closing) return
        setStatus(getString(R.string.status_connecting, endpointName), R.color.status_idle)
    }

    override fun onConnected(endpointName: String) {
        if (closing) return
        requestDialog?.dismiss()
        setStatus(getString(R.string.status_connected, endpointName), R.color.status_connected)
    }

    override fun onGroupHostUpdate(connectedCount: Int) {
        if (closing) return
        setStatus(
            getString(R.string.status_hosting, connectedCount),
            R.color.status_connected
        )
    }

    override fun onDisconnected() {
        if (closing) return
        requestDialog?.dismiss()
        setStatus(getString(R.string.status_disconnected), R.color.status_idle)
    }

    override fun onReconnecting(attempt: Int, maxAttempts: Int) {
        if (closing) return
        setStatus(
            getString(R.string.status_reconnecting, attempt, maxAttempts),
            R.color.status_idle
        )
    }

    override fun onMessageReceived(
        text: String,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = text,
                isSent = false,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText
            )
        )
    }

    override fun onMessageDelivered(localId: Long) {
        if (closing) return
        // Put the second tick on the message, and save the history so
        // the ticks are still right after the app is reopened.
        if (adapter.markDelivered(localId)) {
            ChatHistoryStore.save(this, adapter.snapshot())
        }
    }

    override fun onVoiceMessageReceived(
        filePath: String,
        durationSeconds: Int,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.VOICE,
                audioPath = filePath,
                durationSeconds = durationSeconds,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText
            )
        )
    }

    override fun onPhotoMessageReceived(
        filePath: String,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.PHOTO,
                imagePath = filePath,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText
            )
        )
    }

    override fun onVideoMessageReceived(
        filePath: String,
        durationSeconds: Int,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.VIDEO,
                videoPath = filePath,
                durationSeconds = durationSeconds,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText
            )
        )
    }

    override fun onFileMessageReceived(
        filePath: String,
        fileName: String,
        fileSizeBytes: Long,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.FILE,
                filePath = filePath,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText
            )
        )
    }

    override fun onError(message: String) {
        if (closing) return
        requestDialog?.dismiss()
        setStatus(getString(R.string.error_generic, message), R.color.status_error)
        Toast.makeText(this, R.string.error_hint, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val MAX_RECORDING_MS = 120_000L // 2 minutes
        private const val MAX_RECORDING_SECONDS = 120
        private const val MAX_PHOTO_DIMENSION = 1600 // pixels, longest side
        private const val PHOTO_JPEG_QUALITY = 80
        private const val MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB
        private const val QUOTE_PREVIEW_MAX_CHARS = 60
    }
}
