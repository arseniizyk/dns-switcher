package com.quickdns.switcher

import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Простой и надежный Quick Settings Toggle для включения/выключения активного DNS в один клик.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickDnsTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            DnsVpnService.vpnState.collect { status ->
                updateTile(status)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentStatus = DnsVpnService.vpnState.value

        if (currentStatus.isActive) {
            // Если включен — мгновенно выключаем
            DnsVpnService.stop(this)
        } else {
            // Если выключен — проверяем VPN-разрешение и включаем выбранный сервер
            if (VpnService.prepare(this) != null) {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pi = android.app.PendingIntent.getActivity(
                            this, 0, launchIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        startActivityAndCollapse(pi)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(launchIntent)
                    }
                }
                return
            }

            val allServers = DnsPreferences.getActivePresets(this) + DnsPreferences.getCustomDnsList(this)
            val savedId = DnsPreferences.getSelectedDnsId(this)
            val target = allServers.firstOrNull { it.id == savedId }
                ?: allServers.firstOrNull()
                ?: DnsPreferences.DEFAULT_PRESETS.first()

            DnsVpnService.start(this, target.target, target.name, target.hostname)
        }
    }

    private fun updateTile(status: VpnStatus) {
        val tile = qsTile ?: return
        if (status.isActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = status.currentDnsName ?: "DNS Switcher"
            tile.subtitle = if (status.isUsingFallback) "Резерв (1.1.1.1)" else "Активен"
        } else {
            val allServers = DnsPreferences.getActivePresets(this) + DnsPreferences.getCustomDnsList(this)
            val savedId = DnsPreferences.getSelectedDnsId(this)
            val savedName = allServers.firstOrNull { it.id == savedId }?.name ?: "DNS Switcher"

            tile.state = Tile.STATE_INACTIVE
            tile.label = savedName
            tile.subtitle = "Отключен"
        }
        tile.updateTile()
    }
}
