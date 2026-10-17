package com.quickdns.switcher

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickdns.switcher.ui.theme.QuickDNSSwitcherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DnsItem(
    val id: String,
    val name: String,
    val target: String,
    val description: String,
    val hostname: String? = null,
    val isCustom: Boolean = false,
    val isDoT: Boolean = false
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuickDNSSwitcherTheme {
                var showSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(750)
                    showSplash = false
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSplash,
                        exit = androidx.compose.animation.fadeOut(animationSpec = tween(220))
                    ) {
                        NetworkCoreSplash()
                    }

                    if (!showSplash) {
                        QuickDnsScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkCoreSplash() {
    val coreScale = remember { androidx.compose.animation.core.Animatable(0.2f) }
    val nodeAlpha = remember { androidx.compose.animation.core.Animatable(0.0f) }
    val nodeDistance = remember { androidx.compose.animation.core.Animatable(1.4f) }

    LaunchedEffect(Unit) {
        launch {
            nodeAlpha.animateTo(1f, animationSpec = tween(250))
            nodeAlpha.animateTo(0.2f, animationSpec = tween(400))
        }
        launch {
            nodeDistance.animateTo(0.9f, animationSpec = tween(550, easing = FastOutSlowInEasing))
        }
        launch {
            coreScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(500, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            val dist = nodeDistance.value

            // 3 внешних узла, сходящиеся к центру
            Box(
                modifier = Modifier
                    .offset(y = (-44 * dist).dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981).copy(alpha = nodeAlpha.value))
            )
            Box(
                modifier = Modifier
                    .offset(x = (-38 * dist).dp, y = (32 * dist).dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981).copy(alpha = nodeAlpha.value))
            )
            Box(
                modifier = Modifier
                    .offset(x = (38 * dist).dp, y = (32 * dist).dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981).copy(alpha = nodeAlpha.value))
            )

            // Центральное светящееся ядро безопасности
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .scale(coreScale.value)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF6366F1), Color(0xFF4F46E5))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickDnsScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val vpnStatus by DnsVpnService.vpnState.collectAsState()

    var activePresets by remember {
        mutableStateOf(DnsPreferences.getActivePresets(context))
    }

    var customDnsList by remember {
        mutableStateOf(DnsPreferences.getCustomDnsList(context))
    }

    var selectedDns by remember {
        val savedId = DnsPreferences.getSelectedDnsId(context)
        val all = activePresets + customDnsList
        val initial = all.firstOrNull { it.id == savedId }
            ?: activePresets.firstOrNull()
            ?: customDnsList.firstOrNull()
            ?: DnsPreferences.DEFAULT_PRESETS.first()
        mutableStateOf(initial)
    }

    var pingMap by remember { mutableStateOf(mapOf<String, Long>()) }
    var isCheckingPings by remember { mutableStateOf(false) }

    fun refreshAllPings() {
        coroutineScope.launch {
            isCheckingPings = true
            val all = activePresets + customDnsList
            val results = mutableMapOf<String, Long>()
            all.forEach { dns ->
                val rtt = DnsPingUtil.measureDnsLatency(dns.target, dns.isDoT)
                results[dns.id] = rtt
            }
            pingMap = results
            isCheckingPings = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAllPings()
    }

    // Статус применения DNS и проверка пинга активного сервера
    var activePingMs by remember { mutableStateOf<Long?>(null) }
    var isVerifyingActiveDns by remember { mutableStateOf(false) }

    LaunchedEffect(vpnStatus.isActive, vpnStatus.currentDnsIp, vpnStatus.currentHostname) {
        if (vpnStatus.isActive) {
            isVerifyingActiveDns = true
            val target = vpnStatus.currentHostname ?: vpnStatus.currentDnsIp ?: selectedDns.target
            val isDoT = selectedDns.isDoT || target.any { it.isLetter() }
            val rtt = DnsPingUtil.measureDnsLatency(target, isDoT)
            activePingMs = if (rtt >= 0) rtt else null
            isVerifyingActiveDns = false
        } else {
            activePingMs = null
            isVerifyingActiveDns = false
        }
    }

    var customTargetInput by remember { mutableStateOf("") }
    var customNameInput by remember { mutableStateOf("") }
    var isInputError by remember { mutableStateOf(false) }

    var pendingDnsToStart by remember { mutableStateOf<DnsItem?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDnsToStart?.let { dns ->
                DnsVpnService.start(context, dns.target, dns.name, dns.hostname)
                selectedDns = dns
                DnsPreferences.saveSelectedDnsId(context, dns.id)
            }
        } else {
            Toast.makeText(context, "Разрешение на VPN отклонено", Toast.LENGTH_SHORT).show()
        }
        pendingDnsToStart = null
    }

    fun switchOrStartVpn(dns: DnsItem) {
        selectedDns = dns
        DnsPreferences.saveSelectedDnsId(context, dns.id)
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingDnsToStart = dns
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            DnsVpnService.start(context, dns.target, dns.name, dns.hostname)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Большая кнопка статуса
            item {
                BigStatusButton(
                    isActive = vpnStatus.isActive,
                    currentDnsName = vpnStatus.currentDnsName ?: selectedDns.name,
                    currentDnsTarget = vpnStatus.currentHostname ?: vpnStatus.currentDnsIp ?: selectedDns.target,
                    resolvedIp = vpnStatus.currentDnsIp,
                    isUsingFallback = vpnStatus.isUsingFallback,
                    onToggle = {
                        if (vpnStatus.isActive) {
                            DnsVpnService.stop(context)
                        } else {
                            switchOrStartVpn(selectedDns)
                        }
                    }
                )
            }

            // Информационный баннер реального статуса подключения
            if (vpnStatus.isActive) {
                item {
                    DnsActiveHealthBanner(
                        dnsName = vpnStatus.currentDnsName ?: selectedDns.name,
                        target = vpnStatus.currentHostname ?: vpnStatus.currentDnsIp ?: selectedDns.target,
                        pingMs = activePingMs,
                        isChecking = isVerifyingActiveDns,
                        isFallback = vpnStatus.isUsingFallback
                    )
                }
            }

            // Заголовок списка предустановленных DNS
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Предустановленные DNS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val deletedPresets = DnsPreferences.getDeletedPresetIds(context)
                    if (deletedPresets.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                DnsPreferences.restoreDefaultPresets(context)
                                activePresets = DnsPreferences.DEFAULT_PRESETS
                                refreshAllPings()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Восстановить", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Список предустановленных DNS (с возможностью удаления)
            items(activePresets, key = { it.id }) { dns ->
                val isCurrent = vpnStatus.isActive && (
                    vpnStatus.currentDnsIp == dns.target ||
                    vpnStatus.currentHostname == dns.target ||
                    vpnStatus.currentHostname == dns.hostname
                )
                val isSelected = selectedDns.id == dns.id
                val latency = pingMap[dns.id]

                DnsCard(
                    dns = dns,
                    isActive = isCurrent,
                    isSelected = isSelected,
                    latencyMs = latency,
                    onSelect = {
                        switchOrStartVpn(dns)
                    },
                    onDelete = {
                        if (vpnStatus.isActive && (vpnStatus.currentDnsIp == dns.target || vpnStatus.currentHostname == dns.target)) {
                            DnsVpnService.stop(context)
                        }
                        val currentDeleted = DnsPreferences.getDeletedPresetIds(context).toMutableSet()
                        currentDeleted.add(dns.id)
                        DnsPreferences.saveDeletedPresetIds(context, currentDeleted)
                        activePresets = activePresets.filter { it.id != dns.id }
                        if (selectedDns.id == dns.id) {
                            selectedDns = (activePresets + customDnsList).firstOrNull() ?: DnsPreferences.DEFAULT_PRESETS.first()
                        }
                    }
                )
            }

            // Пользовательские DNS
            if (customDnsList.isNotEmpty()) {
                item {
                    Text(
                        text = "Пользовательские DNS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(customDnsList, key = { it.id }) { dns ->
                    val isCurrent = vpnStatus.isActive && (
                        vpnStatus.currentDnsIp == dns.target ||
                        vpnStatus.currentHostname == dns.target
                    )
                    val isSelected = selectedDns.id == dns.id
                    val latency = pingMap[dns.id]

                    DnsCard(
                        dns = dns,
                        isActive = isCurrent,
                        isSelected = isSelected,
                        latencyMs = latency,
                        onSelect = {
                            switchOrStartVpn(dns)
                        },
                        onDelete = {
                            if (vpnStatus.isActive && vpnStatus.currentDnsIp == dns.target) {
                                DnsVpnService.stop(context)
                            }
                            val updated = customDnsList.filter { it.id != dns.id }
                            customDnsList = updated
                            DnsPreferences.saveCustomDnsList(context, updated)
                            if (selectedDns.id == dns.id) {
                                selectedDns = (activePresets + updated).firstOrNull() ?: DnsPreferences.DEFAULT_PRESETS.first()
                            }
                        }
                    )
                }
            }

            // Форма добавления собственного IPv4 или DoT / DoQ хоста
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Добавить собственный DNS или DoT / DoQ",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = customNameInput,
                            onValueChange = { customNameInput = it },
                            label = { Text("Название (напр. Geohide или Мой Pi-hole)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        coroutineScope.launch {
                                            delay(150)
                                            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                                        }
                                    }
                                }
                        )

                        OutlinedTextField(
                            value = customTargetInput,
                            onValueChange = {
                                customTargetInput = it.trim()
                                isInputError = false
                            },
                            label = { Text("IPv4 адрес или имя хоста (geohide.ru, 1.1.1.1)") },
                            isError = isInputError,
                            supportingText = {
                                if (isInputError) {
                                    Text(
                                        text = "Введите корректный IPv4 (1.1.1.1) или доменное имя (geohide.ru, dns.adguard.com)",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text(
                                        text = "Поддерживаются IPv4 и DoT/DoQ имена хостов",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        coroutineScope.launch {
                                            delay(150)
                                            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                                        }
                                    }
                                }
                        )

                        Button(
                            onClick = {
                                val target = sanitizeDnsTarget(customTargetInput)
                                if (isValidDnsTarget(target)) {
                                    val isDomain = target.any { it.isLetter() }
                                    val name = customNameInput.trim().ifEmpty { target }
                                    val newDns = DnsItem(
                                        id = "custom_${System.currentTimeMillis()}",
                                        name = name,
                                        target = target,
                                        hostname = if (isDomain) target else null,
                                        description = if (isDomain) "DoT / DoQ хост ($target)" else "Пользовательский IPv4 ($target)",
                                        isCustom = true,
                                        isDoT = isDomain
                                    )
                                    val updated = customDnsList + newDns
                                    customDnsList = updated
                                    DnsPreferences.saveCustomDnsList(context, updated)
                                    customTargetInput = ""
                                    customNameInput = ""
                                    isInputError = false
                                    focusManager.clearFocus()
                                    Toast.makeText(context, "DNS $name добавлен!", Toast.LENGTH_SHORT).show()
                                    refreshAllPings()
                                } else {
                                    isInputError = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Добавить в список")
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }
}

@Composable
fun BigStatusButton(
    isActive: Boolean,
    currentDnsName: String,
    currentDnsTarget: String,
    resolvedIp: String?,
    isUsingFallback: Boolean = false,
    onToggle: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = if (isActive) {
            if (isUsingFallback) Color(0xFFF59E0B) else Color(0xFF10B981)
        } else MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 400),
        label = "ButtonColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "PulseRing")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.14f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                if (isUsingFallback) Color(0xFF78350F).copy(alpha = 0.25f)
                else Color(0xFF064E3B).copy(alpha = 0.25f)
            } else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(140.dp)
            ) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(buttonColor.copy(alpha = 0.2f))
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    buttonColor,
                                    buttonColor.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .clickable { onToggle() }
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = if (isActive) "Отключить" else "Включить",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isActive) {
                    if (isUsingFallback) "РЕЗЕРВНЫЙ DNS (1.1.1.1)" else "ЗАЩИТА ВКЛЮЧЕНА"
                } else "DNS ОТКЛЮЧЕН",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = if (isActive) {
                    if (isUsingFallback) Color(0xFFF59E0B) else Color(0xFF10B981)
                } else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            val subtitle = if (isActive) {
                val base = if (resolvedIp != null && resolvedIp != currentDnsTarget) {
                    "Активен: $currentDnsName ($currentDnsTarget → $resolvedIp)"
                } else {
                    "Активен: $currentDnsName ($currentDnsTarget)"
                }
                if (isUsingFallback) {
                    "$base\n(Сервер не ответил, используется резерв 1.1.1.1)"
                } else {
                    base
                }
            } else {
                "Нажмите для подключения через $currentDnsName"
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun DnsCard(
    dns: DnsItem,
    isActive: Boolean,
    isSelected: Boolean,
    latencyMs: Long? = null,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val borderColor = when {
        isActive -> Color(0xFF10B981)
        isSelected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isActive || isSelected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF10B981).copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) Color(0xFF10B981).copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Активен",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dns.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (dns.isDoT) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "DoT / DoQ",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dns.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (latencyMs != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val pingColor = when {
                            latencyMs < 0 -> MaterialTheme.colorScheme.error
                            latencyMs < 50 -> Color(0xFF10B981)
                            latencyMs < 120 -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.error
                        }
                        val pingText = if (latencyMs < 0) "Недоступен" else "${latencyMs} ms"
                        Text(
                            text = "• $pingText",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = pingColor
                        )
                    }
                }

                Text(
                    text = dns.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            } else {
                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isActive) "Активен" else "Выбрать",
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

