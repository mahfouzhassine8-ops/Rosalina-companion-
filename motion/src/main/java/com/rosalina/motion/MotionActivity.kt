package com.rosalina.motion

import android.Manifest
import android.content.*
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.io.File

class MotionActivity:AppCompatActivity() {
    private val bg=Color.rgb(11,9,17);private val panel=Color.rgb(27,21,39)
    private val accent=Color.rgb(194,163,255);private val ink=Color.rgb(244,239,255);private val muted=Color.rgb(180,169,200)
    private lateinit var status:TextView;private lateinit var elapsed:TextView;private lateinit var thermal:TextView;private lateinit var summary:TextView
    private lateinit var progress:ProgressBar;private lateinit var photo:ImageView;private lateinit var prompt:EditText
    private lateinit var video:VideoView;private lateinit var generate:MaterialButton;private lateinit var details:MaterialButton
    private lateinit var attach:MaterialButton;private lateinit var models:MaterialButton;private lateinit var controls:MaterialButton;private lateinit var systemTools:MaterialButton
    private lateinit var save:MaterialButton;private lateinit var share:MaterialButton;private lateinit var play:MaterialButton
    private lateinit var recent:LinearLayout
    private val durations=mutableListOf<MaterialButton>()
    private var seconds=6;private var shape=0;private var steps=12;private var seed=42L
    private var lastPhoto="";private var lastResult="";private var exportJob:Job?=null
    private val form get()=getSharedPreferences("motion-form",MODE_PRIVATE)
    private var selectedPart=ModelPart.VIDEO
    private val pickModel=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{u->MotionSession.importModel(u,selectedPart)}}
    private val pickPhoto=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{u->MotionSession.selectPhoto(u)}}
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);MotionSession.init(applicationContext)
        selectedPart=runCatching { ModelPart.valueOf(savedInstanceState?.getString("selectedPart") ?: form.getString("selectedPart",ModelPart.VIDEO.name).orEmpty()) }.getOrDefault(ModelPart.VIDEO)
        seconds=(savedInstanceState?.getInt("seconds")?:form.getInt("seconds",6)).takeIf{it in listOf(6,8,10)}?:6
        shape=(savedInstanceState?.getInt("shape")?:form.getInt("shape",0)).coerceIn(0,2)
        steps=(savedInstanceState?.getInt("steps")?:form.getInt("steps",12)).coerceIn(8,30)
        seed=savedInstanceState?.getLong("seed")?:form.getLong("seed",42)
        buildUi();prompt.setText(savedInstanceState?.getString("prompt")?:form.getString("prompt",""));updateSummary();refreshHistory()
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){
            launch{MotionSession.state.collect{update(it)}}
            launch{while(isActive){val s=MotionSession.state.value
                elapsed.text=if(s.busy&&s.started>0){
                    if(s.stopping)"Stopping renderer…"
                    else {
                        val now=SystemClock.elapsedRealtime()
                        val spent=maxOf(0L,(now-s.started)/1000)
                        val left=if(s.expectedFinish>now)(s.expectedFinish-now)/1000 else null
                        buildString{
                            s.progress?.let{append("$it% · ")}
                            append(MotionProgressMath.formatDuration(spent)).append(" elapsed")
                            if(s.work=="render"){
                                if(left!=null)append(" · ~").append(MotionProgressMath.formatDuration(left)).append(" remaining")
                                else append(" · ETA calibrating…")
                            }
                        }
                    }
                }else "On-device CPU · experimental draft"
                delay(1000)
            }}
        }}
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun label(s:String,size:Float=14f,color:Int=ink)=TextView(this).apply{text=s;textSize=size;setTextColor(color)}
    private fun background()=GradientDrawable().apply{setColor(panel);cornerRadius=dp(18).toFloat()}
    private fun button(s:String,primary:Boolean=false,action:()->Unit)=MaterialButton(this).apply{
        text=s;isAllCaps=false;textSize=14f;minHeight=dp(48);minimumHeight=dp(48);cornerRadius=dp(16)
        insetTop=dp(3);insetBottom=dp(3);setTextColor(if(primary)bg else ink)
        backgroundTintList=ColorStateList.valueOf(if(primary)accent else panel)
        strokeColor=ColorStateList.valueOf(Color.rgb(74,57,98));strokeWidth=if(primary)0 else dp(1)
        setOnClickListener{action()}
    }
    private fun row(vararg views:View)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL
        views.forEach{addView(it,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{marginEnd=dp(4)})}}
    private fun gap(h:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))}
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun buildUi(){
        val outer=FrameLayout(this).apply{setBackgroundColor(bg)}
        val root=column().apply{setPadding(dp(14),dp(8),dp(14),dp(10))}
        outer.addView(root,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        outer.addOnLayoutChangeListener{v,_,_,_,_,_,_,_,_->val w=minOf(v.width-v.paddingLeft-v.paddingRight,dp(840));if(w>0&&root.layoutParams.width!=w)root.layoutParams=root.layoutParams.apply{width=w}}
        ViewCompat.setOnApplyWindowInsetsListener(outer){v,i->val a=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());v.setPadding(a.left,a.top,a.right,a.bottom);i}
        val title=column().apply{addView(label("ROSALINA",25f).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)});addView(label("PRIVATE  ·  MOTION LAB",11f,accent).apply{letterSpacing=.10f})}
        models=button("Models"){modelDialog()}
        root.addView(LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            addView(title,LinearLayout.LayoutParams(0,-2,1f));addView(models,LinearLayout.LayoutParams(dp(100),dp(54)))})
        root.addView(gap(10))
        status=label("Preparing…",14f).apply{maxLines=4}
        elapsed=label("",12f,muted)
        thermal=label("",12f,muted).apply{visibility=View.GONE}
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progressTintList=ColorStateList.valueOf(accent);visibility=View.GONE}
        details=button("Copy / view details"){detailsDialog()}.apply{visibility=View.GONE}
        root.addView(column().apply{
            background=background();setPadding(dp(14),dp(10),dp(14),dp(8))
            addView(progress,LinearLayout.LayoutParams(-1,dp(6)))
            addView(gap(6));addView(elapsed);addView(gap(4));addView(status);addView(gap(3));addView(thermal);addView(details)
        })
        root.addView(gap(8))
        val body=column();val scroll=ScrollView(this).apply{isFillViewport=true;addView(body)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(label("Bring your photo to life",22f));body.addView(gap(5))
        body.addView(label("Your photo + your motion description → a local MP4.",14f,muted));body.addView(gap(10))
        photo=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;background=background();contentDescription="Your selected gallery photo";visibility=View.GONE}
        body.addView(photo,LinearLayout.LayoutParams(-1,dp(210)))
        attach=button("1 · Choose a photo from Gallery"){pickPhoto.launch(arrayOf("image/*"))};body.addView(attach)
        body.addView(gap(8));body.addView(label("2 · Describe what should move",15f))
        prompt=EditText(this).apply{hint="Slow camera push-in; the flowers sway gently in a light breeze…";textSize=16f;setTextColor(ink);setHintTextColor(muted)
            minLines=3;maxLines=7;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters=arrayOf(InputFilter.LengthFilter(3000));background=background();setPadding(dp(14),dp(10),dp(14),dp(10))}
        body.addView(gap(6));body.addView(prompt);body.addView(gap(10));body.addView(label("3 · Choose clip length",15f))
        listOf(6,8,10).forEach{n->durations+=button("${n}s"){seconds=n;store();updateSummary()}}
        body.addView(row(*durations.toTypedArray()))
        controls=button("Framing, steps & seed"){controlsDialog()};body.addView(controls)
        systemTools=button("Samsung thermal & background"){systemToolsDialog()};body.addView(systemTools)
        summary=label("",13f,accent);body.addView(summary);body.addView(gap(6))
        generate=button("Generate 6-second video",true){
            if(MotionSession.state.value.busy)MotionSession.cancel() else startRender()
        };body.addView(generate,LinearLayout.LayoutParams(-1,dp(60)))
        body.addView(label("First test: low-resolution, 8 fps draft—not HD or smooth 24 fps. CPU video can take a long time. Motion and faces are not guaranteed to match instructions exactly. No audio is generated.",12f,muted).apply{setPadding(dp(2),dp(8),dp(2),dp(14))})
        body.addView(label("Your video",18f));body.addView(gap(6))
        video=VideoView(this).apply{visibility=View.GONE;contentDescription="Generated video preview";setOnPreparedListener{it.isLooping=false;seekTo(1)}
            setOnErrorListener{_,what,extra->MotionSession.notice("Preview error $what/$extra. You can still save or share the MP4.");true}}
        body.addView(video,LinearLayout.LayoutParams(-1,dp(240)))
        play=button("Play clip"){if(video.isPlaying){video.pause();play.text="Play clip"}else{video.start();play.text="Pause"}}
        video.setOnCompletionListener{play.text="Play clip"}
        save=button("Save to gallery"){saveVideo()};share=button("Share MP4"){shareVideo()}
        body.addView(play);body.addView(row(save,share));body.addView(gap(10));body.addView(label("Recent clips",14f,muted))
        recent=column();body.addView(recent);body.addView(gap(16))
        setContentView(outer);ViewCompat.requestApplyInsets(outer)
    }
    private fun spec():MotionSpec {val dims=listOf(256 to 256,320 to 192,192 to 320)[shape.coerceIn(0,2)];return MotionSpec(seconds,dims.first,dims.second,steps,seed)}
    private fun updateSummary(){
        if(!::summary.isInitialized)return
        val s=spec();summary.text="${s.width} × ${s.height} · ${s.exportFrames} output frames · 8 fps · $steps steps"
        durations.forEachIndexed{i,b->val selected=listOf(6,8,10)[i]==seconds;b.backgroundTintList=ColorStateList.valueOf(if(selected)accent else panel);b.setTextColor(if(selected)bg else ink)}
        if(!MotionSession.state.value.busy)generate.text="Generate $seconds-second video"
    }
    private fun update(s:MotionState){
        status.text=s.status;progress.visibility=if(s.busy)View.VISIBLE else View.GONE
        progress.isIndeterminate=s.progress==null;s.progress?.let{progress.progress=it}
        thermal.text=s.thermal;thermal.visibility=if(s.thermal.isBlank())View.GONE else View.VISIBLE
        details.visibility=if(s.details.isNotEmpty())View.VISIBLE else View.GONE
        listOf(attach,models,controls,systemTools,prompt).forEach{it.isEnabled=!s.busy};durations.forEach{it.isEnabled=!s.busy}
        generate.text=when{ s.stopping->"Stopping…";s.busy->"Stop";else->"Generate $seconds-second video" }
        generate.isEnabled=!s.stopping
        save.isEnabled=s.result.isNotEmpty()&&!s.busy&&exportJob?.isActive!=true;share.isEnabled=s.result.isNotEmpty()&&!s.busy;play.isEnabled=s.result.isNotEmpty()&&!s.busy
        if(s.photo!=lastPhoto){lastPhoto=s.photo;photo.setImageURI(if(s.photo.isEmpty())null else Uri.fromFile(File(s.photo)));photo.visibility=if(s.photo.isEmpty())View.GONE else View.VISIBLE;attach.text="Choose / change gallery photo"}
        if(s.result!=lastResult){lastResult=s.result;video.stopPlayback();video.visibility=if(s.result.isEmpty())View.GONE else View.VISIBLE
            if(s.result.isNotEmpty()){video.setVideoPath(s.result);video.seekTo(1)};play.text="Play clip";refreshHistory()}
    }
    private fun startRender(){
        store();val s=MotionSession.state.value
        if(s.videoModel.isBlank()||s.textModel.isBlank()){modelDialog();return}
        if(s.photo.isBlank()){pickPhoto.launch(arrayOf("image/*"));return}
        if(prompt.text.toString().isBlank()){prompt.error="Describe the motion";return}
        if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        video.pause();play.text="Play clip"
        MotionSession.requestRender(prompt.text.toString(),spec())
    }
    private fun modelDialog(){
        val body=column().apply{setPadding(dp(20),dp(8),dp(20),dp(8))}
        body.addView(label("Two video-specific downloads are required. Qwen and the SD 1.5 image model cannot generate these clips. The small frame decoder is already included.",14f,muted))
        var dialog:AlertDialog?=null
        ModelPart.entries.forEach{part->
            val ready=if(part==ModelPart.VIDEO)MotionSession.state.value.videoModel.isNotEmpty() else MotionSession.state.value.textModel.isNotEmpty()
            body.addView(gap(14));body.addView(label(part.label,16f));body.addView(label(if(ready)"VERIFIED · imported" else "Not imported",12f,if(ready)accent else muted))
            body.addView(label(part.fileName,12f,muted))
            body.addView(row(button("Get file"){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(part.url)))}.onFailure{MotionSession.notice("No browser is available")}},
                button("Import file"){selectedPart=part;form.edit().putString("selectedPart",part.name).apply();dialog?.dismiss();pickModel.launch(arrayOf("*/*"))}))
        }
        body.addView(gap(8));body.addView(label("Keep the original Rosalina and Image Lab installed, but close them before rendering so their models do not occupy RAM.",12f,muted))
        dialog=AlertDialog.Builder(this).setTitle("Motion models").setView(ScrollView(this).apply{addView(body)}).setPositiveButton("Done",null).create();dialog.show()
    }
    private fun systemToolsDialog(){
        val body=column().apply{setPadding(dp(20),dp(8),dp(20),0)}
        val thermalInstalled=runCatching{
            packageManager.getLaunchIntentForPackage("com.samsung.android.thermalguardian")!=null
        }.getOrDefault(false)
        body.addView(label(
            if(thermalInstalled)
                "Samsung Thermal Guardian is installed. Motion Lab cannot silently change Samsung's thermal threshold, but it cooperates with Android/Samsung thermal throttling and stops at severe heat."
            else
                "Thermal Guardian is optional. Motion Lab already reads Android thermal status and protects the phone; Samsung's app can separately tune the system threshold.",
            13f,muted
        ))
        body.addView(gap(8))
        val thermalButton=button(if(thermalInstalled)"Open Thermal Guardian" else "Get Thermal Guardian"){
            if(thermalInstalled){
                packageManager.getLaunchIntentForPackage("com.samsung.android.thermalguardian")?.let{startActivity(it)}
            }else{
                runCatching{
                    startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://galaxystore.samsung.com/detail/com.samsung.android.thermalguardian")))
                }.onFailure{MotionSession.notice("Could not open Galaxy Store")}
            }
        }
        body.addView(thermalButton)
        body.addView(gap(8))
        body.addView(label(
            "Background rendering uses an ongoing foreground-service notification and a partial wake lock. For the most reliable long render, set Motion Lab to Unrestricted battery use in Android/Samsung app settings.",
            13f,muted
        ))
        body.addView(button("Open Motion Lab app settings"){
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        })
        AlertDialog.Builder(this).setTitle("Thermal & background").setView(body).setPositiveButton("Done",null).show()
    }

    private fun controlsDialog(){
        val body=column().apply{setPadding(dp(20),dp(8),dp(20),0)}
        body.addView(label("The whole photo is fitted inside the selected shape. Extra borders may be reinterpreted by the model.",13f,muted))
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MotionActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Square · 256 × 256","Landscape · 320 × 192","Portrait · 192 × 320"));setSelection(shape)};body.addView(spinner)
        val stepsField=EditText(this).apply{hint="Sampling steps: 8–30";inputType=InputType.TYPE_CLASS_NUMBER;setText(steps.toString())};body.addView(stepsField)
        val seedField=EditText(this).apply{hint="Seed";inputType=InputType.TYPE_CLASS_NUMBER;setText(seed.toString())};body.addView(seedField)
        AlertDialog.Builder(this).setTitle("Draft video controls").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->shape=spinner.selectedItemPosition;steps=stepsField.text.toString().toIntOrNull()?.coerceIn(8,30)?:12;seed=seedField.text.toString().toLongOrNull()?:42;store();updateSummary()}.show()
    }
    private fun detailsDialog(){val text=MotionSession.state.value.details
        AlertDialog.Builder(this).setTitle("Motion diagnostics").setMessage(text).setPositiveButton("Close",null).setNeutralButton("Copy"){_,_->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rosalina Motion diagnostics",text));Toast.makeText(this,"Details copied",Toast.LENGTH_SHORT).show()
        }.show()
    }
    private fun refreshHistory(){recent.removeAllViews();MotionSession.history().forEach{f->recent.addView(button(f.nameWithoutExtension){MotionSession.openResult(f)})}}
    private fun shareVideo(){val f=File(MotionSession.state.value.result);if(!f.isFile)return
        val u=FileProvider.getUriForFile(this,"$packageName.files",f)
        val send=Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("Video",u)}
        runCatching{startActivity(Intent.createChooser(send,"Share your clip"))}.onFailure{MotionSession.notice(MotionMath.error(it))}
    }
    private fun saveVideo(){if(exportJob?.isActive==true)return;val f=File(MotionSession.state.value.result);if(!f.isFile)return;save.isEnabled=false
        exportJob=lifecycleScope.launch{
            try{withContext(Dispatchers.IO){
                val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,f.name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Rosalina");put(MediaStore.Video.Media.IS_PENDING,1)}
                val u=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:error("Gallery could not create a video entry")
                try{contentResolver.openOutputStream(u)?.use{o->f.inputStream().use{it.copyTo(o)}}?:error("Gallery could not open the video")
                    contentResolver.update(u,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)
                }catch(e:Exception){contentResolver.delete(u,null,null);throw e}
            };MotionSession.notice("Saved in Gallery · Movies/Rosalina")}
            catch(e:CancellationException){throw e}
            catch(e:Exception){MotionSession.fail("SAVE VIDEO",e)}finally{save.isEnabled=!MotionSession.state.value.busy}
        }
    }
    private fun store(){if(::prompt.isInitialized)form.edit().putString("prompt",prompt.text.toString()).putString("selectedPart",selectedPart.name).putInt("seconds",seconds).putInt("shape",shape).putInt("steps",steps).putLong("seed",seed).apply()}
    override fun onSaveInstanceState(out:Bundle){out.putString("prompt",prompt.text.toString());out.putString("selectedPart",selectedPart.name);out.putInt("seconds",seconds);out.putInt("shape",shape);out.putInt("steps",steps);out.putLong("seed",seed);super.onSaveInstanceState(out)}
    override fun onStop(){store();if(::video.isInitialized)video.pause();if(::play.isInitialized)play.text="Play clip";super.onStop()}
    override fun onDestroy(){if(::video.isInitialized)video.stopPlayback();super.onDestroy()}
}
