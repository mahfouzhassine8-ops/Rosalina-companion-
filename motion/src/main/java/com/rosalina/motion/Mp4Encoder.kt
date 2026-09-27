package com.rosalina.motion

import android.media.*
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Streams one RGB frame at a time into Android's AVC encoder. No duplicated/looped frames.
internal object Mp4Encoder {
    suspend fun encode(raw:File,target:File,spec:MotionSpec,progress:(Int)->Unit) {
        spec.validate()
        require(raw.length()==MotionMath.expectedBytes(spec.width,spec.height,spec.modelFrames)) { "Native video output is incomplete" }
        val info=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { c ->
            c.isEncoder && c.supportedTypes.any{it.equals("video/avc",true)} &&
                c.getCapabilitiesForType("video/avc").colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        } ?: error("This device has no compatible H.264 encoder")
        val codec=MediaCodec.createByCodecName(info.name)
        var muxer:MediaMuxer?=null;var muxStarted=false;var codecStarted=false;var success=false
        try {
            val format=MediaFormat.createVideoFormat("video/avc",spec.width,spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE,1_200_000);setInteger(MediaFormat.KEY_FRAME_RATE,spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();codecStarted=true
            val m=MediaMuxer(target.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=m
            var track=-1;var index=0;var inputDone=false;var outputDone=false
            val bufferInfo=MediaCodec.BufferInfo();val rgb=ByteArray(spec.width*spec.height*3)
            val started=SystemClock.elapsedRealtime()
            DataInputStream(BufferedInputStream(FileInputStream(raw))).use { input ->
                val header=ByteArray(20);input.readFully(header)
                val h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                require(h.int==0x31465652 && h.int==spec.width && h.int==spec.height && h.int==spec.modelFrames && h.int==spec.fps) { "Unexpected native video header" }
                while(!outputDone) {
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.elapsedRealtime()-started<180_000) { "Android video encoder timed out" }
                    if(!inputDone) {
                        val ix=codec.dequeueInputBuffer(10_000)
                        if(ix>=0) {
                            if(index<spec.exportFrames) {
                                input.readFully(rgb)
                                val image=codec.getInputImage(ix) ?: error("Video encoder did not provide YUV input planes")
                                fill(image,rgb,spec.width,spec.height)
                                codec.queueInputBuffer(ix,0,spec.width*spec.height*3/2,MotionMath.timestampUs(index,spec.fps),0)
                                ++index;progress(index*100/spec.exportFrames)
                            } else {
                                codec.queueInputBuffer(ix,0,0,MotionMath.timestampUs(index,spec.fps),MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true
                            }
                        }
                    }
                    val ox=codec.dequeueOutputBuffer(bufferInfo,10_000)
                    if(ox==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxStarted);track=m.addTrack(codec.outputFormat);m.start();muxStarted=true
                    } else if(ox>=0) {
                        val b=codec.getOutputBuffer(ox) ?: error("Missing encoded video buffer")
                        if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0) bufferInfo.size=0
                        if(bufferInfo.size>0) {
                            check(muxStarted) { "Video encoder has not published its format" }
                            b.position(bufferInfo.offset);b.limit(bufferInfo.offset+bufferInfo.size);m.writeSampleData(track,b,bufferInfo)
                        }
                        outputDone=bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        codec.releaseOutputBuffer(ox,false)
                    }
                }
            }
            m.stop();muxStarted=false;success=true
        } finally {
            if(codecStarted) runCatching{codec.stop()};codec.release()
            if(muxStarted) runCatching{muxer?.stop()};muxer?.release()
            if(!success) target.delete()
        }
        require(target.length()>1024) { "Encoded MP4 is empty" }
        val r=MediaMetadataRetriever()
        try {
            r.setDataSource(target.absolutePath)
            val ms=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("MP4 duration is missing")
            require(kotlin.math.abs(ms-spec.seconds*1000L)<=150) { "MP4 duration mismatch: ${ms}ms instead of ${spec.seconds}s" }
        } finally { r.release() }
    }
    private fun fill(image:Image,rgb:ByteArray,w:Int,h:Int) {
        require(image.planes.size==3 && image.width>=w && image.height>=h)
        fun put(plane:Image.Plane,x:Int,y:Int,b:Byte) {
            plane.buffer.put(y*plane.rowStride+x*plane.pixelStride,b)
        }
        val p=image.planes
        for(y in 0 until h) for(x in 0 until w) {
            val k=(y*w+x)*3;val r=rgb[k].toInt() and 255;val g=rgb[k+1].toInt() and 255;val b=rgb[k+2].toInt() and 255
            put(p[0],x,y,MotionMath.y(r,g,b))
            if(x%2==0 && y%2==0) {
                var sr=0;var sg=0;var sb=0
                for(dy in 0..1) for(dx in 0..1) {
                    val i=((y+dy)*w+x+dx)*3;sr+=rgb[i].toInt() and 255;sg+=rgb[i+1].toInt() and 255;sb+=rgb[i+2].toInt() and 255
                }
                put(p[1],x/2,y/2,MotionMath.u(sr/4,sg/4,sb/4));put(p[2],x/2,y/2,MotionMath.v(sr/4,sg/4,sb/4))
            }
        }
    }
}
