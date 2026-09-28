package com.rosalina.unified

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dedicated warm Whisper process for Live Voice. Keeping listening separate from TTS
 * prevents the two speech models from repeatedly unloading one another every turn.
 */
class ListenService:NativeRpcService() {
    private var recognizer:OfflineRecognizer?=null
    private var rootPath=""
    private var loadedAt=0L

    private fun engine():OfflineRecognizer {
        val root=ModelStore(this).bundleFile(ModelKey.STT,"tiny.en-tokens.txt").parentFile
            ?:error("Listening model folder is missing")
        if(recognizer==null || root.path!=rootPath) {
            recognizer?.release()
            val start=SystemClock.elapsedRealtime()
            recognizer=OfflineRecognizer(
                config=OfflineRecognizerConfig(
                    modelConfig=OfflineModelConfig(
                        whisper=OfflineWhisperModelConfig(
                            encoder=File(root,"tiny.en-encoder.int8.onnx").path,
                            decoder=File(root,"tiny.en-decoder.int8.onnx").path
                        ),
                        tokens=File(root,"tiny.en-tokens.txt").path,
                        numThreads=2,
                        provider="cpu"
                    )
                )
            )
            rootPath=root.path
            loadedAt=SystemClock.elapsedRealtime()-start
        }
        return recognizer ?:error("Listening engine unavailable")
    }

    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        val start=SystemClock.elapsedRealtime()
        emit("stage","Preparing live listening",null)
        val e=engine()
        if(values.getString("operation")=="prepare")return Bundle().apply{
            putBoolean("prepared",true)
            putLong("setupMs",loadedAt)
            putLong("elapsedMs",SystemClock.elapsedRealtime()-start)
            putLong("pssKb",Debug.getPss().toLong())
        }

        val file=File(values.getString("pcm") ?:error("No microphone recording"))
        require(file.canonicalPath.startsWith(filesDir.canonicalPath+File.separator) && file.length() in 2..960_000){"Invalid microphone recording"}
        emit("stage","Understanding you",null)
        val bytes=file.readBytes()
        val shorts=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples=FloatArray(shorts.remaining()){shorts.get()/32768f}
        currentCoroutineContext().ensureActive()
        val inferenceStart=SystemClock.elapsedRealtime()
        val stream=e.createStream()
        try {
            stream.acceptWaveform(samples,16000)
            e.decode(stream)
            val text=e.getResult(stream).text.trim()
            require(text.isNotBlank()){"No speech recognized. Try speaking closer to the microphone."}
            return Bundle().apply{
                putString("transcript",text)
                putLong("setupMs",loadedAt)
                putLong("inferenceMs",SystemClock.elapsedRealtime()-inferenceStart)
                putLong("elapsedMs",SystemClock.elapsedRealtime()-start)
                putLong("audioMs",samples.size*1000L/16000)
                putLong("pssKb",Debug.getPss().toLong())
            }
        } finally {stream.release()}
    }

    override fun onDestroy(){
        runCatching{recognizer?.release()};recognizer=null
        super.onDestroy()
    }
}
