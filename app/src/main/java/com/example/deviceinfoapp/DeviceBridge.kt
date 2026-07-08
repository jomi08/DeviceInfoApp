package com.example.deviceinfoapp

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

class DeviceBridge(private val context: Context) {

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun Int.ipToString(): String =
        "${this and 0xFF}.${(this shr 8) and 0xFF}.${(this shr 16) and 0xFF}.${(this shr 24) and 0xFF}"

    // ── Camera Launch ────────────────────────────────────────────────────────

    @JavascriptInterface
    fun openBackCamera() {
        (context as? MainActivity)?.launchCamera("back")
    }

    @JavascriptInterface
    fun openFrontCamera() {
        (context as? MainActivity)?.launchCamera("front")
    }

    // ── Device Info ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getDeviceInfo(): String = runCatching {
        JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("hardware", Build.HARDWARE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("buildId", Build.ID)
            put("board", Build.BOARD)
            put("bootloader", Build.BOOTLOADER)
            put("product", Build.PRODUCT)
        }.toString()
    }.getOrDefault("{}")

    // ── Network Info ─────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getNetworkInfo(): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val obj = JSONObject()
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)

        if (caps == null) {
            obj.put("connected", false)
            obj.put("type", "None")
            return obj.toString()
        }

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)      -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)  -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)  -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }

        obj.put("connected", true)
        obj.put("type", type)
        obj.put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        obj.put("bandwidth", "%.1f Mbps".format(caps.linkDownstreamBandwidthKbps / 1000.0))
        obj.put("downstreamBandwidth", "${caps.linkDownstreamBandwidthKbps} Kbps")
        obj.put("upstreamBandwidth", "${caps.linkUpstreamBandwidthKbps} Kbps")

        if (type == "WiFi") {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wi = wm.connectionInfo
            obj.put("ssid", wi.ssid)
            obj.put("bssid", wi.bssid ?: "—")
            obj.put("signalStrength", "${wi.rssi} dBm")
            obj.put("linkSpeed", "${wi.linkSpeed} Mbps")
            obj.put("frequency", "${wi.frequency} MHz")
            obj.put("ipAddress", wi.ipAddress.ipToString())
        }
        obj.toString()
    }.getOrDefault("{}")

    // ── CPU / Memory ─────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getCpuInfo(): String = runCatching {
        val rt = Runtime.getRuntime()
        JSONObject().apply {
            put("cpuCores", rt.availableProcessors())
            put("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown")
            put("allAbis", Build.SUPPORTED_ABIS.joinToString(", "))
            put("hardware", Build.HARDWARE)
            put("maxMemory", "%.0f MB".format(rt.maxMemory() / 1_048_576.0))
            put("totalMemory", "%.0f MB".format(rt.totalMemory() / 1_048_576.0))
            put("freeMemory", "%.0f MB".format(rt.freeMemory() / 1_048_576.0))
        }.toString()
    }.getOrDefault("{}")

    // ── Locale ───────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getLocaleInfo(): String = runCatching {
        val locale = Locale.getDefault()
        val tz     = TimeZone.getDefault()
        JSONObject().apply {
            put("language", locale.displayLanguage)
            put("country", locale.displayCountry)
            put("languageCode", locale.language)
            put("countryCode", locale.country)
            put("timezone", tz.id)
            put("gmtOffset", "${tz.rawOffset / 3_600_000} hrs")
        }.toString()
    }.getOrDefault("{}")

    // ── Camera Features ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun getCameraInfo(): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        JSONObject().apply {
            put("hasAnyCamera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
            put("hasBackCamera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA))
            put("hasFrontCamera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
            put("hasFlash", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
            put("hasAutofocus", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS))
            put("cameraCount", android.hardware.Camera.getNumberOfCameras())
        }.toString()
    }.getOrDefault("{}")

    // ── Sensors ──────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getSensorList(): String = runCatching {
        val sm      = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        val arr     = JSONArray()
        sensors.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("vendor", s.vendor)
                put("version", s.version)
                put("power", "${s.power} mA")
            })
        }
        JSONObject().apply {
            put("count", sensors.size)
            put("sensors", arr)
        }.toString()
    }.getOrDefault("{}")

    // ── Biometric Info ───────────────────────────────────────────────────────

    @JavascriptInterface
    fun getBiometricInfo(): String = runCatching {
        val biometricManager = androidx.biometric.BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or 
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        JSONObject().apply {
            put("available", canAuthenticate == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)
            put("status", when (canAuthenticate) {
                androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> "Supported"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No hardware"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware unavailable"
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "None enrolled"
                else -> "Unknown"
            })
        }.toString()
    }.getOrDefault("{}")

    @JavascriptInterface
    fun authenticate() {
        (context as? MainActivity)?.runOnUiThread {
            (context as? MainActivity)?.authenticateBiometric()
        }
    }

    // ── Audio Info ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getAudioInfo(): String = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        JSONObject().apply {
            put("mode", when (am.mode) {
                android.media.AudioManager.MODE_NORMAL -> "Normal"
                android.media.AudioManager.MODE_RINGTONE -> "Ringtone"
                android.media.AudioManager.MODE_IN_CALL -> "In Call"
                android.media.AudioManager.MODE_IN_COMMUNICATION -> "Communication"
                else -> "Unknown"
            })
            put("isMusicActive", am.isMusicActive)
            put("isSpeakerphoneOn", am.isSpeakerphoneOn)
            put("isMicrophoneMuted", am.isMicrophoneMute)
            put("volumeMusic", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            put("maxVolumeMusic", am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
        }.toString()
    }.getOrDefault("{}")

    // ── GPS Status ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getGpsStatus(): String = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        JSONObject().apply {
            put("gpsEnabled", isGpsEnabled)
            put("networkEnabled", isNetworkEnabled)
            put("locationEnabled", isGpsEnabled || isNetworkEnabled)
        }.toString()
    }.getOrDefault("{}")

    // ── Gallery Picker ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun openGallery() {
        (context as? MainActivity)?.openGallery()
    }

    // ── Speech to Text ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun startSpeechToText() {
        (context as? MainActivity)?.runOnUiThread {
            (context as? MainActivity)?.startSpeechToText()
        }
    }

    @JavascriptInterface
    fun openMaps(lat: Double, lng: Double) {
        (context as? MainActivity)?.openMaps(lat, lng)
    }

    // ── Geocoding ───────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getAddress(lat: Double, lng: Double): String = runCatching {
        val geocoder = android.location.Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(lat, lng, 1)
        addresses?.firstOrNull()?.getAddressLine(0) ?: "Address not found"
    }.getOrDefault("Address service unavailable")
}
