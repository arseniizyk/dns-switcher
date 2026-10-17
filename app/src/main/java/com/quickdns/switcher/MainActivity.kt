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
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
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
    val target: String, // IPv4 или имя хоста DoT / DoQ (напр. geohide.ru, dns.adguard.com)
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuickDnsScreen()
                }
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

    // Таймер применения DNS (DNS кэш и переключение сокетов обычно занимают 5-10 сек)
    var remainingApplySeconds by remember { mutableIntStateOf(0) }
    var activeApplyDnsTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vpnStatus.isActive, vpnStatus.currentDnsIp, vpnStatus.currentHostname) {
        if (vpnStatus.isActive) {
            val currentTarget = vpnStatus.currentHostname ?: vpnStatus.currentDnsIp ?: ""
            if (currentTarget != activeApplyDnsTarget || remainingApplySeconds == 0) {
                activeApplyDnsTarget = currentTarget
                remainingApplySeconds = 10
                while (remainingApplySeconds > 0) {
                    delay(1000L)
                    remainingApplySeconds--
                }
            }
        } else {
            remainingApplySeconds = 0
            activeApplyDnsTarget = null
        }
    }

    // Предустановленные DNS: включая IPv4 и DoT/DoQ хосты по требованию
    val defaultPresets = remember {
        listOf(
            DnsItem("cf", "Cloudflare DNS", "1.1.1.1", "Сверхбыстрый и приватный (1.1.1.1)", hostname = "one.one.one.one"),
            DnsItem("adguard_dot", "AdGuard (DoT / DoQ)", "dns.adguard.com", "Фильтрация рекламы и трекеров (dns.adguard.com)", hostname = "dns.adguard.com", isDoT = true),
            DnsItem("geohide_dot", "Geohide DNS", "geohide.ru", "Обход блокировок и DoT/DoQ (geohide.ru)", hostname = "geohide.ru", isDoT = true),
            DnsItem("google", "Google DNS", "8.8.8.8", "Глобальная стабильность Anycast (8.8.8.8)", hostname = "dns.google"),
            DnsItem("quad9", "Quad9", "9.9.9.9", "Защита от вредоносных сайтов (9.9.9.9)", hostname = "dns.quad9.net")
        )
    }

    var customDnsList by remember {
        mutableStateOf(DnsPreferences.getCustomDnsList(context))
    }

    var selectedDns by remember {
        val savedId = DnsPreferences.getSelectedDnsId(context)
        val initial = (defaultPresets + customDnsList).firstOrNull { it.id == savedId } ?: defaultPresets[0]
        mutableStateOf(initial)
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
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "QuickDNS Switcher",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Большая кнопка статуса (Включено / Выключено)
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

            // Карточка статуса применения DNS с таймером
            if (vpnStatus.isActive) {
                item {
                    DnsApplyStatusBanner(
                        remainingSeconds = remainingApplySeconds,
                        totalSeconds = 10,
                        dnsName = vpnStatus.currentDnsName ?: selectedDns.name
                    )
                }
            }

            // Заголовок списка
            item {
                Text(
                    text = "Предустановленные DNS (IPv4, DoT, DoQ)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Список предустановленных DNS
            items(defaultPresets) { dns ->
                val isCurrent = vpnStatus.isActive && (
                    vpnStatus.currentDnsIp == dns.target ||
                    vpnStatus.currentHostname == dns.target ||
                    vpnStatus.currentHostname == dns.hostname
                )
                val isSelected = selectedDns.id == dns.id

                DnsCard(
                    dns = dns,
                    isActive = isCurrent,
                    isSelected = isSelected,
                    onSelect = {
                        selectedDns = dns
                        switchOrStartVpn(dns)
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

                items(customDnsList) { dns ->
                    val isCurrent = vpnStatus.isActive && (
                        vpnStatus.currentDnsIp == dns.target ||
                        vpnStatus.currentHostname == dns.target
                    )
                    val isSelected = selectedDns.id == dns.id

                    DnsCard(
                        dns = dns,
                        isActive = isCurrent,
                        isSelected = isSelected,
                        onSelect = {
                            selectedDns = dns
                            switchOrStartVpn(dns)
                        },
                        onDelete = {
                            if (vpnStatus.isActive && vpnStatus.currentDnsIp == dns.target) {
                                DnsVpnService.stop(context)
                            }
                            val updated = customDnsList.filter { it.id != dns.id }
                            customDnsList = updated
                            DnsPreferences.saveCustomDnsList(context, updated)
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
                                        text = "Поддерживаются DoT/DoQ имена хостов (geohide.ru, dns.adguard.com)",
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
                Text(
                    text = dns.target,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
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
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
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

    // Check if IPv4
    val parts = clean.split(".")
    if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
        for (part in parts) {
            val num = part.toIntOrNull() ?: return false
            if (num !in 0..255) return false
            if (part.length > 1 && part.startsWith("0")) return false
        }
        return clean != "0.0.0.0" && clean != "255.255.255.255"
    }

    // Check if valid Domain name / Hostname (e.g. geohide.ru, dns.adguard.com)
    val domainRegex = Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$")
    return domainRegex.matches(clean)
}

@Composable
fun DnsApplyStatusBanner(
    remainingSeconds: Int,
    totalSeconds: Int,
    dnsName: String
) {
    val isApplying = remainingSeconds > 0
    val progress = if (totalSeconds > 0) ((totalSeconds - remainingSeconds).toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f) else 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isApplying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                Color(0xFF10B981).copy(alpha = 0.12f)
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isApplying) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color(0xFF10B981).copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isApplying) "Применение DNS на устройстве..." else "DNS успешно применен!",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isApplying) MaterialTheme.colorScheme.onSurface else Color(0xFF10B981)
                    )
                    Text(
                        text = if (isApplying) {
                            "Полное применение через: ${remainingSeconds} сек"
                        } else {
                            "DNS активен. Все новые запросы идут через $dnsName"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isApplying) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isApplying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isApplying) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Очистка системного DNS-кэша и маршрутизация сокетов (~10 сек)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

