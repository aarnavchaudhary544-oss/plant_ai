package com.example.plantid

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.plantid.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var llmInferenceHelper: LlmInferenceHelper
    companion object {
        private const val TAG = "PlantIDApp"
        private const val GEMMA_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val GEMMA_FILENAME = "gemma-4-E2B-v3.task"
    }
    private var lastCapturedBitmap: Bitmap? = null
    
    private lateinit var prefs: SharedPreferences
    private var downloadId: Long = -1L
    
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId && downloadId != -1L) {
                handleDownloadCompletion()
            }
        }
    }
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val inputStream: InputStream? = contentResolver.openInputStream(it)
                if (inputStream != null) {
                    var bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    
                    try {
                        val exifInputStream = contentResolver.openInputStream(it)
                        if (exifInputStream != null) {
                            val exif = ExifInterface(exifInputStream)
                            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                            val matrix = Matrix()
                            when (orientation) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            }
                            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            }
                            exifInputStream.close()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    
                    showImage(bitmap)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        prefs = getSharedPreferences("PlantIDPrefs", Context.MODE_PRIVATE)
        downloadId = prefs.getLong("download_id", -1L)

        initializeApp()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        
        // Resume UI polling if download is already in progress
        if (downloadId != -1L) {
            startDownloadPolling()
        }

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.galleryButton.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.clearButton.setOnClickListener {
            binding.galleryImageView.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            binding.clearButton.isEnabled = false
            binding.resultTextView.text = "Point camera at a plant..."
            binding.confidenceTextView.text = ""
            binding.doctorButton.visibility = View.GONE
            binding.doctorAdviceTextView.text = ""
            lastCapturedBitmap = null
        }
    }
    
    private fun initializeApp() {
        llmInferenceHelper = LlmInferenceHelper(this, GEMMA_FILENAME)
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.doctorButton.setOnClickListener {
            val bitmap = lastCapturedBitmap ?: return@setOnClickListener
            if (llmInferenceHelper.isModelAvailable()) {
                startPlantDoctor(bitmap)
            } else {
                downloadGemmaModel(bitmap)
            }
        }
    }

    private fun downloadGemmaModel(bitmap: Bitmap) {
        if (downloadId != -1L) {
            startDownloadPolling()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val externalDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val file = File(externalDir, GEMMA_FILENAME)
        if (file.exists()) {
            file.delete()
        }

        val request = DownloadManager.Request(Uri.parse(GEMMA_URL))
            .setTitle("Gemma AI Model")
            .setDescription("Downloading AI model for PlantID (~2.5GB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, GEMMA_FILENAME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = downloadManager.enqueue(request)
        prefs.edit().putLong("download_id", downloadId).apply()

        startDownloadPolling()
    }
    
    private fun startDownloadPolling() {
        binding.downloadOverlay.visibility = View.VISIBLE
        binding.downloadStatusText.text = "Downloading Gemma AI (~2.5GB) in background..."
        
        lifecycleScope.launch {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var isDownloading = true
            while (isActive && isDownloading && downloadId != -1L) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusIndex != -1) cursor.getInt(statusIndex) else DownloadManager.STATUS_FAILED
                    
                    val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    
                    if (downloadedIndex != -1 && totalIndex != -1) {
                        val bytesDownloaded = cursor.getLong(downloadedIndex)
                        val bytesTotal = cursor.getLong(totalIndex)
                        val lengthToUse = if (bytesTotal > 0) bytesTotal else 2588147712L
                        
                        val progress = ((bytesDownloaded * 100) / lengthToUse).toInt().coerceIn(0, 100)
                        binding.downloadProgressBar.progress = progress
                    }
                    
                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        isDownloading = false
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            handleDownloadCompletion()
                        } else {
                            binding.downloadOverlay.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "Download failed.", Toast.LENGTH_SHORT).show()
                            downloadId = -1L
                            prefs.edit().remove("download_id").apply()
                        }
                    }
                } else {
                    isDownloading = false
                }
                cursor?.close()
                delay(1000)
            }
        }
    }
    
    private fun handleDownloadCompletion() {
        if (downloadId == -1L) return
        downloadId = -1L
        prefs.edit().remove("download_id").apply()
        
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.downloadOverlay.visibility = View.GONE
        Toast.makeText(this@MainActivity, "Gemma Model Downloaded!", Toast.LENGTH_SHORT).show()
        
        lastCapturedBitmap?.let { bitmap ->
            startPlantDoctor(bitmap)
        }
    }

    private fun startPlantDoctor(bitmap: Bitmap) {
        binding.doctorButton.isEnabled = false
        binding.doctorAdviceTextView.text = "Consulting Plant Doctor (Gemma Vision)...\n\n"
        
        lifecycleScope.launch {
            llmInferenceHelper.getPlantAdvice(bitmap).collect { chunk ->
                binding.doctorAdviceTextView.append(chunk)
                
                // Scroll to bottom
                val scrollView = binding.doctorAdviceTextView.parent as? android.widget.ScrollView
                scrollView?.post {
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
            binding.doctorButton.isEnabled = true
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        
        binding.resultTextView.text = "Capturing..."
        binding.confidenceTextView.text = ""

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    
                    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
                    
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    if (rotationDegrees != 0) {
                        val matrix = Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    }
                    
                    showImage(bitmap)
                    image.close()
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    runOnUiThread {
                        binding.resultTextView.text = "Capture failed"
                    }
                }
            }
        )
    }
    
    private fun showImage(bitmap: Bitmap) {
        runOnUiThread {
            binding.viewFinder.visibility = View.GONE
            binding.galleryImageView.visibility = View.VISIBLE
            binding.galleryImageView.setImageBitmap(bitmap)
            binding.clearButton.isEnabled = true
            binding.resultTextView.text = "Ready to Identify!"
            binding.doctorButton.visibility = View.VISIBLE
            lastCapturedBitmap = bitmap
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        llmInferenceHelper.close()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

}
