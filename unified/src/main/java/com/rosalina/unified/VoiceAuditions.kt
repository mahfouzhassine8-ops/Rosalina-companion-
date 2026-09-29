package com.rosalina.unified
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, text-free comparison receipts. Native and system memory scopes are not conflated. */
internal class VoiceAuditions(private val prefs:SharedPreferences) {
    @Synchronized fun record(engine:String,pack:String,success:Boolean,initializationMs:Long?,firstAudioMs:Long?,playbackMs:Long?,peakPssKb:Long?,memoryScope:String,thermalBefore:Int,thermalAfter:Int,fallback:String="") {
        val old=runCatching{JSONArray(prefs.getString("voice-auditions-v1","[]"))}.getOrElse{JSONArray()}
        val next=JSONArray();for(i in maxOf(0,old.length()-17) until old.length())next.put(old.get(i))
        next.put(JSONObject().put("time",System.currentTimeMillis()).put("engine",engine).put("pack",pack).put("success",success)
            .put("initializationMs",initializationMs ?:JSONObject.NULL).put("firstAudioMs",firstAudioMs ?:JSONObject.NULL).put("playbackMs",playbackMs ?:JSONObject.NULL)
            .put("peakSampledPssKb",peakPssKb ?:JSONObject.NULL).put("memoryScope",memoryScope).put("thermalBefore",thermalBefore).put("thermalAfter",thermalAfter).put("fallback",fallback.take(180)))
        prefs.edit().putString("voice-auditions-v1",next.toString()).apply()
    }
    @Synchronized fun canAccept(pack:String):Boolean {
        val entries=runCatching{JSONArray(prefs.getString("voice-auditions-v1","[]"))}.getOrElse{JSONArray()}
        for(i in entries.length()-1 downTo 0){val r=entries.getJSONObject(i);if(r.optString("engine")=="local" && r.optString("pack")==pack)return r.optBoolean("success",false)}
        return false
    }
    @Synchronized fun summary():String {
        val entries=runCatching{JSONArray(prefs.getString("voice-auditions-v1","[]"))}.getOrElse{JSONArray()}
        if(entries.length()==0)return "No audition measurements yet."
        return (maxOf(0,entries.length()-3) until entries.length()).joinToString("\n"){i->val r=entries.getJSONObject(i)
            "${r.optString("engine")}: ${if(r.optBoolean("success"))"completed" else "failed"}; init=${r.opt("initializationMs")} ms; first playback=${r.opt("firstAudioMs")} ms; playback=${r.opt("playbackMs")} ms; peak sampled PSS=${r.opt("peakSampledPssKb")} KiB (${r.optString("memoryScope")}); thermal ${r.optInt("thermalBefore")}→${r.optInt("thermalAfter")}; fallback=${r.optString("fallback").ifBlank{"none"}}"
        }
    }
}
