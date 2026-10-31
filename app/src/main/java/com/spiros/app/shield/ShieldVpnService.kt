package com.spiros.app.shield

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The Shield: a local, DNS-only VPN. Only traffic to a fake DNS address enters the
 * tunnel; queries for blocked domains get "no such domain", the rest are forwarded to
 * the chosen resolver over a socket that bypasses the tunnel. App traffic itself
 * never passes through SpirOS and nothing is sent to any SpirOS server.
 *
 * In the main profile the user turns it on (system VPN consent). Inside the isolated
 * space SpirOS, as Profile Owner, sets it as always-on VPN, so it covers every app there.
 */
class ShieldVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopShield()
            stopSelf()
            return START_NOT_STICKY
        }
        startShield()
        return START_STICKY
    }

    override fun onRevoke() {
        stopShield()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopShield()
        super.onDestroy()
    }

    @Synchronized
    private fun startShield() {
        if (tunnel != null) return
        val fd = Builder()
            .setSession("SpirOS Shield")
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(FAKE_DNS)
            .addRoute(FAKE_DNS, 32)
            .setMtu(1500)
            .setBlocking(true)
            .apply { runCatching { addDisallowedApplication(packageName) } }
            .establish() ?: return // not authorised (consent missing)
        tunnel = fd
        pool = Executors.newFixedThreadPool(8)
        _running.value = true
        reader = thread(name = "shield-dns", isDaemon = true) { pump(fd) }
    }

    @Synchronized
    private fun stopShield() {
        reader?.interrupt()
        reader = null
        runCatching { tunnel?.close() }
        tunnel = null
        pool?.shutdownNow()
        pool = null
        _running.value = false
    }

    private fun pump(fd: ParcelFileDescriptor) {
        Blocklist.ensureLoaded(this)
        if (ShieldSettings.useFullList(this) && Blocklist.fullListIsStale(this)) {
            thread(isDaemon = true) { Blocklist.downloadFull(this) }
        }
        val upstream = InetAddress.getByName(ShieldSettings.upstream(this).address)
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                break // tunnel closed
            }
            if (length <= 0) continue
            val query = DnsPacket.parse(buffer, length) ?: continue
            val name = query.name
            if (name != null && Blocklist.isBlocked(name)) {
                write(output, DnsPacket.wrapReply(query, DnsPacket.nxdomain(query)))
                recordBlocked(name)
            } else {
                runCatching { pool?.execute { forward(query, upstream, output) } }
            }
        }
    }

    private fun forward(query: DnsPacket.Query, upstream: InetAddress, output: FileOutputStream) {
        runCatching {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 5000
                socket.send(DatagramPacket(query.dns, query.dns.size, upstream, 53))
                val reply = ByteArray(4096)
                val packet = DatagramPacket(reply, reply.size)
                socket.receive(packet)
                write(output, DnsPacket.wrapReply(query, reply.copyOf(packet.length)))
            }
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        synchronized(output) { runCatching { output.write(packet) } }
    }

    companion object {
        const val ACTION_START = "com.spiros.app.shield.START"
        const val ACTION_STOP = "com.spiros.app.shield.STOP"
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"
        private const val RECENT = 30

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _blockedCount = MutableStateFlow(0)
        val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

        private val _recent = MutableStateFlow<List<String>>(emptyList())
        val recent: StateFlow<List<String>> = _recent.asStateFlow()

        private fun recordBlocked(name: String) {
            _blockedCount.value += 1
            _recent.value = (listOf(name) + _recent.value.filter { it != name }).take(RECENT)
        }

        fun start(context: Context) {
            context.startService(Intent(context, ShieldVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShieldVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
