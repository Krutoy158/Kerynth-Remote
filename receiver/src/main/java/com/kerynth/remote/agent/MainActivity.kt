package com.kerynth.remote.agent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

class MainActivity : ComponentActivity() {
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    private var adminActive by mutableStateOf(false)
    private var deviceOwner by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var backgroundLocationGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var serverRunning by mutableStateOf(false)
    private var pairingCode by mutableStateOf("")
    private var paired by mutableStateOf(false)
    private var localAddress by mutableStateOf("")
    private var lastImagePath by mutableStateOf<String?>(null)

    private val preferences by lazy { getSharedPreferences("kerynth_agent", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, KerynthDeviceAdminReceiver::class.java)

        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { refreshState() }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { refreshState() }

        refreshState()

        setContent {
            AgentScreen(
                adminActive = adminActive,
                deviceOwner = deviceOwner,
                locationGranted = locationGranted,
                backgroundLocationGranted = backgroundLocationGranted,
                notificationsGranted = notificationsGranted,
                serverRunning = serverRunning,
                pairingCode = pairingCode,
                paired = paired,
                localAddress = localAddress,
                lastImagePath = lastImagePath,
                onRequestLocation = ::requestForegroundLocation,
                onRequestBackgroundLocation = ::openBackgroundLocationSettings,
                onRequestNotifications = ::requestNotifications,
                onRequestAdmin = ::requestAdmin,
                onLockDevice = ::lockDevice,
                onRebootDevice = ::rebootDevice,
                onStartServer = ::startServer,
                onStopServer = ::stopServer,
                onRevokePairing = ::revokePairing
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::devicePolicyManager.isInitialized) refreshState()
    }

    private fun refreshState() {
        adminActive = devicePolicyManager.isAdminActive(adminComponent)
        deviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
        locationGranted =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        backgroundLocationGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        serverRunning = preferences.getBoolean("server_running", false)
        pairingCode = preferences.getString("pair_code", "") ?: ""
        paired = preferences.getString("auth_token", null) != null
        localAddress = getLocalIpv4Address()
        lastImagePath = preferences.getString("last_image_path", null)
    }

    private fun requestForegroundLocation() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun openBackgroundLocationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Kerynth Remote uses this permission only to lock this device after an authenticated command from your paired Console."
            )
        }
        startActivity(intent)
    }

    private fun lockDevice() {
        if (devicePolicyManager.isAdminActive(adminComponent)) runCatching { devicePolicyManager.lockNow() }
    }

    private fun rebootDevice() {
        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            runCatching { devicePolicyManager.reboot(adminComponent) }
        }
    }

    private fun startServer() {
        val existingToken = preferences.getString("auth_token", null)
        val code = if (existingToken == null) {
            val codeBytes = ByteArray(9).also { SecureRandom().nextBytes(it) }
            Base64.encodeToString(
                codeBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            ).also {
                preferences.edit()
                    .putString("pair_code", it)
                    .putLong("pair_expires", System.currentTimeMillis() + 5 * 60 * 1000L)
                    .apply()
            }
        } else {
            preferences.edit().remove("pair_code").remove("pair_expires").apply()
            ""
        }
        val intent = Intent(this, AgentServerService::class.java).setAction(AgentServerService.ACTION_START)
        if (code.isNotBlank()) intent.putExtra(AgentServerService.EXTRA_PAIR_CODE, code)
        startForegroundService(intent)
        pairingCode = code
        paired = existingToken != null
        serverRunning = true
        localAddress = getLocalIpv4Address()
    }

    private fun stopServer() {
        startService(Intent(this, AgentServerService::class.java).setAction(AgentServerService.ACTION_STOP))
        serverRunning = false
    }

    private fun revokePairing() {
        val codeBytes = ByteArray(9).also { SecureRandom().nextBytes(it) }
        val code = Base64.encodeToString(
            codeBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        preferences.edit()
            .remove("auth_token")
            .putString("pair_code", code)
            .putLong("pair_expires", System.currentTimeMillis() + 5 * 60 * 1000L)
            .apply()
        val intent = Intent(this, AgentServerService::class.java)
            .setAction(AgentServerService.ACTION_START)
            .putExtra(AgentServerService.EXTRA_PAIR_CODE, code)
        if (serverRunning) startService(intent) else startForegroundService(intent)
        pairingCode = code
        paired = false
        serverRunning = true
    }

    private fun getLocalIpv4Address(): String {
        return runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.contains("wlan", true) || it.name.contains("wifi", true)) 0 else 1 }
            interfaces.asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress != null }
                ?.hostAddress ?: ""
        }.getOrDefault("")
    }
}

