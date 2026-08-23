package com.syncr.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class SyncDirection { PHONE_TO_SMB, SMB_TO_PHONE }

/**
 * A sync task pairing a source and destination.
 * Source/target are either a local path (PHONE) or SMB connection+share+path.
 */
data class SyncTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val direction: SyncDirection,

    // Phone side
    val localPath: String = "/storage/emulated/0/DCIM",

    // SMB side  (connectionId references SmbConnection)
    val connectionId: String = "",
    val smbShare: String = "",
    val smbRemotePath: String = "",

    val enabled: Boolean = true,

    /** Poll interval for SMB_TO_PHONE tasks, in minutes. Default 15. */
    val pollIntervalMinutes: Int = 15
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("direction", direction.name)
        put("localPath", localPath)
        put("connectionId", connectionId)
        put("smbShare", smbShare)
        put("smbRemotePath", smbRemotePath)
        put("enabled", enabled)
        put("pollIntervalMinutes", pollIntervalMinutes)
    }

    companion object {
        fun fromJson(json: JSONObject) = SyncTask(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            direction = SyncDirection.valueOf(json.optString("direction", SyncDirection.PHONE_TO_SMB.name)),
            localPath = json.optString("localPath", "/storage/emulated/0/DCIM"),
            connectionId = json.optString("connectionId", ""),
            smbShare = json.optString("smbShare", ""),
            smbRemotePath = json.optString("smbRemotePath", ""),
            enabled = json.optBoolean("enabled", true),
            pollIntervalMinutes = json.optInt("pollIntervalMinutes", 15)
        )
    }
}
