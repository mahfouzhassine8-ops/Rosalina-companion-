package com.rosalina.unified

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID

internal enum class ModelKey(val label:String,val nameOnDisk:String,val sha:String,val url:String,val approximate:String,val bundle:Boolean=false) {
    CHAT("Chat","Qwen3.5-9B-abliterated-Q4_K_M.gguf","dba64d0e5cce0739e27535ee0a6b75249eb8006ce8b2d6c060e20750035c4695","https://huggingface.co/lukey03/Qwen3.5-9B-abliterated-GGUF/resolve/main/Qwen3.5-9B-abliterated-Q4_K_M.gguf","about 6 GB"),
    IMAGE("Image","stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf","b8944e9fe0b69b36ae1b5bb0185b3a7b8ef14347fe0fa9af6c64c4829022261f","https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf","about 2 GB"),
    VIDEO("Video","Wan2.2-TI2V-5B-Q4_K_S.gguf","ab4195ecd022e57455672771d8ec14c2589efc9ddd6b96c3578fbb326797bdbb","https://huggingface.co/QuantStack/Wan2.2-TI2V-5B-GGUF/resolve/main/Wan2.2-TI2V-5B-Q4_K_S.gguf","about 3.12 GB"),
    VIDEO_TEXT("Video language","umt5-xxl-encoder-Q4_K_S.gguf","4a3176f32fd70c0a335b4419fcbf8c86cc875e23498c0fc06f5b4aa0930889e0","https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_S.gguf","about 3.50 GB"),
    TTS("Voice · Kokoro 82M","kokoro-multi-lang-v1_0.tar.bz2","","https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2","about 360 MB unpacked",true),
    STT("Listening · Whisper tiny.en","sherpa-onnx-whisper-tiny.en.tar.bz2","","https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2","about 350 MB unpacked",true)
}
internal fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
internal fun atomicText(file:File,text:String) {
    file.parentFile?.mkdirs();val a=AtomicFile(file);val out=a.startWrite()
    try {out.write(text.toByteArray(Charsets.UTF_8));a.finishWrite(out)} catch(t:Throwable){a.failWrite(out);throw t}
}
internal class ModelStore(private val context:Context) {
    private val root=File(context.filesDir,"models").apply{mkdirs()}
    private val registryFile=File(root,"registry.json")
    private var registry=runCatching{JSONObject(registryFile.readText())}.getOrDefault(JSONObject())
    @Synchronized fun receipt(key:ModelKey):JSONObject? = registry.optJSONObject(key.name)?.let{JSONObject(it.toString())}
    @Synchronized private fun register(key:ModelKey,value:JSONObject) { registry.put(key.name,value);atomicText(registryFile,registry.toString(2)) }
    fun path(key:ModelKey):File? = receipt(key)?.optString("path")?.takeIf{it.isNotBlank()}?.let(::File)?.takeIf{it.exists()}
    fun requirePath(key:ModelKey)=path(key) ?: error("Import the ${key.label.lowercase()} model in Models first")
    fun summary(key:ModelKey):String {
        val r=receipt(key);val file=path(key) ?: return "Not installed · ${key.approximate}"
        val verification=if(r?.optBoolean("publisherVerified")==true) "SHA-256 verified at import" else "SHA-256 recorded · publisher match unavailable"
        return "$verification\n${r?.optLong("bytes",0)?.let{String.format("%.2f GB",it/1e9)}} · Private storage\n${file.path}"
    }
    private fun expected(key:ModelKey):String {
        if(key.sha.isNotBlank())return key.sha
        return runCatching{JSONObject(context.assets.open("voice-model-checksums.json").bufferedReader().use{it.readText()}).optString(key.name)}.getOrDefault("")
    }
    suspend fun import(key:ModelKey,uri:Uri,progress:(String,Int?)->Unit) {
        val expected=expected(key)
        if(expected.isNotBlank()) {
            val existing=File(root,if(key.bundle) "bundles/$expected" else "objects/$expected")
            if(existing.exists() && receipt(key)?.optString("sha256")==expected) {progress("Already installed · existing private copy retained",100);return}
        }
        val size=context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst() && !c.isNull(0))c.getLong(0) else -1L} ?: -1L
        if(size>0) require(StatFs(root.path).availableBytes>size) {"Insufficient storage: ${size/1_000_000} MB required; ${StatFs(root.path).availableBytes/1_000_000} MB available"}
        val incoming=File(root,"incoming-${UUID.randomUUID()}.part")
        var stageDir:File?=null
        try {
            val md=MessageDigest.getInstance("SHA-256");var copied=0L;var next=0L
            context.contentResolver.openInputStream(uri)?.use {input->FileOutputStream(incoming).use{out->
                val buffer=ByteArray(256*1024)
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);md.update(buffer,0,n);copied+=n
                    if(copied>=next){progress("Importing ${key.label} · ${copied/1_000_000} MB",if(size>0)(copied*100/size).toInt().coerceAtMost(99)else null);next=copied+16_000_000}}
                out.fd.sync()
            }} ?: error("Android could not open the selected file")
            require(copied>0){"The selected model file is empty"}
            val actual=hex(md.digest())
            require(expected.isBlank() || actual==expected){"SHA-256 mismatch. Expected $expected; received $actual. The existing model was not changed."}
            val destination=File(root,if(key.bundle)"bundles/$actual" else "objects/$actual")
            destination.parentFile?.mkdirs();var storedBytes=copied
            if(!destination.exists()) {
                if(key.bundle) {
                    progress("Verifying and unpacking ${key.label}",null)
                    val staging=File(root,"unpack-${UUID.randomUUID()}").apply{mkdirs()};stageDir=staging
                    val receipts=JSONObject();var total=0L;var entries=0
                    val names=HashSet<String>()
                    BufferedInputStream(FileInputStream(incoming)).use {compressed->
                        CompressorStreamFactory().createCompressorInputStream(compressed).use {decoded->TarArchiveInputStream(decoded).use {tar->
                            while(true){currentCoroutineContext().ensureActive();val entry=tar.nextTarEntry ?: break
                                require(++entries<=20000){"Too many files in model archive"}
                                require(!entry.isSymbolicLink && !entry.isLink){"Model archive links are not accepted"}
                                val name=entry.name
                                require(!name.startsWith('/') && !name.contains('\\') && name.split('/').none{it==".."}){"Unsafe model archive path"}
                                val f=File(staging,name).canonicalFile
                                require(f.path.startsWith(staging.canonicalPath+File.separator)){"Model archive escaped its private directory"}
                                if(entry.isDirectory){f.mkdirs();continue}
                                require(entry.isFile && names.add(name)){"Unsupported or duplicate archive entry"}
                                require(entry.size in 0..1_500_000_000L && total+entry.size<=2_000_000_000L){"Model archive is too large"}
                                require(StatFs(root.path).availableBytes>entry.size){"Not enough free space to unpack model"}
                                f.parentFile?.mkdirs();val digest=MessageDigest.getInstance("SHA-256");var written=0L
                                FileOutputStream(f).use{out->val buf=ByteArray(256*1024);while(true){currentCoroutineContext().ensureActive();val n=tar.read(buf);if(n<0)break;out.write(buf,0,n);digest.update(buf,0,n);written+=n};out.fd.sync()}
                                require(written==entry.size){"Truncated model archive"};total+=written;receipts.put(name,hex(digest.digest()))
                            }
                        }}
                    }
                    val needed=if(key==ModelKey.TTS)listOf("model.onnx","voices.bin","tokens.txt") else listOf("tiny.en-encoder.int8.onnx","tiny.en-decoder.int8.onnx","tiny.en-tokens.txt")
                    for(name in needed)require(staging.walkTopDown().any{it.isFile && it.name==name}){"Model pack is missing $name"}
                    atomicText(File(staging,"file-checksums.json"),receipts.toString(2));storedBytes=total
                    check(staging.renameTo(destination)){"Cannot finalize model folder"};stageDir=null
                } else check(incoming.renameTo(destination)){"Cannot finalize model file"}
            }
            register(key,JSONObject().put("path",destination.path).put("sha256",actual).put("publisherVerified",expected.isNotBlank()).put("bytes",storedBytes).put("verifiedAt",System.currentTimeMillis()))
            progress("${key.label} installed · checksum ${if(expected.isBlank())"recorded" else "verified"}",100)
        } finally {incoming.delete();stageDir?.deleteRecursively()}
    }
    fun bundleFile(key:ModelKey,name:String):File = requirePath(key).walkTopDown().firstOrNull{it.isFile && it.name==name} ?: error("${key.label} is missing $name")
    fun diagnostic():String=ModelKey.entries.joinToString("\n"){"${it.label}: ${summary(it)}; receipt=${receipt(it)}"}
}
