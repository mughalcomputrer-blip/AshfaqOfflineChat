package com.ashfaq.offlinechat

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class ChatManager(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onSearching()
        fun onConnectionRequest(
            endpointName: String,
            authDigits: String,
            accept: () -> Unit,
            reject: () -> Unit
        )
        fun onConnecting(endpointName: String)
        fun onConnected(endpointName: String)
        fun onDisconnected()
        fun onMessageReceived(text: String)
        fun onError(message: String)
    }

    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_POINT_TO_POINT

    // Random suffix so two phones of the same model still have different names.
    private val localName: String = "${Build.MODEL}-${(1000..9999).random()}"

    private var pendingEndpointId: String? = null
    private var pendingName: String = ""
    private var connectedEndpointId: String? = null
    private var connectedName: String = ""

    fun isConnected(): Boolean = connectedEndpointId != null

    /** Both phones advertise and search at the same time. */
    fun start() {
        stopEverything()
        listener.onSearching()

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        client.startAdvertising(
            localName, SERVICE_ID, connectionCallback, advertisingOptions
        ).addOnFailureListener { e -> fail(describe(e)) }

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        client.startDiscovery(
            SERVICE_ID, discoveryCallback, discoveryOptions
        ).addOnFailureListener { e -> fail(describe(e)) }
    }

    fun disconnect() {
        stopEverything()
        listener.onDisconnected()
    }

    fun sendMessage(text: String): Boolean {
        val endpointId = connectedEndpointId ?: return false
        val payload = Payload.fromBytes(text.toByteArray(Charsets.UTF_8))
        client.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> listener.onError(describe(e)) }
        return true
    }

    private fun stopEverything() {
        connectedEndpointId = null
        pendingEndpointId = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }

    private fun fail(message: String) {
        stopEverything()
        listener.onError(message)
    }

    private fun describe(e: Exception): String = e.message ?: e.toString()

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connectedEndpointId != null || pendingEndpointId != null) return

            // Only the phone with the "smaller" name sends the request,
            // so the two phones do not request each other at once.
            if (localName <= info.endpointName) {
                pendingEndpointId = endpointId
                client.requestConnection(localName, endpointId, connectionCallback)
                    .addOnFailureListener { e ->
                        pendingEndpointId = null
                        fail(describe(e))
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Nothing to do. Searching continues.
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val busyWithOther = connectedEndpointId != null ||
                (pendingEndpointId != null && pendingEndpointId != endpointId)
            if (busyWithOther) {
                client.rejectConnection(endpointId)
                return
            }

            pendingEndpointId = endpointId
            pendingName = info.endpointName

            listener.onConnectionRequest(
                endpointName = info.endpointName,
                authDigits = info.authenticationDigits,
                accept = {
                    listener.onConnecting(info.endpointName)
                    client.acceptConnection(endpointId, payloadCallback)
                        .addOnFailureListener { e -> fail(describe(e)) }
                },
                reject = {
                    client.rejectConnection(endpointId)
                    pendingEndpointId = null
                }
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val code = result.status.statusCode
            if (code == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpointId = endpointId
                connectedName = pendingName
                pendingEndpointId = null
                client.stopAdvertising()
                client.stopDiscovery()
                listener.onConnected(connectedName)
            } else if (code == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                fail("The connection was rejected.")
            } else {
                fail("Connection failed (code $code).")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId == connectedEndpointId) {
                connectedEndpointId = null
                listener.onDisconnected()
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Ignore anything that does not come from the phone we accepted.
            if (endpointId != connectedEndpointId) return
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            listener.onMessageReceived(String(bytes, Charsets.UTF_8))
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) {
            // Text messages arrive in one piece, so nothing to track.
        }
    }

    companion object {
        private const val SERVICE_ID = "com.ashfaq.offlinechat.v1"
    }
}
