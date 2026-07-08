package com.example.deviceinfoapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import java.util.Locale
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        const val CAMERA_REQUEST_CODE = 200
    }

    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0) ?: ""
            webView.evaluateJavascript("window.onSpeechRecognized('$spokenText')", null)
        }
    }

    fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something...")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun authenticateBiometric() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    webView.evaluateJavascript("window.onBiometricResult('Error: $errString')", null)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    webView.evaluateJavascript("window.onBiometricResult('Success: Authenticated!')", null)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    webView.evaluateJavascript("window.onBiometricResult('Failed: Not recognized')", null)
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Login")
            .setSubtitle("Log in using your biometric credential")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    fun openMaps(lat: Double, lng: Double) {
        val uri = "geo:$lat,$lng?q=$lat,$lng"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // ── WebViewAssetLoader ─────────────────────────────────────────────
        // Serves app assets over https://appassets.androidplatform.net/assets/
        // This is the modern, secure replacement for file:///android_asset/
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetsPathHandler(this))
            .build()

        // ── WebView Settings ───────────────────────────────────────────────
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false           // not needed with AssetLoader
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        // ── WebViewClient: intercept asset requests ────────────────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // Let the AssetLoader handle requests for our domain
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        // ── WebChromeClient: handle permissions ────────────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }

        // ── Bridge ─────────────────────────────────────────────────────────
        webView.addJavascriptInterface(DeviceBridge(this), "AndroidBridge")

        requestAppPermissions()

        // Load via the AssetLoader domain (not file:///)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    // Called from JavaScript: AndroidBridge.openCamera("back") / ("front")
    // We expose this via DeviceBridge, but the actual launch happens here
    fun launchCamera(facing: String) {
        val intent = Intent(this, CameraActivity::class.java)
        intent.putExtra(CameraActivity.EXTRA_FACING, facing)
        startActivity(intent)
    }

    fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivity(intent)
    }

    private fun requestAppPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            webView.reload()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}