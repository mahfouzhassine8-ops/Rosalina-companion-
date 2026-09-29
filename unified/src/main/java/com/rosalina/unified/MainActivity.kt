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
import android.text.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample

@OptIn(FlowPreview::class)
class MainActivity:AppCompatActivity() {
    private val session by lazy{Session.get(this)}
    private val drafts by lazy{DraftWriter(session.prefs)}
    private var liveMic:Button?=null
    private var liveMute:Button?=null
    private var liveEnd:Button?=null
    private var liveState:TextView?=null
    private var liveReply:TextView?=null
    private var returnToChatAfterEnd=false
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
    private var chatList:LinearLayout?=null
    private var chatScroll:ScrollView?=null
    private var streaming:TextView?=null
    private var avatar:LiveAvatarView?=null
    private var rendered:List<Pair<String,String>> = emptyList()
    private var liveText=""
    private var chatWindow=60
    private lateinit var drawer:LinearLayout
    private val railButtons=mutableMapOf<CompanionDestination,Button>()
    private val focusSections=listOf(ShellSection.COMPANION,ShellSection.SETTINGS_MODELS)
    private val focusModels=listOf(ModelKey.CHAT,ModelKey.STT)
    private val pickVoiceV3=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null && !session.state.value.busy){ensureNotifications();session.begin(TaskRequest(kind=TaskKind.IMPORT,modelKey="VOICE_V3",uri=uri.toString()))}}
    private val controls=mutableListOf<View>()
    private val pickModel=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};session.begin(TaskRequest(kind=TaskKind.IMPORT,modelKey=session.prefs.getString("pending-model",ModelKey.CHAT.name).orEmpty(),uri=uri.toString()))}}
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
    override fun onDestroy(){drafts.flush();super.onDestroy()}
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
    private fun syncMode() {
        mode=when(section){
            ShellSection.COMPANION->"Chat"
            ShellSection.PHOTO->if(photoMode==PhotoMode.CREATE)"Create" else "Edit"
            ShellSection.ANIMATE->"Animate"
            ShellSection.SETTINGS_MODELS->"Chat"
        }
    }
    private fun selectedDestination()=when {
        section==ShellSection.SETTINGS_MODELS->CompanionDestination.SETTINGS
        companionMode==CompanionMode.LIVE->CompanionDestination.LIVE_VOICE
        else->CompanionDestination.CHAT
    }
    private fun destinationGlyph(value:CompanionDestination)=when(value){CompanionDestination.CHAT->"▤";CompanionDestination.LIVE_VOICE->"◉";CompanionDestination.SETTINGS->"⚙"}
    private fun selectDestination(next:CompanionDestination) {
        closeDrawer();drafts.flush()
        section=if(next==CompanionDestination.SETTINGS)ShellSection.SETTINGS_MODELS else ShellSection.COMPANION
        if(next!=CompanionDestination.SETTINGS)companionMode=if(next==CompanionDestination.CHAT)CompanionMode.CHAT else CompanionMode.LIVE
        syncMode()
        session.prefs.edit().putString("section",section.name).putString("companion-mode",companionMode.name).putString("tab",mode).apply()
        buildPane()
        if(next==CompanionDestination.CHAT)session.prepareChat()
    }
    private fun refreshRail() {
        railButtons.forEach{(key,value)->val selected=key==selectedDestination()
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
        for(item in CompanionDestination.entries) {
            drawer.addView(button("${destinationGlyph(item)}   ${item.label}"){selectDestination(item)}.apply{contentDescription="Drawer ${item.label}"},LinearLayout.LayoutParams(-1,dp(58)).apply{bottomMargin=dp(8)})
        }
        drawer.addView(text("Tap a choice and this menu collapses.",11f,muted).apply{setPadding(dp(4),dp(18),dp(4),0)})
    }
    private fun buildUi() {
        val outer=FrameLayout(this).apply{setBackgroundColor(bg)}
        val shell=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val rail=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(4),dp(6),dp(4),dp(6));background=shape(Color.rgb(20,15,29))}
        rail.addView(button("☰"){toggleDrawer()}.apply{contentDescription="Open navigation menu"},LinearLayout.LayoutParams(-1,dp(52)))
        for(item in CompanionDestination.entries) {
            val b=button(destinationGlyph(item)){selectDestination(item)}.apply{contentDescription=item.label};railButtons[item]=b
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
            addView(stop,LinearLayout.LayoutParams(dp(64),dp(48)))
        })
        if(compact)root.addView(statusCol,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4);bottomMargin=dp(4)})
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(4)).apply{topMargin=dp(4)})
        content=FrameLayout(this);root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(outer);refreshRail()
    }
    private fun buildPane() {
        drafts.flush();avatar?.setActive(false);liveMic=null;liveMute=null;liveEnd=null;liveState=null;liveReply=null
        if(section !in focusSections)section=ShellSection.COMPANION
        content.removeAllViews();controls.clear();chatList=null;chatScroll=null;streaming=null;avatar=null;liveText="";rendered=emptyList()
        refreshRail();syncMode()
        val transitionDetail=when(section){ShellSection.COMPANION->companionMode.name;ShellSection.PHOTO->photoMode.name;ShellSection.ANIMATE->"MOTION";ShellSection.SETTINGS_MODELS->"SETTINGS"}
        session.prefs.edit().putString("last-ui-transition","${System.currentTimeMillis()} · ${section.name} / $transitionDetail").apply()
        when(section) {
            ShellSection.COMPANION->buildCompanionPane()
            ShellSection.PHOTO,ShellSection.ANIMATE->buildCompanionPane()
            ShellSection.SETTINGS_MODELS->buildSettingsModelsPane()
        }
        update(session.state.value)
    }
    private fun buildCompanionPane() {
        val stage=FrameLayout(this).apply{setPadding(dp(2),dp(6),dp(2),dp(2))}
        if(session.prefs.getBoolean("live-avatar",true)) {
            avatar=LiveAvatarView(this).apply{contentDescription="Rosalina live avatar";setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED));bind(session.state.value,session.presentation.snapshot)}
            stage.addView(avatar,FrameLayout.LayoutParams(-1,-1))
        } else stage.addView(text("Rosalina",28f,accent).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        val glass=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=shape(Color.argb(235,18,13,29));elevation=dp(8).toFloat()}
        if(companionMode==CompanionMode.CHAT) {
            glass.addView(text("Chat",20f,accent))
            val messages=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};chatList=messages
            chatScroll=ScrollView(this).apply{isFillViewport=false;isSmoothScrollingEnabled=true;addView(messages)}
            val viewport=(resources.configuration.screenHeightDp*.29f).toInt().coerceIn(140,310)
            glass.addView(chatScroll,LinearLayout.LayoutParams(-1,dp(viewport)).apply{topMargin=dp(4);bottomMargin=dp(6)})
            prompt=field("Message Rosalina…").apply{id=1001;minLines=1;maxLines=3;setText(session.prefs.getString("draft-Chat",""));addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){drafts.put("draft-Chat",s.toString())};override fun afterTextChanged(s:Editable?){} })}
            glass.addView(prompt)
            generate=button("Send",true){submit()};controls+=generate
            glass.addView(generate,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(6)})
        } else {
            liveState=text("Live Voice",21f,accent).apply{gravity=Gravity.CENTER_HORIZONTAL};glass.addView(liveState)
            liveReply=text("Start when you are ready.",13f,muted).apply{gravity=Gravity.CENTER_HORIZONTAL;maxLines=3;setPadding(0,dp(5),0,dp(8))};glass.addView(liveReply)
            liveMic=button("Start",true){voice()};glass.addView(liveMic,LinearLayout.LayoutParams(-1,dp(50)))
            val mute=button("Mute"){session.toggleMicrophoneMute()};liveMute=mute
            val end=button("End"){returnToChatAfterEnd=true;session.stop();if(!session.state.value.busy){returnToChatAfterEnd=false;selectDestination(CompanionDestination.CHAT)}};liveEnd=end
            val more=button("More"){liveOptions()}
            glass.addView(row(mute,end,more),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)})
        }
        stage.addView(ScrollView(this).apply{isFillViewport=false;isNestedScrollingEnabled=true;addView(glass)},FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(8),dp(8),dp(8),dp(8))})
        content.addView(stage,FrameLayout.LayoutParams(-1,-1))
    }
    private fun liveOptions() {
        val choices=arrayOf("Interrupt and listen","Latest conversation text")
        AlertDialog.Builder(this).setTitle("Live Voice").setItems(choices){_,which->
            if(which==0){if(session.state.value.busy)session.interruptAndListen()else voice()}
            else AlertDialog.Builder(this).setTitle("Latest conversation").setMessage(session.transcript().takeLast(4).joinToString("\n\n"){(who,words)->"$who: $words"}.ifBlank{"No conversation yet"}).setPositiveButton("Close",null).show()
        }.setNegativeButton("Close",null).show()
    }
    private fun buildSettingsModelsPane() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(10),dp(8),dp(18))}
        body.addView(text("Settings",28f));body.addView(text("Chat + Live Focus. Photo and video engines stay preserved but inactive so conversation gets the phone.",12f,muted))
        body.addView(text("APP SETTINGS",11f,accent).apply{setPadding(0,dp(18),0,dp(4))})
        body.addView(button("Chat, Live Voice & avatar preferences"){settingsDialog()})
        body.addView(button("Voice V3 · local candidate & comparison"){voiceV3Dialog()})
        body.addView(button("Diagnostics\nDevice, audio route, model and runtime details"){diagnosticsDialog()})
        body.addView(text("CHAT & LISTENING",11f,accent).apply{setPadding(0,dp(18),0,dp(4))})
        for(key in focusModels){
            val installed=session.models.path(key)!=null
            body.addView(LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=shape(panel);setPadding(dp(12),dp(10),dp(12),dp(10))
                addView(text(key.label,15f),LinearLayout.LayoutParams(0,-2,1f))
                addView(text(if(installed)"Installed" else key.approximate,11f,if(installed)Color.rgb(124,235,167) else muted))
            },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)})
        }
        body.addView(button("Models Manager\nChat and listening models"){modelDialog()},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        body.addView(text("Image/video models already on the phone are left untouched, but this build will not load or run them. Rosalina remains private and on-device unless you explicitly enable an implemented online provider.",11f,muted).apply{setPadding(0,dp(14),0,0)})
        content.addView(ScrollView(this).apply{isFillViewport=true;addView(body)},FrameLayout.LayoutParams(-1,-1))
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
        avatar?.bind(s,session.presentation.snapshot)
        thermal.change(if(s.busy)"Private · on-device · ${s.elapsedMs/1000}s elapsed" else "Private · on-device")
        stop.visibility=if(s.busy)View.VISIBLE else View.GONE;stop.isEnabled=!s.stopping
        progress.visibility=if(s.busy && s.kind !in listOf(TaskKind.CHAT,TaskKind.VOICE))View.VISIBLE else View.GONE
        if(progress.isIndeterminate!=(s.percent==null))progress.isIndeterminate=s.percent==null
        if(s.percent!=null && progress.progress!=s.percent)progress.progress=s.percent
        controls.forEach{it.isEnabled=!s.busy && !s.quarantined}
        val liveActive=s.busy && s.kind==TaskKind.VOICE
        liveMic?.apply{isEnabled=!s.stopping && !s.quarantined;visibility=if(liveActive)View.GONE else View.VISIBLE;change("Start")}
        liveMute?.apply{isEnabled=liveActive && !s.stopping;change(if(session.presentation.snapshot.muted)"Unmute" else "Mute")}
        liveEnd?.isEnabled=!s.stopping
        val presentation=session.presentation.snapshot
        liveState?.change(when{presentation.muted->"Microphone muted";!liveActive->"Live Voice";else->presentation.phase.name.lowercase().replaceFirstChar{it.uppercase()}})
        liveReply?.change(when{!liveActive->if(s.error.isBlank())"Start when you are ready." else s.stage;presentation.muted->"Your microphone is paused.";s.answer.isNotBlank()->SpeechText.visible(s.answer);else->session.transcript().lastOrNull()?.second ?:"I'm listening."})
        if(returnToChatAfterEnd && !s.busy){returnToChatAfterEnd=false;selectDestination(CompanionDestination.CHAT);return}
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
        body.addView(text("Your imported Chat and Whisper models remain in private storage.",13f,muted))
        for(key in focusModels){body.addView(text(key.label,17f,accent));body.addView(text(session.models.summary(key),11f,muted));body.addView(row(button("Download"){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(key.url)))}} ,button("Import"){if(!session.state.value.busy){session.prefs.edit().putString("pending-model",key.name).apply();pickModel.launch(arrayOf("*/*"))}else session.notice("Finish or stop the current task before importing")}))}
        body.addView(text("Speech uses the protected Android voice until a local Voice V3 candidate has passed your device acceptance. Existing media model files are preserved and inactive.",12f,muted))
        AlertDialog.Builder(this).setTitle("Rosalina models").setView(ScrollView(this).apply{addView(body)}).setPositiveButton("Close",null).show()
    }
    private fun settingsDialog() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(10))}
        fun toggle(label:String,key:String,default:Boolean)=Switch(this).apply{text=label;setTextColor(ink);isChecked=session.prefs.getBoolean(key,default);body.addView(this)}
        val spoken=toggle("Speak Chat replies","spoken-replies",false)
        val hands=toggle("Hands-free when the audio route supports it","hands-free",true)
        val live=toggle("Live coordination (off uses Classic voice)","live-voice",true)
        val animate=toggle("Animate Rosalina","live-avatar",true)
        val flirty=toggle("Optional affectionate / playful companion style","companion-flirty",false)
        body.addView(text("Companion style is non-explicit and can be turned off. Serious requests remain straightforward.",11f,muted))
        val capabilityKeys=arrayOf("adaptive","cool","maximum")
        val capability=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Adaptive phone load","Cool phone","Maximum phone"));setSelection(capabilityKeys.indexOf(session.prefs.getString("phone-capability","adaptive")).coerceAtLeast(0))}
        body.addView(text("Phone capability",14f,accent));body.addView(capability)
        val pace=SeekBar(this).apply{max=50;progress=(session.prefs.getInt("voice-pace",100)-75).coerceIn(0,50)}
        body.addView(text("Compatibility voice pace · 0.75–1.25×",14f));body.addView(pace)
        body.addView(text("The local candidate uses its native prosody. Whisper, laughter and other effects are not presented as supported controls until audibly verified.",11f,muted))
        body.addView(button("Test current compatibility voice"){if(!session.state.value.busy){ensureNotifications();session.testVoice()}})
        body.addView(row(button("Choose original portrait"){pickAvatar.launch(arrayOf("image/*"))},button("Restore bundled artwork"){session.notice(AvatarAsset.restoreBundled(this));buildPane()}))
        body.addView(text("The supplied reference is a flat sheet. This candidate's portrait has localized 2-D motion; a complete walk cycle and dimensional turn still need a layered rig or authored clips.",11f,muted))
        body.addView(button("Reset Live learning"){if(!session.state.value.busy)session.resetLiveLearning()})
        body.addView(button("Clear conversation"){if(!session.state.value.busy)AlertDialog.Builder(this).setMessage("Clear this conversation? Models are kept.").setNegativeButton("Keep",null).setPositiveButton("Clear"){_,_->session.clearConversation()}.show()})
        val system=field("System prompt").apply{setText(session.prefs.getString("system",Session.DEFAULT_SYSTEM))}
        val tokens=field("Maximum reply length").apply{inputType=InputType.TYPE_CLASS_NUMBER;setText(session.prefs.getInt("max-tokens",1024).toString())}
        body.addView(text("Advanced Chat preferences",14f,accent));body.addView(system);body.addView(tokens)
        AlertDialog.Builder(this).setTitle("Rosalina settings").setView(ScrollView(this).apply{addView(body)}).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->
            session.prefs.edit().putBoolean("spoken-replies",spoken.isChecked).putBoolean("hands-free",hands.isChecked)
                .putBoolean("live-voice",live.isChecked).putBoolean("live-avatar",animate.isChecked).putBoolean("companion-flirty",flirty.isChecked)
                .putString("phone-capability",capabilityKeys[capability.selectedItemPosition]).putInt("voice-pace",75+pace.progress)
                .putString("system",system.text.toString()).putInt("max-tokens",(tokens.text.toString().toIntOrNull() ?:1024).coerceIn(64,4096)).apply()
            buildPane()
        }.show()
    }
    private fun voiceV3Dialog() {
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(12))}
        body.addView(text("Local Voice V3 candidate",21f,accent));body.addView(text(session.voiceV3Summary(),12f,muted))
        body.addView(text("Pocket TTS is being evaluated for natural conversational speech. The proven Android voice stays primary until you approve this exact pack after a sustained phone comparison. No cloud speech or Mac is required.",13f))
        body.addView(button("Import verified candidate ZIP"){if(!session.state.value.busy)pickVoiceV3.launch(arrayOf("application/zip","application/octet-stream"))})
        val phrases=arrayOf(
            "Hi. I'm Rosalina. Let's take this one step at a time.",
            "That sounds difficult. We can slow down, and work through it together.",
            "Well, that was a pleasant surprise! Tell me what happened next.",
            "It's lovely to hear from you. What would make your evening better?"
        )
        val phrase=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,phrases)};body.addView(phrase)
        body.addView(row(button("Hear baseline"){if(!session.state.value.busy){ensureNotifications();session.testVoiceText(phrases[phrase.selectedItemPosition])}},button("Hear local candidate"){if(!session.state.value.busy){ensureNotifications();session.auditionVoiceV3(phrases[phrase.selectedItemPosition])}}))
        body.addView(text("Compare the same phrase. Then test 10–15 minutes, repeated replies, Stop and background/return. A different voice alone does not establish improved quality. Controlled whisper, sigh and laughter are still unverified.",12f,muted))
        body.addView(button("Use candidate after my phone acceptance"){
            if(!session.state.value.busy)AlertDialog.Builder(this).setTitle("Accept this voice pack?")
                .setMessage("Confirm only after hearing a quality improvement and completing sustained Live, Stop and heat tests. Android TTS remains the fallback.")
                .setNegativeButton("Not yet",null).setPositiveButton("I tested and accept"){_,_->runCatching{session.setVoiceV3Approved(true)}.onFailure{session.notice(it.message.orEmpty())}}.show()
        })
        body.addView(button("Use protected compatibility voice"){if(!session.state.value.busy)session.setVoiceV3Approved(false)})
        AlertDialog.Builder(this).setTitle("Voice comparison").setView(ScrollView(this).apply{addView(body)}).setPositiveButton("Close",null).show()
    }
    private fun diagnosticsDialog() {
        lifecycleScope.launch {
            val avatarQuality=avatar?.qualitySummary() ?:"Avatar is not mounted on this screen"
            val info=withContext(Dispatchers.IO){runCatching{session.refreshResources()};session.diagnostics(includeExits=true)+"\nAvatar renderer: $avatarQuality"}
            if(!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))AlertDialog.Builder(this@MainActivity).setTitle("Rosalina diagnostics").setMessage(info).setPositiveButton("Copy diagnostics"){_,_->getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rosalina diagnostics",info))}.setNegativeButton("Close",null).show()
        }
    }
}