fun sanitizeDnsTarget(input: String): String {
    return input.trim()
        .replace(" ", "")
        .removePrefix("https://")
        .removePrefix("tls://")
        .removePrefix("quic://")
        .split("/")[0]
        .split(":")[0]
}

fun isValidDnsTarget(target: String): Boolean {
    val clean = sanitizeDnsTarget(target)
    if (clean.isEmpty()) return false

    val parts = clean.split(".")
    if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
        for (part in parts) {
            val num = part.toIntOrNull() ?: return false
            if (num !in 0..255) return false
            if (part.length > 1 && part.startsWith("0")) return false
        }
        return clean != "0.0.0.0" && clean != "255.255.255.255"
    }

    val domainRegex = Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$")
    return domainRegex.matches(clean)
}

@Composable
fun DnsActiveHealthBanner(
    dnsName: String,
    target: String,
    pingMs: Long?,
    isChecking: Boolean,
    isFallback: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFallback) {
                Color(0xFF78350F).copy(alpha = 0.2f)
            } else {
                Color(0xFF10B981).copy(alpha = 0.12f)
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isFallback) Color(0xFFF59E0B).copy(alpha = 0.4f) else Color(0xFF10B981).copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isFallback) Color(0xFFF59E0B) else Color(0xFF10B981),
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isFallback) "Резервный DNS активен" else "DNS подключен и активен",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isFallback) Color(0xFFF59E0B) else Color(0xFF10B981)
                )

                val statusSubtitle = when {
                    isChecking -> "Проверка доступности резолвера..."
                    pingMs != null && pingMs >= 0 -> "Сервер отвечает • Задержка: ${pingMs} ms"
                    else -> "Все запросы направляются через $dnsName"
                }

                Text(
                    text = statusSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
