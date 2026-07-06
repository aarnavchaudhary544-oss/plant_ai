package com.example.plantid

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.io.File
import android.util.Log

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener

class LlmInferenceHelper(
    private val context: Context,
    private val modelName: String = "gemma-4-E2B-it-web.task"
) {

    private var llmInference: LlmInference? = null
    private var llmSession: LlmInferenceSession? = null
    private val TAG = "LlmInferenceHelper"

    fun isModelAvailable(): Boolean {
        val modelFile = File(context.filesDir, modelName)
        // A valid LLM model will be well over 10MB (typically GBs)
        return modelFile.exists() && modelFile.length() > 10 * 1024 * 1024
    }

    fun initializeModel() {
        if (!isModelAvailable()) {
            Log.e(TAG, "Model file not found.")
            return
        }
        val modelFile = File(context.filesDir, modelName)
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .setMaxNumImages(1)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
            llmSession = LlmInferenceSession.createFromOptions(llmInference, sessionOptions)
            
            Log.d(TAG, "LLM initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LLM", e)
        }
    }

    fun getPlantAdvice(imageBitmap: Bitmap): Flow<String> = callbackFlow {
        if (llmInference == null || llmSession == null) {
            initializeModel()
        }
        
        if (llmInference == null || llmSession == null) {
            trySend("Model not available or failed to load.")
            close()
            return@callbackFlow
        }

        val prompt = "Analyze this image and identify the plant. Then, provide a short, highly accurate, and concise summary of optimal growing conditions, watering needs, and common diseases."
        
        try {
            val mpImage = BitmapImageBuilder(imageBitmap).build()
            
            // Add inputs to session
            llmSession?.addImage(mpImage)
            llmSession?.addQueryChunk(prompt)
            
            // Generate
            llmSession?.generateResponseAsync(ProgressListener { partialResult, done ->
                trySend(partialResult)
                if (done) {
                    close()
                }
            })
            
        } catch (e: Exception) {
            trySend("Error generating response: ${e.message}")
            close()
        }

        awaitClose { 
            // Optional: reset session if needed, but generateResponseAsync usually completes it
        }
    }

    fun close() {
        llmSession?.close()
        llmInference?.close()
        llmSession = null
        llmInference = null
    }
}
