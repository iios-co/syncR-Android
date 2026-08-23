package com.syncr.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * An SMB server connection. Persisted to connections.json.
 * Passwords are stored separately via CredentialManager (KeyStore).
 */
data class SmbConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,         // Display name e.g. "Home NAS"
    val host: String,
    val port: Int = 445,
    val username: String,
    val domain: String = "",
    val share: String = "",   // Optional: default share
    val ssid: String = ""     // The Wi-Fi SSID active when created/tested
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("domain", domain)
        put("share", share)
        put("ssid", ssid)
    }

    companion object {
        fun fromJson(json: JSONObject) = SmbConnection(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            host = json.optString("host", ""),
            port = json.optInt("port", 445),
            username = json.optString("username", ""),
            domain = json.optString("domain", ""),
            share = json.optString("share", ""),
            ssid = json.optString("ssid", "")
        )
    }
}
