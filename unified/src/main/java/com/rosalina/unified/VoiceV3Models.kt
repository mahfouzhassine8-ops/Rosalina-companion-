package com.rosalina.unified

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Chatterbox is distinct from the rejected Pocket directory and all established model receipts. */
internal class VoiceV3Models(private val context:Context) {
    private val root=File(context.filesDir,"voice-v3-chatterbox")
    private val expected by lazy{JSONObject(context.assets.open("voice-v3-pack.json").bufferedReader().use{it.readText()})}
    fun fingerprint():String=runCatching{expected.getString("archiveSha256")}.getOrDefault("")
    fun directory():File?=runCatching {
        val hash=fingerprint();if(hash.isBlank())return null
        val dir=File(root,hash);val verified=File(dir,"verified.json")
        if(!verified.isFile || verified.length()>100_000)return null
        val receipt=JSONObject(verified.readText())
        if(receipt.optString("archiveSha256")!=hash || receipt.optString("engine")!="chatterbox-turbo-onnx-q4")return null
        val files=expected.getJSONObject("files");val stats=receipt.getJSONObject("stats")
        for(name in files.keys()){
            val f=File(dir,name);val stat=stats.getJSONObject(name)
            if(!f.isFile || f.length()!=stat.getLong("bytes") || f.lastModified()!=stat.getLong("modified"))return null
        }
        dir
    }.getOrNull()
    fun requireDirectory()=directory() ?:error("Import the verified Chatterbox phone ZIP in Settings. Existing models are unchanged.")
    fun summary()=if(directory()!=null)"Chatterbox Turbo Q4 · verified private pack · CPU · phone acceptance required" else "Chatterbox Turbo Q4 · phone pack not imported"
    suspend fun importPack(uri:Uri,progress:(String,Int?)->Unit) {
        root.mkdirs();val hash=fingerprint();require(hash.matches(Regex("[0-9a-f]{64}"))){"Voice pack pin is missing"}
        if(directory()!=null){progress("Verified Chatterbox pack already present; no second copy created",100);return}
        val target=File(root,hash)
        require(!target.exists()){"The installed candidate needs verification. It was not overwritten or deleted."}
        val incoming=File(root,"incoming-${UUID.randomUUID()}.part")
        val staging=File(root,"staging-${UUID.randomUUID()}").apply{check(mkdirs())}
        try {
            val totalBytes=expected.getLong("archiveBytes");val digest=MessageDigest.getInstance("SHA-256");var received=0L
            context.contentResolver.openInputStream(uri)?.use{input->FileOutputStream(incoming).use{out->
                val buffer=ByteArray(262144);var shown=-1
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                    received+=n;require(received<=totalBytes){"Wrong candidate ZIP size"}
                    require(StatFs(root.path).availableBytes>n+64_000_000L){"Not enough storage for voice import"}
                    out.write(buffer,0,n);digest.update(buffer,0,n)
                    val percent=(received*45/totalBytes).toInt();if(percent!=shown){shown=percent;progress("Copying verified Chatterbox pack",percent)}
                };out.fd.sync()
            }} ?:error("Android could not open this voice ZIP")
            require(received==totalBytes && hex(digest.digest())==hash){"Wrong Chatterbox phone ZIP checksum. Existing models were not changed."}
            val files=expected.getJSONObject("files");val sizes=expected.getJSONObject("fileSizes")
            val stats=JSONObject();val found=HashSet<String>();var expanded=0L
            val expandedLimit=files.keys().asSequence().sumOf{sizes.getLong(it)}
            ZipInputStream(incoming.inputStream().buffered()).use{zip->
                while(true){currentCoroutineContext().ensureActive();val entry=zip.nextEntry ?:break;val name=entry.name
                    require(!entry.isDirectory && !name.startsWith('/') && !name.contains('\\') && name.split('/').none{it==".." || it.isEmpty()} && files.has(name) && found.add(name)){"Unexpected or duplicate voice ZIP entry"}
                    val file=File(staging,name);require(file.canonicalPath.startsWith(staging.canonicalPath+File.separator)){"Unsafe voice ZIP path"}
                    file.parentFile!!.mkdirs();val md=MessageDigest.getInstance("SHA-256");var length=0L
                    FileOutputStream(file).use{out->val buffer=ByteArray(262144)
                        while(true){currentCoroutineContext().ensureActive();val n=zip.read(buffer);if(n<0)break
                            length+=n;expanded+=n;require(length<=sizes.getLong(name) && expanded<=expandedLimit){"Unpacked voice size mismatch"}
                            require(StatFs(root.path).availableBytes>n+64_000_000L){"Not enough storage to unpack the voice"}
                            out.write(buffer,0,n);md.update(buffer,0,n)
                        };out.fd.sync()
                    }
                    require(length==sizes.getLong(name) && hex(md.digest())==files.getString(name)){"Voice file checksum mismatch: $name"}
                    stats.put(name,JSONObject().put("bytes",length).put("modified",file.lastModified()))
                    progress("Verifying Chatterbox files",45+(expanded*54/expandedLimit).toInt())
                }
            }
            require(found.size==files.length()){"Incomplete voice pack"}
            atomicText(File(staging,"verified.json"),JSONObject().put("archiveSha256",hash).put("engine","chatterbox-turbo-onnx-q4").put("stats",stats).toString())
            check(staging.renameTo(target)){"Could not finalize voice import"}
            progress("Candidate imported · compare before accepting",100)
        } finally {incoming.delete();staging.deleteRecursively()}
    }
}
