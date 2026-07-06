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
            var url = URL(urlStr)
            var connection = url.openConnection() as HttpURLConnection
            var redirect = false
            
            // Handle redirects (up to 3 times)
            for (i in 0..3) {
                connection.connect()
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK && (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_SEE_OTHER)) {
                    val newUrlStr = connection.getHeaderField("Location")
                    connection.disconnect()
                    url = URL(newUrlStr)
                    connection = url.openConnection() as HttpURLConnection
                    redirect = true
                } else {
                    break
                }
            }
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.e("ModelDownloader", "Failed to download model, HTTP code: ${connection.responseCode}")
                return@withContext null
            }
            
            val fileLength = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }
            // Fallback for chunked transfer or missing header
            val lengthToUse = if (fileLength > 0) fileLength else 2588147712L
            
            val inputStream = connection.inputStream
            
            val tempFile = File(context.filesDir, "$fileName.tmp")
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead: Long = 0
            var lastProgress = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                
                val progress = ((totalRead * 100) / lengthToUse).toInt().coerceIn(0, 100)
                if (progress != lastProgress) {
                    lastProgress = progress
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            }

            outputStream.close()
            inputStream.close()
            
            // Reject small files
            if (tempFile.length() < 10 * 1024 * 1024) { // Less than 10MB is not a valid LLM model
                android.util.Log.e("ModelDownloader", "Downloaded file is too small to be a valid LLM model: ${tempFile.length()} bytes")
                tempFile.delete()
                return@withContext null
            }
            
            val finalFile = File(context.filesDir, fileName)
            if (finalFile.exists()) {
                finalFile.delete()
            }
            tempFile.renameTo(finalFile)

            withContext(Dispatchers.Main) {
                onProgress(100)
            }
            return@withContext finalFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
