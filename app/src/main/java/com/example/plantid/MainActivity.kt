package com.example.plantid

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var llmInferenceHelper: LlmInferenceHelper
    companion object {
        private const val TAG = "PlantIDApp"
        private const val GEMMA_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task"
        private const val GEMMA_FILENAME = "gemma-4-E2B-it-web.task"
    }
    private var lastCapturedBitmap: Bitmap? = null

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

        initializeApp()

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.downloadOverlay.visibility = View.VISIBLE
        binding.downloadStatusText.text = "Downloading Gemma AI (~2.5GB)..."
        binding.downloadProgressBar.progress = 0

        val downloader = ModelDownloader(this)
        lifecycleScope.launch {
            val file = downloader.downloadModel(GEMMA_URL, GEMMA_FILENAME) { progress ->
                binding.downloadProgressBar.progress = progress
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.downloadOverlay.visibility = View.GONE
            if (file != null) {
                Toast.makeText(this@MainActivity, "Gemma Model Downloaded!", Toast.LENGTH_SHORT).show()
                startPlantDoctor(bitmap)
            } else {
                Toast.makeText(this@MainActivity, "Failed to download model.", Toast.LENGTH_SHORT).show()
            }
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
    }

}
