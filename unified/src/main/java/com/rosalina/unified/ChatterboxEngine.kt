package com.rosalina.unified

import ai.onnxruntime.*
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded CPU implementation of the exact graph sequence retained in the passed host receipt. */
internal class ChatterboxEngine(private val root:File,private val cancelled:AtomicBoolean):AutoCloseable {
    private val environment=OrtEnvironment.getEnvironment()
    private val options=OrtSession.SessionOptions().apply{
        setIntraOpNumThreads(2);setInterOpNumThreads(1);setMemoryPatternOptimization(false);setCPUArenaAllocator(false)
        addConfigEntry("session.intra_op.allow_spinning","0");addConfigEntry("session.inter_op.allow_spinning","0")
    }
    private var embed:OrtSession?=null;private var language:OrtSession?=null;private var decoder:OrtSession?=null
    private val runLock=Any()
    private var currentRun:OrtSession.RunOptions?=null
    private val tokenizer=ChatterboxTokenizer(File(root,"tokenizer.json"))
    val runtimeVersion:String get()=environment.version
    init {
        try {
            environment.setTelemetry(false)
            checkCancelled();embed=environment.createSession(File(root,"onnx/embed_tokens_q4.onnx").path,options)
            checkCancelled();language=environment.createSession(File(root,"onnx/language_model_q4.onnx").path,options)
            checkCancelled();decoder=environment.createSession(File(root,"onnx/conditional_decoder_quantized.onnx").path,options)
        }catch(t:Throwable){close();throw t}
    }
    fun cancel(){cancelled.set(true);synchronized(runLock){runCatching{currentRun?.setTerminate(true)}}}
    private fun checkCancelled(){if(cancelled.get())throw CancellationException("Speech generation interrupted")}
    private fun floats(data:FloatArray,shape:LongArray)=OnnxTensor.createTensor(environment,FloatBuffer.wrap(data),shape)
    private fun longs(data:LongArray,shape:LongArray)=OnnxTensor.createTensor(environment,LongBuffer.wrap(data),shape)
    private fun output(result:OrtSession.Result,name:String)=result.get(name).orElseThrow{IllegalStateException("Missing voice graph output $name")} as OnnxTensor
    private fun floatData(t:OnnxTensor):FloatArray {val b=t.floatBuffer;return FloatArray(b.remaining()).also{b.get(it)}}
    private fun loadFloat(name:String,expected:Int):FloatArray {
        val bytes=File(root,"conditioning/$name.bin").readBytes();require(bytes.size==expected*4){"Invalid reference conditioning"}
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();return FloatArray(expected).also{b.get(it);require(it.all{v->v.isFinite()})}
    }
    private fun loadPrompt():LongArray {
        val bytes=File(root,"conditioning/prompt.bin").readBytes();require(bytes.size==250*8){"Invalid voice prompt conditioning"}
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();return LongArray(250).also{b.get(it)}
    }
    fun synthesize(text:String,onStep:(Int)->Unit={}):FloatArray {
        checkCancelled();val run=OrtSession.RunOptions()
        synchronized(runLock){currentRun=run;if(cancelled.get())run.setTerminate(true)}
        var previous:OrtSession.Result?=null
        val initial=LinkedHashMap<String,OnnxTensor>()
        var embeddings:OnnxTensor?=null
        try {
            val ids=tokenizer.encode(text)
            longs(ids,longArrayOf(1,ids.size.toLong())).use{input->embed!!.run(mapOf("input_ids" to input),run).use{result->
                val textEmbed=floatData(output(result,"inputs_embeds"));val condition=loadFloat("cond",151*1024)
                embeddings=floats(condition+textEmbed,longArrayOf(1,151L+ids.size,1024))
            }}
            val cacheNames=language!!.inputNames.filter{it.startsWith("past_key_values.")}.sortedWith(compareBy<String>{it.split('.')[1].toInt()}.thenBy{it})
            require(cacheNames.size==48){"Unexpected Chatterbox cache layout"}
            for(name in cacheNames)initial[name]=floats(FloatArray(0),longArrayOf(1,16,0,64))
            var total=151+ids.size;var sequence=total;var position=0L
            val generated=ArrayList<Long>();val repeated=HashSet<Long>().apply{add(6561L)}
            var ended=false
            for(step in 0 until 600) {
                checkCancelled();onStep(step)
                val mask=longs(LongArray(total){1L},longArrayOf(1,total.toLong()))
                val positions=longs(LongArray(sequence){position+it},longArrayOf(1,sequence.toLong()))
                val inputs=LinkedHashMap<String,OnnxTensor>()
                inputs["inputs_embeds"]=embeddings!!;inputs["attention_mask"]=mask;inputs["position_ids"]=positions
                for(name in cacheNames)inputs[name]=previous?.let{output(it,name.replace("past_key_values.","present."))} ?:initial.getValue(name)
                val next=try{language!!.run(inputs,run)}finally{mask.close();positions.close();embeddings?.close();embeddings=null}
                previous?.close();previous=next
                initial.values.forEach{it.close()};initial.clear()
                val logits=output(next,"logits");val values=logits.floatBuffer;val size=6563;val offset=values.limit()-size
                require(offset>=0){"Invalid voice logits"}
                var winner=-1;var highest=Float.NEGATIVE_INFINITY
                for(i in 0 until size){var v=values.get(offset+i);if(i.toLong() in repeated)v=if(v<0)v*1.2f else v/1.2f
                    if(v.isFinite() && v>highest){highest=v;winner=i}}
                require(winner>=0){"Voice model produced invalid logits"}
                if(winner==6562){ended=true;break}
                generated+=winner.toLong();repeated+=winner.toLong()
                longs(longArrayOf(winner.toLong()),longArrayOf(1,1)).use{input->embed!!.run(mapOf("input_ids" to input),run).use{result->embeddings=floats(floatData(output(result,"inputs_embeds")),longArrayOf(1,1,1024))}}
                position=total.toLong();sequence=1;total++
            }
            require(ended && generated.isNotEmpty()){"Voice did not finish within the bounded generation limit"}
            previous?.close();previous=null;checkCancelled()
            val tokens=loadPrompt()+generated.toLongArray()+longArrayOf(4299,4299,4299)
            longs(tokens,longArrayOf(1,tokens.size.toLong())).use{speech->
                floats(loadFloat("speaker",192),longArrayOf(1,192)).use{speaker->
                    floats(loadFloat("features",499*80),longArrayOf(1,499,80)).use{features->
                        decoder!!.run(mapOf("speech_tokens" to speech,"speaker_embeddings" to speaker,"speaker_features" to features),run).use{result->
                            val audio=floatData(output(result,"waveform"));checkCancelled()
                            require(audio.size in 2400..1_440_000 && audio.all{it.isFinite()} && audio.any{kotlin.math.abs(it)>.001f}){"Voice produced invalid or silent audio"}
                            return audio
                        }
                    }
                }
            }
        }catch(t:Throwable){checkCancelled();throw t}
        finally {
            embeddings?.close();previous?.close();initial.values.forEach{it.close()}
            synchronized(runLock){currentRun=null;run.close()}
        }
    }
    override fun close(){runCatching{embed?.close()};runCatching{language?.close()};runCatching{decoder?.close()};embed=null;language=null;decoder=null;runCatching{options.close()}}
}
