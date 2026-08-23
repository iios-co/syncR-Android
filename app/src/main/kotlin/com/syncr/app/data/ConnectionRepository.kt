package com.syncr.app.data

import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * Repository for SMB connections — single source of truth.
 * Follows the Repository pattern from Clean Android Architecture:
 *   UI → ViewModel → Repository → File system
 *
 * Connections are persisted to connections.json in app internal storage.
 * Passwords live in CredentialManager (KeyStore) keyed by connection id.
 */
class ConnectionRepository(private val storageDir: File) {

    private val file = File(storageDir, "connections.json")

    /** Load all saved connections. */
    fun getAll(): List<SmbConnection> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { SmbConnection.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read connections", e)
            emptyList()
        }
    }

    /** Save or update a connection. */
    fun save(connection: SmbConnection) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == connection.id }
        if (idx >= 0) list[idx] = connection else list.add(connection)
        persist(list)
    }

    /** Delete a connection by id. */
    fun delete(id: String) {
        persist(getAll().filter { it.id != id })
    }

    fun getById(id: String): SmbConnection? = getAll().firstOrNull { it.id == id }

    private fun persist(list: List<SmbConnection>) {
        val arr = JSONArray(list.map { it.toJson() })
        file.writeText(arr.toString(2))
    }

    companion object { private const val TAG = "ConnectionRepository" }
}
