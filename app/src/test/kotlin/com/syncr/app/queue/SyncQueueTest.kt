package com.syncr.app.queue

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [SyncQueue].
 *
 * Pure JVM — no Android framework dependencies.
 * Uses a temp directory for WAL file I/O.
 */
class SyncQueueTest {

    private lateinit var walFile: File
    private lateinit var queue: SyncQueue

    @Before
    fun setUp() {
        walFile = Files.createTempFile("wal_test", ".log").toFile()
        walFile.writeText("")           // ensure empty
        queue = SyncQueue(walFile)
    }

    @After
    fun tearDown() {
        walFile.delete()
    }

    // ─── Enqueue ─────────────────────────────────────────────────────────

    @Test
    fun `enqueue returns true for new path`() {
        assertTrue(queue.enqueue("/sdcard/DCIM/photo1.jpg"))
        assertEquals(1, queue.size())
    }

    @Test
    fun `enqueue returns false for duplicate path`() {
        queue.enqueue("/sdcard/DCIM/photo1.jpg")
        val second = queue.enqueue("/sdcard/DCIM/photo1.jpg")
        assertFalse("Duplicate should be suppressed", second)
        assertEquals("Queue should still have 1 item", 1, queue.size())
    }

    @Test
    fun `enqueue multiple distinct paths`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        queue.enqueue("/sdcard/DCIM/b.jpg")
        queue.enqueue("/sdcard/DCIM/c.jpg")
        assertEquals(3, queue.size())
        assertFalse(queue.isEmpty())
    }

    // ─── WAL write ────────────────────────────────────────────────────────

    @Test
    fun `enqueue persists path to WAL file`() {
        queue.enqueue("/sdcard/DCIM/photo.jpg")
        val lines = walFile.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertEquals("/sdcard/DCIM/photo.jpg", lines[0])
    }

    @Test
    fun `WAL grows with each enqueue`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        queue.enqueue("/sdcard/DCIM/b.jpg")
        val lines = walFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
    }

    // ─── WAL read (startup recovery) ─────────────────────────────────────

    @Test
    fun `loadFromWal rehydrates queue from file`() {
        walFile.writeText("/sdcard/DCIM/a.jpg\n/sdcard/DCIM/b.jpg\n")
        val freshQueue = SyncQueue(walFile)
        freshQueue.loadFromWal()
        assertEquals(2, freshQueue.size())
        assertEquals("/sdcard/DCIM/a.jpg", freshQueue.peek())
    }

    @Test
    fun `loadFromWal ignores blank lines`() {
        walFile.writeText("\n/sdcard/DCIM/a.jpg\n\n/sdcard/DCIM/b.jpg\n\n")
        val freshQueue = SyncQueue(walFile)
        freshQueue.loadFromWal()
        assertEquals(2, freshQueue.size())
    }

    @Test
    fun `loadFromWal does not enqueue duplicates`() {
        walFile.writeText("/sdcard/DCIM/a.jpg\n/sdcard/DCIM/a.jpg\n")
        val freshQueue = SyncQueue(walFile)
        freshQueue.loadFromWal()
        assertEquals(1, freshQueue.size())
    }

    @Test
    fun `loadFromWal on missing WAL file is a no-op`() {
        walFile.delete()
        val freshQueue = SyncQueue(walFile)    // file does not exist
        freshQueue.loadFromWal()               // should not throw
        assertEquals(0, freshQueue.size())
    }

    // ─── Remove (batch WAL pruning) ──────────────────────────────────────

    @Test
    fun `remove deletes entry from in-memory queue`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        queue.enqueue("/sdcard/DCIM/b.jpg")
        queue.remove("/sdcard/DCIM/a.jpg")
        assertEquals(1, queue.size())
        assertEquals("/sdcard/DCIM/b.jpg", queue.peek())
    }

    @Test
    fun `pruneWal rewrites WAL without removed entries`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        queue.enqueue("/sdcard/DCIM/b.jpg")
        queue.remove("/sdcard/DCIM/a.jpg")
        queue.pruneWal()
        val lines = walFile.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertEquals("/sdcard/DCIM/b.jpg", lines[0])
    }

    // ─── Compaction / pruneWal ───────────────────────────────────────────

    @Test
    fun `compact truncates WAL to zero bytes`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        queue.remove("/sdcard/DCIM/a.jpg")
        queue.compact()
        assertEquals(0, walFile.length())
    }

    @Test
    fun `pruneWal on empty queue produces empty file`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        queue.remove("/sdcard/DCIM/a.jpg")
        queue.pruneWal()
        val content = walFile.readText().trim()
        assertTrue("File should be empty after pruning empty queue", content.isEmpty())
    }

    // ─── isEmpty / size ───────────────────────────────────────────────────

    @Test
    fun `isEmpty returns true on new queue`() {
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun `isEmpty returns false after enqueue`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        assertFalse(queue.isEmpty())
    }

    @Test
    fun `isEmpty returns true after all items removed`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        queue.remove("/sdcard/DCIM/a.jpg")
        assertTrue(queue.isEmpty())
    }

    // ─── Channel signal ───────────────────────────────────────────────────

    @Test
    fun `enqueue sends signal to channel`() {
        queue.enqueue("/sdcard/DCIM/a.jpg")
        // CONFLATED channel: trySend should have put a value
        val result = queue.signal.tryReceive()
        assertTrue("Channel should have a pending signal", result.isSuccess)
    }
}