@Composable
private fun AgentScreen(
    adminActive: Boolean,
    deviceOwner: Boolean,
    locationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    notificationsGranted: Boolean,
    serverRunning: Boolean,
    pairingCode: String,
    paired: Boolean,
    localAddress: String,
    lastImagePath: String?,
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestAdmin: () -> Unit,
    onLockDevice: () -> Unit,
    onRebootDevice: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRevokePairing: () -> Unit
) {
    val receivedImage = androidx.compose.runtime.remember(lastImagePath) {
        lastImagePath?.let { path -> runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A1019)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("KERYNTH", color = Color(0xFF9CCBFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("Remote Agent", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
                    Text("Управляемое устройство", color = Color(0xFF9EADBF))
                }

                SectionCard("Первоначальная настройка", "Выдай разрешения только для тех функций, которые собираешься использовать.") {
                    PermissionRow(
                        "Уведомления",
                        if (notificationsGranted) "Разрешено" else "Не разрешено",
                        "Показывают сообщения и статус Agent.",
                        if (notificationsGranted) null else "Разрешить",
                        onRequestNotifications
                    )
                    PermissionRow(
                        "Геолокация",
                        if (locationGranted) "Разрешено" else "Не разрешено",
                        "Нужна для получения последней известной позиции устройства.",
                        if (locationGranted) null else "Разрешить",
                        onRequestLocation
                    )
                    PermissionRow(
                        "Фоновая геолокация",
                        when {
                            backgroundLocationGranted -> "Разрешено"
                            !locationGranted -> "Сначала разреши геолокацию"
                            else -> "Требует настройки"
                        },
                        "Для получения позиции, когда Agent не открыт. Android 11+ настраивается отдельно.",
                        if (locationGranted && !backgroundLocationGranted) "Открыть настройки" else null,
                        onRequestBackgroundLocation
                    )
                    PermissionRow(
                        "Блокировка экрана",
                        if (adminActive) "Device Admin активен" else "Не активировано",
                        "Разрешает блокировку после команды от сопряжённой Console.",
                        if (adminActive) null else "Активировать",
                        onRequestAdmin
                    )
                    PermissionRow(
                        "Перезагрузка",
                        if (deviceOwner) "Device Owner активен" else "Недоступно",
                        "Требует специального режима управляемого устройства, а не обычного разрешения.",
                        null,
                        {}
                    )
                }

                SectionCard("Локальная связь", "Оба телефона должны быть подключены к одной Wi-Fi-сети. Agent остаётся видимым в уведомлениях.") {
                    Text(
                        if (serverRunning) "Состояние: сервер запущен" else "Состояние: остановлен",
                        color = if (serverRunning) Color(0xFF86D6A3) else Color(0xFFFFC27A),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Адрес: " + if (localAddress.isNotBlank()) "$localAddress:${AgentServerService.SERVER_PORT}" else "IP не определён",
                        color = Color.White
                    )
                    if (!paired && pairingCode.isNotBlank() && serverRunning) {
                        Text("Одноразовый код сопряжения", color = Color(0xFF9EADBF), fontSize = 12.sp)
                        Text(pairingCode, color = Color(0xFF9CCBFF), fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Код действует 5 минут и используется один раз.", color = Color(0xFF9EADBF), fontSize = 12.sp)
                    }
                    if (paired) {
                        Text("Console сопряжена", color = Color(0xFF86D6A3), fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = onRevokePairing, modifier = Modifier.fillMaxWidth()) {
                            Text("Отозвать доступ и создать новый код")
                        }
                    }
                    Button(
                        onClick = if (serverRunning) onStopServer else onStartServer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (serverRunning) "Остановить Agent" else "Запустить Agent")
                    }
                    Text(
                        "Команды защищены одноразовым кодом, HMAC-проверкой и AES-GCM шифрованием. Для доступа из другой сети потребуется интернет-сервер.",
                        color = Color(0xFF8193A9),
                        fontSize = 12.sp
                    )
                }

                SectionCard("Lost Mode", "Дистанционная блокировка требует активного Device Admin. Перезагрузка работает только при Device Owner.") {
                    if (!adminActive) {
                        OutlinedButton(onClick = onRequestAdmin, modifier = Modifier.fillMaxWidth()) {
                            Text("Включить разрешение блокировки")
                        }
                    } else {
                        Button(onClick = onLockDevice, modifier = Modifier.fillMaxWidth()) {
                            Text("Проверить локальную блокировку")
                        }
                    }
                    if (deviceOwner) {
                        OutlinedButton(onClick = onRebootDevice, modifier = Modifier.fillMaxWidth()) {
                            Text("Перезагрузить этот телефон")
                        }
                    }
                }

                if (receivedImage != null) {
                    SectionCard("Последнее изображение", "Изображение получено через сопряжённую Console.") {
                        Image(
                            bitmap = receivedImage,
                            contentDescription = "Полученное изображение",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Text("Agent • 0.5.0", color = Color(0xFF71839A), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162334))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle, color = Color(0xFFB5C2D2), fontSize = 13.sp)
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    status: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(status, color = Color(0xFF9CCBFF), fontSize = 11.sp)
        }
        Text(description, color = Color(0xFF9EADBF), fontSize = 12.sp)
        if (actionLabel != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}
