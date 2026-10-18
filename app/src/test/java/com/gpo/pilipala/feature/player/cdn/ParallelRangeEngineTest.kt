package com.gpo.pilipala.feature.player.cdn

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** ParallelRangeEngine against a local Range server with failure modes. */
class ParallelRangeEngineTest {
    private val file = Random(7).nextBytes(5 * 1024 * 1024 + 12345)
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    /** Paths whose first N requests fail midway. */
    @Volatile private var failFirst = 0
    private val failed = AtomicInteger()
    private val stalls = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                val path = ex.requestURI.path
                val range = ex.requestHeaders.getFirst("Range")
                if (path.startsWith("/norange") || range == null) {
                    ex.sendResponseHeaders(200, file.size.toLong())
                    ex.responseBody.use { it.write(file) }
                    return@createContext
                }
                val m = Regex("""bytes=(\d+)-(\d*)""").find(range)!!
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].ifEmpty { "${file.size - 1}" }.toInt().coerceAtMost(file.size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $s-$e/${file.size}")
                val len = e - s + 1
                ex.sendResponseHeaders(206, len.toLong())
                Thread.sleep(20) // a little latency
                ex.responseBody.use { out ->
                    if (path.startsWith("/stall") && stalls.incrementAndGet() == 2) {
                        out.write(file, s, len / 4)
                        out.flush()
                        Thread.sleep(10_000) // silent connection, no error
                    }
                    if (path.startsWith("/flaky") && failed.incrementAndGet() <= failFirst) {
                        out.write(file, s, len / 2)
                        out.flush()
                        throw IOException("drop") // connection closes mid-body
                    }
                    out.write(file, s, len)
                }
            } catch (_: IOException) {
            } finally {
                inFlight.decrementAndGet()
                ex.close()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun engine(hosts: List<String>, from: Long, to: Long, threads: Int = 6): ParallelRangeEngine {
        val call = client.newCall(ParallelRangeEngine.request(hosts[0], emptyMap(), from, minOf(to, from + 256 * 1024 - 1)))
        val resp = call.execute()
        val cr = ParallelRangeEngine.parseContentRange(resp.header("Content-Range"))!!
        return ParallelRangeEngine(
            client, executor, emptyMap(), hosts, 1, from, to, 256 * 1024, { threads },
            object : ParallelRangeEngine.Events {}, resp, call, cr[1],
        )
    }

    private fun drain(e: ParallelRangeEngine, step: Int = 7_000): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(step)
        while (true) {
            val n = e.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        e.close()
        return out.toByteArray()
    }

    @Test
    fun wholeFileInOrder() {
        val e = engine(listOf(url("/a.m4s")), 0, file.size - 1L)
        assertArrayEquals(file, drain(e))
        assertTrue("parallel requests: ${maxInFlight.get()}", maxInFlight.get() >= 4)
    }

    @Test
    fun midFileOpenRange() {
        val from = 1_000_003L
        val e = engine(listOf(url("/a.m4s")), from, file.size - 1L)
        assertArrayEquals(file.copyOfRange(from.toInt(), file.size), drain(e))
    }

    @Test
    fun boundedRangeSmallerThanOnePiece() {
        val e = engine(listOf(url("/a.m4s")), 10, 1000)
        assertArrayEquals(file.copyOfRange(10, 1001), drain(e))
    }

    @Test
    fun brokenPiecesResumeOnFallbackHost() {
        failFirst = 3
        val e2 = engine(listOf(url("/flaky.m4s"), url("/b.m4s")), 0, file.size - 1L)
        assertArrayEquals(file, drain(e2))
        assertTrue(failed.get() >= 3)
    }

    @Test
    fun stalledPieceMovesToAnotherHost() {
        val t0 = System.nanoTime()
        val e = engine(listOf(url("/stall.m4s"), url("/b.m4s")), 0, file.size - 1L)
        assertArrayEquals(file, drain(e))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 6_000)
    }

    @Test
    fun closeMidwayStopsDownloads() {
        val e = engine(listOf(url("/a.m4s")), 0, file.size - 1L, threads = 4)
        val buf = ByteArray(1000)
        e.read(buf, 0, buf.size)
        e.close()
        Thread.sleep(300)
        val before = requests.get()
        Thread.sleep(300)
        assertEquals(before, requests.get())
    }

    @Test
    fun readerThatStopsBoundsTheWindow() {
        val e = engine(listOf(url("/a.m4s")), 0, file.size - 1L, threads = 4)
        Thread.sleep(800) // nobody reads: at most threads + 2 pieces may be fetched
        assertTrue("requests ${requests.get()}", requests.get() <= 1 + 4 + 2)
        assertEquals(file.size, drain(e).size)
    }
}
