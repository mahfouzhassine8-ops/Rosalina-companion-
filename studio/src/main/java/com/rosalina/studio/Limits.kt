package com.rosalina.studio

object Limits {
    const val IMAGE_SHA = "b8944e9fe0b69b36ae1b5bb0185b3a7b8ef14347fe0fa9af6c64c4829022261f"
    const val CHAT_SHA = "dba64d0e5cce0739e27535ee0a6b75249eb8006ce8b2d6c060e20750035c4695"
    const val IMAGE_URL = "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf"
    const val CHAT_URL = "https://huggingface.co/lukey03/Qwen3.5-9B-abliterated-GGUF/resolve/main/Qwen3.5-9B-abliterated-Q4_K_M.gguf"
    val SIZES = listOf(512 to 512, 384 to 512, 512 to 384)
    fun validate(width: Int, height: Int, steps: Int, strength: Float) {
        require(width to height in SIZES) { "Choose a supported image size" }
        require(steps in 4..30) { "Steps must be between 4 and 30" }
        require(strength.isFinite() && strength in .1f.. .9f) { "Strength must be 0.1 to 0.9" }
    }
    fun imageIntent(text: String): String? {
        val m = Regex("(?is)^(?:/image\\s+|(?:generate|create|draw|make)\\s+(?:me\\s+)?(?:an?\\s+)?(?:image|picture|photo|illustration|wallpaper)\\s+(?:of\\s+)?)(.+)$").matchEntire(text.trim())
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
    fun error(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
}
