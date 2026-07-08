package com.example.plantid

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import android.content.res.AssetFileDescriptor

class ImageClassifierHelper(
    private val context: Context,
    private val classifierListener: ClassifierListener?
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    
    private var inputSizeWidth = 224
    private var inputSizeHeight = 224
    private var isChannelsFirst = false
    private var inputChannels = 3
    
    private val TAG = "ImageClassifierHelper"

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        try {
            val buffer = loadModelFileFromAssets("fruit_model.tflite")
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            interpreter = Interpreter(buffer, options)
            
            val inputTensor = interpreter?.getInputTensor(0)
                if (inputTensor != null) {
                    val shape = inputTensor.shape()
                    Log.d(TAG, "Input Tensor Shape: ${shape.joinToString()}")
                    Log.d(TAG, "Input Tensor DataType: ${inputTensor.dataType()}")
                    
                    // Fix dynamic batch size if present
                    if (shape[0] < 1) {
                        val newShape = shape.clone()
                        newShape[0] = 1
                        interpreter?.resizeInput(0, newShape)
                        interpreter?.allocateTensors() // Re-allocate after resize
                    }

                    val finalShape = interpreter!!.getInputTensor(0).shape()
                    if (finalShape.size == 4) {
                        if (finalShape[1] == 3 || finalShape[1] == 1) {
                            isChannelsFirst = true
                            inputChannels = finalShape[1]
                            inputSizeHeight = finalShape[2]
                            inputSizeWidth = finalShape[3]
                        } else {
                            isChannelsFirst = false
                            inputSizeHeight = finalShape[1]
                            inputSizeWidth = finalShape[2]
                            inputChannels = finalShape[3]
                        }
                    }
                }
                
                try {
                    labels = context.assets.open("labels.txt").bufferedReader().useLines { it.toList() }
                    Log.d(TAG, "Loaded ${labels.size} labels")
                } catch (e: Exception) {
                    labels = emptyList()
                }
        } catch (e: Exception) {
            e.printStackTrace()
            classifierListener?.onError("Failed to initialize interpreter: ${e.message}")
        }
    }

    private fun loadModelFileFromAssets(fileName: String): MappedByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun classify(image: Bitmap) {
        val currentInterpreter = interpreter
        if (currentInterpreter == null) {
            classifierListener?.onError("Interpreter not initialized")
            return
        }

        try {
            val inputTensor = currentInterpreter.getInputTensor(0)
            val outputTensor = currentInterpreter.getOutputTensor(0)
            
            val inputType = inputTensor.dataType()
            val outputType = outputTensor.dataType()
            
            val reqInputCapacity = inputTensor.numBytes()
            val reqOutputCapacity = outputTensor.numBytes()

            Log.d(TAG, "Allocating input buffer size: $reqInputCapacity")
            val inputBuffer = ByteBuffer.allocateDirect(reqInputCapacity)
            inputBuffer.order(ByteOrder.nativeOrder())

            val scaledBitmap = Bitmap.createScaledBitmap(image, inputSizeWidth, inputSizeHeight, true)
            val intValues = IntArray(inputSizeWidth * inputSizeHeight)
            scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

            if (inputType == DataType.FLOAT32) {
                val floatBuffer = inputBuffer.asFloatBuffer()
                if (isChannelsFirst) {
                    val channelSize = inputSizeWidth * inputSizeHeight
                    for (i in 0 until channelSize) {
                        val valPixel = intValues[i]
                        floatBuffer.put(i, ((valPixel shr 16) and 0xFF) / 255.0f)
                        if (inputChannels >= 3) {
                            floatBuffer.put(channelSize + i, ((valPixel shr 8) and 0xFF) / 255.0f)
                            floatBuffer.put(2 * channelSize + i, (valPixel and 0xFF) / 255.0f)
                        }
                    }
                } else {
                    for (valPixel in intValues) {
                        floatBuffer.put(((valPixel shr 16) and 0xFF) / 255.0f)
                        if (inputChannels >= 3) {
                            floatBuffer.put(((valPixel shr 8) and 0xFF) / 255.0f)
                            floatBuffer.put((valPixel and 0xFF) / 255.0f)
                        }
                    }
                }
            } else if (inputType == DataType.UINT8) {
                if (isChannelsFirst) {
                    val channelSize = inputSizeWidth * inputSizeHeight
                    for (i in 0 until channelSize) {
                        val valPixel = intValues[i]
                        inputBuffer.put(i, ((valPixel shr 16) and 0xFF).toByte())
                        if (inputChannels >= 3) {
                            inputBuffer.put(channelSize + i, ((valPixel shr 8) and 0xFF).toByte())
                            inputBuffer.put(2 * channelSize + i, (valPixel and 0xFF).toByte())
                        }
                    }
                } else {
                    for (valPixel in intValues) {
                        inputBuffer.put(((valPixel shr 16) and 0xFF).toByte())
                        if (inputChannels >= 3) {
                            inputBuffer.put(((valPixel shr 8) and 0xFF).toByte())
                            inputBuffer.put((valPixel and 0xFF).toByte())
                        }
                    }
                }
            } else {
                 Log.e(TAG, "Unsupported input type: $inputType")
                 classifierListener?.onError("Unsupported input type: $inputType")
                 return
            }
            
            inputBuffer.rewind()
            
            Log.d(TAG, "Allocating output buffer size: $reqOutputCapacity")
            val outputBuffer = ByteBuffer.allocateDirect(reqOutputCapacity)
            outputBuffer.order(ByteOrder.nativeOrder())

            // THIS IS WHERE IT WAS CRASHING
            currentInterpreter.run(inputBuffer, outputBuffer)

            outputBuffer.rewind()
            val labelCount = outputTensor.shape().last() // Usually last dimension is classes
            val probabilities = FloatArray(labelCount)
            
            if (outputType == DataType.FLOAT32) {
                val floatOut = outputBuffer.asFloatBuffer()
                for (i in 0 until labelCount) {
                    probabilities[i] = floatOut.get(i)
                }
            } else if (outputType == DataType.UINT8) {
                for (i in 0 until labelCount) {
                    probabilities[i] = (outputBuffer.get().toInt() and 0xFF) / 255.0f
                }
            } else {
                Log.e(TAG, "Unsupported output type: $outputType")
            }

            val results = mutableListOf<CustomClassification>()
            val maxLabels = minOf(labels.size, probabilities.size)
            
            for (i in 0 until maxLabels) {
                results.add(CustomClassification(labels[i], probabilities[i]))
            }
            
            results.sortByDescending { it.score }
            
            classifierListener?.onResults(results.take(3))
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Inference failed with exception: ${e.message}")
            classifierListener?.onError("Inference failed: ${e.message}")
        }
    }

    data class CustomClassification(val label: String, val score: Float)

    interface ClassifierListener {
        fun onError(error: String)
        fun onResults(results: List<CustomClassification>?)
    }
}
