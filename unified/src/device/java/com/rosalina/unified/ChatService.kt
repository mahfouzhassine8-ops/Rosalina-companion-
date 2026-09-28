package com.rosalina.unified

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class ChatService : NativeRpcService() {
    private var engine: InferenceEngine? = null
    private var loaded = ""
    private var loadedSystem = ""

    private suspend fun getEngine():InferenceEngine {
        return engine ?: AiChat.getInferenceEngine(this).also {
            engine=it
            withTimeout(30000) {
                it.state.first { s -> s is InferenceEngine.State.Initialized || s is InferenceEngine.State.ModelReady || s is InferenceEngine.State.Error }
            }
        }
    }

    private suspend fun freshLoad(
        e:InferenceEngine,
        path:String,
        system:String,
        emit:(String,String,Bundle?)->Unit
    ):Long {
        val started=SystemClock.elapsedRealtime()
        when(e.state.value) {
            is InferenceEngine.State.ModelReady,
            is InferenceEngine.State.Error -> e.cleanUp()
            is InferenceEngine.State.Initialized -> Unit
            else -> withTimeout(15000) {
                e.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error }
            }.let {
                when(it) {
                    is InferenceEngine.State.ModelReady,
                    is InferenceEngine.State.Error -> e.cleanUp()
                    else -> Unit
                }
            }
        }
        emit("stage","Loading chat model",null)
        e.loadModel(path)
        check(e.state.value is InferenceEngine.State.ModelReady){"Chat model load failed: "+e.state.value}
        emit("stage","Preparing chat",null)
        e.setSystemPrompt(system)
        check(e.state.value is InferenceEngine.State.ModelReady){"Chat system prompt failed: "+e.state.value}
        loaded=path
        loadedSystem=system
        return SystemClock.elapsedRealtime()-started
    }

    private suspend fun generate(
        e:InferenceEngine,
        prompt:String,
        maxTokens:Int,
        emit:(String,String,Bundle?)->Unit
    ):GenerationResult {
        val started=SystemClock.elapsedRealtime()
        var firstText=0L
        var pieces=0
        val answer=StringBuilder()
        val batch=StreamBatch()
        try {
            e.sendUserPrompt(prompt,maxTokens).collect { token->
                val now=SystemClock.elapsedRealtime()
                if(token.isNotEmpty()) {
                    if(firstText==0L)firstText=now
                    pieces++
                    answer.append(token)
                    batch.append(token,now)?.let{emit("token",it,null)}
                }
            }
        } finally {
            batch.flush()?.let{emit("token",it,null)}
        }
        return GenerationResult(answer.toString(),firstText,started,pieces)
    }

    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        val path=values.getString("model") ?:error("Chat model not selected")
        val prompt=values.getString("prompt").orEmpty()
        val system=(values.getString("system") ?:Session.DEFAULT_SYSTEM).ifBlank{Session.DEFAULT_SYSTEM}
        val prepareOnly=values.getString("operation")=="prepare"
        val start=SystemClock.elapsedRealtime()
        val e=getEngine()
        var warm=loaded==path && loadedSystem==system && e.state.value is InferenceEngine.State.ModelReady
        var setupMs=0L

        if(!warm)setupMs=freshLoad(e,path,system,emit)
        val loadedAt=SystemClock.elapsedRealtime()

        if(prepareOnly)return Bundle().apply{
            putBoolean("prepared",true)
            putBoolean("warmModel",warm)
            putLong("modelSetupMs",if(setupMs>0)setupMs else loadedAt-start)
        }

        require(prompt.isNotBlank()){"Prompt is empty"}
        emit("stage","Rosalina is responding",null)
        val maxTokens=values.getInt("maxTokens",1024).coerceIn(64,4096)

        var generated=generate(e,prompt,maxTokens,emit)
        var recovered=false
        if(generated.answer.isBlank()) {
            emit("stage","Recovering chat engine",null)
            setupMs+=freshLoad(e,path,system,emit)
            warm=false
            recovered=true
            emit("stage","Rosalina is responding",null)
            generated=generate(e,prompt,maxTokens,emit)
        }

        check(generated.answer.isNotBlank()){
            "The local chat engine returned no text after a clean reload: "+e.state.value
        }
        return Bundle().apply{
            putString("answer",generated.answer)
            putBoolean("warmModel",warm)
            putBoolean("recovered",recovered)
            putLong("modelSetupMs",setupMs)
            putLong("firstTextMs",if(generated.firstText==0L)0L else generated.firstText-generated.started)
            putLong("responseMs",SystemClock.elapsedRealtime()-generated.started)
            putInt("textPieces",generated.pieces)
            putInt("characters",generated.answer.length)
            putLong("chatPssKb",Debug.getPss().toLong())
        }
    }

    private data class GenerationResult(
        val answer:String,
        val firstText:Long,
        val started:Long,
        val pieces:Int
    )
}
