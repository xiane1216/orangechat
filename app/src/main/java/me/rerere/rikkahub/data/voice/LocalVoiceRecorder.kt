package me.rerere.rikkahub.data.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

/**
 * 本地 WAV 录音器 (按住说话兜底): 云端 ASR 未配置/启动失败时使用。
 * 16kHz 单声道 PCM16, 录音数据在内存累积, stop() 时写出到
 * filesDir/voice_messages/voice_<ts>.wav (与云端 ASR 的录音路径保持一致, 方便后续识别)。
 */
class LocalVoiceRecorder(private val context: Context) {

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false
    private val chunks = ArrayList<ShortArray>()
    private var totalSamples = 0
    private val lock = Any()

    @SuppressLint("MissingPermission") // 调用方 (ChatInput) 已确保录音权限
    fun start(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            recorder = rec
            synchronized(lock) { chunks.clear(); totalSamples = 0 }
            rec.startRecording()
            running = true
            thread = Thread {
                val buf = ShortArray(SAMPLE_RATE / 10) // 100ms
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(lock) {
                            chunks.add(buf.copyOf(n))
                            totalSamples += n
                        }
                    }
                }
            }.apply {
                priority = Thread.NORM_PRIORITY - 1 // 略低于主线程, 不抢 UI 帧
                start()
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "LocalVoiceRecorder start failed", e)
            false
        }
    }

    /** 停止并写出 WAV; 返回文件绝对路径, 录音过短(<0.3s)返回 null */
    fun stop(): String? {
        running = false
        try { thread?.join(600) } catch (_: InterruptedException) {}
        thread = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null

        val samples: ShortArray
        synchronized(lock) {
            samples = ShortArray(totalSamples)
            var off = 0
            for (c in chunks) { System.arraycopy(c, 0, samples, off, c.size); off += c.size }
            chunks.clear()
        }
        if (totalSamples < SAMPLE_RATE * 3 / 10) return null // <0.3s 视为误触

        return try {
            val dir = File(context.filesDir, "voice_messages").apply { mkdirs() }
            val out = File(dir, "voice_${System.currentTimeMillis()}.wav")
            RandomAccessFile(out, "rw").use { raf ->
                val dataLen = totalSamples * 2
                val header = ByteArray(44)
                fun put16(off: Int, v: Int) { header[off] = (v and 0xFF).toByte(); header[off + 1] = ((v shr 8) and 0xFF).toByte() }
                fun put32(off: Int, v: Int) { put16(off, v and 0xFFFF); put16(off + 2, (v shr 16) and 0xFFFF) }
                header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
                put32(4, 36 + dataLen)
                header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
                header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
                put32(16, 16); put16(20, 1); put16(22, 1)
                put32(24, SAMPLE_RATE); put32(28, SAMPLE_RATE * 2); put16(32, 2); put16(34, 16)
                header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
                put32(40, dataLen)
                raf.write(header)
                val bb = java.nio.ByteBuffer.allocate(dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in samples) bb.putShort(s)
                raf.write(bb.array())
            }
            out.absolutePath
        } catch (e: Exception) {
            android.util.Log.w(TAG, "LocalVoiceRecorder write wav failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "LocalVoiceRecorder"
        private const val SAMPLE_RATE = 16000
    }
}
