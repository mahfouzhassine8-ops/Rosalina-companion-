package com.rosalina.unified

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.*

/** Parameters are visual controls, not an expression label painted over a fixed image. */
internal data class FacePose(val leftEye:Float=1f,val rightEye:Float=1f,val browLeft:Float=0f,
    val browRight:Float=0f,val browLift:Float=0f,val smile:Float=0f,val blush:Float=0f,val head:Float=0f)
internal object FaceLibrary {
    fun pose(e:RosalinaExpression)=when(e) {
        RosalinaExpression.NEUTRAL->FacePose(.94f,.94f,smile=.1f)
        RosalinaExpression.HAPPY->FacePose(.74f,.74f,-3f,3f,-1.5f,.85f,.1f,1f)
        RosalinaExpression.SHY->FacePose(.63f,.68f,-4f,4f,1f,.35f,.55f,3f)
        RosalinaExpression.TEASING->FacePose(.6f,.95f,7f,-8f,-1f,.7f,.15f,-3f)
        RosalinaExpression.SAD->FacePose(.8f,.78f,-10f,10f,-2f,-.75f,0f,1.5f)
        RosalinaExpression.SURPRISED->FacePose(1.1f,1.1f,-4f,4f,-5f,.05f,0f,-1f)
        RosalinaExpression.THINKING->FacePose(.77f,.94f,8f,-5f,-1f,-.15f,0f,-2.5f)
        RosalinaExpression.BLUSH->FacePose(.9f,.85f,-3f,3f,0f,.5f,.9f,2f)
        RosalinaExpression.LISTENING->FacePose(1f,.98f,-2f,2f,-1f,.1f,0f,1f)
        RosalinaExpression.SPEAKING->FacePose(.93f,.94f,0f,0f,0f,.25f,0f,0f)
        RosalinaExpression.EYES_CLOSED->FacePose(.025f,.025f,0f,0f,0f,.35f,.1f,1f)
        RosalinaExpression.WINK->FacePose(.025f,1f,3f,-4f,-1f,.7f,.2f,-2f)
    }
}
internal data class RigPose(val face:FacePose,val blink:Float,val breath:Float,val sway:Float,
    val head:Float,val leftArm:Float,val hand:Float,val rightArm:Float,val hair:Float,
    val mouth:MouthPose,val gesture:Gesture,val actionTime:Float)
internal object RigMotion {
    fun sample(s:CompanionSnapshot,tier:MotionTier,now:Long,animate:Boolean=true):RigPose {
        val performance=s.performance.atPhase(s.phase);val face=FaceLibrary.pose(performance.expression)
        val t=now/1000.0;val elapsed=((now-s.changedAt).coerceAtLeast(0)/1000f)
        val talking=s.phase==CompanionPhase.SPEAKING && s.playbackActive
        val moving=animate && tier!=MotionTier.STATIC && s.phase!=CompanionPhase.INTERRUPTED
        // Nonuniform blink spacing; this never advances an engine state or randomly changes emotion.
        val cycle=(t/4.7).toInt();val within=t-cycle*4.7;val blinkStart=3.5+.35*sin(cycle*1.73)
        val blink=if(moving && within in blinkStart..blinkStart+.19)sin((within-blinkStart)/.19*PI).toFloat() else 0f
        val secondary=moving && tier.secondary
        val breath=if(moving)(sin(t*1.31)*.0028).toFloat() else 0f
        val sway=if(secondary)(sin(t*.49)*.7).toFloat() else 0f
        // A restrained gesture envelope occurs once at real playback onset, then rests.
        val envelope=if(talking && secondary && elapsed<3.6f)sin((elapsed/3.6f)*PI).toFloat() else 0f
        val amount=performance.intensity.coerceIn(0f,1f)*envelope
        val arms=when(performance.gesture){Gesture.OPEN_HAND->3.8f;Gesture.PLAYFUL->2.8f;Gesture.REASSURING->1.3f;else->.7f}
        val mouth=if(talking)s.mouth.bounded() else MouthPose()
        return RigPose(face,blink,breath,sway,face.head+if(moving)(sin(t*.73)*.45).toFloat() else 0f,
            -amount*arms,amount*2.8f,amount*1.4f,if(secondary)(sin(t*.91+.9)*.9).toFloat() else 0f,
            mouth,if(secondary && talking)performance.gesture else Gesture.NONE,elapsed)
    }
}
internal data class RigLayer(val name:String,val bitmap:Bitmap,val x:Float,val y:Float,val pivotX:Float,val pivotY:Float,
    val parent:String,val order:Int,val space:String)
