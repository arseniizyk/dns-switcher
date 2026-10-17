package com.quickdns.switcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object DnsPingUtil {

    // Минимальный DNS query пакет для запроса A-записи домена google.com (Transaction ID: 0x1A2B)
    private val TEST_DNS_QUERY = byteArrayOf(
        0x1A.toByte(), 0x2B.toByte(), // ID
        0x01.toByte(), 0x00.toByte(), // Flags: Standard query, recursion desired
        0x00.toByte(), 0x01.toByte(), // Questions: 1
        0x00.toByte(), 0x00.toByte(), // Answer RRs: 0
        0x00.toByte(), 0x00.toByte(), // Authority RRs: 0
        0x00.toByte(), 0x00.toByte(), // Additional RRs: 0
        // Name: google.com (6google3com0)
        0x06.toByte(), 0x67.toByte(), 0x6F.toByte(), 0x6F.toByte(), 0x67.toByte(), 0x6C.toByte(), 0x65.toByte(),
        0x03.toByte(), 0x63.toByte(), 0x6F.toByte(), 0x6D.toByte(),
        0x00.toByte(),
        0x00.toByte(), 0x01.toByte(), // Type: A
        0x00.toByte(), 0x01.toByte()  // Class: IN
    )

    /**
     * Измеряет RTT (время отклика в мс) до целевого DNS сервера.
     * Возвращает задержку в миллисекундах или -1 при недоступности/таймауте.
     */
    suspend fun measureDnsLatency(target: String, isDoT: Boolean): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            if (isDoT) {
                val socket = Socket()
                socket.connect(InetSocketAddress(target, 853), 1500)
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(socket, target, 853, true) as SSLSocket
                sslSocket.soTimeout = 1500
                sslSocket.startHandshake()
                val latency = System.currentTimeMillis() - start
                try {
                    sslSocket.close()
                } catch (ignored: Exception) {}
                latency
            } else {
                val socket = DatagramSocket()
                socket.soTimeout = 1500
                val address = InetAddress.getByName(target)
                val packet = DatagramPacket(TEST_DNS_QUERY, TEST_DNS_QUERY.size, address, 53)
                socket.send(packet)

                val buffer = ByteArray(512)
                val recvPacket = DatagramPacket(buffer, buffer.size)
                socket.receive(recvPacket)
                val latency = System.currentTimeMillis() - start
                socket.close()
                latency
            }
        } catch (e: Exception) {
            -1L
        }
    }
}
