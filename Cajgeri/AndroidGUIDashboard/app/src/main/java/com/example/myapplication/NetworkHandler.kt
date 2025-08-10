package com.example.myapplication

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader

data class ConnectedDevice(
    val ipAddress: String,
    val macAddress: String,
    val vendor: String = "Unknown"
)

// Composable komponenta za prikaz jednog uređaja
@Composable
fun DeviceItem(device: ConnectedDevice) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)

    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = device.ipAddress,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "MAC: ${device.macAddress}",
                fontSize = 14.sp,
                color = Color.Gray
            )

            if (device.vendor != "Unknown") {
                Text(
                    text = "Vendor: ${device.vendor}",
                    fontSize = 14.sp,
                    color = Color.Blue
                )
            }
        }
    }
}

// Composable za prikaz liste uređaja
@Composable
fun ConnectedDevicesScreen() {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<ConnectedDevice>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val deviceScanner = remember { HotspotDeviceScanner(context) }

    LaunchedEffect(Unit) {
        scanDevices(deviceScanner, devices, isLoading, errorMessage) { newDevices, loading, error ->
            devices = newDevices
            isLoading = loading
            errorMessage = error
        }
    }

    Scaffold(
        topBar = {


        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (errorMessage != null) {
                ErrorMessage(errorMessage!!)
            } else {
                DevicesList(devices)
            }
        }
    }
}

@Composable
fun DevicesList(devices: List<ConnectedDevice>) {
    if (devices.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No devices connected to hotspot",
                fontSize = 16.sp,
                color = Color.Gray
            )
        }
    } else {
        Column {
            Text(
                text = "Connected devices: ${devices.size}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn {
                items(devices) { device ->
                    DeviceItem(device)
                }
            }
        }
    }
}

@Composable
fun ErrorMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color.Red,
            fontSize = 16.sp
        )
    }
}

// Funkcija za skeniranje uređaja
private fun scanDevices(
    scanner: HotspotDeviceScanner,
    currentDevices: List<ConnectedDevice>,
    isLoading: Boolean,
    errorMessage: String?,
    onResult: (List<ConnectedDevice>, Boolean, String?) -> Unit
) {
    onResult(currentDevices, true, null)

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val devices = scanner.getConnectedDevices()
            withContext(Dispatchers.Main) {
                onResult(devices, false, null)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(emptyList(), false, "Error scanning devices: ${e.message}")
            }
        }
    }
}

// Klasa za skeniranje uređaja
class HotspotDeviceScanner(private val context: Context) {

    private val TAG = "HotspotScanner"

