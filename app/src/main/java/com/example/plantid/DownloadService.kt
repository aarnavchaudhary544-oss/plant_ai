package com.example.plantid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadService : Service() {

    companion object {
        const val ACTION_PROGRESS = "com.example.plantid.DOWNLOAD_PROGRESS"
        const val ACTION_COMPLETED = "com.example.plantid.DOWNLOAD_COMPLETED"
        const val ACTION_FAILED = "com.example.plantid.DOWNLOAD_FAILED"
        const val EXTRA_PROGRESS = "progress"
        
        private const val NOTIFICATION_CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
        
        private const val GEMMA_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val GEMMA_FILENAME = "gemma-4-E2B-v3.litertlm"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isDownloading = false
    
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isDownloading) {
            isDownloading = true
            
            // Start the service in the foreground immediately
            val notification = createNotification(0)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (e: Exception) {
                    Log.e("PlantIDApp", "Error starting foreground service with type", e)
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            
            startDownload()
        }
        return START_NOT_STICKY
    }

    private fun startDownload() {
        serviceScope.launch {
            try {
                val externalDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val file = File(externalDir, GEMMA_FILENAME)
                if (file.exists()) {
                    file.delete()
                }

                val url = URL(GEMMA_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                }

                val fileLength = connection.contentLength.toLong()
                val lengthToUse = if (fileLength > 0) fileLength else 2588147712L

                val input: InputStream = connection.inputStream
                val output = FileOutputStream(file)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (input.read(data).also { count = it } != -1) {
                    if (!isActive) break
                    total += count.toLong()
                    output.write(data, 0, count)

                    val progress = ((total * 100) / lengthToUse).toInt().coerceIn(0, 100)
                    if (progress > lastProgress) {
                        lastProgress = progress
                        updateNotification(progress)
                        broadcastProgress(progress)
                    }
                }

                output.flush()
                output.close()
                input.close()

                if (isActive) {
                    Log.d("PlantIDApp", "Download complete!")
                    broadcastCompleted()
                }
            } catch (e: Exception) {
                Log.e("PlantIDApp", "Download failed", e)
                broadcastFailed()
            } finally {
                isDownloading = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun broadcastProgress(progress: Int) {
        val intent = Intent(ACTION_PROGRESS)
        intent.putExtra(EXTRA_PROGRESS, progress)
        sendBroadcast(intent)
    }

    private fun broadcastCompleted() {
        val intent = Intent(ACTION_COMPLETED)
        sendBroadcast(intent)
    }

    private fun broadcastFailed() {
        val intent = Intent(ACTION_FAILED)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of downloading AI models"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading Gemma AI Model")
            .setContentText(if (progress < 100) "$progress%" else "Download complete")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(progress: Int) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
