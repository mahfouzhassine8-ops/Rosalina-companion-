package com.rosalina.unified

import android.Manifest
import android.content.*
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.flow.sample
import java.io.File

@OptIn(FlowPreview::class)
class MainActivity:AppCompatActivity() {
    private val session by lazy{Session.get(this)}
    private val drafts by lazy{DraftWriter(session.prefs)}
    private var liveMic:Button?=null
    private var saveJob:Job?=null
    private val previewJobs=mutableMapOf<ImageView,Job>()
    private lateinit var drawerScrim:View
    private val drawerBack=object:OnBackPressedCallback(false){override fun handleOnBackPressed(){closeDrawer()}}
    private val bg=Color.rgb(13,10,19);private val panel=Color.rgb(30,24,41)
    private val ink=Color.rgb(247,241,255);private val muted=Color.rgb(179,168,194);private val accent=Color.rgb(194,166,255)
    private var section=ShellSection.COMPANION
    private var companionMode=CompanionMode.CHAT
    private var photoMode=PhotoMode.CREATE
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
    private var avatar:LiveAvatarView?=null
    private var rendered:List<Pair<String,String>> = emptyList()
    private var liveText=""
    private var chatWindow=60
    private var lastResult="";private var lastPhoto=""
    private lateinit var drawer:LinearLayout
    private val railButtons=mutableMapOf<ShellSection,Button>()
    private val focusSections=listOf(ShellSection.COMPANION,ShellSection.SETTINGS_MODELS)
    private val focusModels=listOf(ModelKey.CHAT,ModelKey.STT,ModelKey.TTS)
    private val controls=mutableListOf<View>()
    private val pickModel=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};session.begin(TaskRequest(kind=TaskKind.IMPORT,modelKey=session.prefs.getString("pending-model",ModelKey.CHAT.name).orEmpty(),uri=uri.toString()))}}
    private val pickPhoto=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)session.importPhoto(uri)}
    private val pickAvatar=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)lifecycleScope.launch {
            val message=withContext(Dispatchers.IO){runCatching{AvatarAsset.importOriginal(this@MainActivity,uri)}}
            message.onSuccess{session.notice(it);if(section==ShellSection.COMPANION)buildPane()}
                .onFailure{session.notice("Avatar import failed: ${it.message}")}
        }
    }
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    private val bluetoothPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->session.prefs.edit().putBoolean("bluetooth-permission-asked",true).apply();if(!allowed)session.notice("Nearby devices permission declined · Live Voice will use wired/USB audio or the phone speaker");continueVoiceAfterBluetooth()}
    private val microphonePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(allowed)session.interruptAndListen()else session.notice("Microphone permission declined; text chat remains available")}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy=savedInstanceState?.getString("mode") ?:session.prefs.getString("tab","Chat").orEmpty()
        section=ShellSection.parse(savedInstanceState?.getString("section") ?:session.prefs.getString("section",null),legacy)
        if(section !in focusSections)section=ShellSection.COMPANION
        companionMode=CompanionMode.parse(savedInstanceState?.getString("companion-mode") ?:session.prefs.getString("companion-mode","CHAT"))
        photoMode=PhotoMode.parse(savedInstanceState?.getString("photo-mode") ?:session.prefs.getString("photo-mode",if(legacy=="Edit")"EDIT" else "CREATE"))
        syncMode()
        buildUi();buildPane();onBackPressedDispatcher.addCallback(this,drawerBack)
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){
            launch{session.state.sample(50).collect{update(it)}}
        }}
    }
    override fun onResume(){super.onResume();avatar?.setActive(true);lifecycleScope.launch{withContext(Dispatchers.IO){runCatching{session.refreshResources()}};if(section==ShellSection.COMPANION && companionMode==CompanionMode.CHAT && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))session.prepareChat()}}
    override fun onPause(){drafts.flush();avatar?.setActive(false);super.onPause()}
    override fun onDestroy(){drafts.flush();previewJobs.values.forEach{it.cancel()};previewJobs.clear();super.onDestroy()}
    override fun onSaveInstanceState(outState:Bundle){
        drafts.flush();outState.putString("mode",mode);outState.putString("section",section.name);outState.putString("companion-mode",companionMode.name);outState.putString("photo-mode",photoMode.name)
        super.onSaveInstanceState(outState)
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun text(value:String,size:Float=14f,color:Int=ink)=TextView(this).apply{text=value;textSize=size;setTextColor(color);setLineSpacing(dp(2).toFloat(),1f)}
    private fun shape(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(16).toFloat();setStroke(dp(1),Color.rgb(70,57,91))}
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;textSize=13f;minHeight=dp(48);minimumHeight=dp(48);minWidth=0;minimumWidth=0;setPadding(dp(8),dp(8),dp(8),dp(8));setTextColor(if(primary)bg else ink);background=RippleDrawable(ColorStateList.valueOf(Color.argb(45,220,200,255)),shape(if(primary)accent else panel),null);setOnClickListener{action()}}
    private fun row(vararg views:View)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;views.forEach{addView(it,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(dp(2),dp(4),dp(2),dp(4))})}}
    private fun field(hintText:String)=EditText(this).apply{hint=hintText;textSize=16f;setTextColor(ink);setHintTextColor(muted);background=shape(panel);minLines=2;maxLines=6;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;setPadding(dp(14),dp(12),dp(14),dp(12));filters=arrayOf(InputFilter.LengthFilter(8000))}
    private fun TextView.change(value:String){if(text.toString()!=value)text=value}
    private fun backendChoice()=session.prefs.getString("render-backend",null) ?:if(session.prefs.getBoolean("vulkan",false))"vulkan" else "auto"
    private fun syncMode() {
        mode=when(section){
            ShellSection.COMPANION->"Chat"
            ShellSection.PHOTO->if(photoMode==PhotoMode.CREATE)"Create" else "Edit"
            ShellSection.ANIMATE->"Animate"
            ShellSection.SETTINGS_MODELS->"Chat"
        }
    }
    private fun sectionLabel(value:ShellSection)=when(value){
        ShellSection.COMPANION->"Companion"
        ShellSection.PHOTO->"Photo"
        ShellSection.ANIMATE->"Animate"
        ShellSection.SETTINGS_MODELS->"Settings & Models"
    }
    private fun sectionGlyph(value:ShellSection)=when(value){
        ShellSection.COMPANION->"♥"
        ShellSection.PHOTO->"▧"
        ShellSection.ANIMATE->"▶"
        ShellSection.SETTINGS_MODELS->"⚙"
    }
    private fun selectSection(next:ShellSection) {
        closeDrawer()
        if(session.state.value.busy && next!=section){session.notice("Stop the current task before switching sections");return}
        section=next;syncMode()
        session.prefs.edit().putString("section",section.name).putString("tab",mode).apply()
        buildPane()
        if(section==ShellSection.COMPANION && companionMode==CompanionMode.CHAT)session.prepareChat()
    }
    private fun refreshRail() {
        railButtons.forEach{(key,value)->
            val selected=key==section
            value.setTextColor(if(selected)bg else ink);value.background=shape(if(selected)accent else panel)
        }
    }
    private fun closeDrawer(){drawer.visibility=View.GONE;drawerScrim.visibility=View.GONE;drawerBack.isEnabled=false}
    private fun toggleDrawer(){
        if(drawer.visibility==View.VISIBLE){closeDrawer();return}
        drawer.visibility=View.VISIBLE;drawerScrim.visibility=View.VISIBLE;drawerBack.isEnabled=true
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){drawer.alpha=0f;drawer.animate().alpha(1f).setDuration(140).start()}else drawer.alpha=1f
    }
    private fun buildDrawer() {
        drawer.removeAllViews()
        drawer.addView(text("ROSALINA",24f))
        drawer.addView(text("PRIVATE  ·  ON DEVICE",10f,accent).apply{letterSpacing=.12f})
        drawer.addView(text("MENU",10f,muted).apply{setPadding(0,dp(24),0,dp(6))})
        for(item in focusSections) {
            val sub=when(item){
                ShellSection.COMPANION->"Chat & Live"
                ShellSection.PHOTO->"Create & Edit"
                ShellSection.ANIMATE->"Motion workspace"
                ShellSection.SETTINGS_MODELS->"Preferences, diagnostics & models"
            }
            drawer.addView(button("${sectionGlyph(item)}   ${sectionLabel(item)}\n$sub"){selectSection(item)},LinearLayout.LayoutParams(-1,dp(68)).apply{bottomMargin=dp(6)})
        }
        drawer.addView(text("Tap a choice and this menu collapses.",11f,muted).apply{setPadding(dp(4),dp(18),dp(4),0)})
    }
    private fun buildUi() {
        val outer=FrameLayout(this).apply{setBackgroundColor(bg)}
        val shell=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val rail=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(4),dp(6),dp(4),dp(6));background=shape(Color.rgb(20,15,29))}
        rail.addView(button("☰"){toggleDrawer()}.apply{contentDescription="Open navigation menu"},LinearLayout.LayoutParams(-1,dp(52)))
        for(item in focusSections) {
            val b=button(sectionGlyph(item)){selectSection(item)}.apply{contentDescription=sectionLabel(item)};railButtons[item]=b
            rail.addView(b,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(7)})
        }
        shell.addView(rail,LinearLayout.LayoutParams(dp(62),-1))
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(8))}
        shell.addView(root,LinearLayout.LayoutParams(0,-1,1f))
        outer.addView(shell,FrameLayout.LayoutParams(-1,-1))
        drawerScrim=View(this).apply{setBackgroundColor(Color.argb(110,0,0,0));visibility=View.GONE;contentDescription="Close navigation menu";setOnClickListener{closeDrawer()}}
        outer.addView(drawerScrim,FrameLayout.LayoutParams(-1,-1))
        drawer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(16),dp(18),dp(16),dp(18));background=shape(Color.rgb(24,18,34));elevation=dp(18).toFloat()}
        outer.addView(drawer,FrameLayout.LayoutParams(dp(270),-1,Gravity.START))
        buildDrawer()
        ViewCompat.setOnApplyWindowInsetsListener(outer){v,insets->val b=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);insets}
        val title=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("ROSALINA",22f));addView(text("PRIVATE  ·  ON DEVICE",10f,accent).apply{letterSpacing=.12f})}
        status=text("Ready",12f);thermal=text("",10f,muted)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.GONE}
        stop=button("Stop"){session.stop()}.apply{visibility=View.GONE}
        val statusCol=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;addView(status);addView(thermal)}
        val compact=resources.configuration.screenWidthDp<560
        root.addView(LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            addView(title,LinearLayout.LayoutParams(0,-2,if(compact)1f else .75f))
            if(!compact)addView(statusCol,LinearLayout.LayoutParams(0,-2,1.1f))
            addView(button("Diagnostics"){diagnosticsDialog()},LinearLayout.LayoutParams(dp(94),dp(48)))
            addView(stop,LinearLayout.LayoutParams(dp(64),dp(48)))
        })
        if(compact)root.addView(statusCol,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4);bottomMargin=dp(4)})
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(4)).apply{topMargin=dp(4)})
        content=FrameLayout(this);root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(outer);refreshRail()
    }
    private fun buildPane() {
        drafts.flush();avatar?.setActive(false);previewJobs.values.forEach{it.cancel()};previewJobs.clear();liveMic=null
        if(section !in focusSections)section=ShellSection.COMPANION
        content.removeAllViews();controls.clear();preview=null;reference=null;resultLabel=null;chatList=null;chatScroll=null;streaming=null;avatar=null;liveText="";lastResult="";lastPhoto="";rendered=emptyList()
        refreshRail();syncMode()
        val transitionDetail=when(section){ShellSection.COMPANION->companionMode.name;ShellSection.PHOTO->photoMode.name;ShellSection.ANIMATE->"MOTION";ShellSection.SETTINGS_MODELS->"SETTINGS"}
        session.prefs.edit().putString("last-ui-transition","${System.currentTimeMillis()} · ${section.name} / $transitionDetail").apply()
        when(section) {
            ShellSection.COMPANION->buildCompanionPane()
            ShellSection.PHOTO->buildPhotoPane()
            ShellSection.ANIMATE->buildAnimatePane()
            ShellSection.SETTINGS_MODELS->buildSettingsModelsPane()
        }
        update(session.state.value)
    }
    private fun buildCompanionPane() {
        val stage=FrameLayout(this).apply{setPadding(dp(2),dp(6),dp(2),dp(2))}
        if(session.prefs.getBoolean("live-avatar",true)) {
            avatar=LiveAvatarView(this).apply{contentDescription="Rosalina live avatar";setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED));bind(session.state.value)}
            stage.addView(avatar,FrameLayout.LayoutParams(-1,-1))
        } else {
            stage.addView(text("Rosalina\nLive Avatar is off in Settings.",22f,muted).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        }
        val glass=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10));background=shape(Color.argb(235,30,24,41))}
        val chatButton=button("Chat",companionMode==CompanionMode.CHAT){
            if(companionMode!=CompanionMode.CHAT){companionMode=CompanionMode.CHAT;session.prefs.edit().putString("companion-mode",companionMode.name).apply();buildPane();session.prepareChat()}
        }
        val liveButton=button("Live",companionMode==CompanionMode.LIVE){
            if(companionMode!=CompanionMode.LIVE){companionMode=CompanionMode.LIVE;session.prefs.edit().putString("companion-mode",companionMode.name).apply();buildPane()}
        }
        glass.addView(row(chatButton,liveButton))
        if(companionMode==CompanionMode.CHAT) {
            val messages=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};chatList=messages
            chatScroll=ScrollView(this).apply{isFillViewport=false;isSmoothScrollingEnabled=true;addView(messages)}
            glass.addView(chatScroll,LinearLayout.LayoutParams(-1,dp(150)).apply{topMargin=dp(4);bottomMargin=dp(6)})
            prompt=field("Message Rosalina…").apply{id=1001;minLines=1;maxLines=3;setText(session.prefs.getString("draft-Chat",""));addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){drafts.put("draft-Chat",s.toString())};override fun afterTextChanged(s:Editable?){} })}
            glass.addView(prompt)
            generate=button("Send",true){submit()};controls+=generate
            glass.addView(generate,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(6)})
        } else {
            glass.addView(text("Live with Rosalina",20f,accent).apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(0,dp(8),0,dp(2))})
            glass.addView(text("Start to listen. Hands-free interruption depends on your audio route and echo cancellation; tap Speak to interrupt manually.",11f,muted).apply{gravity=Gravity.CENTER_HORIZONTAL})
            val mic=button("◉  Start / Speak",true){voice()};liveMic=mic
            val end=button("■  End"){session.stop()}
            glass.addView(row(mic,end),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        }
        stage.addView(ScrollView(this).apply{isFillViewport=false;isNestedScrollingEnabled=true;addView(glass)},FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(8),dp(8),dp(8),dp(8))})
        content.addView(stage,FrameLayout.LayoutParams(-1,-1))
    }
    private fun buildPhotoPane() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(10),dp(8),dp(12))}
        body.addView(text("Photo",28f));body.addView(text("Create something new or edit one of your images.",12f,muted))
        val create=button("Create",photoMode==PhotoMode.CREATE){if(photoMode!=PhotoMode.CREATE){photoMode=PhotoMode.CREATE;session.prefs.edit().putString("photo-mode",photoMode.name).apply();buildPane()}}
        val edit=button("Edit",photoMode==PhotoMode.EDIT){if(photoMode!=PhotoMode.EDIT){photoMode=PhotoMode.EDIT;session.prefs.edit().putString("photo-mode",photoMode.name).apply();buildPane()}}
        body.addView(row(create,edit))
        mode=if(photoMode==PhotoMode.CREATE)"Create" else "Edit"
        val draftKey="draft-$mode"
        prompt=field(if(photoMode==PhotoMode.CREATE)"Describe what you want to create…" else "Describe how to transform your photo…").apply{id=1001;setText(session.prefs.getString(draftKey,""));addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){drafts.put(draftKey,s.toString())};override fun afterTextChanged(s:Editable?){} })}
        body.addView(prompt)
        if(photoMode==PhotoMode.EDIT) {
            reference=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;background=shape(panel)}
            body.addView(reference,LinearLayout.LayoutParams(-1,dp(190)).apply{topMargin=dp(8)})
            body.addView(row(button("Choose photo"){pickPhoto.launch(arrayOf("image/*"))},button("Use last result"){val f=File(session.state.value.result);if(f.extension=="png"&&f.exists()){session.prefs.edit().putString("photo",f.path).apply();lastPhoto="";update(session.state.value)}else session.notice("Generate an image first")}))
        }
        body.addView(button(profileDescription()){renderSettings()},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        generate=button(if(photoMode==PhotoMode.CREATE)"Create image" else "Transform photo",true){submit()};controls+=generate;body.addView(generate)
        preview=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;background=shape(panel)}
        body.addView(preview,LinearLayout.LayoutParams(-1,dp(300)).apply{topMargin=dp(10)})
        resultLabel=text("No generated result yet",12f,muted);body.addView(resultLabel)
        body.addView(row(button("Open"){openResult()},button("Save"){saveResult()},button("Share"){shareResult()}))
        content.addView(ScrollView(this).apply{isFillViewport=true;addView(body)},FrameLayout.LayoutParams(-1,-1))
    }
    private fun buildAnimatePane() {
        mode="Animate"
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(10),dp(8),dp(12))}
        body.addView(text("Animate",28f));body.addView(text("Turn an image into a living moment.",12f,muted))
        reference=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;background=shape(panel)}
        body.addView(reference,LinearLayout.LayoutParams(-1,dp(220)).apply{topMargin=dp(8)})
        body.addView(row(button("Choose image"){pickPhoto.launch(arrayOf("image/*"))},button("Use last generated image"){val f=File(session.state.value.result);if(f.extension=="png"&&f.exists()){session.prefs.edit().putString("photo",f.path).apply();lastPhoto="";update(session.state.value)}else session.notice("Generate an image first")}))
        prompt=field("Describe the motion…").apply{id=1001;setText(session.prefs.getString("draft-Animate",""));addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){drafts.put("draft-Animate",s.toString())};override fun afterTextChanged(s:Editable?){} })}
        body.addView(prompt);body.addView(button(profileDescription()){renderSettings()})
        generate=button("Render",true){submit()};controls+=generate;body.addView(generate)
        preview=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;background=shape(panel)}
        body.addView(preview,LinearLayout.LayoutParams(-1,dp(300)).apply{topMargin=dp(10)})
        resultLabel=text("No generated result yet",12f,muted);body.addView(resultLabel)
        body.addView(row(button("Open"){openResult()},button("Save MP4"){saveResult()},button("Share"){shareResult()}))
        content.addView(ScrollView(this).apply{isFillViewport=true;addView(body)},FrameLayout.LayoutParams(-1,-1))
    }
    private fun buildSettingsModelsPane() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(10),dp(8),dp(18))}
        body.addView(text("Settings & Models",28f));body.addView(text("Chat + Live Focus. Photo and video engines stay preserved but inactive so conversation gets the phone.",12f,muted))
        body.addView(text("APP SETTINGS",11f,accent).apply{setPadding(0,dp(18),0,dp(4))})
        body.addView(button("Companion · Live Voice · Live Avatar\nOpen conversation, voice and avatar preferences"){settingsDialog()})
        body.addView(button("Diagnostics\nDevice, audio route, model and runtime details"){diagnosticsDialog()})
        body.addView(text("MODELS",11f,accent).apply{setPadding(0,dp(18),0,dp(4))})
        for(key in focusModels){
            val installed=session.models.path(key)!=null
            body.addView(LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=shape(panel);setPadding(dp(12),dp(10),dp(12),dp(10))
                addView(text(key.label,15f),LinearLayout.LayoutParams(0,-2,1f))
                addView(text(if(installed)"Installed" else key.approximate,11f,if(installed)Color.rgb(124,235,167) else muted))
            },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)})
        }
        body.addView(button("Models Manager\nChat, listening and voice models"){modelDialog()},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        body.addView(text("Image/video models already on the phone are left untouched, but this build will not load or run them. Rosalina remains private and on-device unless you explicitly enable an implemented online provider.",11f,muted).apply{setPadding(0,dp(14),0,0)})
        content.addView(ScrollView(this).apply{isFillViewport=true;addView(body)},FrameLayout.LayoutParams(-1,-1))
    }
    private fun profileDescription():String {
        val profile=if(mode=="Animate")"${session.prefs.getInt("seconds",6)} seconds · ${session.prefs.getString("aspect","256×256")} · 12 steps" else if(session.prefs.getBoolean("standard",false))"Standard · 512×512 · 12 steps" else "Phone Safe · 384×384 · 8 steps"
        return "$profile\nProcessing: ${backendChoice()}"
    }
    private fun ensureNotifications(){if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
    private fun submit() {
        if(session.state.value.busy)return
        if(section==ShellSection.SETTINGS_MODELS)return
        val p=prompt.text.toString().trim();if(p.isBlank()){session.notice("Enter a message first");return}
        val kind=when(section){
            ShellSection.COMPANION->TaskKind.CHAT
            ShellSection.SETTINGS_MODELS->return
            ShellSection.PHOTO,ShellSection.ANIMATE->{session.notice("Photo and video tools are inactive in Chat + Live Focus");return}
        }
        mode="Chat"
        ensureNotifications()
        val r=TaskRequest(kind=kind,prompt=p)
        if(session.begin(r) && section==ShellSection.COMPANION){
            prompt.text.clear();drafts.flush()
            if(kind in listOf(TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE)){
                section=if(kind==TaskKind.ANIMATE)ShellSection.ANIMATE else ShellSection.PHOTO
                photoMode=if(kind==TaskKind.EDIT)PhotoMode.EDIT else PhotoMode.CREATE
                session.prefs.edit().putString("section",section.name).putString("photo-mode",photoMode.name).putString("draft-$mode",p).putString("tab",mode).apply();buildPane()
            }
        }
    }
    private fun continueVoiceAfterBluetooth() {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        else session.interruptAndListen()
    }
    private fun voice() {
        if(session.state.value.stage.startsWith("Listening")){session.finishListening=true;return}
        ensureNotifications()
        if(Build.VERSION.SDK_INT>=31 &&
            ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED &&
            !session.prefs.getBoolean("bluetooth-permission-asked",false)) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);return
        }
        continueVoiceAfterBluetooth()
    }
    private fun update(s:TaskState) {
        status.change(s.stage+if(s.voiceStage.isNotBlank())"\n${s.voiceStage}" else "")
        avatar?.bind(s)
        thermal.change(if(s.busy)"Private · on-device · ${s.elapsedMs/1000}s elapsed" else "Private · on-device")
        stop.visibility=if(s.busy)View.VISIBLE else View.GONE;stop.isEnabled=!s.stopping
        progress.visibility=if(s.busy && s.kind !in listOf(TaskKind.CHAT,TaskKind.VOICE))View.VISIBLE else View.GONE
        if(progress.isIndeterminate!=(s.percent==null))progress.isIndeterminate=s.percent==null
        if(s.percent!=null && progress.progress!=s.percent)progress.progress=s.percent
        controls.forEach{it.isEnabled=!s.busy && !s.quarantined}
        liveMic?.apply{
            isEnabled=!s.stopping && !s.quarantined
            change(when{!s.busy->"◉  Start / Speak";s.stage.startsWith("Listening")->"◉  Finish speaking";else->"◉  Interrupt / Speak"})
        }
        val photo=session.prefs.getString("photo","").orEmpty();if(photo!=lastPhoto){lastPhoto=photo;reference?.let{loadPreview(it,photo)}}
        if(s.result!=lastResult){lastResult=s.result;preview?.let{if(File(s.result).extension=="png")loadPreview(it,s.result)else it.setImageDrawable(null)};resultLabel?.text=if(s.result.isBlank())"No generated result yet" else File(s.result).name+if(s.result.endsWith(".mp4"))" · tap Open to play" else ""}
        chatList?.let{list->
            val scroll=chatScroll;val follow=scroll==null || list.height-scroll.height-scroll.scrollY<dp(72)
            val history=session.transcript();var changed=false
            if(history!==rendered || streaming==null) {
                rendered=history;list.removeAllViews()
                if(history.size>chatWindow)list.addView(button("Earlier messages"){chatWindow+=60;streaming=null;update(session.state.value)})
                for((role,message)in history.takeLast(chatWindow)) {
                    list.addView(text(role.uppercase(),10f,accent).apply{setPadding(dp(4),dp(2),0,dp(5))})
                    val visible=if(role=="Rosalina")SpeechText.visible(message)else message
                    if(visible.isNotBlank())list.addView(text(visible,16f).apply{setTextIsSelectable(true);background=shape(panel);setPadding(dp(14),dp(11),dp(14),dp(11))},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})
                }
                streaming=text("",16f).apply{setTextIsSelectable(true);setPadding(dp(4),dp(6),dp(4),dp(12))};list.addView(streaming);liveText="";changed=true
            }
            val incoming=if(history.lastOrNull()?.second==s.answer)"" else SpeechText.visible(s.answer)
            if(incoming!=liveText){if(incoming.startsWith(liveText))streaming?.append(incoming.substring(liveText.length))else streaming?.text=incoming;liveText=incoming;changed=true}
            if(changed && follow)scroll?.let{it.post{it.scrollTo(0,list.height)}}
        }
    }
    private fun modelDialog() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
        body.addView(text("This update keeps the unified app's existing model imports. Other Rosalina apps remain untouched.",13f,muted))
        for(key in focusModels){body.addView(text(key.label,17f,accent));body.addView(text(session.models.summary(key),11f,muted));body.addView(row(button("Download"){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(key.url)))}} ,button("Import"){if(!session.state.value.busy){session.prefs.edit().putString("pending-model",key.name).apply();pickModel.launch(arrayOf("*/*"))}else session.notice("Finish or stop the current task before importing")}))}
        body.addView(text("Only Chat, Listening and Voice are active in this build. Existing image/video model files are preserved and ignored.",12f,muted))
        AlertDialog.Builder(this).setTitle("Rosalina models").setView(ScrollView(this).apply{addView(body)}).setPositiveButton("Close",null).show()
    }
    private fun settingsDialog() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(6),dp(16),dp(6))}
        val system=field("System prompt").apply{setText(session.prefs.getString("system",Session.DEFAULT_SYSTEM))};body.addView(text("Chat system prompt",16f,accent));body.addView(system)
        val tokens=field("Maximum response tokens").apply{inputType=InputType.TYPE_CLASS_NUMBER;minLines=1;setText(session.prefs.getInt("max-tokens",1024).toString())};body.addView(text("Maximum response length · 64–4096 tokens",13f));body.addView(tokens)
        val spoken=Switch(this).apply{text="Read text-chat replies aloud";setTextColor(ink);isChecked=session.prefs.getBoolean("spoken-replies",false)};body.addView(spoken)
        val handsFree=Switch(this).apply{text="Hands-free interruption in Voice mode";setTextColor(ink);isChecked=session.prefs.getBoolean("hands-free",true)};body.addView(handsFree)
        val liveVoice=Switch(this).apply{text="Live conversation mode · keep local voice engines warm";setTextColor(ink);isChecked=session.prefs.getBoolean("live-voice",true)};body.addView(liveVoice)
        val liveAvatar=Switch(this).apply{text="Live avatar · show Rosalina in Chat and Voice";setTextColor(ink);isChecked=session.prefs.getBoolean("live-avatar",true)};body.addView(liveAvatar)
        body.addView(text("The old 441×640 repository JPEG is damaged and is deliberately not used. Until the exact original is selected, Rosalina uses the clean 180×261 fallback. Choose your original Rosalina image for full source fidelity; its encoded bytes are copied unchanged into private app storage and only the display decode is memory-sized.",11f,muted))
        body.addView(row(button(if(AvatarAsset.hasPrivateOriginal(this))"Replace original HD portrait" else "Choose original HD portrait"){pickAvatar.launch(arrayOf("image/*"))},button("Restore bundled portrait"){session.notice(AvatarAsset.restoreBundled(this));if(section==ShellSection.COMPANION)buildPane()}))
        body.addView(text("Localized 2-D head, hair, breathing and blink motion remains. Speech movement follows actual playback energy, not phonemes. Motion pauses in the background or when Android animations are disabled.",11f,muted))
        val liveEndpoint=SeekBar(this).apply{max=850;progress=(session.prefs.getInt("live-endpoint-ms",820)-550).coerceIn(0,850)}
        body.addView(text("Live turn timing · quicker ← pause before Rosalina answers → more patient",13f));body.addView(liveEndpoint)
        body.addView(text("Live mode keeps Qwen, Whisper and Rosalina's voice in separate local processes during the session. It listens while she speaks and supports barge-in. If free RAM is too low, use Classic Voice V2.",12f,muted))
        val adaptiveLive=Switch(this).apply{text="Adaptive Live learning · learn my conversation rhythm";setTextColor(ink);isChecked=session.prefs.getBoolean("adaptive-live-learning",true)};body.addView(adaptiveLive)
        body.addView(text(session.liveLearningSummary(),11f,muted))
        val online=Switch(this).apply{text="Allow internet enhancements · local fallback always available";setTextColor(ink);isChecked=session.prefs.getBoolean("online-enhancements",false)};body.addView(online)
        body.addView(text("This preference is saved only. No internet or Mac execution provider is implemented in this candidate; Chat and Live run locally. No API key is bundled.",11f,muted))

        body.addView(text("VOICE V2 · EXPRESSIVE ROSALINA",16f,accent))
        val styleKeys=arrayOf("adaptive","natural","warm","breathy","deep","bright","squeaky","intimate")
        val styleNames=arrayOf("Adaptive · Rosalina chooses naturally","Natural","Warm","Breathy","Low & smoky","Bright & playful","Squeaky · stylized","Intimate")
        val style=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,styleNames)
            setSelection(styleKeys.indexOf(session.prefs.getString("voice-style","adaptive")).coerceAtLeast(0))
        };body.addView(style)
        val realism=Switch(this).apply{text="Realism Guard · preserve natural voice quality";setTextColor(ink);isChecked=session.prefs.getBoolean("voice-realism",true)};body.addView(realism)
        body.addView(text("Realism Guard keeps Adaptive/Natural pitch, breathiness, rasp, pace and energy in conservative ranges. Squeaky is always an explicit stylized effect.",12f,muted))

        val expression=SeekBar(this).apply{max=100;progress=session.prefs.getInt("voice-expression",75).coerceIn(0,100)}
        body.addView(text("Expression strength · subtle → full",13f));body.addView(expression)
        val pitch=SeekBar(this).apply{max=160;progress=(80+session.prefs.getInt("voice-pitch",0)).coerceIn(0,160)}
        body.addView(text("Pitch · low ← natural → high · ±8 semitones manual range",13f));body.addView(pitch)
        val breath=SeekBar(this).apply{max=60;progress=session.prefs.getInt("voice-breath",0).coerceIn(0,60)}
        body.addView(text("Breathiness · clean → airy/breathy",13f));body.addView(breath)
        val tone=SeekBar(this).apply{max=160;progress=(80+session.prefs.getInt("voice-tone",0)).coerceIn(0,160)}
        body.addView(text("Tone · warm/dark ← neutral → bright",13f));body.addView(tone)
        val rasp=SeekBar(this).apply{max=40;progress=session.prefs.getInt("voice-rasp",0).coerceIn(0,40)}
        body.addView(text("Rasp / vocal fry · clean → textured",13f));body.addView(rasp)
        val energy=SeekBar(this).apply{max=80;progress=(40+session.prefs.getInt("voice-energy",0)).coerceIn(0,80)}
        body.addView(text("Energy · soft ← natural → energetic",13f));body.addView(energy)
        val legacyPace=(session.prefs.getFloat("speed",1f)*100).toInt()
        val pace=SeekBar(this).apply{max=60;progress=(session.prefs.getInt("voice-pace",legacyPace)-70).coerceIn(0,60)}
        body.addView(text("Pace · 0.70× to 1.30×",13f));body.addView(pace)

        val voices=arrayOf("Alloy","Aoede","Bella","Heart · Rosalina default","Jessica","Kore","Nicole","Nova","River","Sarah","Sky")
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,voices);setSelection(session.prefs.getInt("speaker",3).coerceIn(0,10))};body.addView(text("Base Rosalina voice identity",13f));body.addView(spinner)
        body.addView(text("Pitch is changed independently from pace when Android's pitch-preserving playback is available. Breath/tone/rasp are streamed locally with smoothing and clipping protection. Local voice remains available even when online enhancements are disabled or unavailable.",12f,muted))
        body.addView(text("Live Voice starts only after you tap Mic. Speaker interruption requires enabled echo cancellation; otherwise use a supported headphone route or tap Mic. Stop ends listening. Classic mode remains available above.",12f,muted))
        body.addView(button("Test Rosalina speaker"){if(!session.state.value.busy){ensureNotifications();session.testVoice()}})
        body.addView(button("Reset Live learning"){if(!session.state.value.busy)session.resetLiveLearning()})
        body.addView(button("Clear conversation"){if(!session.state.value.busy)AlertDialog.Builder(this).setMessage("Clear this app's conversation? Models and other apps will not change.").setNegativeButton("Keep",null).setPositiveButton("Clear"){_,_->session.clearConversation()}.show()})
        AlertDialog.Builder(this).setTitle("Rosalina settings").setView(ScrollView(this).apply{addView(body)}).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->
            val pacePercent=70+pace.progress
            session.prefs.edit()
                .putString("system",system.text.toString()).putBoolean("spoken-replies",spoken.isChecked).putBoolean("hands-free",handsFree.isChecked)
                .putBoolean("live-voice",liveVoice.isChecked).putBoolean("live-avatar",liveAvatar.isChecked).putInt("live-endpoint-ms",550+liveEndpoint.progress)
                .putBoolean("adaptive-live-learning",adaptiveLive.isChecked).putBoolean("online-enhancements",online.isChecked)
                .putString("voice-style",styleKeys[style.selectedItemPosition]).putBoolean("voice-realism",realism.isChecked).putInt("voice-expression",expression.progress)
                .putInt("voice-pitch",pitch.progress-80).putInt("voice-breath",breath.progress).putInt("voice-tone",tone.progress-80)
                .putInt("voice-rasp",rasp.progress).putInt("voice-energy",energy.progress-40).putInt("voice-pace",pacePercent)
                .putFloat("speed",pacePercent/100f).putInt("speaker",spinner.selectedItemPosition)
                .putInt("max-tokens",(tokens.text.toString().toIntOrNull() ?:1024).coerceIn(64,4096)).apply();buildPane()
        }.show()
    }
    private fun renderSettings() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(6),dp(16),dp(6))}
        val standard=Switch(this).apply{text="Standard · 512×512 / 12 steps";setTextColor(ink);isChecked=session.prefs.getBoolean("standard",false)}
        val choices=arrayOf("auto","vulkan","cpu")
        val backend=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Automatic · check GPU first","Vulkan candidate · CPU fallback","CPU · paced for heat"));setSelection(choices.indexOf(backendChoice()).coerceAtLeast(0))}
        val duration=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("6 seconds","8 seconds","10 seconds"));setSelection(listOf(6,8,10).indexOf(session.prefs.getInt("seconds",6)).coerceAtLeast(0))}
        val aspects=arrayOf("256×256","320×192","192×320");val aspect=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,aspects);setSelection(aspects.indexOf(session.prefs.getString("aspect","256×256")).coerceAtLeast(0))}
        val strength=SeekBar(this).apply{max=80;progress=((session.prefs.getFloat("strength",.65f)-.1f)*100).toInt()}
        val seed=field("Seed").apply{inputType=InputType.TYPE_CLASS_NUMBER;setText(session.prefs.getLong("seed",42).toString())}
        if(mode=="Animate"){body.addView(duration);body.addView(aspect)}else body.addView(standard)
        if(mode=="Edit"){body.addView(text("Transformation strength · 0.1 to 0.9"));body.addView(strength)}
        body.addView(text("Seed"));body.addView(seed);body.addView(text("Processing backend"));body.addView(backend)
        body.addView(text("Automatic checks actual GPU computation before model loading. Setup failure falls back to paced CPU. Thermal pacing slows work without changing selected resolution or steps. Severe reduces the work budget; Critical and above stop the task. Diagnostics records actual stage time and memory; GPU speed is not assumed.",12f,muted))
        AlertDialog.Builder(this).setTitle("Generation settings").setView(ScrollView(this).apply{addView(body)}).setNegativeButton("Cancel",null).setPositiveButton("Apply"){_,_->session.prefs.edit().putBoolean("standard",standard.isChecked).putString("render-backend",choices[backend.selectedItemPosition]).putInt("seconds",listOf(6,8,10)[duration.selectedItemPosition]).putString("aspect",aspects[aspect.selectedItemPosition]).putFloat("strength",.1f+strength.progress/100f).putLong("seed",seed.text.toString().toLongOrNull() ?:42).apply();buildPane()}.show()
    }
    private fun diagnosticsDialog() {
        lifecycleScope.launch {
            val info=withContext(Dispatchers.IO){runCatching{session.refreshResources()};session.diagnostics(includeExits=true)}
            if(!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))AlertDialog.Builder(this@MainActivity).setTitle("Rosalina diagnostics").setMessage(info).setPositiveButton("Copy diagnostics"){_,_->getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rosalina diagnostics",info))}.setNegativeButton("Close",null).show()
        }
    }
    private fun loadPreview(view:ImageView,path:String){
        previewJobs.remove(view)?.cancel();view.tag=path
        if(path.isBlank()){view.setImageDrawable(null);return}
        previewJobs[view]=lifecycleScope.launch {
            var decoded:Bitmap?=null;var applied=false
            try{
                withContext(Dispatchers.IO){
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0){"Photo cannot be decoded"}
                    var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
                    decoded=BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=sample})
                }
                ensureActive();if(view.tag==path){view.setImageBitmap(decoded);applied=true}
            }catch(e:CancellationException){throw e}
            catch(t:Throwable){if(view.tag==path){view.setImageDrawable(null);session.notice("Preview unavailable: ${t.message}")}}
            finally{if(!applied)decoded?.recycle()}
        }
    }
    private fun resultFile():File?=File(session.state.value.result).takeIf{it.isFile}.also{if(it==null)session.notice("No saved result is available yet")}
    private fun mime(file:File)=if(file.extension=="mp4")"video/mp4" else "image/png"
    private fun openResult(){val file=resultFile() ?:return;runCatching{val uri=FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{session.notice("No application available to open this result")}}
    private fun shareResult(){val file=resultFile() ?:return;runCatching{val uri=FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(file)).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share Rosalina result"))}.onFailure{session.notice("Sharing unavailable: ${it.message}")}}
    private fun saveResult() {
        if(saveJob?.isActive==true)return
        val file=resultFile() ?:return
        saveJob=lifecycleScope.launch(Dispatchers.IO) {
            var uri:Uri?=null
            try {
                val video=file.extension=="mp4";val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,file.name);put(MediaStore.MediaColumns.MIME_TYPE,mime(file));put(MediaStore.MediaColumns.RELATIVE_PATH,if(video)"Movies/Rosalina" else "Pictures/Rosalina");put(MediaStore.MediaColumns.IS_PENDING,1)}
                uri=contentResolver.insert(if(video)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?:error("Android could not create a gallery item")
                contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{input->val buffer=ByteArray(65536);while(true){ensureActive();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}}} ?:error("Gallery output unavailable")
                contentResolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null);session.notice("Saved to your gallery")
            }catch(t:Throwable){uri?.let{runCatching{contentResolver.delete(it,null,null)}};if(t is CancellationException)throw t;session.notice("Save failed: ${t.message}")}
        }
    }
}
