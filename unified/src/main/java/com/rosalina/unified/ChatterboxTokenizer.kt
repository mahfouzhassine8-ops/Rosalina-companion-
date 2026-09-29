package com.rosalina.unified

import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.regex.Pattern

/** Byte-level BPE for the pinned Chatterbox tokenizer, including its double end token. */
internal class ChatterboxTokenizer(file:File) {
    private val vocab=HashMap<String,Long>()
    private val ranks=HashMap<Pair<String,String>,Int>()
    private val special=HashMap<String,Long>()
    private val bytesToChars=Array(256){""}
    // Android uses Unicode classes by default and rejects UNICODE_CHARACTER_CLASS.
    // Explicit whitespace properties also preserve the same behavior in desktop tests.
    private val words=Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\p{IsWhite_Space}\\p{L}\\p{N}]+|\\p{IsWhite_Space}+(?!\\P{IsWhite_Space})|\\p{IsWhite_Space}+")
    private val specialPattern:Pattern
    private val cache=object:LinkedHashMap<String,List<Long>>(256,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,List<Long>>?)=size>512}
    init {
        require(file.length() in 1..15_000_000){"Tokenizer size is invalid"}
        val doc=JSONObject(file.readText());val model=doc.getJSONObject("model")
        require(model.getString("type")=="BPE" && doc.isNull("normalizer")){"Unexpected tokenizer format"}
        val v=model.getJSONObject("vocab");v.keys().forEach{vocab[it]=v.getLong(it)}
        val merges=model.getJSONArray("merges")
        for(i in 0 until merges.length()){
            val m=merges.get(i)
            val pair=if(m is org.json.JSONArray)Pair(m.getString(0),m.getString(1)) else m.toString().split(' ',limit=2).let{require(it.size==2);Pair(it[0],it[1])}
            ranks[pair]=i
        }
        val tokens=doc.getJSONArray("added_tokens")
        for(i in 0 until tokens.length()){val t=tokens.getJSONObject(i);special[t.getString("content")]=t.getLong("id")}
        specialPattern=Pattern.compile(special.keys.sortedByDescending{it.length}.joinToString("|"){Pattern.quote(it)})
        val direct=(33..126).toList()+(161..172).toList()+(174..255).toList()
        var extra=0
        for(i in 0..255)bytesToChars[i]=(if(i in direct)i else 256+extra++).toChar().toString()
    }
    private fun piece(word:String):List<Long> = cache[word] ?:run {
        val encoded=word.toByteArray(Charsets.UTF_8).map{bytesToChars[it.toInt() and 255]}.toMutableList()
        while(encoded.size>1){
            var index=-1;var best=Int.MAX_VALUE
            for(i in 0 until encoded.lastIndex){val rank=ranks[encoded[i] to encoded[i+1]] ?:continue;if(rank<best){best=rank;index=i}}
            if(index<0)break
            val a=encoded[index];val b=encoded[index+1];var i=0
            while(i<encoded.size-1){if(encoded[i]==a && encoded[i+1]==b){encoded[i]=a+b;encoded.removeAt(i+1)};i++}
        }
        encoded.map{vocab[it] ?:error("Tokenizer contains an unknown byte piece")}.also{cache[word]=it}
    }
    @Synchronized fun encode(text:String):LongArray {
        require(text.length in 1..500){"Use a speech clause between 1 and 500 characters"}
        val result=ArrayList<Long>();var offset=0
        fun ordinary(s:String){val m=words.matcher(s);while(m.find())result.addAll(piece(m.group()))}
        val m=specialPattern.matcher(text)
        while(m.find()){ordinary(text.substring(offset,m.start()));result+=special.getValue(m.group());offset=m.end()}
        ordinary(text.substring(offset));result+=50256L;result+=50256L
        require(result.size<=1024){"Speech clause token limit exceeded"}
        return result.toLongArray()
    }
}
