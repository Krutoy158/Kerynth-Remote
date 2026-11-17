package com.kerynth.remote.agent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsActive
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, KerynthDeviceAdminReceiver::class.java)

        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            refreshPermissionState()
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            refreshPermissionState()
        }

        refreshPermissionState()

        setContent {
            AgentScreen(
                adminActive = adminActive,
                deviceOwner = deviceOwner,
                locationGranted = locationGranted,
                backgroundLocationGranted = backgroundLocationGranted,
                notificationsGranted = notificationsGranted,
                onRequestLocation = ::requestForegroundLocation,
                onRequestBackgroundLocation = ::openBackgroundLocationSettings,
                onRequestNotifications = ::requestNotifications,
                onRequestAdmin = ::requestAdmin,
                onLockDevice = ::lockDevice,
                onRebootDevice = ::rebootDevice
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::devicePolicyManager.isInitialized) {
            refreshPermissionState()
        }
    }

    private fun refreshPermissionState() {
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
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            )
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
                "Kerynth Remote uses this permission only to lock this device after an authenticated Lost Mode command."
            )
        }
        startActivity(intent)
    }

    private fun lockDevice() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.lockNow()
        }
    }

    private fun rebootDevice() {
        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            runCatching {
                devicePolicyManager.reboot(adminComponent)
            }
        }
    }
}

@Composable
private fun AgentScreen(
    adminActive: Boolean,
    deviceOwner: Boolean,
    locationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestAdmin: () -> Unit,
    onLockDevice: () -> Unit,
    onRebootDevice: () -> Unit
) {
    var acceptingSessions by rememberSaveable { mutableStateOf(false) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0A1019)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "KERYNTH",
                        color = Color(0xFF9CCBFF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "Remote Agent",
                        color = Color.White,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Настройка управляемого устройства",
                        color = Color(0xFF9EADBF)
                    )
                }

                SectionCard(
                    title = "Первоначальная настройка",
                    subtitle = "Выдай только те разрешения, которые нужны для выбранных функций. Все системные запросы Android показываются отдельно."
                ) {
                    PermissionRow(
                        title = "Уведомления",
                        status = if (notificationsGranted) "Разрешено" else "Не разрешено",
                        description = "Нужно для входящих сообщений и видимых уведомлений Agent.",
                        actionLabel = if (notificationsGranted) null else "Разрешить",
                        onAction = onRequestNotifications
                    )
                    PermissionRow(
                        title = "Геолокация при использовании",
                        status = if (locationGranted) "Разрешено" else "Не разрешено",
                        description = "Нужно для получения координат, когда приложение используется.",
                        actionLabel = if (locationGranted) null else "Разрешить",
                        onAction = onRequestLocation
                    )
                    PermissionRow(
                        title = "Фоновая геолокация",
                        status = when {
                            backgroundLocationGranted -> "Разрешено"
                            !locationGranted -> "Сначала разреши геолокацию"
                            else -> "Требует настройки"
                        },
                        description = "Нужна для обновления местоположения в Lost Mode, когда Agent не открыт. На Android 11+ включается отдельно в настройках приложения.",
                        actionLabel = if (locationGranted && !backgroundLocationGranted) "Открыть настройки" else null,
                        onAction = onRequestBackgroundLocation
                    )
                    PermissionRow(
                        title = "Блокировка устройства",
                        status = if (adminActive) "Device Admin активен" else "Не активировано",
                        description = "Обычное административное разрешение Android для блокировки экрана.",
                        actionLabel = if (adminActive) null else "Активировать",
                        onAction = onRequestAdmin
                    )
                    PermissionRow(
                        title = "Системная перезагрузка",
                        status = if (deviceOwner) "Device Owner активен" else "Не настроено",
                        description = "Не выдаётся обычным диалогом после установки. Требует отдельной процедуры управляемого устройства.",
                        actionLabel = null,
                        onAction = {}
                    )
                    PermissionRow(
                        title = "Трансляция экрана",
                        status = "Подтверждается для каждой сессии",
                        description = "Android показывает системный запрос перед каждой новой трансляцией. Постоянно разрешить её заранее нельзя.",
                        actionLabel = null,
                        onAction = {}
                    )
                }

                SectionCard(
                    title = "Подключение",
                    subtitle = if (acceptingSessions) {
                        "Локальный демонстрационный режим приёма включён."
                    } else {
                        "Устройство пока не принимает новые сеансы."
                    }
                ) {
                    Button(
                        onClick = { acceptingSessions = !acceptingSessions },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (acceptingSessions) {
                                "Приостановить приём"
                            } else {
                                "Разрешить запросы на подключение"
                            }
                        )
                    }
                    Text(
                        text = "Настоящее сопряжение Agent и Console будет подключено следующим этапом.",
                        color = Color(0xFF8193A9),
                        fontSize = 12.sp
                    )
                }

                SectionCard(
                    title = "Lost Mode",
                    subtitle = "Действия доступны только на самом Agent. Удалённые команды появятся после защищённого сопряжения."
                ) {
                    if (!adminActive) {
                        OutlinedButton(
                            onClick = onRequestAdmin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Включить разрешение блокировки")
                        }
                    } else {
                        Button(
                            onClick = onLockDevice,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Проверить блокировку на этом телефоне")
                        }
                    }

                    if (deviceOwner) {
                        OutlinedButton(
                            onClick = onRebootDevice,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Перезагрузить этот телефон")
                        }
                    }
                }

                Text(
                    text = "Agent • 0.4.0 • Сопряжение и удалённые команды ещё не подключены",
                    color = Color(0xFF71839A),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162334))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
