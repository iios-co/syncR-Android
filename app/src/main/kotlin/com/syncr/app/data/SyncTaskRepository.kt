package com.syncr.app.data

import android.util.Log
import org.json.JSONArray
import java.io.File

class SyncTaskRepository(private val storageDir: File) {

    private val file = File(storageDir, "sync_tasks.json")

    fun getAll(): List<SyncTask> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { SyncTask.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sync tasks", e)
            emptyList()
        }
    }

    fun save(task: SyncTask) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == task.id }
        if (idx >= 0) list[idx] = task else list.add(task)
        persist(list)
    }

    fun delete(id: String) { persist(getAll().filter { it.id != id }) }

    fun getById(id: String): SyncTask? = getAll().firstOrNull { it.id == id }

    private fun persist(list: List<SyncTask>) =
        file.writeText(org.json.JSONArray(list.map { it.toJson() }).toString(2))

    companion object { private const val TAG = "SyncTaskRepository" }
}
