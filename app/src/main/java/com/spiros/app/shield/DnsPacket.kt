package com.spiros.app.shield

/**
 * Minimal IPv4/UDP/DNS handling for the Shield tunnel. Only packets to the fake DNS
 * server are routed into the tunnel, so everything here is a DNS query.
 */
internal object DnsPacket {

    class Query(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        /** The DNS message (UDP payload). */
        val dns: ByteArray,
        /** Queried name, or null if it could not be parsed. */
        val name: String?,
        /** Offset just past the question section. */
        val questionEnd: Int,
    )

    fun parse(packet: ByteArray, length: Int): Query? {
        if (length < 20 || (packet[0].toInt() shr 4) != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet[9].toInt() != 17 || length < ihl + 8 + 12) return null
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        if (dstPort != 53) return null
        val dns = packet.copyOfRange(ihl + 8, length)
        val (name, end) = parseName(dns)
        return Query(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dns = dns,
            name = name,
            questionEnd = end,
        )
    }

    /** A "no such domain" answer to [query]. */
    fun nxdomain(query: Query): ByteArray {
        val end = if (query.questionEnd in 12..query.dns.size) query.questionEnd else 12
        val reply = query.dns.copyOf(end)
        reply[2] = (reply[2].toInt() or 0x80).toByte() // QR = response, keep opcode and RD
        reply[3] = (0x80 or 0x03).toByte() // RA, RCODE = NXDOMAIN
        for (i in 6 until 12) reply[i] = 0 // no answer / authority / additional records
        return reply
    }

    /** Wrap a DNS reply in IPv4 + UDP headers going back to the querying app. */
    fun wrapReply(query: Query, dns: ByteArray): ByteArray {
        val total = 20 + 8 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        System.arraycopy(query.dstIp, 0, p, 12, 4)
        System.arraycopy(query.srcIp, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, query.dstPort)
        put16(p, 22, query.srcPort)
        put16(p, 24, 8 + dns.size)
        // UDP checksum 0 = not computed (allowed for IPv4).
        System.arraycopy(dns, 0, p, 28, dns.size)
        return p
    }

    private fun parseName(dns: ByteArray): Pair<String?, Int> {
        if (dns.size < 12 || u16(dns, 4) < 1) return null to 12
        val labels = ArrayList<String>()
        var pos = 12
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            if (len and 0xC0 != 0 || pos + 1 + len > dns.size || labels.size > 127) return null to 12
            labels += String(dns, pos + 1, len, Charsets.US_ASCII)
            pos += 1 + len
        }
        return labels.joinToString(".").ifEmpty { null } to (pos + 4) // QTYPE + QCLASS
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, at: Int, value: Int) {
        b[at] = (value shr 8).toByte()
        b[at + 1] = value.toByte()
    }

    private fun checksum(b: ByteArray, from: Int, len: Int): Int {
        var sum = 0
        var i = from
        while (i < from + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
