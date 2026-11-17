package com.kerynth.remote

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PowerSettingsNew
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KerynthRemoteApp()
        }
    }
}

@Composable
private fun KerynthRemoteApp() {
    var selectedImage by rememberSaveable { mutableStateOf<Uri?>(null) }
    var receivingEnabled by rememberSaveable { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedImage = uri
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF9CCBFF),
            secondary = Color(0xFFB8A6FF),
            background = Color(0xFF0B1018),
            surface = Color(0xFF151E2B),
            onBackground = Color(0xFFEAF1FA),
            onSurface = Color(0xFFEAF1FA)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0B1018)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "KERYNTH",
                            color = Color(0xFF9CCBFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp
                        )
                        Text(
                            text = "Remote",
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFF1B2B3D)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Rounded.Devices,
                            contentDescription = null,
                            tint = Color(0xFF9CCBFF),
                            modifier = Modifier.padding(14.dp).size(28.dp)
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151E2B)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Устройство-получатель", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (receivingEnabled) "Режим приёма включён на этом устройстве"
                            else "Приём выключен",
                            color = Color(0xFFAFBED0)
                        )
                        Button(
                            onClick = { receivingEnabled = !receivingEnabled },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = if (receivingEnabled) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Link,
                                contentDescription = null
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(if (receivingEnabled) "Остановить приём" else "Включить режим приёма")
                        }
                        Text(
                            "Приём включается вручную. Полученные изображения будут показываться только в открытом приложении.",
                            color = Color(0xFF8294A9),
                            fontSize = 12.sp
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151E2B)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = null,
                                tint = Color(0xFF9CCBFF)
                            )
                            Spacer(Modifier.size(10.dp))
                            Text("Отправка изображения", fontWeight = FontWeight.SemiBold)
                        }
                        if (selectedImage != null) {
                            AsyncImage(
                                model = selectedImage,
                                contentDescription = "Выбранное изображение",
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                "Выбери изображение на этом устройстве. Передача между телефонами появится после настройки соединения.",
                                color = Color(0xFFAFBED0)
                            )
                        }
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (selectedImage == null) "Выбрать изображение" else "Выбрать другое")
                        }
                    }
                }

                Spacer(Modifier.weight(1f))
                Text(
                    "Версия 0.1.0 • Локальный интерфейс",
                    color = Color(0xFF64758A),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
