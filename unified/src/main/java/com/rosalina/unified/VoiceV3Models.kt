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

/** Separate, pinned candidate bundle. Never changes the established ModelStore registry. */
internal class VoiceV3Models(private val context:Context) {
    private val root=File(context.filesDir,"voice-v3")
    private fun pins()=JSONObject(context.assets.open("voice-v3-pack.json").bufferedReader().use{it.readText()})
    fun fingerprint():String=runCatching{pins().getString("archiveSha256")}.getOrDefault("")
    fun directory():File? {
        val hash=fingerprint();if(hash.isBlank())return null
        return File(root,hash).takeIf{File(it,"verified.json").isFile}
    }
    fun requireDirectory()=directory() ?:error("Import the verified Voice V3 candidate pack in Settings first")
    fun summary()=if(directory()!=null)"Pocket TTS candidate · verified private pack · CPU · not phone-approved" else "Pocket TTS candidate · pack not imported"
    suspend fun importPack(uri:Uri,progress:(String,Int?)->Unit) {
        root.mkdirs()
        val expected=pins();val hash=expected.getString("archiveSha256")
        require(hash.matches(Regex("[0-9a-f]{64}"))){"Voice V3 pack pin is missing"}
        if(directory()!=null){progress("Voice V3 pack already imported; existing copy retained",100);return}
        val incoming=File(root,"incoming-${UUID.randomUUID()}.part")
        val staging=File(root,"staging-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
            context.contentResolver.openInputStream(uri)?.use{input->FileOutputStream(incoming).use{out->
                val buf=ByteArray(262144)
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break
                    bytes+=n;require(bytes<=1_500_000_000L){"Candidate pack is too large"}
                    require(StatFs(root.path).availableBytes>n+64_000_000L){"Not enough storage for candidate pack"}
                    out.write(buf,0,n);digest.update(buf,0,n)
                };out.fd.sync()
            }} ?:error("Android could not open the candidate pack")
            require(hex(digest.digest())==hash){"Wrong Voice V3 pack checksum. Existing models were not changed."}
            progress("Verifying Voice V3 files",null)
            val files=expected.getJSONObject("files");val found=HashSet<String>();var unpacked=0L
            ZipInputStream(incoming.inputStream().buffered()).use{zip->
                while(true){currentCoroutineContext().ensureActive();val entry=zip.nextEntry ?:break
                    val name=entry.name
                    require(!entry.isDirectory && !name.contains('/') && !name.contains('\\') && name!=".." && files.has(name) && found.add(name)){"Unexpected entry in candidate pack"}
                    val file=File(staging,name);val md=MessageDigest.getInstance("SHA-256")
                    FileOutputStream(file).use{out->val buf=ByteArray(262144);while(true){currentCoroutineContext().ensureActive();val n=zip.read(buf);if(n<0)break
                        unpacked+=n;require(unpacked<=2_000_000_000L){"Unpacked candidate exceeds size limit"}
                        require(StatFs(root.path).availableBytes>n+64_000_000L){"Not enough storage to unpack voice"}
                        out.write(buf,0,n);md.update(buf,0,n)
                    };out.fd.sync()}
                    require(hex(md.digest())==files.getString(name)){"Voice V3 file verification failed: $name"}
                }
            }
            require(found.size==files.length()){"Incomplete Voice V3 pack"}
            atomicText(File(staging,"verified.json"),expected.toString())
            check(staging.renameTo(File(root,hash))){"Could not finish candidate voice import"}
            progress("Voice V3 candidate imported; test before promotion",100)
        } finally {incoming.delete();staging.deleteRecursively()}
    }
}
