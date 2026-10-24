package com.ashfaq.offlinechat

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), ChatManager.Listener {

    private lateinit var chatManager: ChatManager
    private lateinit var adapter: MessageAdapter
    private lateinit var statusText: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var editMessage: EditText

    private var requestDialog: AlertDialog? = null
    private var closing = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            chatManager.start()
        } else {
            setStatus(getString(R.string.permissions_denied), R.color.status_error)
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
    }

    override fun onDestroy() {
        closing = true
        requestDialog?.dismiss()
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

    override fun onError(message: String) {
        if (closing) return
        requestDialog?.dismiss()
        setStatus(getString(R.string.error_generic, message), R.color.status_error)
        Toast.makeText(this, R.string.error_hint, Toast.LENGTH_LONG).show()
    }
}
