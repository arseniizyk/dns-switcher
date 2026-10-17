package com.quickdns.switcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Надежный VpnService для быстрой смены DNS без Root.
 *
 * Архитектурное решение проблемы "сеть отпадает":
 * 1. TUN интерфейс настраивается с адресом 10.0.0.2/32.
 * 2. DNS серверы назначаются на 10.0.0.1 и 10.0.0.3, и ТОЛЬКО эти два адреса маршрутизируются (addRoute).
 * 3. Вызывается builder.allowBypass() и addDisallowedApplication(packageName) — благодаря этому
 *    весь обычный интернет-трафик (HTTP, HTTPS, видео, Telegram, игры) идет напрямую через Wi-Fi / LTE!
 * 4. Вызывается setUnderlyingNetworks(null) для корректной работы системного сетевого монитора Android.
 * 5. TUN интерфейс перехватывает исключительно DNS-запросы к 10.0.0.1 / 10.0.0.3 (порт 53 UDP).
 * 6. Запросы пересылаются на вышестоящий DNS через защищенный сокет protect():
 *    - Сначала пробуется прямой UDP (порт 53)
 *    - Если порт 53 недоступен (блокировка провайдером/DoT-хост) — автоматически используется DoT (порт 853 TLS)
 *    - В крайнем случае безопасный fallback на резервный DNS, чтобы интернет никогда не пропадал.
 * 7. В ответном пакете Source IP устанавливается в точности равным тому IP, на который система посылала запрос,
 *    с корректно рассчитанными контрольными суммами IPv4 и UDP.
 */
