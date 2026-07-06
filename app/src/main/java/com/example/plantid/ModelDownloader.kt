package com.example.plantid

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(private val context: Context) {
    fun isModelDownloaded(fileName: String): Boolean {
        return File(context.filesDir, fileName).exists()
    }

    suspend fun downloadModel(urlStr: String, fileName: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            
            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val file = File(context.filesDir, fileName)
            val outputStream = FileOutputStream(file)

            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalRead: Long = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                
                // Update progress every megabyte to avoid spamming the UI thread
                if (totalRead % (1024 * 1024) < 4096) {
                    val progress = if (fileLength > 0) ((totalRead * 100) / fileLength).toInt() else 0
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            }

            outputStream.close()
            inputStream.close()
            
            // Reject small files (like 404 HTML pages)
            if (file.length() < 10 * 1024 * 1024) { // Less than 10MB is not a valid LLM model
                android.util.Log.e("ModelDownloader", "Downloaded file is too small to be a valid LLM model: ${file.length()} bytes")
                file.delete()
                return@withContext null
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
            }
            return@withContext file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
