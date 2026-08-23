package com.syncr.app.integration

import com.syncr.app.queue.SyncQueue
import com.syncr.app.smb.SmbSessionManager
import com.syncr.app.transfer.TransferEngine
import com.syncr.app.transfer.TransferResult
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Integration test: simulates an inotify event → queue → transfer pipeline
 * without a real SMB connection.
 *
 * Verifies:
 * 1. Enqueuing a path triggers the sync channel signal.
 * 2. TransferEngine.transfer() is called with the correct path.
 * 3. Successful transfer causes the item to be removed from the queue.
 * 4. Failed transfer leaves the item in the queue (retryable).
 * 5. Non-retryable failure removes the item permanently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPipelineTest {

    private lateinit var walFile: File
    private lateinit var testFile: File
    private lateinit var syncQueue: SyncQueue
    private lateinit var mockSession: SmbSessionManager
    private lateinit var transferEngine: TransferEngine

    @Before
    fun setUp() {
        val tempDir = Files.createTempDirectory("pipeline_test").toFile()
        walFile = File(tempDir, "pending_sync.log")
        testFile = File(tempDir, "photo.jpg").also { it.writeText("fake image data") }

        syncQueue = SyncQueue(walFile)
        mockSession = mockk(relaxed = true)
        transferEngine = mockk()
    }

    @After
    fun tearDown() {
        walFile.parentFile?.deleteRecursively()
    }

    // ─── 1. Enqueue → signal ──────────────────────────────────────────────

    @Test
    fun `enqueue sends signal on channel`() {
        syncQueue.enqueue(testFile.absolutePath)
        val result = syncQueue.signal.tryReceive()
        assertTrue("Signal should fire after enqueue", result.isSuccess)
    }

    // ─── 2. Signal → transfer called with correct path ────────────────────

    @Test
    fun `sync worker receives signal and calls transfer`() = runTest {
        coEvery { transferEngine.transfer(testFile.absolutePath) } returns TransferResult.Success

        val workerJob = launch {
            syncQueue.signal.receive()
            val path = syncQueue.peek()!!
            transferEngine.transfer(path)
        }

        syncQueue.enqueue(testFile.absolutePath)
        advanceUntilIdle()
        workerJob.join()

        coVerify(exactly = 1) { transferEngine.transfer(testFile.absolutePath) }
    }

    // ─── 3. Successful transfer → item removed ────────────────────────────

    @Test
    fun `successful transfer removes item from queue and WAL`() = runTest {
        coEvery { transferEngine.transfer(testFile.absolutePath) } returns TransferResult.Success

        syncQueue.enqueue(testFile.absolutePath)
        assertEquals(1, syncQueue.size())

        val path = syncQueue.peek()!!
        val result = transferEngine.transfer(path)
        if (result is TransferResult.Success) {
            syncQueue.remove(path)
        }

        assertTrue("Queue should be empty after success", syncQueue.isEmpty())
        val walLines = walFile.readLines().filter { it.isNotBlank() }
        assertTrue("WAL should not contain transferred path", walLines.none { it == path })
    }

    // ─── 4. Retryable failure → item stays in queue ───────────────────────

    @Test
    fun `retryable failure leaves item in queue`() = runTest {
        coEvery { transferEngine.transfer(testFile.absolutePath) } returns
            TransferResult.Failure("Connection reset", retryable = true)

        syncQueue.enqueue(testFile.absolutePath)
        val path = syncQueue.peek()!!
        val result = transferEngine.transfer(path)

        if (result is TransferResult.Failure && result.retryable) {
            // do NOT remove; leave for retry
        } else {
            syncQueue.remove(path)
        }

        assertEquals("Item should remain in queue", 1, syncQueue.size())
    }

    // ─── 5. Non-retryable failure → item dropped from queue ──────────────

    @Test
    fun `non-retryable failure removes item permanently`() = runTest {
        coEvery { transferEngine.transfer(testFile.absolutePath) } returns
            TransferResult.Failure("File not found", retryable = false)

        syncQueue.enqueue(testFile.absolutePath)
        val path = syncQueue.peek()!!
        val result = transferEngine.transfer(path)

        if (result is TransferResult.Failure && !result.retryable) {
            syncQueue.remove(path)
        }

        assertTrue("Permanently failed item should be dropped", syncQueue.isEmpty())
    }

    // ─── 6. WAL rehydration after crash ───────────────────────────────────

    @Test
    fun `queue rehydrates from WAL after restart`() {
        // Write some paths to WAL as if a previous session crashed
        walFile.writeText("${testFile.absolutePath}\n/sdcard/DCIM/b.jpg\n")

        val freshQueue = SyncQueue(walFile)
        freshQueue.loadFromWal()

        assertEquals(2, freshQueue.size())
        assertEquals(testFile.absolutePath, freshQueue.peek())
    }

    // ─── 7. Duplicate suppression end-to-end ─────────────────────────────

    @Test
    fun `duplicate inotify events for same file produce one queue entry`() {
        // Simulate camera app firing CLOSE_WRITE twice (burst mode, or app retry)
        syncQueue.enqueue(testFile.absolutePath)
        syncQueue.enqueue(testFile.absolutePath)
        syncQueue.enqueue(testFile.absolutePath)

        assertEquals("Only one entry should exist", 1, syncQueue.size())
    }
}
