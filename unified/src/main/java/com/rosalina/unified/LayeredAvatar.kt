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

/** Native 2-D cutout animation of the user-selected design; no 3-D or phoneme claim. */
internal class PortraitAvatarRenderer(val bitmap:Bitmap) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val skin=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
    private val path=Path()
    private fun polygon(vararg points:Float)=Path().apply{moveTo(points[0],points[1]);for(i in 2 until points.size step 2)lineTo(points[i],points[i+1]);close()}
    private val head=polygon(373f,0f,645f,0f,646f,115f,614f,213f,581f,255f,574f,291f,470f,291f,463f,252f,427f,206f,390f,115f)
    private val leftArm=polygon(345f,338f,381f,350f,343f,457f,316f,554f,274f,660f,248f,734f,234f,789f,215f,843f,183f,841f,182f,769f,208f,704f,240f,620f,279f,520f,300f,420f)
    private val rightArm=polygon(620f,338f,657f,334f,695f,445f,739f,556f,784f,664f,811f,728f,848f,787f,860f,841f,826f,855f,798f,817f,779f,763f,742f,704f,700f,616f,668f,531f,637f,449f)
    private val hairLeft=polygon(311f,298f,360f,284f,349f,380f,310f,454f,292f,553f,257f,601f,222f,564f,213f,493f,254f,397f)
    private val hairRight=polygon(653f,285f,683f,295f,704f,363f,747f,462f,782f,540f,782f,604f,747f,611f,714f,567f,711f,493f,679f,420f)
    private fun inset(p:Path,x:Float,y:Float)=Path(p).apply{transform(Matrix().apply{setScale(.98f,.98f,x,y)})}
    // Overlap source pixels at each joint to avoid antialiased clip cracks at rest.
    private val cuts=Path().apply{addPath(inset(head,520f,150f));addPath(inset(leftArm,285f,585f));addPath(inset(rightArm,743f,589f));addPath(inset(hairLeft,292f,445f));addPath(inset(hairRight,716f,445f))}
    private fun part(c:Canvas,clip:Path,rotation:Float,x:Float,y:Float){c.save();c.rotate(rotation,x,y);c.clipPath(clip);c.drawBitmap(bitmap,0f,0f,paint);c.restore()}
    fun draw(c:Canvas,width:Int,height:Int,p:RigPose){
        skin.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),Color.rgb(13,15,29),Color.rgb(33,21,45),Shader.TileMode.CLAMP)
        c.drawPaint(skin);skin.shader=null
        val scale=min(width/1025f,height/1535f)*.97f
        c.save();c.translate((width-1025f*scale)/2f,(height-1535f*scale)/2f);c.scale(scale,scale)
        c.rotate(p.sway*.65f,520f,960f);c.scale(1f,1f+p.breath,520f,950f)
        c.save();c.clipOutPath(cuts);c.drawBitmap(bitmap,0f,0f,paint);c.restore()
        // Keep photograph-derived joint travel small so seams stay restrained.
        part(c,hairLeft,p.hair*.32f,352f,300f);part(c,hairRight,-p.hair*.32f,666f,300f)
        part(c,leftArm,p.leftArm*.18f,349f,349f);part(c,rightArm,p.rightArm*.25f,646f,350f)
        c.save();c.rotate(p.head*.28f,520f,276f);c.clipPath(head);c.drawBitmap(bitmap,0f,0f,paint)
        eye(c,480f,158f,(p.face.leftEye*(1f-p.blink)).coerceIn(0f,1f))
        eye(c,555f,158f,(p.face.rightEye*(1f-p.blink)).coerceIn(0f,1f))
        if(p.face.blush>.05f){
            skin.shader=RadialGradient(469f,189f,21f,intArrayOf(Color.argb((38*p.face.blush).toInt(),235,94,117),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            c.drawOval(446f,175f,492f,203f,skin)
            skin.shader=RadialGradient(571f,189f,21f,intArrayOf(Color.argb((38*p.face.blush).toInt(),235,94,117),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            c.drawOval(548f,175f,594f,203f,skin);skin.shader=null
        }
        val m=p.mouth.bounded()
        if(m.open>.02f){
            // Replace the source smile only during real playback articulation.
            skin.shader=RadialGradient(520f,206f,23f,intArrayOf(Color.rgb(251,224,205),Color.rgb(250,222,203)),null,Shader.TileMode.CLAMP)
            c.drawOval(500f,198f,540f,218f,skin);skin.shader=null
            val w=10f+7f*m.wide-4f*m.round;val h=2f+14f*m.open
            skin.color=Color.rgb(84,36,48);c.drawOval(520f-w,204f-h*.3f,520f+w,206f+h,skin)
            skin.color=Color.rgb(202,113,133);c.drawOval(520f-w*.64f,206f+h*.48f,520f+w*.64f,206f+h*.88f,skin)
            if(m.open>.45f){skin.color=Color.rgb(247,231,220);c.drawRoundRect(521f-w*.7f,205f-h*.23f,519f+w*.7f,208f,2f,2f,skin)}
        }
        c.restore();c.restore()
    }
    private fun eye(c:Canvas,x:Float,y:Float,openness:Float){
        val closed=1f-openness
        if(closed<.12f)return
        skin.shader=LinearGradient(x,y-15f,x,y+20f,bitmap.getPixel(520,145),bitmap.getPixel(x.toInt(),180),Shader.TileMode.CLAMP)
        if(openness<.16f){
            c.drawOval(x-25f,y-16f,x+25f,y+19f,skin);skin.shader=null
            ink.color=Color.rgb(69,36,37);ink.strokeWidth=2.8f
            path.reset();path.moveTo(x-20f,y);path.quadTo(x,y+7f,x+20f,y-1f);c.drawPath(path,ink)
        }else{
            // Upper eyelid progressively occludes the source eye; preserve its iris pixels.
            c.save();c.clipRect(x-25f,y-17f,x+25f,y-14f+closed*32f)
            c.drawOval(x-25f,y-17f,x+25f,y+20f,skin);c.restore();skin.shader=null
        }
    }
    companion object {
        private const val SHA="a028f5c39944d3df164e28f039539b3021225269d07451cabc2f0c03bef2f013"
        fun load(context:Context):PortraitAvatarRenderer {
            val encoded=context.assets.open("avatar-portrait/character.b64").bufferedReader().use{it.readText()}
            val bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)
            require(hex(MessageDigest.getInstance("SHA-256").digest(bytes))==SHA){"Portrait checksum mismatch"}
            val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?:error("Portrait cannot decode")
            require(image.width==1025 && image.height==1535){"Portrait geometry changed"}
            return PortraitAvatarRenderer(image)
        }
    }
}
