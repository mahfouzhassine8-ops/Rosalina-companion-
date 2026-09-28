package com.rosalina.unified
import android.os.Bundle
import kotlinx.coroutines.delay
/** Emulator-only protocol fixture. Normal inference is explicitly unavailable, never simulated. */
class ChatService:NativeRpcService() {
    override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle {
        check(values.getString("operation")=="qa-protocol") {"Qwen inference is excluded from x86_64 lifecycle/codec QA; this is not an inference test"}
        delay(values.getLong("delay",0).coerceIn(0,10000))
        return Bundle().apply{putInt("sequence",values.getInt("sequence"));putString("scope","IPC fixture only, no model inference")}
    }
}