class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var inStream: FileInputStream? = null
    private var outStream: FileOutputStream? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dnsThreadPool = Executors.newFixedThreadPool(8)

    @Volatile
    private var isRunning = false
    private var relayThread: Thread? = null

    @Volatile
    private var upstreamTargetName: String = "Cloudflare"

    @Volatile
    private var upstreamTargetHost: String = "1.1.1.1"

    @Volatile
    private var upstreamResolvedAddresses: List<InetAddress> = listOf(InetAddress.getByName("1.1.1.1"))

    @Volatile
    private var isDoTHost: Boolean = false

    companion object {
        private const val TAG = "DnsVpnService"
        const val ACTION_START = "com.quickdns.switcher.ACTION_START"
        const val ACTION_STOP = "com.quickdns.switcher.ACTION_STOP"
        const val EXTRA_DNS_IP = "EXTRA_DNS_IP"
        const val EXTRA_DNS_NAME = "EXTRA_DNS_NAME"
        const val EXTRA_DNS_HOSTNAME = "EXTRA_DNS_HOSTNAME"
        private const val NOTIFICATION_CHANNEL_ID = "quick_dns_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // Виртуальные IP-адреса локального TUN DNS proxy
        const val TUN_INTERFACE_IP = "10.0.0.2"
        const val TUN_PRIMARY_DNS = "10.0.0.1"
        const val TUN_SECONDARY_DNS = "10.0.0.3"

        private val _vpnState = MutableStateFlow(
            VpnStatus(
                isActive = false,
                currentDnsIp = null,
                currentDnsName = null,
                currentHostname = null
            )
        )
        val vpnState: StateFlow<VpnStatus> = _vpnState.asStateFlow()

        fun start(context: Context, dnsTarget: String, dnsName: String = "Пользовательский", hostname: String? = null) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DNS_IP, dnsTarget)
                putExtra(EXTRA_DNS_NAME, dnsName)
                if (hostname != null) {
                    putExtra(EXTRA_DNS_HOSTNAME, hostname)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        val dnsTarget = intent?.getStringExtra(EXTRA_DNS_IP) ?: "1.1.1.1"
        val dnsName = intent?.getStringExtra(EXTRA_DNS_NAME) ?: "Cloudflare"
        val hostname = intent?.getStringExtra(EXTRA_DNS_HOSTNAME)

        serviceScope.launch {
            setupAndStartVpn(dnsTarget, dnsName, hostname)
        }

        return START_STICKY
    }

    private suspend fun setupAndStartVpn(dnsTarget: String, dnsName: String, explicitHostname: String?) {
        try {
            val cleanTarget = dnsTarget.trim()
                .removePrefix("https://")
                .removePrefix("tls://")
                .removePrefix("quic://")
                .split("/")[0]
                .split(":")[0]

            val isHostname = cleanTarget.any { it.isLetter() }
            val hostnameToDisplay = explicitHostname ?: if (isHostname) cleanTarget else null

            upstreamTargetName = dnsName
            upstreamTargetHost = cleanTarget
            isDoTHost = isHostname || dnsName.contains("DoT", ignoreCase = true) || dnsName.contains("DoQ", ignoreCase = true)

            // Резолвим IP адрес апстрима в отдельном потоке
            val resolvedList = withContext(Dispatchers.IO) {
                resolveUpstream(cleanTarget)
            }
            upstreamResolvedAddresses = resolvedList

            val displayIp = resolvedList.firstOrNull()?.hostAddress ?: cleanTarget
            Log.i(TAG, "Подготовка DNS: $dnsName ($cleanTarget) -> $displayIp")

            // Если VPN уже стабильно запущен — мгновенное переключение апстрима без разрыва сетевого TUN!
            if (vpnInterface != null && isRunning) {
                Log.i(TAG, "Мгновенное переключение активного DNS апстрима на лету...")
                updateNotificationAndState(displayIp, dnsName, hostnameToDisplay)
                return
            }

            // Иначе создаем новую чистую сессию
            stopRelay()

            val builder = Builder()
                .setSession("QuickDNS Switcher")
                .setMtu(1500)
                .addAddress(TUN_INTERFACE_IP, 32)
                // Назначаем виртуальные DNS серверы
                .addDnsServer(TUN_PRIMARY_DNS)
                .addDnsServer(TUN_SECONDARY_DNS)
                // Маршрутизируем ТОЛЬКО эти виртуальные DNS адреса в TUN!
                .addRoute(TUN_PRIMARY_DNS, 32)
                .addRoute(TUN_SECONDARY_DNS, 32)
                .setBlocking(true)

            // Разрешаем всему остальному трафику обходить VPN (критично для стабильности сети!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.allowBypass()
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось добавить packageName в disallowed", e)
                }
            }

            // Уведомляем систему о физической сети
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(null)
            }

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                inStream = FileInputStream(vpnInterface!!.fileDescriptor)
                outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                isRunning = true

                startDnsRelay()
                updateNotificationAndState(displayIp, dnsName, hostnameToDisplay)
                Log.i(TAG, "QuickDNS VpnService успешно запущен и готов к работе!")
            } else {
                Log.e(TAG, "Не удалось создать VPN интерфейс: establish() вернул null")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска VpnService", e)
            stopVpn()
        }
    }

    private fun resolveUpstream(target: String): List<InetAddress> {
        return try {
            val list = InetAddress.getAllByName(target).toList()
            if (list.isNotEmpty()) list else listOf(InetAddress.getByName(fallbackIpForHost(target)))
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка резолва $target: ${e.message}, используем резервный IP")
            listOf(InetAddress.getByName(fallbackIpForHost(target)))
        }
    }

    private fun fallbackIpForHost(host: String): String {
        return when (host.lowercase()) {
            "geohide.ru" -> "193.233.112.67"
            "dns.adguard.com" -> "94.140.14.14"
            "one.one.one.one", "cloudflare-dns.com" -> "1.1.1.1"
            "dns.google" -> "8.8.8.8"
            "dns.quad9.net" -> "9.9.9.9"
            else -> "1.1.1.1"
        }
    }

    /**
     * Поток чтения пакетов из TUN интерфейса
     */
    private fun startDnsRelay() {
        relayThread = Thread({
            val inS = inStream ?: return@Thread
            val outS = outStream ?: return@Thread
            val packetBuffer = ByteArray(32767)

            Log.i(TAG, "Поток перехвата DNS пакетов запущен")

            while (isRunning && vpnInterface != null) {
                try {
                    val length = inS.read(packetBuffer)
                    if (length <= 0) break

                    // Проверяем IPv4 (version 4) и протокол UDP (17)
                    val version = (packetBuffer[0].toInt() and 0xF0) shr 4
                    if (version != 4) continue

                    val protocol = packetBuffer[9].toInt() and 0xFF
                    if (protocol != 17) continue

                    val ipHeaderLen = (packetBuffer[0].toInt() and 0x0F) * 4
                    if (length < ipHeaderLen + 8) continue

                    // Читаем порт источника и порт назначения UDP
                    val srcPort = ((packetBuffer[ipHeaderLen].toInt() and 0xFF) shl 8) or
                            (packetBuffer[ipHeaderLen + 1].toInt() and 0xFF)
                    val destPort = ((packetBuffer[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                            (packetBuffer[ipHeaderLen + 3].toInt() and 0xFF)

                    // Обрабатываем только запросы на порт 53 (DNS)
                    if (destPort == 53) {
                        val udpLength = ((packetBuffer[ipHeaderLen + 4].toInt() and 0xFF) shl 8) or
                                (packetBuffer[ipHeaderLen + 5].toInt() and 0xFF)

                        val rawPayloadLen = udpLength - 8
                        val dnsPayloadLen = minOf(rawPayloadLen, length - ipHeaderLen - 8)
                        if (dnsPayloadLen <= 0) continue

                        val dnsQuery = ByteArray(dnsPayloadLen)
                        System.arraycopy(packetBuffer, ipHeaderLen + 8, dnsQuery, 0, dnsPayloadLen)

                        // Исходный IP клиента (10.0.0.2)
                        val clientIp = ByteArray(4)
                        System.arraycopy(packetBuffer, 12, clientIp, 0, 4)

                        // Виртуальный DNS IP, на который система посылала запрос (10.0.0.1 или 10.0.0.3)
                        val queriedServerIp = ByteArray(4)
                        System.arraycopy(packetBuffer, 16, queriedServerIp, 0, 4)

                        // Пересылаем запрос в пуле рабочих потоков
                        dnsThreadPool.execute {
                            resolveAndReply(
                                dnsQuery = dnsQuery,
                                clientIp = clientIp,
                                clientPort = srcPort,
                                queriedServerIp = queriedServerIp,
                                outStream = outS
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.w(TAG, "Чтение из TUN прервано или ошибка: ${e.message}")
                    }
                    break
                }
            }
            Log.i(TAG, "Поток перехвата DNS пакетов остановлен")
        }, "DnsRelayWorker")

        relayThread?.start()
    }

    /**
     * Разрешает DNS запрос через апстрим (UDP или DoT) и отправляет ответ в TUN
     */
    private fun resolveAndReply(
        dnsQuery: ByteArray,
        clientIp: ByteArray,
        clientPort: Int,
        queriedServerIp: ByteArray,
        outStream: FileOutputStream
    ) {
        val response = queryUpstreamWithFallback(dnsQuery) ?: return

        try {
            val replyPacket = buildIpv4UdpPacket(
                srcIp = queriedServerIp, // Ответ строго от того IP, который опрашивал клиент (10.0.0.1)
                dstIp = clientIp,        // Клиентский IP (10.0.0.2)
                srcPort = 53,            // DNS порт
                dstPort = clientPort,    // Порт клиента
                payload = response
            )

            synchronized(outStream) {
                if (isRunning && vpnInterface != null) {
                    outStream.write(replyPacket)
                    outStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка записи ответа в TUN", e)
        }
    }

    /**
     * Многоуровневый резолвер: UDP -> DoT -> Fallback DNS
     */
    private fun queryUpstreamWithFallback(query: ByteArray): ByteArray? {
        val targets = upstreamResolvedAddresses
        val targetHost = upstreamTargetHost
        val preferDoT = isDoTHost

        // 1. Если это DoT/DoQ хост (например geohide.ru, dns.adguard.com) — пробуем DoT (TLS на 853)
        if (preferDoT) {
            val dotResp = queryDnsOverTls(query, targetHost, targets.firstOrNull())
            if (dotResp != null) return dotResp
        }

        // 2. Пробуем прямой UDP через защищенный сокет
        for (target in targets) {
            val udpResp = queryUdp(query, target, 53, timeoutMs = 2000)
            if (udpResp != null) return udpResp
        }

        // 3. Если UDP не ответил, пробуем DoT на порту 853
        val dotResp = queryDnsOverTls(query, targetHost, targets.firstOrNull())
        if (dotResp != null) return dotResp

        // 4. Защита от потери интернета: безопасный fallback на резервный Cloudflare 1.1.1.1
        try {
            val fallbackAddr = InetAddress.getByName("1.1.1.1")
            val fallbackResp = queryUdp(query, fallbackAddr, 53, timeoutMs = 2500)
            if (fallbackResp != null) {
                Log.d(TAG, "Использован fallback DNS 1.1.1.1 для сохранения доступа в сеть")
                return fallbackResp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback DNS query failed", e)
        }

        return null
    }

    /**
     * Отправка запроса по стандартному UDP через защищенный сокет
     */
    private fun queryUdp(query: ByteArray, address: InetAddress, port: Int, timeoutMs: Int): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = timeoutMs

            val sendPacket = DatagramPacket(query, query.size, address, port)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            val len = receivePacket.length
            if (len > 0) {
                val result = ByteArray(len)
                System.arraycopy(receiveBuffer, 0, result, 0, len)
                result
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Отправка запроса по DNS-over-TLS (RFC 7858, порт 853)
     */
    private fun queryDnsOverTls(query: ByteArray, hostName: String, address: InetAddress?): ByteArray? {
        var sslSocket: SSLSocket? = null
        return try {
            val targetAddr = address ?: InetAddress.getByName(hostName)
            val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val rawSocket = DatagramSocket()
            rawSocket.close()

            // Создаем TCP сокет и защищаем его
            val plainSocket = java.net.Socket()
            protect(plainSocket)
            plainSocket.connect(InetSocketAddress(targetAddr, 853), 2500)
            plainSocket.soTimeout = 3000

            // Накладываем TLS
            sslSocket = socketFactory.createSocket(
                plainSocket,
                hostName,
                853,
                true
            ) as SSLSocket

            sslSocket.startHandshake()

            val out = DataOutputStream(BufferedOutputStream(sslSocket.getOutputStream()))
            val inStream = DataInputStream(BufferedInputStream(sslSocket.getInputStream()))

            // В RFC 7858 запрос предваряется 2 байтами длины
            out.writeShort(query.size)
            out.write(query)
            out.flush()

            val responseLen = inStream.readUnsignedShort()
            if (responseLen in 1..4096) {
                val resp = ByteArray(responseLen)
                inStream.readFully(resp)
                resp
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "DoT fallback: ${e.message}")
            null
        } finally {
            try {
                sslSocket?.close()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Сборка валидного IPv4 + UDP пакета с расчетом контрольных сумм IP и UDP
     */
    private fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payload.size
        val packet = ByteArray(totalLen)

        // 1. Заголовок IPv4
        packet[0] = 0x45.toByte() // Version: 4, IHL: 5 (20 байт)
        packet[1] = 0x00.toByte() // ToS / DSCP
        packet[2] = ((totalLen shr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x1A.toByte() // Идентификатор
        packet[5] = 0x2B.toByte()
        packet[6] = 0x40.toByte() // Flags: Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // Protocol: UDP

        // Исходный IP (10.0.0.1)
        System.arraycopy(srcIp, 0, packet, 12, 4)
        // IP назначения (10.0.0.2)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        // Контрольная сумма IP (RFC 1071)
        var ipSum = 0
        for (i in 0 until ipHeaderLen step 2) {
            if (i != 10) {
                ipSum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
        }
        while (ipSum shr 16 > 0) {
            ipSum = (ipSum and 0xFFFF) + (ipSum shr 16)
        }
        val ipChecksum = (ipSum.inv()) and 0xFFFF
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // 2. Заголовок UDP
        val udpOffset = ipHeaderLen
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()

        val udpLen = udpHeaderLen + payload.size
        packet[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        packet[udpOffset + 6] = 0x00.toByte() // Контрольная сумма (пока 0)
        packet[udpOffset + 7] = 0x00.toByte()

        // Полезная нагрузка DNS
        System.arraycopy(payload, 0, packet, udpOffset + udpHeaderLen, payload.size)

        // 3. Расчет контрольной суммы UDP с псевдо-заголовком (RFC 768)
        var udpSum = 0
        // Псевдо-заголовок: Src IP
        udpSum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        udpSum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        // Dst IP
        udpSum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        udpSum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        // Protocol (17) и UDP length
        udpSum += 17
        udpSum += udpLen

        // Заголовок UDP и данные
        for (i in udpOffset until totalLen step 2) {
            if (i != udpOffset + 6) {
                val high = packet[i].toInt() and 0xFF
                val low = if (i + 1 < totalLen) packet[i + 1].toInt() and 0xFF else 0
                udpSum += (high shl 8) or low
            }
        }

        while (udpSum shr 16 > 0) {
            udpSum = (udpSum and 0xFFFF) + (udpSum shr 16)
        }
        var udpChecksum = (udpSum.inv()) and 0xFFFF
        if (udpChecksum == 0) udpChecksum = 0xFFFF // 0x0000 резервировано как "нет суммы", 0xFFFF = 0

        packet[udpOffset + 6] = ((udpChecksum shr 8) and 0xFF).toByte()
        packet[udpOffset + 7] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    private fun updateNotificationAndState(resolvedIp: String, dnsName: String, hostname: String?) {
        val notification = createNotification(resolvedIp, dnsName, hostname)
        startForeground(NOTIFICATION_ID, notification)

        _vpnState.value = VpnStatus(
            isActive = true,
            currentDnsIp = resolvedIp,
            currentDnsName = dnsName,
            currentHostname = hostname
        )
    }

    private fun stopRelay() {
        isRunning = false
        try {
            relayThread?.interrupt()
        } catch (ignored: Exception) {}
        relayThread = null

        try {
            inStream?.close()
        } catch (ignored: Exception) {}
        inStream = null

        try {
            outStream?.close()
        } catch (ignored: Exception) {}
        outStream = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка закрытия vpnInterface", e)
        } finally {
            vpnInterface = null
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Остановка QuickDNS...")
        stopRelay()
        _vpnState.value = VpnStatus(
            isActive = false,
            currentDnsIp = null,
            currentDnsName = null,
            currentHostname = null
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        try {
            dnsThreadPool.shutdown()
            dnsThreadPool.awaitTermination(500, TimeUnit.MILLISECONDS)
        } catch (ignored: Exception) {}
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "QuickDNS Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о работе DNS перенаправления"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(dnsIp: String, dnsName: String, hostname: String?): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val desc = if (hostname != null) "DNS: $dnsName ($hostname → $dnsIp)" else "DNS: $dnsName ($dnsIp)"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("QuickDNS Switcher Активен")
            .setContentText(desc)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

data class VpnStatus(
    val isActive: Boolean,
    val currentDnsIp: String?,
    val currentDnsName: String?,
    val currentHostname: String? = null
)
