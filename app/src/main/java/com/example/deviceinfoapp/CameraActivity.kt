package com.example.deviceinfoapp

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FACING = "extra_facing"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnFlip: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var tvFacingLabel: TextView

    private var imageCapture: ImageCapture? = null
    private var currentFacing = CameraSelector.LENS_FACING_BACK
    private lateinit var cameraProvider: ProcessCameraProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView   = findViewById(R.id.previewView)
        btnCapture    = findViewById(R.id.btnCapture)
        btnFlip       = findViewById(R.id.btnFlip)
        btnClose      = findViewById(R.id.btnClose)
        btnGallery    = findViewById(R.id.btnGallery)
        tvFacingLabel = findViewById(R.id.tvFacingLabel)

        // Read which camera to start with (passed from JS → Bridge → MainActivity)
        val facing = intent.getStringExtra(EXTRA_FACING) ?: "back"
        currentFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK

        updateFacingLabel()
        startCamera()

        btnCapture.setOnClickListener { takePhoto() }
        btnFlip.setOnClickListener   { flipCamera() }
        btnClose.setOnClickListener  { finish() }
        btnGallery.setOnClickListener { openGallery() }
    }

    // ── Gallery ──────────────────────────────────────────────────────────────

    private fun openGallery() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        // Filter to only show images in our specific folder
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            // For older versions, we'd check DATA path, but Pictures/DeviceInfo 
            // is specifically a Scoped Storage / Q+ path in this app's logic.
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("Pictures/DeviceInfo%")
        } else null

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val contentUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "image/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "No photos found in DeviceInfo folder", Toast.LENGTH_SHORT).show()
            }
        } ?: Toast.makeText(this, "Could not access gallery", Toast.LENGTH_SHORT).show()
    }

    // ── Camera Setup ─────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentFacing)
            .build()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Flip Camera ───────────────────────────────────────────────────────────

    private fun flipCamera() {
        currentFacing = if (currentFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK
        updateFacingLabel()
        bindCameraUseCases()
    }

    private fun updateFacingLabel() {
        tvFacingLabel.text = if (currentFacing == CameraSelector.LENS_FACING_FRONT)
            "Front Camera" else "Back Camera"
    }

    // ── Capture Photo ─────────────────────────────────────────────────────────

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Save to MediaStore (works on all API levels)
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DeviceInfo")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(
                        this@CameraActivity,
                        "📸 Photo saved to Pictures/DeviceInfo",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@CameraActivity,
                        "Capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}