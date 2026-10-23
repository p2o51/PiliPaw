package com.gpo.pilipala.core.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class LatestFileWriterTest {
    @get:Rule val temp = TemporaryFolder()
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun serializationIsDeferredAndLatestSnapshotWins() {
        val queue = Queue()
        val writer = LatestFileWriter(queue) { _, e -> throw AssertionError(e) }
        val file = File(temp.root, "history.json")
        var encoded = 0
        repeat(500) { index -> writer.write(file) { encoded++; "$index" } }
        assertEquals(0, encoded)
        assertFalse(file.exists())
        assertEquals(1, queue.tasks.size)
        queue.drain()
        assertEquals(1, encoded)
        assertEquals("499", file.readText())
    }

    @Test fun newSnapshotDuringWriteCannotBeOverwrittenByOldOne() {
        val queue = Queue()
        val writer = LatestFileWriter(queue) { _, e -> throw AssertionError(e) }
        val file = temp.newFile("history.json")
        writer.write(file) {
            writer.write(file) { "new" }
            "old"
        }
        queue.drain()
        assertEquals("new", file.readText())
    }

    @Test fun failureKeepsPreviousFileAndDoesNotStopOtherWrites() {
        val queue = Queue()
        val failures = mutableListOf<Exception>()
        val writer = LatestFileWriter(queue) { _, e -> failures += e }
        val file = temp.newFile("history.json").apply { writeText("previous") }
        val next = File(temp.root, "watch_later.json")
        writer.write(file) { throw IOException("disk/encoding failure") }
        writer.write(next) { "saved" }
        queue.drain()
        assertEquals("previous", file.readText())
        assertEquals("saved", next.readText())
        assertEquals(1, failures.size)
        assertFalse(File(temp.root, "history.json.tmp").exists())
        writer.write(file) { "retry" }
        queue.drain()
        assertEquals("retry", file.readText())
    }

    @Test fun failedRenameIsReportedAndTemporaryFileIsRemoved() {
        val queue = Queue()
        val failures = mutableListOf<Exception>()
        val writer = LatestFileWriter(queue) { _, e -> failures += e }
        val destination = temp.newFolder("history.json")
        writer.write(destination) { "data" }
        queue.drain()
        assertTrue(destination.isDirectory)
        assertEquals(1, failures.size)
        assertFalse(File(temp.root, "history.json.tmp").exists())
    }
}
