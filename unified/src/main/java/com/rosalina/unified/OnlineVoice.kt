package com.rosalina.unified

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** Credentials never enter Session preferences, IPC, receipts, diagnostics, or the APK. */
internal class OnlineVoiceSettings(private val context:Context) {
    private val settings=context.getSharedPreferences("online-voice-settings",Context.MODE_PRIVATE)
    private val vault=context.getSharedPreferences("online-voice-vault",Context.MODE_PRIVATE)
    private val alias="rosalina-online-voice-key-v1"
    private val aad="${context.packageName}:online-voice-v1".toByteArray()
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        check(create){"Re-enter the online voice credential; the encryption key is unavailable"}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun configured()=vault.contains("ciphertext") && voiceId().matches(Regex("[A-Za-z0-9_-]{1,80}"))
    fun enabled()=settings.getBoolean("explicitly-enabled-v1",false) && configured()
    fun voiceId()=settings.getString("voice-id","").orEmpty()
    fun model()=settings.getString("model","eleven_flash_v2_5").orEmpty()
    fun save(voiceId:String,model:String,credential:String,explicitlyEnabled:Boolean) {
        require(voiceId.matches(Regex("[A-Za-z0-9_-]{1,80}"))){"Enter a valid provider voice ID"}
        require(model in listOf("eleven_flash_v2_5","eleven_multilingual_v2","eleven_v3")){"Unsupported voice model"}
        if(credential.isNotBlank()) {
            require(credential.length<=512 && credential.none{it=='\r' || it=='\n'}){"Invalid credential"}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(aad)
            val bytes=cipher.doFinal(credential.toByteArray())
            check(vault.edit().putString("ciphertext",Base64.encodeToString(bytes,Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit()){"Credential storage failed"}
        }
        require(!explicitlyEnabled || vault.contains("ciphertext")){"Add an API key before enabling online voice"}
        check(settings.edit().putString("voice-id",voiceId).putString("model",model).putBoolean("explicitly-enabled-v1",explicitlyEnabled).commit()){"Provider settings could not be saved"}
    }
    fun disable(){settings.edit().putBoolean("explicitly-enabled-v1",false).apply()}
    fun removeCredential(){disable();vault.edit().clear().commit();runCatching{KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}}
    fun credential():String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        val iv=Base64.decode(vault.getString("iv","").orEmpty(),Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,iv));cipher.updateAAD(aad)
        return cipher.doFinal(Base64.decode(vault.getString("ciphertext","").orEmpty(),Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun summary()="Optional online voice: ${if(enabled())"explicitly enabled" else "off"}; credential configured=${configured()}; model=${model()}; sends the current response clause only; provider retention/account policies apply"
}

/** Fixed HTTPS adapter. No microphone, transcript, history or system prompt is sent. */
internal class OnlineVoice(private val context:Context,val settings:OnlineVoiceSettings=OnlineVoiceSettings(context)) {
    private val cancelled=AtomicBoolean(false)
    private val connection=AtomicReference<HttpsURLConnection?>(null)
    private val output=PcmSpeechOutput(context,cancelled)
    private val closeScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    fun stop(){
        output.interrupt();val active=connection.getAndSet(null)
        // Keep socket cleanup off the UI thread; playback is stopped synchronously above.
        if(active!=null)closeScope.launch{runCatching{active.disconnect()}}
    }
    suspend fun speak(text:String,performance:PerformanceState,emit:(String,String,Bundle?)->Unit):Bundle=withContext(Dispatchers.IO) {
        require(settings.enabled()){"Online voice is off"};require(text.length in 1..450){"Online speech clause is too long"}
        cancelled.set(false);currentCoroutineContext().ensureActive()
        val started=SystemClock.elapsedRealtime();val requestText=PerformanceDirector.onlineText(text,performance,settings.model())
        val url=URL("https://api.elevenlabs.io/v1/text-to-speech/${settings.voiceId()}/stream?output_format=pcm_24000")
        val con=url.openConnection() as HttpsURLConnection
        connection.set(con)
        try {
            con.requestMethod="POST";con.connectTimeout=3000;con.readTimeout=3000;con.instanceFollowRedirects=false;con.doOutput=true
            con.setRequestProperty("Content-Type","application/json");con.setRequestProperty("Accept","application/octet-stream")
            con.setRequestProperty("xi-api-key",settings.credential())
            val body=JSONObject().put("text",requestText).put("model_id",settings.model())
            val payload=body.toString().toByteArray();con.setFixedLengthStreamingMode(payload.size)
            emit("stage","Requesting optional online voice",null)
            con.outputStream.use{it.write(payload)}
            val code=con.responseCode
            check(code==200){"Online voice request failed (HTTP $code)"} // Do not log provider bodies or credentials.
            val initialized=SystemClock.elapsedRealtime()-started
            val bytes=ByteArrayOutputStream()
            con.inputStream.use{input->val buffer=ByteArray(16384)
                while(true){currentCoroutineContext().ensureActive();if(cancelled.get())throw CancellationException("Online speech stopped")
                    check(SystemClock.elapsedRealtime()-started<8000){"Online voice response timed out"}
                    val n=input.read(buffer);if(n<0)break
                    require(bytes.size()+n<=2_160_000){"Online voice audio exceeds the bounded clause limit"};bytes.write(buffer,0,n)
                }
            }
            val raw=bytes.toByteArray();require(raw.size>=4800 && raw.size%2==0){"Provider returned invalid PCM"}
            val pcmBytes=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm=ShortArray(pcmBytes.remaining());pcmBytes.get(pcm)
            for(i in pcm.indices)pcm[i]=(pcm[i]*performance.volume).toInt().coerceIn(-32768,32767).toShort()
            output.play(pcm,24000,started,emit).apply{
                putString("engine","Optional ElevenLabs ${settings.model()}");putBoolean("offline",false)
                putLong("setupMs",initialized);putLong("pssKb",Debug.getPss().toLong())
            }
        }finally {connection.compareAndSet(con,null);runCatching{con.disconnect()}}
    }
}
