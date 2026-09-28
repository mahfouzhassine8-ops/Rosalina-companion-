package com.rosalina.unified

import android.Manifest
import android.content.*
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import java.io.File

class MainActivity:AppCompatActivity() {
    private val session by lazy{Session.get(this)}
    private val bg=Color.rgb(13,10,19);private val panel=Color.rgb(30,24,41)
    private val ink=Color.rgb(247,241,255);private val muted=Color.rgb(179,168,194);private val accent=Color.rgb(194,166,255)
    private var mode="Chat"
    private lateinit var root:LinearLayout
    private lateinit var content:FrameLayout
    private lateinit var status:TextView
    private lateinit var thermal:TextView
    private lateinit var progress:ProgressBar
    private lateinit var stop:Button
    private lateinit var prompt:EditText
    private lateinit var generate:Button
    private var preview:ImageView?=null
    private var reference:ImageView?=null
    private var resultLabel:TextView?=null
    private var chatList:LinearLayout?=null
    private var chatScroll:ScrollView?=null
    private var streaming:TextView?=null
    private var rendered=emptyList<Pair<String,String>>()
    private var lastResult="";private var lastPhoto=""
    private val tabs=mutableListOf<Button>()
    private val controls=mutableListOf<View>()
    private val pickModel=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};val key=session.prefs.getString("pending-model",ModelKey.CHAT.name).orEmpty();session.begin(TaskRequest(kind=TaskKind.IMPORT,modelKey=key,uri=uri.toString()))}
    }
    private val pickPhoto=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)session.importPhoto(uri)}
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){ }
    private val microphonePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(allowed)session.interruptAndListen()else session.notice("Microphone permission was declined; text chat is still available")}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        mode=savedInstanceState?.getString("mode") ?: session.prefs.getString("tab","Chat").orEmpty()
        if(mode !in listOf("Chat","Create","Edit","Animate"))mode="Chat"
        buildUi();buildPane()
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){session.state.collect{update(it)}}}
    }
    override fun onResume(){super.onResume();session.refreshResources()}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("mode",mode);super.onSaveInstanceState(outState)}
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun text(value:String,size:Float=14f,color:Int=ink)=TextView(this).apply{text=value;textSize=size;setTextColor(color);setLineSpacing(dp(2).toFloat(),1f)}
    private fun shape(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(16).toFloat();setStroke(dp(1),Color.rgb(70,57,91))}
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=Button(this).apply {
        text=label;isAllCaps=false;textSize=13f;minHeight=dp(48);minimumHeight=dp(48);minWidth=0;minimumWidth=0
        setPadding(dp(8),dp(8),dp(8),dp(8));setTextColor(if(primary)bg else ink);background=shape(if(primary)accent else panel)
        setOnClickListener{action()}
    }
    private fun row(vararg views:View)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;views.forEach{addView(it,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(dp(2),dp(4),dp(2),dp(4))})}}
    private fun field(hintText:String)=EditText(this).apply {
        hint=hintText;textSize=16f;setTextColor(ink);setHintTextColor(muted);background=shape(panel);minLines=2;maxLines=6
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(14),dp(12),dp(14),dp(12));filters=arrayOf(InputFilter.LengthFilter(8000))
    }
    private fun buildUi() {
        val outer=FrameLayout(this).apply{setBackgroundColor(bg)}
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(8))}
        outer.addView(root,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        outer.addOnLayoutChangeListener{v,_,_,_,_,_,_,_,_->val width=minOf(v.width-v.paddingLeft-v.paddingRight,dp(840));if(width>0 && root.layoutParams.width!=width)root.layoutParams=root.layoutParams.apply{this.width=width}}
        ViewCompat.setOnApplyWindowInsetsListener(outer){v,insets->val b=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);insets}
        val title=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("ROSALINA",24f));addView(text("PRIVATE  ·  ON DEVICE",11f,accent).apply{letterSpacing=.12f})}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(title,LinearLayout.LayoutParams(0,-2,1f));addView(button("Models"){modelDialog()},LinearLayout.LayoutParams(dp(82),dp(50)));addView(button("Settings"){settingsDialog()},LinearLayout.LayoutParams(dp(82),dp(50)))}
        root.addView(header)
        status=text("Ready",14f);thermal=text("",11f,muted)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.GONE}
        stop=button("Stop"){session.stop()}.apply{visibility=View.GONE}
        val stateBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=shape(panel);setPadding(dp(12),dp(8),dp(12),dp(8));addView(text("UNIFIED CANDIDATE",10f,accent));addView(status);addView(thermal);addView(progress,LinearLayout.LayoutParams(-1,dp(5)));addView(row(button("Diagnostics"){diagnosticsDialog()},stop))}
        root.addView(stateBox,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(6)})
        for(label in listOf("Chat","Create","Edit","Animate"))tabs+=button(label){mode=label;session.prefs.edit().putString("tab",mode).apply();buildPane()}
        root.addView(row(*tabs.toTypedArray()))
        content=FrameLayout(this);root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(outer)
    }
    private fun buildPane() {
        content.removeAllViews();controls.clear();preview=null;reference=null;resultLabel=null;chatList=null;chatScroll=null;streaming=null;lastResult="";lastPhoto="";rendered=emptyList()
        tabs.forEach{it.setTextColor(if(it.text==mode)bg else ink);it.background=shape(if(it.text==mode)accent else panel)}
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(8),dp(4),dp(8))}
        prompt=field(if(mode=="Chat")"Message Rosalina…"else if(mode=="Animate")"Describe the motion…"else if(mode=="Edit")"Describe how to transform your photo…"else "Describe what you want to create…").apply {
            id=1001;setText(session.prefs.getString("draft-$mode",""));addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){session.prefs.edit().putString("draft-$mode",s.toString()).apply()};override fun afterTextChanged(s:Editable?){} })
        }
        if(mode=="Chat") {
            val messages=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};chatList=messages
            val scroll=ScrollView(this).apply{isFillViewport=true;addView(messages)};chatScroll=scroll
            body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));body.addView(prompt)
            generate=button("Send",true){submit()};controls+=generate
            body.addView(row(button("Photo"){pickPhoto.launch(arrayOf("image/*"))},button("Mic"){voice()},generate))
            body.addView(text("Create, edit or animate from chat. Tap Mic to interrupt spoken replies.",11f,muted))
            content.addView(body,FrameLayout.LayoutParams(-1,-1))
        } else {
            val scroll=ScrollView(this).apply{isFillViewport=true;addView(body)};content.addView(scroll)
            body.addView(text(if(mode=="Animate")"Bring a photo to life"else if(mode=="Edit")"Transform your photo"else "Describe what you want to create",20f))
            body.addView(prompt)
            if(mode!="Create") {
                reference=ImageView(this).apply{adjustViewBounds=true;maxHeight=dp(180);scaleType=ImageView.ScaleType.FIT_CENTER}
                body.addView(reference,LinearLayout.LayoutParams(-1,dp(160)));val pick=button("Choose photo"){pickPhoto.launch(arrayOf("image/*"))};controls+=pick;body.addView(pick)
                val reuse=button("Use last generated image"){val f=File(session.state.value.result);if(f.extension=="png" && f.exists()){session.prefs.edit().putString("photo",f.path).apply();lastPhoto="";update(session.state.value)}else session.notice("Generate an image first")};controls+=reuse;body.addView(reuse)
            }
            val options=button(profileDescription()){renderSettings()};controls+=options;body.addView(options)
            generate=button(if(mode=="Animate")"Animate"else if(mode=="Edit")"Transform photo"else "Create image",true){submit()};controls+=generate;body.addView(generate)
            body.addView(text(if(mode=="Animate")"Actual local image-to-video diffusion · phone speed not yet validated"else "One local image · no cloud inference",11f,muted))
            preview=ImageView(this).apply{adjustViewBounds=true;maxHeight=dp(380);scaleType=ImageView.ScaleType.FIT_CENTER};body.addView(preview,LinearLayout.LayoutParams(-1,dp(280)))
            resultLabel=text("No generated result yet",12f,muted);body.addView(resultLabel)
            body.addView(row(button("Open"){openResult()},button("Save"){saveResult()},button("Share"){shareResult()}))
        }
        update(session.state.value)
    }
    private fun profileDescription():String {
        if(mode=="Animate")return "${session.prefs.getInt("seconds",6)} seconds · ${session.prefs.getString("aspect","256×256")} · 12 steps"
        return if(session.prefs.getBoolean("standard",false))"Standard · 512×512 · 12 steps"else "Phone Safe · 384×384 · 8 steps · candidate"
    }
    private fun ensureNotifications(){if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
    private fun submit() {
        if(session.state.value.busy)return
        val p=prompt.text.toString().trim();val kind=if(mode=="Chat")Route.kind(p)else TaskKind.valueOf(mode.uppercase())
        val photo=session.prefs.getString("photo","").orEmpty()
        if(kind in listOf(TaskKind.EDIT,TaskKind.ANIMATE) && photo.isBlank()){session.notice("Choose a photo, then send the request again");pickPhoto.launch(arrayOf("image/*"));return}
        ensureNotifications()
        val aspect=session.prefs.getString("aspect","256×256").orEmpty().split('×');val w=aspect.getOrNull(0)?.toIntOrNull() ?:256;val h=aspect.getOrNull(1)?.toIntOrNull() ?:256
        val r=TaskRequest(kind=kind,prompt=p,photo=photo,profile=if(session.prefs.getBoolean("standard",false))RenderProfile.Standard else RenderProfile.Draft,seconds=if(mode=="Chat")Route.seconds(p)else session.prefs.getInt("seconds",6),width=w,height=h,strength=session.prefs.getFloat("strength",.65f),seed=session.prefs.getLong("seed",42),backend=if(session.prefs.getBoolean("vulkan",false))"vulkan"else"cpu")
        if(session.begin(r)){if(mode=="Chat" && kind!=TaskKind.CHAT){mode=kind.name.lowercase().replaceFirstChar{it.uppercase()};session.prefs.edit().putString("tab",mode).putString("draft-$mode",p).apply();buildPane()}else if(mode=="Chat")prompt.text.clear()}
    }
    private fun voice() {
        if(session.state.value.stage.startsWith("Listening")){session.finishListening=true;return}
        ensureNotifications()
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)microphonePermission.launch(Manifest.permission.RECORD_AUDIO) else session.interruptAndListen()
    }
    private fun update(s:TaskState) {
        status.text=s.stage+if(s.voiceStage.isNotBlank())"\n"+s.voiceStage else ""
        thermal.text="Thermal: ${ThermalPolicy.label(s.thermal)} · Available RAM: ${String.format("%.1f",s.availableBytes/1e9)} GB"+if(s.busy)"\n${s.elapsedMs/1000}s elapsed${if(s.eta.isBlank())""else" · ${s.eta}"}"else""
        stop.visibility=if(s.busy)View.VISIBLE else View.GONE;stop.isEnabled=!s.stopping
        progress.visibility=if(s.busy)View.VISIBLE else View.GONE;progress.isIndeterminate=s.percent==null;if(s.percent!=null)progress.progress=s.percent
        controls.forEach{it.isEnabled=!s.busy && !s.quarantined}
        val photo=session.prefs.getString("photo","").orEmpty()
        if(photo!=lastPhoto){lastPhoto=photo;reference?.let{loadPreview(it,photo)}}
        if(s.result!=lastResult){lastResult=s.result;preview?.let{if(File(s.result).extension=="png")loadPreview(it,s.result)else it.setImageDrawable(null)};resultLabel?.text=if(s.result.isBlank())"No generated result yet"else File(s.result).name+if(s.result.endsWith(".mp4"))" · tap Open to play"else""}
        chatList?.let{list->
            val history=session.transcript()
            if(history!=rendered || streaming==null){rendered=history;list.removeAllViews();for((role,message)in history){list.addView(text(role.uppercase(),10f,accent));list.addView(text(message,16f).apply{setTextIsSelectable(true);background=shape(panel);setPadding(dp(12),dp(10),dp(12),dp(10))},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})};streaming=text("",16f).apply{setTextIsSelectable(true)};list.addView(streaming)}
            streaming?.text=if(history.lastOrNull()?.second==s.answer)""else s.answer
        }
    }
    private fun modelDialog() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
        body.addView(text("Your existing Rosalina apps and model downloads are untouched. Import downloaded files here once.",13f,muted))
        for(key in ModelKey.entries){body.addView(text(key.label,17f,accent));body.addView(text(session.models.summary(key),11f,muted));body.addView(row(button("Download"){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(key.url)))}} ,button("Import"){if(!session.state.value.busy){session.prefs.edit().putString("pending-model",key.name).apply();pickModel.launch(arrayOf("*/*"))}else session.notice("Finish or stop the current task before importing a model")}))}
        body.addView(text("Video decoder: bundled and checksum-checked. Voice imports accept the original .tar.bz2 packs.",12f,muted))
        AlertDialog.Builder(this).setTitle("Rosalina models").setView(ScrollView(this).apply{addView(body)}).setPositiveButton("Close",null).show()
    }
    private fun settingsDialog() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(6),dp(16),dp(6))}
        val system=field("System prompt").apply{setText(session.prefs.getString("system",Session.DEFAULT_SYSTEM))};body.addView(text("Chat system prompt",16f,accent));body.addView(system)
        val spoken=Switch(this).apply{text="Read text-chat replies aloud";setTextColor(ink);isChecked=session.prefs.getBoolean("spoken-replies",false)};body.addView(spoken)
        val speed=SeekBar(this).apply{max=70;progress=((session.prefs.getFloat("speed",1f)-.7f)*100).toInt()};body.addView(text("Voice speed · 0.7× to 1.4×",14f));body.addView(speed)
        val voices=arrayOf("Alloy","Aoede","Bella","Heart · Rosalina default","Jessica","Kore","Nicole","Nova","River","Sarah","Sky")
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,voices);setSelection(session.prefs.getInt("speaker",3).coerceIn(0,10))};body.addView(spinner)
        body.addView(text("Voice uses Kokoro 82M locally. Tap Mic to interrupt playback and begin a new utterance. Automatic hands-free acoustic barge-in is not claimed in this candidate.",12f,muted))
        body.addView(button("Clear conversation"){if(!session.state.value.busy)AlertDialog.Builder(this).setMessage("Clear this app's conversation? Models and other Rosalina apps will not be changed.").setNegativeButton("Keep",null).setPositiveButton("Clear"){_,_->session.clearConversation()}.show()})
        body.addView(button("Open Samsung Thermal Guardian"){val launch=packageManager.getLaunchIntentForPackage("com.samsung.android.thermalguardian") ?: packageManager.getLaunchIntentForPackage("com.android.samsung.utilityapp");if(launch!=null)startActivity(launch)else session.notice("Samsung Thermal Guardian is not installed or has no launch activity")})
        AlertDialog.Builder(this).setTitle("Rosalina settings").setView(ScrollView(this).apply{addView(body)}).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->session.prefs.edit().putString("system",system.text.toString()).putBoolean("spoken-replies",spoken.isChecked).putFloat("speed",.7f+speed.progress/100f).putInt("speaker",spinner.selectedItemPosition).apply()}.show()
    }
    private fun renderSettings() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(6),dp(16),dp(6))}
        val standard=Switch(this).apply{text="Standard · 512×512 / 12 steps";setTextColor(ink);isChecked=session.prefs.getBoolean("standard",false)}
        val vulkan=Switch(this).apply{text="Vulkan candidate · advanced testing";setTextColor(ink);isChecked=session.prefs.getBoolean("vulkan",false)}
        val duration=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("6 seconds","8 seconds","10 seconds"));setSelection(listOf(6,8,10).indexOf(session.prefs.getInt("seconds",6)).coerceAtLeast(0))}
        val aspects=arrayOf("256×256","320×192","192×320");val aspect=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,aspects);setSelection(aspects.indexOf(session.prefs.getString("aspect","256×256")).coerceAtLeast(0))}
        val strength=SeekBar(this).apply{max=80;progress=((session.prefs.getFloat("strength",.65f)-.1f)*100).toInt()}
        val seed=field("Seed").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(session.prefs.getLong("seed",42).toString())}
        if(mode=="Animate"){body.addView(duration);body.addView(aspect)}else body.addView(standard)
        if(mode=="Edit"){body.addView(text("Transformation strength · 0.1 to 0.9"));body.addView(strength)}
        body.addView(text("Seed"));body.addView(seed);body.addView(vulkan)
        body.addView(text("Phone Safe is a lighter candidate, not a Samsung benchmark claim. Standard keeps its resolution and steps. Vulkan requires actual device validation; setup failures fall back to CPU before sampling.",12f,muted))
        AlertDialog.Builder(this).setTitle("Generation settings").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Apply"){_,_->session.prefs.edit().putBoolean("standard",standard.isChecked).putBoolean("vulkan",vulkan.isChecked).putInt("seconds",listOf(6,8,10)[duration.selectedItemPosition]).putString("aspect",aspects[aspect.selectedItemPosition]).putFloat("strength",.1f+strength.progress/100f).putLong("seed",seed.text.toString().toLongOrNull() ?:42).apply();buildPane()}.show()
    }
    private fun diagnosticsDialog() {
        val info=session.diagnostics()
        AlertDialog.Builder(this).setTitle("Rosalina diagnostics").setMessage(info).setPositiveButton("Copy diagnostics"){_,_->getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rosalina diagnostics",info))}.setNegativeButton("Close",null).show()
    }
    private fun loadPreview(view:ImageView,path:String) {
        if(path.isBlank() || !File(path).isFile){view.setImageDrawable(null);return}
        val options=BitmapFactory.Options().apply{inSampleSize=2};view.setImageBitmap(BitmapFactory.decodeFile(path,options))
    }
    private fun resultFile():File?=File(session.state.value.result).takeIf{it.isFile}
    private fun mime(file:File)=if(file.extension=="mp4")"video/mp4"else"image/png"
    private fun openResult(){val file=resultFile() ?: return;val uri=FileProvider.getUriForFile(this,"$packageName.files",file);runCatching{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{session.notice("No application is available to open this result")}}
    private fun shareResult(){val file=resultFile() ?: return;val uri=FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(file)).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share Rosalina result"))}
    private fun saveResult() {
        val file=resultFile() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            var uri:Uri?=null
            try {
                val video=file.extension=="mp4";val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,file.name);put(MediaStore.MediaColumns.MIME_TYPE,mime(file));put(MediaStore.MediaColumns.RELATIVE_PATH,if(video)"Movies/Rosalina"else"Pictures/Rosalina");put(MediaStore.MediaColumns.IS_PENDING,1)}
                uri=contentResolver.insert(if(video)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Android could not create a gallery item")
                contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}} ?: error("Gallery output is unavailable")
                contentResolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null);session.notice("Saved to your gallery")
            }catch(t:Throwable){uri?.let{contentResolver.delete(it,null,null)};session.notice("Save failed: ${t.message}")}
        }
    }
}
