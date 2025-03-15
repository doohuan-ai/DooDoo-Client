package com.example.speechrecognition

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 流式语音识别客户端
 * 负责音频采集和与后端服务器的WebSocket通信
 */
class SpeechRecognitionClient(private val activity: FragmentActivity) {
    companion object {
        private const val TAG = "SpeechRecognitionClient"
        private const val SAMPLE_RATE = 16000 // 采样率
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO // 单声道
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT // 16位PCM
        private const val PERMISSION_REQUEST_CODE = 200
        private const val BACKEND_URL = "ws://your-backend-server:8000/ws/asr" // 替换为你的后端服务器地址
    }

    // 回调接口
    interface RecognitionCallback {
        fun onRecognitionResult(text: String, isFinal: Boolean)
        fun onError(message: String)
    }

    private var callback: RecognitionCallback? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var bufferSize = 0

    /**
     * 初始化客户端
     */
    fun initialize(callback: RecognitionCallback) {
        this.callback = callback
        
        // 检查并请求录音权限
        if (!checkPermission()) {
            requestPermission()
            return
        }
        
        // 计算缓冲区大小
        bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        // 初始化OkHttp客户端
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        Log.d(TAG, "客户端初始化完成，缓冲区大小: $bufferSize 字节")
    }

    /**
     * 开始录音并识别
     */
    fun startRecognition() {
        if (isRecording) {
            Log.d(TAG, "已经在录音中")
            return
        }
        
        if (!checkPermission()) {
            callback?.onError("没有录音权限")
            return
        }
        
        // 连接WebSocket
        connectWebSocket()
    }

    /**
     * 停止录音和识别
     */
    fun stopRecognition() {
        isRecording = false
        recordingJob?.cancel()
        
        // 发送结束标志
        sendFinalAudioChunk()
        
        // 释放资源
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        Log.d(TAG, "停止录音和识别")
    }

    /**
     * 释放资源
     */
    fun release() {
        stopRecognition()
        webSocket?.close(1000, "正常关闭")
        webSocket = null
        client = null
        callback = null
    }

    /**
     * 连接WebSocket
     */
    private fun connectWebSocket() {
        val request = Request.Builder()
            .url(BACKEND_URL)
            .build()
        
        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket连接成功")
                
                // 发送音频配置
                val config = JSONObject().apply {
                    put("audio_config", JSONObject().apply {
                        put("format", "pcm")
                        put("rate", SAMPLE_RATE)
                        put("bits", 16)
                        put("channel", 1)
                    })
                }
                
                webSocket.send(config.toString())
                
                // 开始录音
                startRecording()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "收到文本消息: $text")
                
                try {
                    val json = JSONObject(text)
                    
                    // 检查是否有错误
                    if (json.has("error")) {
                        val errorMsg = json.getString("error")
                        callback?.onError(errorMsg)
                        return
                    }
                    
                    // 解析识别结果
                    if (json.has("result")) {
                        val result = json.getJSONObject("result")
                        if (result.has("text")) {
                            val recognizedText = result.getString("text")
                            val isLast = json.optBoolean("is_last", false)
                            
                            // 回调识别结果
                            callback?.onRecognitionResult(recognizedText, isLast)
                            
                            // 如果是最后一包，停止录音
                            if (isLast) {
                                stopRecognition()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析消息失败", e)
                    callback?.onError("解析消息失败: ${e.message}")
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败", t)
                callback?.onError("WebSocket连接失败: ${t.message}")
                stopRecognition()
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket连接关闭: $reason")
            }
        })
    }

    /**
     * 开始录音
     */
    private fun startRecording() {
        if (isRecording) {
            return
        }
        
        try {
            // 初始化AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback?.onError("AudioRecord初始化失败")
                return
            }
            
            isRecording = true
            audioRecord?.startRecording()
            
            // 在协程中处理音频数据
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(bufferSize)
                val byteBuffer = ByteBuffer.allocate(bufferSize)
                
                // 计算每个音频包的大小（200ms的音频数据）
                val bytesPerPacket = (SAMPLE_RATE * 2 * 0.2).toInt() // 采样率 * 字节/样本 * 时间(秒)
                
                try {
                    while (isRecording && isActive) {
                        val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        
                        if (readSize > 0) {
                            // 将音频数据添加到缓冲区
                            byteBuffer.put(buffer, 0, readSize)
                            
                            // 如果缓冲区中的数据足够一个包，则发送
                            if (byteBuffer.position() >= bytesPerPacket) {
                                val audioData = ByteArray(bytesPerPacket)
                                byteBuffer.flip()
                                byteBuffer.get(audioData, 0, bytesPerPacket)
                                
                                // 如果缓冲区中还有剩余数据，保留到下一次发送
                                if (byteBuffer.hasRemaining()) {
                                    val remaining = ByteArray(byteBuffer.remaining())
                                    byteBuffer.get(remaining)
                                    byteBuffer.clear()
                                    byteBuffer.put(remaining)
                                } else {
                                    byteBuffer.clear()
                                }
                                
                                // 发送音频数据
                                sendAudioChunk(audioData, false)
                            }
                        }
                        
                        // 短暂延迟，避免CPU占用过高
                        delay(10)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "录音过程中出错", e)
                    callback?.onError("录音过程中出错: ${e.message}")
                }
            }
            
            Log.d(TAG, "开始录音")
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            callback?.onError("启动录音失败: ${e.message}")
            isRecording = false
        }
    }

    /**
     * 发送音频数据
     */
    private fun sendAudioChunk(audioData: ByteArray, isLast: Boolean) {
        if (webSocket == null) {
            Log.e(TAG, "WebSocket未连接")
            return
        }
        
        try {
            // 添加标志位表示是否为最后一包
            val dataWithFlag = ByteArray(audioData.size + 1)
            dataWithFlag[0] = if (isLast) 1 else 0
            System.arraycopy(audioData, 0, dataWithFlag, 1, audioData.size)
            
            // 发送二进制数据
            webSocket?.send(ByteString.of(*dataWithFlag))
        } catch (e: Exception) {
            Log.e(TAG, "发送音频数据失败", e)
            callback?.onError("发送音频数据失败: ${e.message}")
        }
    }

    /**
     * 发送最后一包音频数据
     */
    private fun sendFinalAudioChunk() {
        // 发送一个空的最后一包
        val emptyData = ByteArray(1)
        emptyData[0] = 1 // 标记为最后一包
        
        webSocket?.send(ByteString.of(*emptyData))
        Log.d(TAG, "发送最后一包音频数据")
    }

    /**
     * 检查录音权限
     */
    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 请求录音权限
     */
    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }
}
