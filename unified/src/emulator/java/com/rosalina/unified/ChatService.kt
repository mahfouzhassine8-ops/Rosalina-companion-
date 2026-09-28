package com.rosalina.unified
import android.os.Bundle
/** Emulator package contains no substitute Qwen engine. Inference tests require the ARM64 candidate. */
class ChatService:NativeRpcService(){override suspend fun execute(values:Bundle,emit:(String,String,Bundle?)->Unit):Bundle=error("Qwen inference is excluded from x86_64 lifecycle/codec QA; this is not an inference test")}
