package com.spiros.app.engine

import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Minimal RFB (VNC) 3.8 client for the guest's screen. QEMU serves it on a Unix
 * socket inside SpirOS's private storage, so no other app can connect to it.
 * Raw encoding only (it is a local socket, bandwidth is not an issue) plus the
 * DesktopSize pseudo-encoding so the guest can change resolution.
 */
class VncClient(
    /** Opens a connection to the RFB server, or returns null if it is not up yet. */
    private val connector: () -> Transport?,
    private val connectTimeoutMs: Long = 20_000,
    /** Upper bound on screen updates per second (Settings > Performance & battery). */
    private val maxFps: Int = 30,
    /** 16-bit colour (RGB565): half the data per frame, hardly visible on a phone. */
    private val colors16: Boolean = false,
) {
    /** A connected byte stream to the server. */
    interface Transport {
        val input: InputStream
        val output: OutputStream
        fun close()
    }

    /** Unix socket in SpirOS's own storage (the Lab's QEMU). */
    constructor(socketPath: String, connectTimeoutMs: Long = 20_000, maxFps: Int = 30) :
        this({ localSocket(socketPath) }, connectTimeoutMs, maxFps)

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    /** Increments after every screen update; read it to redraw. */
    private val _frame = MutableStateFlow(0)
    val frame: StateFlow<Int> = _frame.asStateFlow()

    // Speed figures for problem reports: frames and bytes over the last few seconds.
    @Volatile private var statFrames = 0
    @Volatile private var statBytes = 0L
    @Volatile private var statSince = System.currentTimeMillis()
    @Volatile var lastStats: String = ""
        private set

    private fun countFrame(bytes: Long) {
        statFrames++; statBytes += bytes
        val now = System.currentTimeMillis()
        if (now - statSince >= 5000) {
            val sec = (now - statSince) / 1000.0
            lastStats = "%dx%d, %s colour, %.1f frames/s, %.2f MB/s".format(width, height, if (colors16) "16-bit" else "24-bit", statFrames / sec, statBytes / sec / 1048576)
            statFrames = 0; statBytes = 0; statSince = now
        }
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var running = true
    private var socket: Transport? = null
    private var output: DataOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()

    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)
    private var buttons = 0

    fun start() {
        thread(name = "vnc", isDaemon = true) {
            runCatching { session() }
            close()
        }
    }

    fun close() {
        running = false
        _connected.value = false
        runCatching { socket?.close() }
        socket = null
        writer.shutdownNow()
    }

    // ---- input ---------------------------------------------------------------------

    /** Pointer in guest pixels; [pressed] = left button. */
    fun pointer(x: Int, y: Int, pressed: Boolean) {
        buttons = if (pressed) 1 else 0
        send {
            writeByte(5)
            writeByte(buttons)
            writeShort(x.coerceIn(0, maxOf(0, width - 1)))
            writeShort(y.coerceIn(0, maxOf(0, height - 1)))
        }
    }

    fun click(x: Int, y: Int) {
        pointer(x, y, true)
        pointer(x, y, false)
    }

    /** Right mouse button (desktop context menus); bound to a long press. */
    fun rightClick(x: Int, y: Int) {
        rawPointer(x, y, 4)
        rawPointer(x, y, 0)
    }

    /** Mouse wheel: [up] scrolls up. */
    fun scroll(x: Int, y: Int, up: Boolean) {
        rawPointer(x, y, if (up) 8 else 16)
        rawPointer(x, y, 0)
    }

    private fun rawPointer(x: Int, y: Int, mask: Int) = send {
        writeByte(5)
        writeByte(mask)
        writeShort(x.coerceIn(0, maxOf(0, width - 1)))
        writeShort(y.coerceIn(0, maxOf(0, height - 1)))
    }

    fun key(keysym: Int) {
        keyEvent(keysym, true)
        keyEvent(keysym, false)
    }

    fun type(text: String) = text.forEach { c -> key(if (c == '\n') KEY_ENTER else c.code) }

    private fun keyEvent(keysym: Int, down: Boolean) = send {
        writeByte(4)
        writeByte(if (down) 1 else 0)
        writeShort(0)
        writeInt(keysym)
    }

    private fun send(block: DataOutputStream.() -> Unit) {
        val out = output ?: return
        runCatching {
            writer.execute {
                runCatching {
                    synchronized(out) {
                        out.block()
                        out.flush()
                    }
                }
            }
        }
    }

    // ---- protocol ------------------------------------------------------------------

    private fun session() {
        val s = connectWithRetry() ?: return
        socket = s
        val input = DataInputStream(BufferedInputStream(s.input, 1 shl 16))
        val out = DataOutputStream(s.output)

        // Version
        val version = ByteArray(12).also(input::readFully)
        check(String(version).startsWith("RFB ")) { "not an RFB server" }
        out.write("RFB 003.008\n".toByteArray())

        // Security: choose "None" (the socket is private to SpirOS)
        val count = input.readUnsignedByte()
        check(count > 0) { "server refused connection" }
        val types = ByteArray(count).also(input::readFully)
        check(types.contains(1.toByte())) { "no supported security type" }
        out.writeByte(1)
        out.flush()
        check(input.readInt() == 0) { "security handshake failed" }

        // ClientInit (shared) / ServerInit
        out.writeByte(1)
        out.flush()
        resize(input.readUnsignedShort(), input.readUnsignedShort())
        input.skipBytes(16) // server pixel format; we set our own below
        input.skipBytes(input.readInt()) // desktop name

        // SetPixelFormat: little-endian true colour, 32 bpp 0x00RRGGBB or 16 bpp RGB565.
        out.writeByte(0)
        out.write(ByteArray(3))
        if (colors16) {
            out.writeByte(16); out.writeByte(16); out.writeByte(0); out.writeByte(1)
            out.writeShort(31); out.writeShort(63); out.writeShort(31)
            out.writeByte(11); out.writeByte(5); out.writeByte(0)
        } else {
            out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
            out.writeShort(255); out.writeShort(255); out.writeShort(255)
            out.writeByte(16); out.writeByte(8); out.writeByte(0)
        }
        out.write(ByteArray(3))
        // SetEncodings: CopyRect (scrolling / moved windows cost almost nothing), Raw, DesktopSize
        out.writeByte(2)
        out.writeByte(0)
        out.writeShort(3)
        out.writeInt(1)
        out.writeInt(0)
        out.writeInt(-223)
        out.flush()
        val bpp = if (colors16) 2 else 4

        output = out
        _connected.value = true
        requestUpdate(incremental = false)

        var raw = ByteArray(0)
        var lastFrame = 0L
        val minInterval = 1000L / maxFps.coerceIn(1, 120)
        while (running) {
            when (input.readUnsignedByte()) {
                0 -> { // FramebufferUpdate
                    input.skipBytes(1)
                    val rects = input.readUnsignedShort()
                    var dx0 = Int.MAX_VALUE
                    var dy0 = Int.MAX_VALUE
                    var dx1 = 0
                    var dy1 = 0
                    var frameBytes = 0L
                    repeat(rects) {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort()
                        val h = input.readUnsignedShort()
                        when (input.readInt()) {
                            0 -> {
                                val size = w * h * bpp
                                if (raw.size < size) raw = ByteArray(size)
                                input.readFully(raw, 0, size)
                                if (colors16) blit16(raw, x, y, w, h) else blit(raw, x, y, w, h)
                                frameBytes += size
                                dx0 = minOf(dx0, x); dy0 = minOf(dy0, y)
                                dx1 = maxOf(dx1, x + w); dy1 = maxOf(dy1, y + h)
                            }
                            1 -> { // CopyRect: move an area already on screen
                                val sx = input.readUnsignedShort()
                                val sy = input.readUnsignedShort()
                                copyRect(sx, sy, x, y, w, h)
                                dx0 = minOf(dx0, x); dy0 = minOf(dy0, y)
                                dx1 = maxOf(dx1, x + w); dy1 = maxOf(dy1, y + h)
                            }
                            -223 -> {
                                resize(w, h)
                                dx0 = 0; dy0 = 0; dx1 = w; dy1 = h
                            }
                            else -> error("unsupported encoding")
                        }
                    }
                    // Copy only the area that changed into the on-screen bitmap.
                    dx1 = minOf(dx1, width); dy1 = minOf(dy1, height)
                    if (dx1 > dx0 && dy1 > dy0) {
                        _bitmap.value?.setPixels(pixels, dy0 * width + dx0, width, dx0, dy0, dx1 - dx0, dy1 - dy0)
                        _frame.value += 1
                    }
                    countFrame(frameBytes)
                    // Ask for the next update no sooner than the frame-rate cap allows:
                    // less work for both the Linux side and the phone.
                    val wait = minInterval - (System.currentTimeMillis() - lastFrame)
                    if (wait > 0) Thread.sleep(wait)
                    lastFrame = System.currentTimeMillis()
                    requestUpdate(incremental = true)
                }
                1 -> { // SetColourMapEntries (unused with true colour)
                    input.skipBytes(3)
                    input.skipBytes(input.readUnsignedShort() * 6)
                }
                2 -> Unit // Bell
                3 -> { // ServerCutText
                    input.skipBytes(3)
                    input.skipBytes(input.readInt())
                }
                else -> error("unknown server message")
            }
        }
    }

    private fun requestUpdate(incremental: Boolean) = send {
        writeByte(3)
        writeByte(if (incremental) 1 else 0)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
    }

    private fun resize(w: Int, h: Int) {
        width = w
        height = h
        pixels = IntArray(w * h)
        _bitmap.value = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
    }

    /**
     * Copies a raw rectangle into the frame. The server sends little-endian 0x00RRGGBB,
     * so each row is one bulk copy of ints, then the alpha byte is set (much faster
     * than assembling every pixel from bytes, which matters at 30 frames a second).
     */
    private fun blit(raw: ByteArray, x: Int, y: Int, w: Int, h: Int) {
        val ints = java.nio.ByteBuffer.wrap(raw, 0, w * h * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val cols = minOf(w, width - x)
        if (cols <= 0) return
        for (row in 0 until h) {
            val yy = y + row
            if (yy >= height) break
            val start = yy * width + x
            ints.position(row * w)
            ints.get(pixels, start, cols)
            var p = start
            val end = start + cols
            while (p < end) {
                pixels[p] = pixels[p] or OPAQUE
                p++
            }
        }
    }

    /** RGB565 little-endian to opaque ARGB. */
    private fun blit16(raw: ByteArray, x: Int, y: Int, w: Int, h: Int) {
        val cols = minOf(w, width - x)
        if (cols <= 0) return
        for (row in 0 until h) {
            val yy = y + row
            if (yy >= height) break
            var src = row * w * 2
            var p = yy * width + x
            val end = p + cols
            while (p < end) {
                val v = (raw[src].toInt() and 0xFF) or ((raw[src + 1].toInt() and 0xFF) shl 8)
                val r = (v ushr 11) and 31
                val g = (v ushr 5) and 63
                val b = v and 31
                pixels[p] = OPAQUE or (((r shl 3) or (r ushr 2)) shl 16) or (((g shl 2) or (g ushr 4)) shl 8) or ((b shl 3) or (b ushr 2))
                src += 2
                p++
            }
        }
    }

    /** CopyRect: the server moved an area (scroll, window drag); copy it locally. */
    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        val cols = minOf(w, width - x, width - sx)
        if (cols <= 0) return
        val rows = minOf(h, height - y, height - sy)
        if (rows <= 0) return
        // Overlapping areas: copy rows in the direction that never reads a row already overwritten.
        val order = if (sy < y) (rows - 1 downTo 0) else (0 until rows)
        for (r in order) System.arraycopy(pixels, (sy + r) * width + sx, pixels, (y + r) * width + x, cols)
    }

    private fun connectWithRetry(): Transport? {
        repeat((connectTimeoutMs / 250).toInt()) {
            if (!running) return null
            runCatching { connector() }.getOrNull()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    companion object {
        private const val OPAQUE = 0xFF shl 24
        private fun localSocket(path: String): Transport? {
            val s = LocalSocket()
            return try {
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                object : Transport {
                    override val input: InputStream = s.inputStream
                    override val output: OutputStream = s.outputStream
                    override fun close() = s.close()
                }
            } catch (e: Exception) {
                runCatching { s.close() }
                null
            }
        }

        /** A socket handed over by another process (the SpirOS Engine's desktop). */
        fun fromDescriptor(pfd: ParcelFileDescriptor): Transport = object : Transport {
            override val input: InputStream = FileInputStream(pfd.fileDescriptor)
            override val output: OutputStream = FileOutputStream(pfd.fileDescriptor)
            override fun close() {
                // Wake the blocked reader before releasing the descriptor.
                runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) }
                runCatching { pfd.close() }
            }
        }

        const val KEY_ENTER = 0xFF0D
        const val KEY_BACKSPACE = 0xFF08
        const val KEY_TAB = 0xFF09
        const val KEY_ESCAPE = 0xFF1B
        const val KEY_LEFT = 0xFF51
        const val KEY_UP = 0xFF52
        const val KEY_RIGHT = 0xFF53
        const val KEY_DOWN = 0xFF54
    }
}
