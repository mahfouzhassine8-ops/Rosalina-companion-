package com.rosalina.unified

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A distinct, origin-bound encrypted vault; never reads or migrates cloud credentials. */
internal class StudioVoiceSettings(context:Context) {
    private val prefs=context.getSharedPreferences("studio-voice-settings",Context.MODE_PRIVATE)
    private val vault=context.getSharedPreferences("studio-voice-vault",Context.MODE_PRIVATE)
    private val alias="rosalina-studio-voice-v1"
    private val packageName=context.packageName
    private val lock=Any()
    fun endpoint()=prefs.getString("root","").orEmpty()
    fun model()=prefs.getString("model","tts-1").orEmpty()
    fun voice()=prefs.getString("voice","default").orEmpty()
    fun style()=runCatching{StudioStyleMode.valueOf(prefs.getString("style","NONE").orEmpty())}.getOrDefault(StudioStyleMode.NONE)
    fun configured()=endpoint().isNotBlank() && vault.contains("ciphertext")
    fun enabled()=prefs.getBoolean("consent-v1",false) && configured()
    fun preferred()=enabled() && prefs.getBoolean("prefer-studio",false)
    fun disable(){prefs.edit().putBoolean("consent-v1",false).putBoolean("prefer-studio",false).commit()}
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        check(create){"Re-enter the Studio credentials on this device"}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun aad(root:String)="$packageName:studio-v1:$root".toByteArray(Charsets.UTF_8)
    private fun credentials(root:String):Pair<String,String> {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        val iv=Base64.decode(vault.getString("iv","").orEmpty(),Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,iv));cipher.updateAAD(aad(root))
        val clear=cipher.doFinal(Base64.decode(vault.getString("ciphertext","").orEmpty(),Base64.NO_WRAP))
        try{val json=JSONObject(clear.toString(Charsets.UTF_8));return json.optString("key") to json.optString("pin")}
        finally{clear.fill(0)}
    }
    fun save(root:String,model:String,voice:String,style:StudioStyleMode,apiKey:String,pin:String,consent:Boolean,prefer:Boolean)=synchronized(lock) {
        val target=StudioEndpoint.parse(root.trim())
        require(model.matches(Regex("[A-Za-z0-9_.:/-]{1,120}")) && voice.matches(Regex("[A-Za-z0-9_.:/-]{1,160}"))){"Enter a valid installed engine and voice ID"}
        require(apiKey.length<=512 && apiKey.none{it.code<32 || it.code>126}){"Invalid Studio API key"}
        require(pin.isBlank() || pin.matches(Regex("[0-9]{6}"))){"The sharing PIN must contain six digits"}
        val same=target.root==endpoint()
        // Blank entries preserve secrets ONLY for the exact already-configured origin.
        val old=if(same && configured())runCatching{credentials(target.root)}.getOrNull() else null
        val k=apiKey.ifBlank{old?.first.orEmpty()};val p=pin.ifBlank{old?.second.orEmpty()}
        require(k.isNotBlank() || p.isNotBlank()){"Add a Studio API key or sharing PIN. Credentials are never carried to a different address."}
        require(!prefer || consent){"Enable Studio consent before preferring it"}
        disable() // Fail closed if any following persistence step fails.
        val clear=JSONObject().put("key",k).put("pin",p).toString().toByteArray()
        try{
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(aad(target.root))
            val sealed=cipher.doFinal(clear)
            check(vault.edit().putString("ciphertext",Base64.encodeToString(sealed,Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit()){"Studio credential storage failed"}
            check(prefs.edit().putString("root",target.root).putString("model",model).putString("voice",voice)
                .putString("style",style.name).putBoolean("consent-v1",consent).putBoolean("prefer-studio",prefer).commit()){"Studio settings could not be saved"}
        }finally{clear.fill(0)}
    }
    fun clear()=synchronized(lock){disable();vault.edit().clear().commit();runCatching{KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)};Unit}
    fun snapshot():StudioConfig=synchronized(lock){
        check(enabled()){"Studio Voice is off"}
        val endpoint=StudioEndpoint.parse(endpoint());val (key,pin)=credentials(endpoint.root)
        StudioConfig(endpoint,model(),voice(),style(),key,pin)
    }
    fun summary()="Studio Voice: ${if(enabled())if(preferred())"preferred when available" else "audition only" else "off"}; credentials configured=${configured()}; private-network HTTPS only; current reply text and bounded style only; no microphone/history upload"
}
/** Deliberately not a data class: toString/copy must never expose credentials. */
internal class StudioConfig(val endpoint:StudioEndpoint,val model:String,val voice:String,val style:StudioStyleMode,internal val apiKey:String,internal val pin:String) {
    override fun toString()="StudioConfig(credentials=REDACTED)"
}
