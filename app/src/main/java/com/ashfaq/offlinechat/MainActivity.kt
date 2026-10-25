package com.ashfaq.offlinechat

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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

    private var requestDialog: AlertDialog? = null
    private var closing = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Version 1 stays in portrait so a rotation cannot drop the connection.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        editMessage = findViewById(R.id.editMessage)
        buttonMic = findViewById(R.id.buttonMic)
        val buttonFind: MaterialButton = findViewById(R.id.buttonFind)
        val buttonDisconnect: MaterialButton = findViewById(R.id.buttonDisconnect)
        val buttonSend: MaterialButton = findViewById(R.id.buttonSend)

        adapter = MessageAdapter()
        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.adapter = adapter

        chatManager = ChatManager(this, this)

        buttonFind.setOnClickListener { onFindClicked() }
        buttonDisconnect.setOnClickListener { chatManager.disconnect() }
        buttonSend.setOnClickListener { sendCurrentMessage() }
        buttonMic.setOnClickListener { onMicClicked() }
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
        if (!chatManager.sendMessage(text)) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(ChatMessage(text = text, isSent = true))
        editMessage.text.clear()
    }

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
        if (!chatManager.sendVoiceMessage(file, durationSeconds)) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.VOICE,
                audioPath = file.absolutePath,
                durationSeconds = durationSeconds
            )
        )
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
                reject()
                setStatus(getString(R.string.status_searching), R.color.status_idle)
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

    override fun onMessageReceived(text: String) {
        if (closing) return
        addMessage(ChatMessage(text = text, isSent = false))
    }

    override fun onVoiceMessageReceived(filePath: String, durationSeconds: Int) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.VOICE,
                audioPath = filePath,
                durationSeconds = durationSeconds
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
    }
}
