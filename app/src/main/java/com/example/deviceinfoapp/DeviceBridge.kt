package com.example.deviceinfoapp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

class DeviceBridge(private val context: Context) {

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun Long.toFormattedBytes(): String = when {
        this < 1_024L             -> "$this B"
        this < 1_048_576L         -> "%.1f KB".format(this / 1_024.0)
        this < 1_073_741_824L     -> "%.1f MB".format(this / 1_048_576.0)
        else                      -> "%.2f GB".format(this / 1_073_741_824.0)
    }

    private fun Int.ipToString(): String =
        "${this and 0xFF}.${(this shr 8) and 0xFF}.${(this shr 16) and 0xFF}.${(this shr 24) and 0xFF}"

    // ── Camera Launch ────────────────────────────────────────────────────────
    // These are called from JavaScript via AndroidBridge.openBackCamera()
    // We cast to MainActivity so we can call launchCamera()

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

    // ── Screen Info ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getScreenInfo(): String = runCatching {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getMetrics(metrics)

        val densityLabel = when {
            metrics.densityDpi <= 120 -> "ldpi"
            metrics.densityDpi <= 160 -> "mdpi"
            metrics.densityDpi <= 240 -> "hdpi"
            metrics.densityDpi <= 320 -> "xhdpi"
            metrics.densityDpi <= 480 -> "xxhdpi"
            else                      -> "xxxhdpi"
        }

        JSONObject().apply {
            put("widthPx", metrics.widthPixels)
            put("heightPx", metrics.heightPixels)
            put("density", metrics.density)
            put("densityDpi", metrics.densityDpi)
            put("densityLabel", densityLabel)
            put("xdpi", "%.1f".format(metrics.xdpi))
            put("ydpi", "%.1f".format(metrics.ydpi))
        }.toString()
    }.getOrDefault("{}")

    // ── Battery Info ─────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getBatteryInfo(): String = runCatching {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return "{}"

        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val temp    = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val health  = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val tech    = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
        val pct     = if (scale > 0) "%.0f".format(level / scale.toFloat() * 100) else "?"

        JSONObject().apply {
            put("percentage", pct)
            put("status", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING     -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING  -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL         -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                else -> "Unknown"
            })
            put("pluggedVia", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC       -> "AC Charger"
                BatteryManager.BATTERY_PLUGGED_USB      -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Unplugged"
            })
            put("temperature", "${temp / 10.0}°C")
            put("voltage", "$voltage mV")
            put("health", when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD         -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT     -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD         -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                else -> "Unknown"
            })
            put("technology", tech)
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

    // ── Storage Info ─────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getStorageInfo(): String = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val total     = stat.blockCountLong * blockSize
        val free      = stat.availableBlocksLong * blockSize
        val used      = total - free

        JSONObject().apply {
            put("totalInternal", total.toFormattedBytes())
            put("usedInternal", used.toFormattedBytes())
            put("freeInternal", free.toFormattedBytes())
            put("usedPercent", "%.1f".format(if (total > 0) used / total.toDouble() * 100 else 0.0))
            val extAvail = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
            put("externalMounted", extAvail)
            if (extAvail) {
                val ext = StatFs(Environment.getExternalStorageDirectory().path)
                val eb  = ext.blockSizeLong
                put("totalExternal", (ext.blockCountLong * eb).toFormattedBytes())
                put("freeExternal", (ext.availableBlocksLong * eb).toFormattedBytes())
            }
        }.toString()
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

    // ── Vibrate ──────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun vibrate(milliseconds: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(
                        milliseconds.toLong(), VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(
                        milliseconds.toLong(), VibrationEffect.DEFAULT_AMPLITUDE
                    ))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(milliseconds.toLong())
                }
            }
        }
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