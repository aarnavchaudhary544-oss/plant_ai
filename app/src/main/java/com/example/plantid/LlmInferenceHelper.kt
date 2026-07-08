package com.example.plantid

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import java.io.File
import java.io.ByteArrayOutputStream
import android.util.Log
import android.graphics.Bitmap

class LlmInferenceHelper(
    private val context: Context,
    private val modelName: String = "gemma-4-E2B-v3.litertlm"
) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val TAG = "LlmInferenceHelper"

    private var initializationError: String? = null

    fun isModelAvailable(): Boolean {
        val externalDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val modelFile = File(externalDir, modelName)
        // Ensure the file is completely downloaded (2.58 GB)
        return modelFile.exists() && modelFile.length() >= 2580000000L
    }

    suspend fun initializeModel() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        val externalDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val modelFile = File(externalDir, modelName)
        if (!modelFile.exists()) return@withContext

        try {
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = 4096,
                maxNumImages = 1
            )
            
            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            engine = newEngine
            
            // Conversation can be reused or created per request, but we'll create one here
            conversation = newEngine.createConversation()
            
            Log.d(TAG, "LiteRT-LM Engine initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM Engine", e)
            initializationError = e.message ?: e.toString()
        }
    }

    fun getPlantAdvice(imageBitmap: Bitmap, customQuestion: String? = null): Flow<String> = callbackFlow {
        if (engine == null || conversation == null) {
            initializeModel()
        }
        
        if (engine == null || conversation == null) {
            val errorMsg = initializationError ?: "Model not available or failed to load."
            trySend(errorMsg)
            close()
            return@callbackFlow
        }

        val prompt = customQuestion ?: "Analyze this image and identify the plant. Then, provide a short, highly accurate, and concise summary of optimal growing conditions, watering needs, and common diseases."
        
        try {
            // Resize if too large
            val maxDim = 896
            var scaledBitmap = imageBitmap
            if (imageBitmap.width > maxDim || imageBitmap.height > maxDim) {
                val ratio = Math.min(maxDim.toFloat() / imageBitmap.width, maxDim.toFloat() / imageBitmap.height)
                val width = (ratio * imageBitmap.width).toInt()
                val height = (ratio * imageBitmap.height).toInt()
                scaledBitmap = Bitmap.createScaledBitmap(imageBitmap, width, height, true)
            }

            val stream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val byteArray = stream.toByteArray()
            
            val contentList = listOf(
                Content.ImageBytes(byteArray),
                Content.Text(prompt)
            )
            
            val contents = Contents.of(contentList)
            
            // sendMessageAsync returns a kotlinx.coroutines.flow.Flow<Message>
            // but we need to collect it within a coroutine!
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    conversation?.sendMessageAsync(contents)?.collect { message ->
                        trySend(message.toString())
                    }
                    close()
                } catch (e: Exception) {
                    trySend("Error generating response: ${e.message}")
                    close()
                }
            }
            
        } catch (e: Exception) {
            trySend("Error preparing request: ${e.message}")
            close()
        }

        awaitClose { 
            // We shouldn't necessarily close the engine here if we want to reuse it,
            // but we should clean up if needed.
        }
    }

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}