    fun getConnectedDevices(): List<ConnectedDevice> {
        return try {
            val devices = readArpTable()
            devices.map { device ->
                device.copy(vendor = getVendorFromMac(device.macAddress))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning devices", e)
            emptyList()
        }
    }

    private fun readArpTable(): List<ConnectedDevice> {
        val devices = mutableListOf<ConnectedDevice>()

        try {
            val br = BufferedReader(FileReader("/proc/net/arp"))
            var line: String?

            // Preskoči zaglavlje
            br.readLine()

            while (br.readLine().also { line = it } != null) {
                val parts = line!!.trim().split("\\s+".toRegex())

                if (parts.size >= 6) {
                    val ipAddress = parts[0]
                    val macAddress = parts[3]
                    val flags = parts[2]

                    // Proveri da li je uređaj aktivno povezan
                    if (flags == "0x2" &&
                        macAddress != "00:00:00:00:00:00" &&
                        isValidIpAddress(ipAddress) &&
                        !isLocalAddress(ipAddress)) {

                        devices.add(ConnectedDevice(ipAddress, macAddress))
                    }
                }
            }

            br.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ARP table", e)
        }

        return devices
    }

    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false

            parts.all { part ->
                val num = part.toIntOrNull() ?: return false
                num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isLocalAddress(ip: String): Boolean {
        return ip.startsWith("127.") || ip == "0.0.0.0"
    }

    private fun getVendorFromMac(mac: String): String {
        // Mapa poznatih vendor MAC prefiksa
        val vendorMap = mapOf(
            "00:50:56" to "VMware",
            "00:0C:29" to "VMware",
            "00:1C:42" to "Parallels",
            "00:05:69" to "VMware",
            "08:00:27" to "VirtualBox",
            "02:42:AC" to "Docker",
            "52:54:00" to "QEMU/KVM",
            "00:16:3E" to "Xen",
            "AC:DE:48" to "Private",
            "00:15:5D" to "Hyper-V",
            "00:1B:44" to "SanDisk",
            "00:1E:AE" to "Continental",
            "00:21:27" to "Samsung",
            "00:23:DF" to "Apple",
            "00:24:2B" to "Hon Hai",
            "00:25:BB" to "Apple",
            "00:26:BB" to "Apple",
            "00:1D:4F" to "Apple",
            "00:1E:C2" to "Apple",
            "00:1F:5B" to "Apple",
            "00:1F:F3" to "Apple",
            "00:21:E9" to "Apple",
            "00:23:12" to "Apple",
            "00:23:32" to "Apple",
            "00:23:6C" to "Apple",
            "00:23:DF" to "Apple",
            "00:24:36" to "Apple",
            "00:25:00" to "Apple",
            "00:26:4A" to "Apple",
            "00:26:B0" to "Apple",
            "00:26:BB" to "Apple",
            "00:11:75" to "Intel",
            "00:12:F0" to "Intel",
            "00:13:02" to "Intel",
            "00:13:20" to "Intel",
            "00:13:CE" to "Intel",
            "00:13:E8" to "Intel",
            "00:15:00" to "Intel",
            "00:15:17" to "Intel",
            "00:16:6F" to "Intel",
            "00:16:76" to "Intel",
            "00:16:EB" to "Intel",
            "00:17:08" to "Intel",
            "00:17:31" to "Intel",
            "00:18:39" to "Intel",
            "00:19:D1" to "Intel",
            "00:1B:21" to "Intel",
            "00:1B:77" to "Intel",
            "00:1C:C0" to "Intel",
            "00:1D:E0" to "Intel",
            "00:1E:64" to "Intel",
            "00:1E:67" to "Intel",
            "00:1F:3B" to "Intel",
            "00:1F:3C" to "Intel",
            "00:21:5D" to "Intel",
            "00:21:6A" to "Intel",
            "00:21:6B" to "Intel",
            "00:22:FB" to "Intel",
            "00:23:14" to "Intel",
            "00:24:D6" to "Intel",
            "00:24:D7" to "Intel",
            "00:26:C6" to "Intel",
            "00:26:C7" to "Intel",
            "00:AA:00" to "Intel",
            "00:40:96" to "Cisco",
            "00:17:94" to "Samsung",
            "00:18:AF" to "Samsung",
            "00:1B:98" to "Samsung",
            "00:1C:43" to "Samsung",
            "00:1D:25" to "Samsung",
            "00:1E:7D" to "Samsung",
            "00:1E:B0" to "Samsung",
            "00:1F:CD" to "Samsung",
            "00:21:4C" to "Samsung",
            "00:22:2D" to "Samsung",
            "00:23:39" to "Samsung",
            "00:24:54" to "Samsung",
            "00:25:66" to "Samsung",
            "00:26:5D" to "Samsung",
            "00:26:75" to "Samsung",
            "00:26:F3" to "Samsung",
            "00:27:F4" to "Samsung",
            "00:27:FC" to "Samsung",
            "00:28:FF" to "Samsung",
            "00:30:48" to "Super Micro",
            "00:30:67" to "Biostar",
            "00:30:C1" to "Systeme Lauer",
            "00:30:F1" to "Accton",
            "00:30:F2" to "Nortel",
            "00:30:F3" to "LanCell",
            "00:30:F4" to "TeraForce",
            "00:30:F5" to "TurboComm",
            "00:30:F6" to "Biostar",
            "00:30:F7" to "BVM",
            "00:30:F8" to "Asante",
            "00:30:F9" to "Terawave",
            "00:30:FA" to "LanAccess",
            "00:30:FB" to "Mega Vision",
            "00:30:FC" to "Aaeon",
            "00:30:FD" to "Artesyn Embedded",
            "00:30:FE" to "PCTEL",
            "00:30:FF" to "HMS Industrial",
            "00:50:C2" to "IEEE Registration",
            "00:50:C3" to "Uniden",
            "00:50:C4" to "Dell",
            "00:50:C5" to "Compaq",
            "00:50:C6" to "3Com",
            "00:50:C7" to "Nortel",
            "00:50:C8" to "Cisco",
            "00:50:C9" to "Avaya",
            "00:50:CA" to "DEC",
            "00:50:CB" to "3Com",
            "00:50:CC" to "Apple",
            "00:50:CD" to "Nortel",
            "00:50:CE" to "Tektronix",
            "00:50:CF" to "3Com",
            "00:50:D0" to "Dell",
            "00:50:D1" to "3Com",
            "00:50:D2" to "Avaya",
            "00:50:D3" to "Apple",
            "00:50:D4" to "3Com",
            "00:50:D5" to "Nortel",
            "00:50:D6" to "Cisco",
            "00:50:D7" to "Dell",
            "00:50:D8" to "3Com",
            "00:50:D9" to "Avaya",
            "00:50:DA" to "Apple",
            "00:50:DB" to "3Com",
            "00:50:DC" to "Nortel",
            "00:50:DD" to "Cisco",
            "00:50:DE" to "Dell",
            "00:50:DF" to "3Com",
            "00:50:E0" to "Avaya",
            "00:50:E1" to "Apple",
            "00:50:E2" to "3Com",
            "00:50:E3" to "Nortel",
            "00:50:E4" to "Cisco",
            "00:50:E5" to "Dell",
            "00:50:E6" to "3Com",
            "00:50:E7" to "Avaya",
            "00:50:E8" to "Apple",
            "00:50:E9" to "3Com",
            "00:50:EA" to "Nortel",
            "00:50:EB" to "Cisco",
            "00:50:EC" to "Dell",
            "00:50:ED" to "3Com",
            "00:50:EE" to "Avaya",
            "00:50:EF" to "Apple",
            "00:50:F0" to "3Com",
            "00:50:F1" to "Nortel",
            "00:50:F2" to "Microsoft",
            "00:50:F3" to "Cisco",
            "00:50:F4" to "Dell",
            "00:50:F5" to "3Com",
            "00:50:F6" to "Avaya",
            "00:50:F7" to "Apple",
            "00:50:F8" to "3Com",
            "00:50:F9" to "Nortel",
            "00:50:FA" to "Cisco",
            "00:50:FB" to "Dell",
            "00:50:FC" to "3Com",
            "00:50:FD" to "Avaya",
            "00:50:FE" to "Apple",
            "00:50:FF" to "3Com",
            "00:60:00" to "XYCOM",
            "00:60:01" to "InnoSys",
            "00:60:02" to "Screen Subtitling",
            "00:60:03" to "Teraoka",
            "00:60:04" to "Computadores",
            "00:60:05" to "Creative",
            "00:60:06" to "Artiza",
            "00:60:07" to "Fujitsu",
            "00:60:08" to "Electronics",
            "00:60:09" to "Broadband",
            "00:60:0A" to "Elonex",
            "00:60:0B" to "Dot",
            "00:60:0C" to "DSC",
            "00:60:0D" to "Nexus",
            "00:60:0E" to "Yamaha",
            "00:60:0F" to "Net2Edge",
            "00:60:10" to "DNI",
            "00:60:11" to "Paxdata",
            "00:60:12" to "Shindengen",
            "00:60:13" to "MCM",
            "00:60:14" to "NTP",
            "00:60:15" to "NetScout",
            "00:60:16" to "Quotron",
            "00:60:17" to "Raynet",
            "00:60:18" to "SPECTRA",
            "00:60:19" to "Pioneer",
            "00:60:1A" to "BTM",
            "00:60:1B" to "Nippon",
            "00:60:1C" to "Opus",
            "00:60:1D" to "Crest",
            "00:60:1E" to "Nokia",
            "00:60:1F" to "Nortel",
            "00:60:20" to "Scan",
            "00:60:21" to "Scitex",
            "00:60:22" to "Integr",
            "00:60:23" to "SECO",
            "00:60:24" to "Oracle",
            "00:60:25" to "Redlake",
            "00:60:26" to "Luxcom",
            "00:60:27" to "Compu",
            "00:60:28" to "Dell",
            "00:60:29" to "Pingtel",
            "00:60:2A" to "Multidata",
            "00:60:2B" to "Toyo",
            "00:60:2C" to "Intrinsyc",
            "00:60:2D" to "KeunYoung",
            "00:60:2E" to "Nippon",
            "00:60:2F" to "DVS",
            "00:60:30" to "VADEM",
            "00:60:31" to "EPiCON",
            "00:60:32" to "Fluke",
            "00:60:33" to "NetScout",
            "00:60:34" to "Amati",
            "00:60:35" to "Pronet",
            "00:60:36" to "DSC",
            "00:60:37" to "Nexus",
            "00:60:38" to "Yamaha",
            "00:60:39" to "Net2Edge",
            "00:60:3A" to "DNI",
            "00:60:3B" to "Paxdata",
            "00:60:3C" to "Shindengen",
            "00:60:3D" to "MCM",
            "00:60:3E" to "NTP",
            "00:60:3F" to "NetScout",
            "00:60:40" to "Quotron",
            "00:60:41" to "Raynet",
            "00:60:42" to "SPECTRA",
            "00:60:43" to "Pioneer",
            "00:60:44" to "BTM",
            "00:60:45" to "Nippon",
            "00:60:46" to "Opus",
            "00:60:47" to "Crest",
            "00:60:48" to "Nokia",
            "00:60:49" to "Nortel",
            "00:60:4A" to "Scan",
            "00:60:4B" to "Scitex",
            "00:60:4C" to "Integr",
            "00:60:4D" to "SECO",
            "00:60:4E" to "Oracle",
            "00:60:4F" to "Redlake",
            "00:60:50" to "Luxcom",
            "00:60:51" to "Compu",
            "00:60:52" to "Dell",
            "00:60:53" to "Pingtel",
            "00:60:54" to "Multidata",
            "00:60:55" to "Toyo",
            "00:60:56" to "Intrinsyc",
            "00:60:57" to "KeunYoung",
            "00:60:58" to "Nippon",
            "00:60:59" to "DVS",
            "00:60:5A" to "VADEM",
            "00:60:5B" to "EPiCON",
            "00:60:5C" to "Fluke",
            "00:60:5D" to "NetScout",
            "00:60:5E" to "Amati",
            "00:60:5F" to "Pronet",
            "00:60:60" to "DSC",
            "00:60:61" to "Nexus",
            "00:60:62" to "Yamaha",
            "00:60:63" to "Net2Edge",
            "00:60:64" to "DNI",
            "00:60:65" to "Paxdata",
            "00:60:66" to "Shindengen",
            "00:60:67" to "MCM",
            "00:60:68" to "NTP",
            "00:60:69" to "NetScout",
            "00:60:6A" to "Quotron",
            "00:60:6B" to "Raynet",
            "00:60:6C" to "SPECTRA",
            "00:60:6D" to "Pioneer",
            "00:60:6E" to "BTM",
            "00:60:6F" to "Nippon",
            "00:60:70" to "Opus",
            "00:60:71" to "Crest",
            "00:60:72" to "Nokia",
            "00:60:73" to "Nortel",
            "00:60:74" to "Scan",
            "00:60:75" to "Scitex",
            "00:60:76" to "Integr",
            "00:60:77" to "SECO",
            "00:60:78" to "Oracle",
            "00:60:79" to "Redlake",
            "00:60:7A" to "Luxcom",
            "00:60:7B" to "Compu",
            "00:60:7C" to "Dell",
            "00:60:7D" to "Pingtel",
            "00:60:7E" to "Multidata",
            "00:60:7F" to "Toyo",
            "00:60:80" to "Intrinsyc",
            "00:60:81" to "KeunYoung",
            "00:60:82" to "Nippon",
            "00:60:83" to "DVS",
            "00:60:84" to "VADEM",
            "00:60:85" to "EPiCON",
            "00:60:86" to "Fluke",
            "00:60:87" to "NetScout",
            "00:60:88" to "Amati",
            "00:60:89" to "Pronet",
            "00:60:8A" to "DSC",
            "00:60:8B" to "Nexus",
            "00:60:8C" to "Yamaha",
            "00:60:8D" to "Net2Edge",
            "00:60:8E" to "DNI",
            "00:60:8F" to "Paxdata",
            "00:60:90" to "Shindengen",
            "00:60:91" to "MCM",
            "00:60:92" to "NTP",
            "00:60:93" to "NetScout",
            "00:60:94" to "Quotron",
            "00:60:95" to "Raynet",
            "00:60:96" to "SPECTRA",
            "00:60:97" to "Pioneer",
            "00:60:98" to "BTM",
            "00:60:99" to "Nippon",
            "00:60:9A" to "Opus",
            "00:60:9B" to "Crest",
            "00:60:9C" to "Nokia",
            "00:60:9D" to "Nortel",
            "00:60:9E" to "Scan",
            "00:60:9F" to "Scitex",
            "00:60:A0" to "Integr",
            "00:60:A1" to "SECO",
            "00:60:A2" to "Oracle",
            "00:60:A3" to "Redlake",
            "00:60:A4" to "Luxcom",
            "00:60:A5" to "Compu",
            "00:60:A6" to "Dell",
            "00:60:A7" to "Pingtel",
            "00:60:A8" to "Multidata",
            "00:60:A9" to "Toyo",
            "00:60:AA" to "Intrinsyc",
            "00:60:AB" to "KeunYoung",
            "00:60:AC" to "Nippon",
            "00:60:AD" to "DVS",
            "00:60:AE" to "VADEM",
            "00:60:AF" to "EPiCON",
            "00:60:B0" to "Fluke",
            "00:60:B1" to "NetScout",
            "00:60:B2" to "Amati",
            "00:60:B3" to "Pronet",
            "00:60:B4" to "DSC",
            "00:60:B5" to "Nexus",
            "00:60:B6" to "Yamaha",
            "00:60:B7" to "Net2Edge",
            "00:60:B8" to "DNI",
            "00:60:B9" to "NEC",
            "00:60:BA" to "Paxdata",
            "00:60:BB" to "Shindengen",
            "00:60:BC" to "MCM",
            "00:60:BD" to "NTP",
            "00:60:BE" to "NetScout",
            "00:60:BF" to "Quotron",
            "00:60:C0" to "Raynet",
            "00:60:C1" to "SPECTRA",
            "00:60:C2" to "Pioneer",
            "00:60:C3" to "BTM",
            "00:60:C4" to "Nippon",
            "00:60:C5" to "Opus",
            "00:60:C6" to "Crest",
            "00:60:C7" to "Nokia",
            "00:60:C8" to "Nortel",
            "00:60:C9" to "Scan",
            "00:60:CA" to "Scitex",
            "00:60:CB" to "Integr",
            "00:60:CC" to "SECO",
            "00:60:CD" to "Oracle",
            "00:60:CE" to "Redlake",
            "00:60:CF" to "Luxcom",
            "00:60:D0" to "Compu",
            "00:60:D1" to "Dell",
            "00:60:D2" to "Pingtel",
            "00:60:D3" to "Multidata",
            "00:60:D4" to "Toyo",
            "00:60:D5" to "Intrinsyc",
            "00:60:D6" to "KeunYoung",
            "00:60:D7" to "Nippon",
            "00:60:D8" to "DVS",
            "00:60:D9" to "VADEM",
            "00:60:DA" to "EPiCON",
            "00:60:DB" to "Fluke",
            "00:60:DC" to "NetScout",
            "00:60:DD" to "Amati",
            "00:60:DE" to "Pronet",
            "00:60:DF" to "DSC",
            "00:60:E0" to "Nexus",
            "00:60:E1" to "Yamaha",
            "00:60:E2" to "Net2Edge",
            "00:60:E3" to "DNI",
            "00:60:E4" to "Paxdata",
            "00:60:E5" to "Shindengen",
            "00:60:E6" to "MCM",
            "00:60:E7" to "NTP",
            "00:60:E8" to "NetScout",
            "00:60:E9" to "Quotron",
            "00:60:EA" to "Raynet",
            "00:60:EB" to "SPECTRA",
            "00:60:EC" to "Pioneer",
            "00:60:ED" to "BTM",
            "00:60:EE" to "Nippon",
            "00:60:EF" to "Opus",
            "00:60:F0" to "Crest",
            "00:60:F1" to "Nokia",
            "00:60:F2" to "Nortel",
            "00:60:F3" to "Scan",
            "00:60:F4" to "Scitex",
            "00:60:F5" to "Integr",
            "00:60:F6" to "SECO",
            "00:60:F7" to "Oracle",
            "00:60:F8" to "Redlake",
            "00:60:F9" to "Luxcom",
            "00:60:FA" to "Compu",
            "00:60:FB" to "Dell",
            "00:60:FC" to "Pingtel",
            "00:60:FD" to "Multidata",
            "00:60:FE" to "Toyo",
            "00:60:FF" to "Intrinsyc"
        )

        val prefix = if (mac.length >= 8) mac.substring(0, 8).uppercase() else mac
        return vendorMap[prefix] ?: "Unknown"
    }
}