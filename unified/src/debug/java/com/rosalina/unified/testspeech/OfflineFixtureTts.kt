package com.rosalina.unified.testspeech

import android.media.AudioFormat
import android.speech.tts.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/** Deterministic PCM delivered through Android's REAL TextToSpeechService framework. Not a human voice. */
class OfflineFixtureTts:TextToSpeechService() {
    private val stopped=AtomicBoolean(false)
    private val name="rosalina-fixture-en-us"
    override fun onIsLanguageAvailable(lang:String?,country:String?,variant:String?)=if(lang=="eng" || lang=="en")TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    override fun onLoadLanguage(lang:String?,country:String?,variant:String?)=onIsLanguageAvailable(lang,country,variant)
    override fun onGetLanguage()=arrayOf("eng","USA","")
    override fun onGetVoices()=mutableListOf(Voice(name,Locale.US,Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,false,emptySet()))
    override fun onGetDefaultVoiceNameFor(lang:String?,country:String?,variant:String?)=name
    override fun onIsValidVoiceName(voiceName:String?)=if(voiceName==name)TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(voiceName:String?)=onIsValidVoiceName(voiceName)
    override fun onStop(){stopped.set(true)}
    override fun onSynthesizeText(request:SynthesisRequest?,callback:SynthesisCallback?) {
        callback ?:return;stopped.set(false)
        if(request?.charSequenceText?.toString()=="fixture-error"){callback.error(TextToSpeech.ERROR_SYNTHESIS);return}
        val seconds=if(request?.charSequenceText?.toString()=="fixture-long")5 else 1
        val rate=16000
        if(callback.start(rate,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS)return
        val pcm=ByteBuffer.allocate(rate*seconds*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rate*seconds){i->pcm.putShort((sin(i*2.0*Math.PI*440/rate)*5000).toInt().toShort())}
        val bytes=pcm.array();var offset=0
        while(offset<bytes.size && !stopped.get()){
            val count=minOf(callback.maxBufferSize,bytes.size-offset)
            if(callback.audioAvailable(bytes,offset,count)!=TextToSpeech.SUCCESS)return
            offset+=count
        }
        if(!stopped.get())callback.done()
    }
}