internal class RigAssets(val layers:List<RigLayer>,val description:String) {
    val byName=layers.associateBy{it.name};val bytes=layers.sumOf{it.bitmap.allocationByteCount.toLong()}
    companion object {
        @Volatile private var cached:RigAssets?=null
        private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"Rosalina-artwork").apply{isDaemon=true}}
        private val main=Handler(Looper.getMainLooper())
        fun request(context:Context,view:LiveAvatarView) {
            cached?.let{view.artworkReady(it,null);return}
            val app=context.applicationContext;val weak=WeakReference(view)
            executor.execute {
                val result=runCatching{cached ?: load(app).also{cached=it}}
                main.post{weak.get()?.artworkReady(result.getOrNull(),result.exceptionOrNull()?.message)}
            }
        }
        fun load(context:Context):RigAssets {
            val manifest=context.assets.open("avatar-rig/rig.json").bufferedReader().use{JSONObject(it.readText())}
            require(manifest.getString("referenceSha256")==AvatarAsset.TEMPLATE_SHA256){"Unrecognized avatar reference"}
            val entries=manifest.getJSONArray("layers");require(entries.length() in 20..64)
            val layers=ArrayList<RigLayer>();val names=HashSet<String>()
            try {
                for(i in 0 until entries.length()){
                    val e=entries.getJSONObject(i);val name=e.getString("name");val file=e.getString("file")
                    require(names.add(name) && file.matches(Regex("[a-z_]+\\.png"))){"Invalid rig layer"}
                    val bytes=context.assets.open("avatar-rig/$file").use{it.readBytes()};require(bytes.size<=2_000_000)
                    require(hex(MessageDigest.getInstance("SHA-256").digest(bytes))==e.getString("sha256")){"Avatar layer checksum mismatch"}
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?:error("Avatar layer decode failed")
                    val b=e.getJSONArray("bounds");val p=e.getJSONArray("pivot")
                    require(bitmap.width==b.getInt(2)-b.getInt(0) && bitmap.height==b.getInt(3)-b.getInt(1))
                    layers+=RigLayer(name,bitmap,b.getDouble(0).toFloat(),b.getDouble(1).toFloat(),p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),e.getString("parent"),e.getInt("order"),e.getString("space"))
                }
                require(layers.all{it.parent.isBlank() || it.parent in names})
                return RigAssets(layers.sortedBy{it.order},manifest.getString("representation"))
            }catch(t:Throwable){layers.forEach{it.bitmap.recycle()};throw t}
        }
    }
}

