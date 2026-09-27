package com.rosalina.studio

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.io.File
import kotlin.random.Random

class StudioActivity : AppCompatActivity() {
    private val bg=Color.rgb(13,10,19)
    private val panel=Color.rgb(30,24,41)
    private val ink=Color.rgb(247,241,255)
    private val muted=Color.rgb(179,168,194)
    private val accent=Color.rgb(194,166,255)
    private val formPrefs by lazy { getSharedPreferences("studio_form",MODE_PRIVATE) }
    private var mode="Create"
    private var pendingImage=true
    private lateinit var status: TextView
    private lateinit var badge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var details: MaterialButton
    private lateinit var cancel: MaterialButton
    private lateinit var models: MaterialButton
    private lateinit var settings: MaterialButton
    private lateinit var chatPane: LinearLayout
    private lateinit var imagePane: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var send: MaterialButton
    private lateinit var imagePrompt: EditText
    private lateinit var summary: TextView
    private lateinit var reference: ImageView
    private lateinit var attach: MaterialButton
    private lateinit var improve: MaterialButton
    private lateinit var advanced: MaterialButton
    private lateinit var generate: MaterialButton
    private lateinit var preview: ImageView
    private lateinit var resultLabel: TextView
    private lateinit var save: MaterialButton
    private lateinit var share: MaterialButton
    private lateinit var gallery: LinearLayout
    private val tabs=mutableListOf<MaterialButton>()
    private var sizeIndex=0
    private var steps=12
    private var strength=.55f
    private var seed=-1L
    private var negative="blurry, low quality, distorted"
    private var rendered: List<ChatLine> = emptyList()
    private var lastBubble: TextView?=null
    private var lastResult=""
    private var lastRef=""
    private var previewBitmap: Bitmap?=null
    private var refBitmap: Bitmap?=null
    private val pickModel=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { StudioSession.importModel(it,pendingImage) } }
    private val pickPhoto=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { StudioSession.importReference(it) } }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Rendering also works when notification permission is declined. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StudioSession.init(applicationContext)
        mode=savedInstanceState?.getString("mode") ?: formPrefs.getString("mode","Create").orEmpty()
        if(mode !in listOf("Chat","Create","Edit")) mode="Create"
        pendingImage=savedInstanceState?.getBoolean("pendingImage") ?: true
        sizeIndex=formPrefs.getInt("size",0).coerceIn(0,2)
        steps=formPrefs.getInt("steps",12).coerceIn(4,30)
        strength=formPrefs.getFloat("strength",.55f).coerceIn(.1f,.9f)
        seed=formPrefs.getLong("seed",-1L)
        negative=formPrefs.getString("negative",negative).orEmpty()
        buildUi()
        applyMode(false)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { StudioSession.state.collect { update(it) } } }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun text(value:String,size:Float=14f,color:Int=ink)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); typeface=Typeface.create("sans-serif",Typeface.NORMAL)
    }
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=MaterialButton(this).apply {
        text=label; isAllCaps=false; textSize=14f; minHeight=dp(48); minimumHeight=dp(48)
        cornerRadius=dp(16); insetTop=dp(3); insetBottom=dp(3)
        setTextColor(if(primary) bg else ink)
        backgroundTintList=ColorStateList.valueOf(if(primary) accent else panel)
        strokeColor=ColorStateList.valueOf(Color.rgb(70,57,91)); strokeWidth=if(primary) 0 else dp(1)
        setOnClickListener { action() }
    }
    private fun field(hintText:String,lines:Int=1)=EditText(this).apply {
        hint=hintText; textSize=16f; setTextColor(ink); setHintTextColor(muted)
        typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        minLines=lines; maxLines=if(lines>1) 8 else 3
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background=shape(panel,16)
        setPadding(dp(14),dp(12),dp(14),dp(12))
    }
    private fun shape(color:Int,radius:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun row(vararg views: View)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        views.forEach { addView(it,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply { marginEnd=dp(4) }) }
    }
    private fun spacer(n:Int)=View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(n)) }
    private fun buildUi() {
        val outer=FrameLayout(this).apply { setBackgroundColor(bg) }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(6),dp(12),dp(8)) }
        outer.addView(root,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,Gravity.CENTER))
        outer.addOnLayoutChangeListener { v,_,_,_,_,_,_,_,_ ->
            val desired=minOf(v.width-v.paddingLeft-v.paddingRight,dp(840))
            if(desired>0 && root.layoutParams.width!=desired) root.layoutParams=root.layoutParams.apply { width=desired }
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, wi ->
            val b=wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left,b.top,b.right,b.bottom); wi
        }
        val heading=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(text("ROSALINA",24f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
            addView(text("PRIVATE  ·  IMAGE LAB",11f,accent).apply { letterSpacing=.12f })
        }
        models=button("Models") { modelDialog() }
        settings=button("Settings") { settingsDialog() }
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            addView(heading,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            addView(models,LinearLayout.LayoutParams(dp(90),dp(54)))
            addView(settings,LinearLayout.LayoutParams(dp(94),dp(54)))
        })
        badge=text("ON DEVICE",11f,accent)
        status=text("Preparing studio…",13f,muted).apply { maxLines=3 }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { progressTintList=ColorStateList.valueOf(accent); max=100; visibility=View.GONE }
        details=button("Details") { diagnosticsDialog() }.apply { visibility=View.GONE }
        cancel=button("Stop") { StudioSession.cancel() }.apply { visibility=View.GONE }
        val statusBox=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; background=shape(panel,16); setPadding(dp(14),dp(10),dp(14),dp(10))
            addView(badge); addView(spacer(3)); addView(status)
            addView(progress,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6)))
            addView(row(details,cancel))
        }
        root.addView(spacer(8)); root.addView(statusBox); root.addView(spacer(8))
        listOf("Chat","Create","Edit").forEach { label -> tabs+=button(label) {
            if(!StudioSession.state.value.busy) { storeForm(); mode=label; applyMode(true) }
        } }
        root.addView(row(*tabs.toTypedArray()))
        root.addView(spacer(8))
        val content=FrameLayout(this)
        root.addView(content,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        chatPane=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        chatList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(4),dp(8),dp(4),dp(12)) }
        chatScroll=ScrollView(this).apply { isFillViewport=true; addView(chatList) }
        chatPane.addView(chatScroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        chatInput=field("Message Rosalina…").apply { setText(formPrefs.getString("chat_draft","")) }
        send=button("Send",true) {
            val s=StudioSession.state.value
            if(s.busy && s.work=="chat") StudioSession.cancel()
            else {
                val p=chatInput.text.toString().trim()
                val route=Limits.imageIntent(p)
                if(route!=null) { imagePrompt.setText(route); mode="Create"; applyMode(true); StudioSession.notice("Image request prepared · review it, then tap Generate") }
                else if(s.chatPath.isBlank()) modelDialog()
                else { StudioSession.send(p); if(p.isNotEmpty()) chatInput.text.clear() }
            }
        }
        chatPane.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.BOTTOM
            addView(chatInput,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            addView(send,LinearLayout.LayoutParams(dp(85),dp(56)))
        })
        content.addView(chatPane)
        imagePane=ScrollView(this).apply { isFillViewport=true }
        val imageBody=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(4),dp(6),dp(4),dp(20)) }
        imagePane.addView(imageBody)
        imageBody.addView(text("Describe what you want to create",20f))
        imageBody.addView(spacer(4))
        imageBody.addView(text("Generated on your phone. No image API or credits.",13f,muted))
        imageBody.addView(spacer(12))
        imagePrompt=field("A glass house above the ocean at sunset, cinematic lighting…",3).apply { setText(formPrefs.getString("prompt","")) }
        imageBody.addView(imagePrompt)
        attach=button("Choose reference photo") { pickPhoto.launch(arrayOf("image/*")) }
        imageBody.addView(attach)
        reference=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="Reference photo; generation uses a centered crop"; clipToOutline=true; background=shape(panel,16) }
        imageBody.addView(reference,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(150)))
        improve=button("Refine prompt with Qwen") {
            if(StudioSession.state.value.chatPath.isBlank()) modelDialog()
            else StudioSession.send(imagePrompt.text.toString(),true)
        }
        advanced=button("Image controls") { advancedDialog() }
        imageBody.addView(row(improve,advanced))
        summary=text("",12f,muted).apply { setPadding(dp(4),dp(6),dp(4),dp(6)) }
        imageBody.addView(summary)
        generate=button("Generate image",true) { if(StudioSession.state.value.work=="render") StudioSession.cancel() else generateImage() }
        imageBody.addView(generate,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(60)))
        imageBody.addView(text("CPU test build: rendering can take minutes. Stop is always available. Edit transforms the whole photo; it is not a selective background or face-preserving editor.",12f,muted).apply { setPadding(dp(4),dp(8),dp(4),dp(12)) })
        resultLabel=text("Your next creation appears here",17f)
        imageBody.addView(resultLabel)
        imageBody.addView(spacer(8))
        preview=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; adjustViewBounds=true; contentDescription="Generated image"; background=shape(panel,18) }
        imageBody.addView(preview,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(300)))
        save=button("Save to gallery") { saveResult() }
        share=button("Share") { shareResult() }
        imageBody.addView(row(save,share))
        imageBody.addView(spacer(12)); imageBody.addView(text("Recent creations",14f,muted))
        gallery=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        imageBody.addView(HorizontalScrollView(this).apply { addView(gallery) })
        content.addView(imagePane)
        setContentView(outer)
        ViewCompat.requestApplyInsets(outer)
    }
    private fun applyMode(animate:Boolean) {
        chatPane.visibility=if(mode=="Chat") View.VISIBLE else View.GONE
        imagePane.visibility=if(mode=="Chat") View.GONE else View.VISIBLE
        attach.visibility=if(mode=="Edit") View.VISIBLE else View.GONE
        reference.visibility=if(mode=="Edit" && StudioSession.state.value.reference.isNotBlank()) View.VISIBLE else View.GONE
        tabs.forEach { b -> b.backgroundTintList=ColorStateList.valueOf(if(b.text.toString()==mode) accent else panel); b.setTextColor(if(b.text.toString()==mode) bg else ink) }
        if(animate) (if(mode=="Chat") chatPane else imagePane).apply { alpha=0f; animate().alpha(1f).setDuration(140).start() }
        formPrefs.edit().putString("mode",mode).apply()
        update(StudioSession.state.value)
    }
    private fun update(s: StudioState) {
        status.text=s.status
        badge.text=when { s.work=="render" -> "IMAGE ENGINE · LOCAL CPU"; s.warmChat -> "QWEN ACTIVE · LOCAL · 8K"; else -> "ON DEVICE · NO CLOUD INFERENCE" }
        progress.visibility=if(s.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate=s.progress==null
        s.progress?.let { progress.progress=it }
        details.visibility=if(s.details.isNotBlank()) View.VISIBLE else View.GONE
        cancel.visibility=if(s.busy) View.VISIBLE else View.GONE
        models.isEnabled=!s.busy; settings.isEnabled=!s.busy
        tabs.forEach { it.isEnabled=!s.busy }
        send.text=if(s.busy && s.work=="chat") "Stop" else "Send"
        send.isEnabled=!s.busy || s.work=="chat"
        chatInput.isEnabled=!s.busy
        imagePrompt.isEnabled=!s.busy
        improve.isEnabled=!s.busy; advanced.isEnabled=!s.busy; attach.isEnabled=!s.busy
        generate.text=when { s.work=="render" -> "Stop generation"; s.imagePath.isBlank() -> "Add image model to begin"; mode=="Edit" -> "Transform photo"; else -> "Generate image" }
        generate.isEnabled=!s.busy || s.work=="render"
        val size=Limits.SIZES[sizeIndex]
        summary.text="${size.first} × ${size.second} · $steps steps · seed ${if(seed<0) "random" else seed.toString()}" + if(mode=="Edit") " · strength $strength" else ""
        if(s.suggestedPrompt.isNotBlank()) { imagePrompt.setText(s.suggestedPrompt); StudioSession.consumeSuggestion(); storeForm() }
        if(s.messages!=rendered || chatList.childCount==0) {
            if(s.messages.isNotEmpty() && s.messages.size==rendered.size && s.messages.dropLast(1)==rendered.dropLast(1) && lastBubble!=null) {
                lastBubble?.text=s.messages.last().text.ifBlank { "Thinking…" }
            } else {
                chatList.removeAllViews(); lastBubble=null
                if(s.messages.isEmpty()) {
                    chatList.addView(text("A space for your ideas",22f).apply { setPadding(dp(8),dp(24),dp(8),dp(8)) })
                    chatList.addView(text(if(s.chatPath.isBlank()) "Import your existing Qwen file under Models to chat here. Your original Rosalina app remains untouched." else "Qwen is verified. Send a message to start a local chat, or ask ‘Create an image of…’ to prepare an image request.",15f,muted).apply { setPadding(dp(8),0,dp(8),dp(16)) })
                }
                s.messages.forEach { line ->
                    chatList.addView(text(if(line.user) "YOU" else "ROSALINA",10f,accent).apply { setPadding(dp(8),dp(14),dp(8),dp(5)) })
                    val b=text(line.text.ifBlank { "Thinking…" },16f).apply {
                        background=shape(if(line.user) Color.rgb(49,37,66) else panel,16)
                        setPadding(dp(14),dp(12),dp(14),dp(12)); setTextIsSelectable(true)
                    }
                    chatList.addView(b); lastBubble=b
                }
                if(s.messages.isNotEmpty()) chatList.addView(text("Saved transcript stays here. Switching engines starts a fresh Qwen context; the transcript is not replayed automatically.",11f,muted).apply { setPadding(dp(8),dp(16),dp(8),0) })
            }
            rendered=s.messages
            if(s.work=="chat") chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
        reference.visibility=if(mode=="Edit" && s.reference.isNotBlank()) View.VISIBLE else View.GONE
        if(s.reference.isNotBlank() && (s.reference!=lastRef || (!s.busy && s.work.isEmpty() && reference.drawable==null))) {
            refBitmap?.recycle(); refBitmap=BitmapFactory.decodeFile(s.reference); reference.setImageBitmap(refBitmap); lastRef=s.reference
        }
        if(s.result!=lastResult) {
            previewBitmap?.recycle(); previewBitmap=if(s.result.isNotBlank()) BitmapFactory.decodeFile(s.result) else null
            preview.setImageBitmap(previewBitmap); lastResult=s.result
            resultLabel.text=if(previewBitmap!=null) "Your creation · ${previewBitmap!!.width} × ${previewBitmap!!.height}" else "Your next creation appears here"
            refreshGallery()
        }
        if(gallery.childCount==0) refreshGallery()
        save.isEnabled=!s.busy && s.result.isNotBlank(); share.isEnabled=save.isEnabled
    }
    private fun refreshGallery() {
        gallery.removeAllViews()
        StudioSession.results().take(8).forEach { file ->
            val image=ImageView(this).apply {
                scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="Open saved image ${file.name}"
                setImageBitmap(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=4 }))
                setOnClickListener { StudioSession.selectResult(file) }
            }
            gallery.addView(image,LinearLayout.LayoutParams(dp(90),dp(90)).apply { setMargins(dp(3),dp(8),dp(3),0) })
        }
    }
    private fun modelDialog() {
        val s=StudioSession.state.value
        val items=arrayOf("Get image model · SD 1.5 Q4 · 1.57 GB", "Import image model${if(s.imagePath.isNotBlank()) " · verified" else ""}",
            "Import existing Qwen chat file${if(s.chatPath.isNotBlank()) " · verified" else ""}","Get Qwen chat model · only if needed", "About models and privacy")
        AlertDialog.Builder(this).setTitle("Two models, different jobs").setItems(items) { _, index ->
            when(index) {
                0 -> openUrl(Limits.IMAGE_URL)
                1 -> { pendingImage=true; pickModel.launch(arrayOf("*/*")) }
                2 -> { pendingImage=false; pickModel.launch(arrayOf("*/*")) }
                3 -> openUrl(Limits.CHAT_URL)
                4 -> AlertDialog.Builder(this).setTitle("Models stay on this phone").setMessage(
                    "The APK contains the engines, not the model downloads. Import SD 1.5 Q4 for images. Qwen is optional for chat and prompt refinement. Only one heavy engine runs at a time in this app.\n\nImage Lab is a separate test app, so the original working Rosalina is preserved. Close that original app before a render to release its RAM.\n\nInference has no network permission. Downloads open in your browser; Share sends only the image you choose. App backup is disabled. Uninstalling Image Lab deletes its private files; save creations to your gallery first.\n\nSD 1.5 uses CreativeML Open RAIL-M; model use remains subject to its license. Qwen files retain their model license. Image editing is whole-photo image-to-image, not guaranteed face or detail preservation."
                ).setPositiveButton("OK",null).setNeutralButton("Image model license") { _,_ -> openUrl("https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF") }.show()
            }
        }.setNegativeButton("Close",null).show()
    }
    private fun advancedDialog() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0) }
        val choices=Spinner(this).apply { adapter=ArrayAdapter(this@StudioActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Square · 512 × 512","Portrait · 384 × 512","Landscape · 512 × 384")); setSelection(sizeIndex) }
        val stepField=field("Steps · 4–30").apply { inputType=InputType.TYPE_CLASS_NUMBER; setText(steps.toString()) }
        val seedField=field("Seed · -1 for random").apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(seed.toString()) }
        val strengthField=field("Edit strength · 0.1–0.9").apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(strength.toString()) }
        val neg=field("Negative prompt",2).apply { setText(negative) }
        box.addView(text("Aspect ratio",13f,muted)); box.addView(choices)
        box.addView(text("Steps (more is slower)",13f,muted)); box.addView(stepField)
        box.addView(text("Seed (-1 = new variation)",13f,muted)); box.addView(seedField)
        box.addView(text("Edit strength (higher changes more)",13f,muted)); box.addView(strengthField)
        box.addView(text("Avoid in the image",13f,muted)); box.addView(neg)
        val dialog=AlertDialog.Builder(this).setTitle("Image controls").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val ns=stepField.text.toString().toInt(); val st=strengthField.text.toString().toFloat(); val se=seedField.text.toString().toLong()
                val dim=Limits.SIZES[choices.selectedItemPosition]; Limits.validate(dim.first,dim.second,ns,st)
                require(se>=-1) { "Seed must be -1 or a non-negative number" }
                sizeIndex=choices.selectedItemPosition; steps=ns; strength=st; seed=se; negative=neg.text.toString().take(2000)
                storeForm(); update(StudioSession.state.value); dialog.dismiss()
            } catch(e: Exception) { Toast.makeText(this,Limits.error(e),Toast.LENGTH_LONG).show() }
        } }
        dialog.show()
    }
    private fun settingsDialog() {
        AlertDialog.Builder(this).setTitle("Studio settings").setItems(arrayOf("Chat personality","Start a new chat","Build and device info")) { _, i ->
            when(i) {
                0 -> { val input=field("System prompt",4).apply { setText(StudioSession.systemPrompt()) }; AlertDialog.Builder(this).setTitle("Chat personality").setMessage("Saving starts a fresh Qwen context; your transcript stays.").setView(input).setPositiveButton("Save") { _,_ -> StudioSession.saveSystemPrompt(input.text.toString()) }.setNegativeButton("Cancel",null).show() }
                1 -> AlertDialog.Builder(this).setTitle("Clear saved chat?").setMessage("This clears the transcript and Qwen context in Image Lab only. Models and images stay.").setPositiveButton("Clear chat") { _,_ -> StudioSession.clearChat() }.setNegativeButton("Cancel",null).show()
                2 -> AlertDialog.Builder(this).setTitle("Image Lab test build").setMessage("${BuildConfig.VERSION_NAME}\n${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}\n${Build.SUPPORTED_ABIS.joinToString()}\n\nImages: local CPU, maximum 4 threads. No GPU/NPU claim.\nChat: preserved llama.cpp engine.\n\nPhoto-to-video is not included in this build.").setPositiveButton("OK",null).show()
            }
        }.show()
    }
    private fun diagnosticsDialog() {
        val d=StudioSession.state.value.details
        AlertDialog.Builder(this).setTitle("Diagnostic details").setMessage(d).setPositiveButton("Close",null)
            .setNeutralButton("Copy") { _,_ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rosalina diagnostics",d)); Toast.makeText(this,"Details copied",Toast.LENGTH_SHORT).show() }.show()
    }
    private fun generateImage() {
        if(StudioSession.state.value.imagePath.isBlank()) { modelDialog(); return }
        val size=Limits.SIZES[sizeIndex]
        val request=RenderRequest(imagePrompt.text.toString().trim(),negative,size.first,size.second,steps,strength,
            if(seed<0) Random.nextLong(0,Long.MAX_VALUE) else seed,mode=="Edit")
        storeForm()
        if(!StudioSession.prepareRender(request)) return
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        try { startForegroundService(Intent(this,RenderService::class.java)) }
        catch(e: Exception) { StudioSession.renderFailedToStart(e) }
    }
    private fun saveResult() {
        val path=StudioSession.state.value.result
        if(path.isBlank()) return
        save.isEnabled=false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,File(path).name); put(MediaStore.Images.Media.MIME_TYPE,"image/png"); put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Rosalina"); put(MediaStore.Images.Media.IS_PENDING,1) }
                    val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Gallery could not create image")
                    try {
                        contentResolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } } ?: error("Cannot write image")
                        contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
                    } catch(e: Exception) { contentResolver.delete(uri,null,null); throw e }
                }
                StudioSession.notice("Saved to gallery · Pictures / Rosalina")
            } catch(e: Exception) { StudioSession.notice("Save failed: ${Limits.error(e)}") }
            finally { save.isEnabled=!StudioSession.state.value.busy }
        }
    }
    private fun shareResult() {
        runCatching {
            val file=File(StudioSession.state.value.result)
            val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share creation"))
        }.onFailure { StudioSession.notice("Share failed: ${Limits.error(it)}") }
    }
    private fun openUrl(url:String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }.onFailure { StudioSession.notice("No browser is available to open the model download") } }
    private fun storeForm() {
        if(!::imagePrompt.isInitialized) return
        formPrefs.edit().putString("mode",mode).putString("prompt",imagePrompt.text.toString()).putString("chat_draft",chatInput.text.toString())
            .putInt("size",sizeIndex).putInt("steps",steps).putFloat("strength",strength).putLong("seed",seed).putString("negative",negative).apply()
    }
    override fun onPause() { storeForm(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("mode",mode); outState.putBoolean("pendingImage",pendingImage); super.onSaveInstanceState(outState) }
    override fun onDestroy() { preview.setImageDrawable(null); reference.setImageDrawable(null); previewBitmap?.recycle(); refBitmap?.recycle(); super.onDestroy() }
}
