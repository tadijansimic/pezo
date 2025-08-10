package com.example.myapplication

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.skydoves.colorpicker.compose.AlphaSlider
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.ColorPickerController
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody // VAŽNO: Proverite da li je ova linija prisutna
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.regex.Pattern

// Konstantno ime za Shared Preferences fajl i ključ
const val PREFS_NAME = "PezoPresets"
const val PRESETS_KEY = "saved_presets"
const val ESP_IP_KEY = "esp_ip_address" // Novi ključ za IP adresu ESP-a

// Definicija Json objekta na top-levelu za bolju kontrolu i robusnost
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

class MainActivity : ComponentActivity() {
    // Koristimo MutableState za IP adresu kako bi Compose mogao da reaguje na promene
    var espIpAddress by mutableStateOf("")
    private var udpThread: Thread? = null
    private var listening: Boolean = false // Kontrolna varijabla za UDP listener

    override fun onResume() {
        super.onResume()
        listening = true

        espIpAddress = loadEspIp(this)
        startUdpListener(6969)

    }

    override fun onPause() {
        super.onPause()
        listening = false
        udpThread?.interrupt()
        udpThread = null
    }

    private fun startUdpListener(port: Int) {
        if (udpThread?.isAlive == true) return

        udpThread = Thread {
            try {
                val socket = DatagramSocket(port)
                socket.broadcast = true
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (listening && !Thread.currentThread().isInterrupted) {
                    try {
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        println("Primljen UDP paket: $message")

                        // Provera da li poruka sadrži očekivani format "esp{ip_address}"
                        val pattern = Pattern.compile("esp\\{(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\}")
                        val matcher = pattern.matcher(message)

                        if (matcher.find()) {
                            val discoveredIp = matcher.group(1)
                            if (discoveredIp != null && discoveredIp != espIpAddress) {
                                runOnUiThread {
                                    espIpAddress = discoveredIp
                                    saveEspIp(this, discoveredIp)
                                    Toast.makeText(this, "ESP IP found: $discoveredIp", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (listening) {
                            e.printStackTrace()
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { start() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppUI(espIpAddress = espIpAddress) { newIp ->
                        espIpAddress = newIp
                        saveEspIp(this, newIp)
                    }
                }
            }
        }
    }
}

@Composable
fun darkColorScheme(): ColorScheme {
    return darkColorScheme(
        primary = Color(0xFF105C61),
        onPrimary = Color.White,
        background = Color(0xFF2B2D30),
        onBackground = Color.White,
        surface = Color(0xFF2B2D30),
        onSurface = Color(0xFFb3bbc8),
        surfaceVariant = Color(0xFF3A3D40),
        onSurfaceVariant = Color(0xFFb3bbc8),
        secondary = Color(0xFF00ADB5),
        onSecondary = Color.White,
        outline = Color(0xFF5D6169)
    )
}

@Serializable
data class RGB(val r: Int, val g: Int, val b: Int)

@Serializable
data class Preset(
    val name: String,
    val color1: RGB,
    val color2: RGB,
    val brightness: Int,
    val mode: Int
)
@Serializable
data class EspState(
    val color1: RGB,
    val color2: RGB,
    val brightness: Int,
    val mode: Int
)

fun savePresets(context: Context, presets: List<Preset>) {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPrefs.edit {
        val jsonString = AppJson.encodeToString(presets)
        putString(PRESETS_KEY, jsonString)
    }
}

fun loadPresets(context: Context): List<Preset> {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val jsonString = sharedPrefs.getString(PRESETS_KEY, null)

    if (jsonString.isNullOrBlank()) {
        return emptyList()
    }

    return try {
        AppJson.decodeFromString<List<Preset>>(jsonString)
    } catch (e: Exception) {
        e.printStackTrace()
        sharedPrefs.edit { remove(PRESETS_KEY) }
        Toast.makeText(context, "Error loading preset!", Toast.LENGTH_LONG).show()
        emptyList()
    }
}

fun saveEspIp(context: Context, ipAddress: String) {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPrefs.edit {
        putString(ESP_IP_KEY, ipAddress)
    }
}

fun loadEspIp(context: Context): String {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // Vraćamo prazan string ako IP adresa nije pronađena
    return sharedPrefs.getString(ESP_IP_KEY, "") ?: ""
}

@Composable
fun AppUI(espIpAddress: String, onEspIpAddressChange: (String) -> Unit) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val context = LocalContext.current

    val color1Controller = rememberColorPickerController()
    val color2Controller = rememberColorPickerController()

    var globalBrightness by remember { mutableFloatStateOf(127f) }
    var selectedMode by remember { mutableIntStateOf(0) }
    var presets by remember { mutableStateOf(emptyList<Preset>()) }

    var currentEspIp by remember { mutableStateOf(espIpAddress) }

    LaunchedEffect(espIpAddress) {
        currentEspIp = espIpAddress
    }

    LaunchedEffect(Unit) {
        presets = loadPresets(context)
    }

    LaunchedEffect(currentEspIp) {
        if (currentEspIp.isNotBlank()) {
            try {
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("http://$currentEspIp/color")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body.string()
                        withContext(Dispatchers.Main) {
                            val espState = AppJson.decodeFromString<EspState>(body)

                            color1Controller.selectByColor(Color(espState.color1.r, espState.color1.g, espState.color1.b, 255), false)
                            color2Controller.selectByColor(Color(espState.color2.r, espState.color2.g, espState.color2.b, 255), false)

                            selectedMode = espState.mode
                            globalBrightness = espState.brightness.toFloat()
                            Toast.makeText(context, "ESP state loaded; IP: $currentEspIp", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed loading via: $currentEspIp: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network error while loading via: $currentEspIp: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(context, "Waiting for ESP...", Toast.LENGTH_SHORT).show()
        }
    }

    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    val onDeletePreset: (Preset) -> Unit = { presetToDelete ->
        val updatedList = presets.toMutableList().apply {
            remove(presetToDelete)
        }
        presets = updatedList
        savePresets(context, presets)
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp))
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        // Prikaz trenutne ESP IP adrese
        Text(
            text = "ПЕЖО dashboard",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp
        )
        Spacer(Modifier.height(24.dp))

        ColorPickerBox(
            title = "Primary Color",
            controller = color1Controller,
            showAlphaSlider = false
        )
        Spacer(Modifier.height(48.dp))

        ColorPickerBox(
            title = "Secondary Color",
            controller = color2Controller,
            showAlphaSlider = false
        )
        Spacer(Modifier.height(48.dp))

        Text(
            "Preview",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(Modifier.height(12.dp))
        val previewGradient = getGradientForMode(
            selectedMode,
            color1Controller.selectedColor.value,
            color2Controller.selectedColor.value
        )
        Box(
            modifier = Modifier
                .width(360.dp)
                .height(50.dp)
                .background(previewGradient, shape = RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(48.dp))

        Text(
            "Global Brightness",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Slider(
            value = globalBrightness,
            onValueChange = { globalBrightness = it },
            valueRange = 0f..255f,
            modifier = Modifier.width(360.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        )
        Spacer(Modifier.height(48.dp))

        Text(
            "Mode",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.width(360.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            (0..2).forEach { modeIndex ->
                Button(
                    onClick = { selectedMode = modeIndex },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedMode == modeIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("Mode $modeIndex", color = if (selectedMode == modeIndex) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { showSaveDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Save Preset", color = MaterialTheme.colorScheme.onPrimary)
            }
            Button(
                onClick = {
                    if (currentEspIp.isNotBlank()) {
                        lifecycleOwner.lifecycleScope.launch {
                            sendToESP(
                                context = context,
                                espIp = currentEspIp, // Prosleđivanje dinamičke IP adrese
                                color1 = color1Controller.selectedColor.value,
                                color2 = color2Controller.selectedColor.value,
                                brightness = globalBrightness.toInt(),
                                mode = selectedMode
                            )
                        }
                    } else {
                        Toast.makeText(context, "ESP IP adress not found.", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Update", color = MaterialTheme.colorScheme.onSecondary)
            }
        }

        Spacer(Modifier.height(48.dp))

        Text(
            "Saved Presets",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier
                .width(400.dp)
                .height(274.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            items(presets.size) { index ->
                val preset = presets[index]
                PresetItem(
                    preset = preset,
                    onClick = {
                        color1Controller.selectByColor(
                            Color(preset.color1.r, preset.color1.g, preset.color1.b),
                            false
                        )
                        color2Controller.selectByColor(
                            Color(preset.color2.r, preset.color2.g, preset.color2.b),
                            false
                        )
                        globalBrightness = preset.brightness.toFloat()
                        selectedMode = preset.mode
                        Toast.makeText(context, "Preset '${preset.name}' loaded", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { presetToDelete ->
                        onDeletePreset(presetToDelete)
                    }
                )
                if (index < presets.size - 1) {
                    Divider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (currentEspIp.isNotBlank()) "ESP IP: $currentEspIp" else "Tražim ESP...",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
    }


    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                showSaveDialog = false
                presetName = ""
            },
            title = { Text("Sačuvaj Preset", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                TextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    placeholder = { Text("Unesite ime preseta", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            val newPreset = Preset(
                                name = presetName.trim(),
                                color1 = color1Controller.selectedColor.value.toRGB(),
                                color2 = color2Controller.selectedColor.value.toRGB(),
                                brightness = globalBrightness.toInt(),
                                mode = selectedMode
                            )

                            val existingIndex = presets.indexOfFirst { it.name == newPreset.name }

                            presets = if (existingIndex != -1) {
                                presets.toMutableList().apply {
                                    set(existingIndex, newPreset)
                                }
                            } else {
                                presets + newPreset
                            }

                            savePresets(context, presets)

                            presetName = ""
                            showSaveDialog = false
                        } else {
                            Toast.makeText(context, "Preset name can't be empty!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Sačuvaj", color = MaterialTheme.colorScheme.onPrimary)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showSaveDialog = false
                        presetName = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text("Otkaži", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun getGradientForMode(mode: Int, color1: Color, color2: Color): Brush {
    val c1 = color1.copy(alpha = 1f)
    val c2 = color2.copy(alpha = 1f)

    return when (mode) {
        0 -> Brush.horizontalGradient(
            colors = listOf(c1, c2)
        )
        1 -> Brush.horizontalGradient(
            colors = listOf(c1, c2, c1)
        )
        2 -> Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to c1,
                0.499f to c2,
                0.5f to c1,
                1.0f to c2
            )
        )
        else -> Brush.horizontalGradient(
            colors = listOf(c1, c2)
        )
    }
}


@Composable
fun ColorPickerBox(title: String, controller: ColorPickerController, showAlphaSlider: Boolean) {
    var selectedColorHex by remember { mutableStateOf("#FFFFFFFF") }
    LaunchedEffect(controller.selectedColor.value) {
        selectedColorHex = String.format("#%08X", controller.selectedColor.value.toArgb())
    }

    Column(
        modifier = Modifier
            .width(360.dp)
            .wrapContentHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        HsvColorPicker(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            controller = controller
        )
        Spacer(Modifier.height(12.dp))
        BrightnessSlider(
            modifier = Modifier
                .fillMaxWidth()
                .height(25.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
            controller = controller
        )
        if (showAlphaSlider) {
            Spacer(Modifier.height(12.dp))
            AlphaSlider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                controller = controller
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(controller.selectedColor.value, shape = RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = selectedColorHex,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PresetItem(preset: Preset, onClick: () -> Unit, onDelete: (Preset) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.0f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    preset.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onDelete(preset) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete preset",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val presetGradient = getGradientForMode(
                preset.mode,
                Color(preset.color1.r, preset.color1.g, preset.color1.b),
                Color(preset.color2.r, preset.color2.g, preset.color2.b)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(presetGradient, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Brightness: ${preset.brightness}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    "Mod: ${preset.mode}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
suspend fun sendToESP(
    context: Context,
    espIp: String, // Dodat parametar za dinamičku IP adresu
    color1: Color,
    color2: Color,
    brightness: Int,
    mode: Int
) {
    withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        // Kreiraj objekat u formatu koji ESP direktno razume
        val payload = EspState(
            brightness = brightness,
            mode = mode,
            color1 = color1.toRGB(),
            color2 = color2.toRGB()
        )

        val json = AppJson.encodeToString(payload)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://$espIp/color")
            .post(body).build()

        try {
            val response = client.newCall(request).execute()
            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    Toast.makeText(context, "✅ Updated ESP-u", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "❌ Error: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "⚠️ Network error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

fun Color.toRGB(): RGB =
    RGB((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