/** A scene renderer with independent joints and facial layers. No drawBitmapMesh/whole-picture warp. */
internal class LayeredAvatarRenderer(private val assets:RigAssets) {
    private val image=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
    private val path=Path();private val matrices=HashMap<String,Matrix>();private val body=Matrix()
    private val blush=Paint(Paint.ANTI_ALIAS_FLAG)
    private val head=Matrix();private var frame=RigPose(FacePose(),0f,0f,0f,0f,0f,0f,0f,0f,MouthPose(),Gesture.NONE,0f)
    init{assets.layers.forEach{matrices[it.name]=Matrix()}}
    fun draw(canvas:Canvas,width:Int,height:Int,pose:RigPose){
        frame=pose
        drawScene(canvas,width.toFloat(),height.toFloat())
        val action=pose.gesture in listOf(Gesture.WALK,Gesture.TURN) && pose.actionTime<6.2f
        if(action){drawDistanceAction(canvas,width.toFloat(),height.toFloat(),pose);return}
        val visible=if(width>height*1.15f)640f else 1060f
        val scale=min(width/530f,height/visible)
        canvas.save();canvas.translate((width-594f*scale)/2f,height*.018f-54f*scale);canvas.scale(scale,scale)
        body.reset();body.setRotate(pose.sway,337f,760f);body.preScale(1f,1f+pose.breath,337f,770f)
        assets.layers.filter{it.space=="hero"}.forEach{layer->
            val transform=matrices.getValue(layer.name);transform.set(body)
            when(layer.name){
                "hair_back","hair_left_tip","hair_right_tip"->transform.preRotate(pose.hair,layer.pivotX,layer.pivotY)
                "arm_left_upper"->transform.preRotate(pose.leftArm,layer.pivotX,layer.pivotY)
                "hand_left"->{transform.preRotate(pose.leftArm,227f,362f);transform.preRotate(pose.hand,layer.pivotX,layer.pivotY)}
                "arm_right_upper","arm_right_lower"->transform.preRotate(pose.rightArm,475f,421f)
            }
            if(layer.name=="head" || layer.parent=="head"){
                transform.preRotate(pose.head,341f,334f)
                if(layer.name=="head")head.set(transform)
            }
            when(layer.name){
                "eye_left"->transform.preScale(1f,(pose.face.leftEye*(1f-pose.blink)).coerceAtLeast(.025f),layer.pivotX,layer.pivotY)
                "eye_right"->transform.preScale(1f,(pose.face.rightEye*(1f-pose.blink)).coerceAtLeast(.025f),layer.pivotX,layer.pivotY)
                "brow_left"->{transform.preTranslate(0f,pose.face.browLift);transform.preRotate(pose.face.browLeft,layer.pivotX,layer.pivotY)}
                "brow_right"->{transform.preTranslate(0f,pose.face.browLift);transform.preRotate(pose.face.browRight,layer.pivotX,layer.pivotY)}
                "hair_side_left"->transform.preRotate(pose.hair*.7f,layer.pivotX,layer.pivotY)
                "hair_side_right"->transform.preRotate(-pose.hair*.7f,layer.pivotX,layer.pivotY)
            }
            if(layer.name=="mouth_closed" || (layer.name=="eye_left" && pose.face.leftEye*(1f-pose.blink)<.12f) || (layer.name=="eye_right" && pose.face.rightEye*(1f-pose.blink)<.12f))return@forEach
            canvas.save();canvas.concat(transform);canvas.drawBitmap(layer.bitmap,layer.x,layer.y,image);canvas.restore()
        }
        drawFaceFinish(canvas,pose)
        canvas.restore()
    }
    private fun drawFaceFinish(canvas:Canvas,p:RigPose){
        canvas.save();canvas.concat(head)
        if(p.face.blush>0f){
            blush.shader=RadialGradient(277f,268f,25f,intArrayOf(Color.argb((64*p.face.blush).toInt(),241,71,114),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawOval(251f,252f,303f,284f,blush)
            blush.shader=RadialGradient(375f,258f,24f,intArrayOf(Color.argb((64*p.face.blush).toInt(),241,71,114),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawOval(351f,244f,400f,275f,blush);blush.shader=null
        }
        line.color=Color.rgb(63,27,38);line.strokeWidth=2.4f
        if(p.face.leftEye*(1f-p.blink)<.12f){path.reset();path.moveTo(247f,239f);path.quadTo(273f,251f,302f,239f);canvas.drawPath(path,line);canvas.drawLine(249f,239f,244f,236f,line)}
        if(p.face.rightEye*(1f-p.blink)<.12f){path.reset();path.moveTo(343f,224f);path.quadTo(370f,236f,396f,223f);canvas.drawPath(path,line);canvas.drawLine(394f,224f,399f,220f,line)}
        canvas.save();canvas.rotate(-9f,339f,299f)
        val m=p.mouth;val open=m.open.coerceIn(0f,1f)
        val w=18f+7f*m.wide-5f*m.round;val h=1.5f+13f*open
        val smile=p.face.smile
        path.reset();path.moveTo(339-w,299f-smile*4f)
        if(open>.025f){
            path.quadTo(339f,298f-smile*2f,339+w,299f-smile*4f)
            path.quadTo(341f,301f+h,339-w,299f-smile*4f);path.close()
            fill.color=Color.rgb(66,24,45);canvas.drawPath(path,fill)
            canvas.save();canvas.clipPath(path);fill.color=Color.rgb(200,109,133);canvas.drawOval(329f,300+h*.44f,351f,306+h,fill)
            if(open>.3f){fill.color=Color.rgb(233,200,201);canvas.drawRect(322f,296f,356f,300f,fill)};canvas.restore()
            line.color=Color.rgb(103,46,67);line.strokeWidth=1.1f;canvas.drawPath(path,line)
        }else{
            path.quadTo(339f,300f+smile*8f,339+w,299f-smile*4f)
            line.color=Color.rgb(109,59,78);line.strokeWidth=1.25f;canvas.drawPath(path,line)
        }
        canvas.restore();canvas.restore()
    }
    private fun drawScene(c:Canvas,w:Float,h:Float){
        fill.shader=LinearGradient(0f,0f,w,h,intArrayOf(Color.rgb(8,11,24),Color.rgb(20,12,36),Color.rgb(8,9,20)),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,fill)
        fill.shader=RadialGradient(w*.88f,h*.28f,max(w,h)*.48f,intArrayOf(Color.argb(92,134,61,197),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,fill)
        fill.shader=RadialGradient(w*.15f,h*.4f,max(w,h)*.4f,intArrayOf(Color.argb(55,238,153,113),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,fill)
        fill.shader=null;line.strokeWidth=max(1f,w/650f);line.color=Color.argb(47,160,94,230)
        c.drawLine(w*.12f,h*.04f,w*.12f,h*.78f,line);c.drawLine(w*.86f,h*.04f,w*.86f,h*.78f,line)
        line.color=Color.argb(31,87,126,208);c.drawLine(w*.12f,h*.3f,w*.86f,h*.3f,line);c.drawLine(w*.12f,h*.6f,w*.86f,h*.6f,line)
    }
    private fun drawDistanceAction(c:Canvas,w:Float,h:Float,p:RigPose){
        val t=p.actionTime;val scale=min(w/240f,h/370f).coerceAtMost(3.4f)
        val turn=p.gesture==Gesture.TURN
        val progress=(t/6.2f).coerceIn(0f,1f)
        val x=w*.5f+if(turn)0f else sin(progress*PI).toFloat()*w*.09f
        c.save();c.translate(x,h*.81f);c.scale(scale,scale);c.translate(-876f,-846f)
        if(turn && progress in .35f.. .78f){
            c.translate(876f-973f,0f)
            val visibility=(abs(cos(progress*PI*2)).toFloat()).coerceAtLeast(.12f)
            c.scale(visibility,1f,973f,717f)
            for(l in assets.layers.filter{it.space=="turn"}){c.save();if(l.name=="turn_hair")c.rotate(p.hair,974f,637f);c.drawBitmap(l.bitmap,l.x,l.y,image);c.restore()}
        }else{
            val walk=if(turn)0f else sin(t*5.8f)*8f
            for(l in assets.layers.filter{it.space=="walk"}){
                c.save()
                // Base body excludes animated lower legs so stride never creates duplicate limbs.
                if(l.name=="walk_base"){c.rotate(walk*.05f,876f,717f)}
                else c.rotate(if(l.name=="walk_leg_left")walk else -walk,l.pivotX,l.pivotY)
                c.drawBitmap(l.bitmap,l.x,l.y,image);c.restore()
            }
        }
        c.restore()
    }
}
