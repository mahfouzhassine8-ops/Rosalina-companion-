package com.rosalina.unified

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Speech-service root only. No credentials, redirects, arbitrary paths or cloud destinations. */
internal class StudioEndpoint private constructor(val root:String,val host:String,val port:Int) {
    companion object {
        fun parse(value:String):StudioEndpoint {
            require(value.length in 1..512 && value.none{it.isWhitespace() || it.code<32}){"Enter an HTTPS Studio service address"}
            val uri=try{URI(value)}catch(_:Exception){throw IllegalArgumentException("Invalid Studio address")}
            require(uri.scheme.equals("https",true) && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null){"Studio requires HTTPS without credentials, query or fragment in the address"}
            val host=uri.host?.lowercase(Locale.ROOT)?.removePrefix("[")?.removeSuffix("]")
                ?:throw IllegalArgumentException("Studio address needs a host")
            require(host.isNotBlank() && '%' !in host && host!="localhost" && !host.endsWith(".localhost")){"Use your Mac's private-network HTTPS address, not this phone's localhost"}
            require(uri.rawPath.orEmpty() in listOf("","/","/v1","/v1/")){"Enter the service root or its /v1 address only"}
            val port=if(uri.port==-1)443 else uri.port;require(port in 1..65535){"Invalid Studio port"}
            val printable=if(':' in host)"[$host]" else host
            return StudioEndpoint("https://$printable${if(port==443)"" else ":$port"}",host,port)
        }
    }
}

/** Check the actual connected peer, not a separate DNS lookup vulnerable to rebinding. */
internal object StudioNetworkPolicy {
    fun isPrivate(address:InetAddress):Boolean {
        if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress)return false
        val b=address.address.map{it.toInt() and 255}
        if(b.size==4)return b[0]==10 || (b[0]==172 && b[1] in 16..31) ||
            (b[0]==192 && b[1]==168) || (b[0]==100 && b[1] in 64..127)
        // IPv6 ULA, including private tailnet addresses. Do not treat public IPv6 as local.
        return b.size==16 && (b[0] and 254)==252
    }
    fun requirePrivate(address:InetAddress){require(isPrivate(address)){"Studio destination is not on a private network"}}
}

internal enum class StudioStyleMode(val label:String) {
    NONE("No style instructions"), OMNIVOICE_TAGS("OmniVoice design tags"), FREE_TEXT("Engine supports free-text style")
}
internal object StudioStyle {
    fun instructions(mode:StudioStyleMode,state:PerformanceState):String? {
        val p=state.bounded()
        return when(mode) {
            StudioStyleMode.NONE->null
            // The documented tags are not a promise of breaths, laughs or full emotion control.
            StudioStyleMode.OMNIVOICE_TAGS->if(p.delivery==Delivery.WHISPER)"female, whisper" else "female"
            StudioStyleMode.FREE_TEXT->{
                val delivery=when(p.delivery){
                    Delivery.NATURAL->"natural conversational delivery";Delivery.SOFT->"gentle, softer delivery"
                    Delivery.EMPHATIC->"clear emphasis without shouting";Delivery.THOUGHTFUL->"thoughtful pacing with natural pauses"
                    Delivery.PLAYFUL->"warm playful delivery";Delivery.WHISPER->"quiet whisper-like delivery, only if supported"
                    Delivery.SIGH->"a small natural sigh, only if supported; never read the word sigh"
                    Delivery.CHUCKLE->"a small natural chuckle, only if supported; never read a stage direction"
                    Delivery.LAUGH->"a brief natural laugh, only if supported; never read a stage direction"
                }
                // Finite vocabulary only: no user prompt, transcript or history enters instructions.
                val emotion=p.emotion.takeIf{it in setOf("neutral","happy","reassuring","subdued","soft","gentle","natural","thoughtful","playful","teasing","shy","surprised")} ?:"neutral"
                "Use $delivery with a $emotion tone. Speak only the supplied response text; do not read these instructions."
            }
        }
    }
}

/** Preserve a byte split across HTTP chunks; reject a truncated sample at end-of-stream. */
internal class StudioPcmDecoder(private val volume:Float=1f) {
    private var low:Int?=null
    var bytes:Long=0;private set
    fun decode(data:ByteArray,count:Int=data.size):ShortArray {
        require(count in 0..data.size);bytes+=count
        require(bytes<=24_000L*2*60){"Studio audio exceeded the 60-second clause limit"}
        val out=ShortArray((count+if(low==null)0 else 1)/2);var i=0;var at=0
        while(i<count){val l=low ?: (data[i++].toInt() and 255)
            if(i==count){low=l;break}
            val h=data[i++].toInt();low=null
            val signed=(l or (h shl 8)).toShort().toInt()
            out[at++]=(signed*volume.coerceIn(.55f,1f)).toInt().coerceIn(-32768,32767).toShort()
        }
        return if(at==out.size)out else out.copyOf(at)
    }
    fun finish(){require(low==null && bytes>=2){"Studio returned empty or truncated PCM"}}
}

internal enum class StudioFailure(val label:String) {
    DISABLED("Studio Voice is off"), BUSY("A previous Studio connection is still closing"),
    AUTH("Studio authentication failed; check the API key and sharing PIN"),
    NETWORK("Studio unavailable; check that the Mac is awake and connected"),
    TLS("Studio TLS verification failed; use a trusted HTTPS certificate"),
    ADDRESS("Studio destination must be on a private network"),
    TIMEOUT("Studio audio timed out; phone voice selected"),
    FORMAT("Studio returned unsupported or incomplete PCM"),
    SERVER("Studio could not synthesize this request"),
    AUDIO("Android could not play Studio audio")
}
internal class StudioVoiceException(val failure:StudioFailure):java.io.IOException(failure.label)
internal object StudioFallbackPolicy {
    // A partial utterance must not be replayed from its beginning by another engine.
    fun mayReplayClause(playbackStarted:Boolean,cancelled:Boolean)=!playbackStarted && !cancelled
    fun useOnlineAfterStudioFailure()=false
}
