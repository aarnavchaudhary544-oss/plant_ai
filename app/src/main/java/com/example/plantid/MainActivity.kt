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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.os.Build
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

import android.widget.EditText
import android.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var llmInferenceHelper: LlmInferenceHelper
    companion object {
        private const val TAG = "PlantIDApp"
        private const val GEMMA_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val GEMMA_FILENAME = "gemma-4-E2B-v3.litertlm"
    }
    private var lastCapturedBitmap: Bitmap? = null
    
    private var isDownloading = false
    
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_PROGRESS -> {
                    val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                    binding.downloadProgressBar.progress = progress
                    if (binding.downloadOverlay.visibility == View.GONE) {
                        binding.downloadOverlay.visibility = View.VISIBLE
                        binding.downloadStatusText.text = "Downloading Gemma AI (~2.58GB) in background..."
                    }
                }
                DownloadService.ACTION_COMPLETED -> {
                    handleDownloadCompletion()
                }
                DownloadService.ACTION_FAILED -> {
                    isDownloading = false
                    binding.downloadOverlay.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Download failed.", Toast.LENGTH_SHORT).show()
                }
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
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_PROGRESS)
            addAction(DownloadService.ACTION_COMPLETED)
            addAction(DownloadService.ACTION_FAILED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
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
            binding.askQuestionButton.visibility = View.GONE
            binding.doctorAdviceTextView.text = ""
            binding.chatContainer.visibility = View.GONE
            lastCapturedBitmap = null
        }
        
        binding.closeChatButton.setOnClickListener {
            binding.chatContainer.visibility = View.GONE
            binding.galleryImageView.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            binding.bottomCard.visibility = View.VISIBLE
            binding.clearButton.isEnabled = false
            binding.resultTextView.text = "Point camera at a plant..."
            binding.confidenceTextView.text = ""
            binding.doctorButton.visibility = View.GONE
            binding.askQuestionButton.visibility = View.GONE
            binding.chatResponseTextView.text = ""
            lastCapturedBitmap = null
        }
        
        initializeApp()
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
                startPlantDoctor(bitmap, null)
            } else {
                downloadGemmaModel(bitmap)
            }
        }

        binding.askQuestionButton.setOnClickListener {
            val bitmap = lastCapturedBitmap ?: return@setOnClickListener
            if (llmInferenceHelper.isModelAvailable()) {
                showQuestionPopup(bitmap)
            } else {
                downloadGemmaModel(bitmap)
            }
        }
    }

    private fun showQuestionPopup(bitmap: Bitmap) {
        val editText = EditText(this)
        editText.hint = "Ask a question about this plant..."
        
        AlertDialog.Builder(this)
            .setTitle("Ask a Question")
            .setView(editText)
            .setPositiveButton("Ask") { _, _ ->
                val question = editText.text.toString()
                if (question.isNotBlank()) {
                    startPlantDoctor(bitmap, question)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadGemmaModel(bitmap: Bitmap, isInstantId: Boolean = false) {
        if (isDownloading) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isDownloading = true
        
        binding.downloadOverlay.visibility = View.VISIBLE
        binding.downloadStatusText.text = "Downloading Gemma AI (~2.58GB) in background..."
        binding.downloadProgressBar.progress = 0

        val serviceIntent = Intent(this, DownloadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download service", e)
            Toast.makeText(this, "Failed to start background download.", Toast.LENGTH_SHORT).show()
            isDownloading = false
            binding.downloadOverlay.visibility = View.GONE
        }
    }
    
    private fun handleDownloadCompletion() {
        isDownloading = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.downloadOverlay.visibility = View.GONE
        Toast.makeText(this@MainActivity, "Gemma Model Downloaded!", Toast.LENGTH_SHORT).show()
        
        lastCapturedBitmap?.let { bitmap ->
            startPlantDoctor(bitmap, null)
        }
    }

    private fun startPlantDoctor(bitmap: Bitmap, customQuestion: String?) {
        binding.doctorButton.isEnabled = false
        binding.askQuestionButton.isEnabled = false
        
        // Transition to Chat UI
        binding.galleryImageView.visibility = View.GONE
        binding.bottomCard.visibility = View.GONE
        binding.chatContainer.visibility = View.VISIBLE
        
        binding.chatImageView.setImageBitmap(bitmap)
        val currentName = binding.resultTextView.text.toString().trim()
        binding.chatPlantNameTextView.text = if (currentName == "Ready to Identify!" || currentName == "Identifying with Gemma...") "Unknown Plant" else currentName
        
        if (customQuestion == null) {
            binding.chatResponseTextView.text = "Consulting Plant Doctor (Gemma Vision)...\n\n"
        } else {
            binding.chatResponseTextView.text = "Asking: \"$customQuestion\"\n\n"
        }
        
        lifecycleScope.launch {
            llmInferenceHelper.getPlantAdvice(bitmap, customQuestion).collect { chunk ->
                binding.chatResponseTextView.append(chunk)
                
                // Scroll to bottom
                val scrollView = binding.chatResponseTextView.parent as? android.widget.ScrollView
                scrollView?.post {
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
            binding.doctorButton.isEnabled = true
            binding.askQuestionButton.isEnabled = true
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
            binding.confidenceTextView.text = ""
            binding.doctorButton.visibility = View.VISIBLE
            binding.askQuestionButton.visibility = View.VISIBLE
            lastCapturedBitmap = bitmap
            
            if (llmInferenceHelper.isModelAvailable()) {
                identifyWithGemma(bitmap)
            } else {
                downloadGemmaModel(bitmap, isInstantId = true)
            }
        }
    }
    
    private fun identifyWithGemma(bitmap: Bitmap) {
        binding.resultTextView.text = "Identifying with Gemma..."
        lifecycleScope.launch {
            val shortPrompt = "What is the precise name of this plant? Reply with ONLY the name of the plant, nothing else."
            var fullName = ""
            llmInferenceHelper.getPlantAdvice(bitmap, shortPrompt).collect { chunk ->
                fullName += chunk
                binding.resultTextView.text = fullName.trim()
            }
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
