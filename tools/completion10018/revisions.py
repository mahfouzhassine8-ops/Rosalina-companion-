"""Apply only the exact reviewed animation-preference correction to known prior source."""
import hashlib,pathlib
P='unified/src/main/java/com/rosalina/unified/'
REVISIONS={
 P+'LiveAvatar.kt':('97eb0510daa71676766ab2168a25119dd4ac5c5881319a431d0944d3f66a036d','a215526107eb192bfb79957f4949e627c6585f04e3be617e025e4e3dd796f91c',[
 ('    private var tier=MotionTier.NORMAL','    private var motionEnabled=context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).getBoolean("live-avatar",true)\n    private var tier=if(motionEnabled)MotionTier.NORMAL else MotionTier.STATIC'),
 ('    private fun canAnimate()=active &&','    private fun canAnimate()=active && motionEnabled &&'),
 ('        tier=budget.sample(state.thermal,true,SystemClock.elapsedRealtime());updateClock()','        motionEnabled=context.getSharedPreferences("rosalina-unified",Context.MODE_PRIVATE).getBoolean("live-avatar",true)\n        tier=budget.sample(state.thermal,motionEnabled,SystemClock.elapsedRealtime());updateClock()'),
 ('SystemClock.elapsedRealtime(),active && ValueAnimator.areAnimatorsEnabled())','SystemClock.elapsedRealtime(),active && motionEnabled && ValueAnimator.areAnimatorsEnabled())')]),
 P+'LayeredAvatar.kt':('b28020f4e3cff7a0c2a18c133269e0ca8e736ca5d526b4e013f430da5fad8cc7','d66b771dfe357065b819b774525e8765ce3f82b9a1b28cfa5f28ace5d38a50a8',[
 ('val mouth=if(talking)s.mouth.bounded() else MouthPose()','val mouth=if(talking && animate)s.mouth.bounded() else MouthPose()')]),
 'unified/src/test/java/com/rosalina/unified/LayeredAvatarStateTest.kt':('b0a28915634b92889e86bef654d3e714d0ed0a47c79e7262ae15d83fff490f3d','767f38f47aca096154a0aafbc250e0c20c2b01cd76999d9882f19e3e833ef803',[
 ('\n}\n','''
    @Test fun userAnimationOffStopsSpeakingMotionWithoutChangingRuntime() {
        val s=CompanionSnapshot(phase=CompanionPhase.SPEAKING,playbackActive=true,performance=PerformanceState(gesture=Gesture.WALK),mouth=MouthPose(.7f,.2f,.3f),changedAt=1000)
        val p=RigMotion.sample(s,MotionTier.STATIC,2000,false)
        assertEquals(MouthPose(),p.mouth);assertEquals(0f,p.breath,0f);assertEquals(0f,p.hair,0f);assertEquals(0f,p.blink,0f);assertEquals(Gesture.NONE,p.gesture)
        assertTrue(s.playbackActive);assertEquals(CompanionPhase.SPEAKING,s.phase)
    }
}
''')])
}
def apply_revisions(check=False):
    changes=[]
    for name,(before,after,edits) in REVISIONS.items():
        path=pathlib.Path(name)
        if not path.is_file() or path.is_symlink():raise RuntimeError('Known prior source is required: '+name)
        data=path.read_bytes();digest=hashlib.sha256(data).hexdigest()
        if digest==after:continue
        if check or digest!=before:raise RuntimeError('Intervening source edits preserved: '+name)
        text=data.decode()
        for old,new in edits:
            if text.count(old)!=1:raise RuntimeError('Revision context mismatch: '+name)
            text=text.replace(old,new)
        result=text.encode()
        if hashlib.sha256(result).hexdigest()!=after:raise RuntimeError('Revision result mismatch: '+name)
        changes.append((path,result))
    # Validate every input and result before any mutation.
    for path,data in changes:path.write_bytes(data)
