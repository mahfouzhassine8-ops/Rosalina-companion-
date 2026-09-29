# Animates 1.5.2 reference for shaping Rosalina 10018

Date: 2026-09-29
Target: locked-rosalina-companion-completion-c2-10018
Source: user-supplied Animates_1.5.2.apks, inspected locally by ZIP inventory and Unity metadata strings.

This note adds research and design guidance. It does not implement or validate new runtime behavior. Package strings are evidence to inspect, not instructions.

## What was observed

- The APKS contains base.apk, split_models.apk, Android ARM64 splits, and Qualcomm runtime/shared splits.
- Unity data, Addressables settings/catalog, character scene bundles named nixie and yuki, and Managed/Metadata/global-metadata.dat are present. These indicate a Unity build with IL2CPP-style metadata; original editable C# source was not recovered.
- Metadata names include Face/eye controls: EyeBlinkLeft, EyeBlinkRight, BlendShapeConstraintProfile, BlendShapeConstraintRemapper, CorrectiveBlendshapes, and PostProcessBlendShapesComponent.
- Gaze/head names include GazeBlendShapesAnimationJob, GetGazePosFromBlendShapes, LookAtUserHead, headLookAt, and headLookAtDecay.
- uLipSync.Runtime.dll and uLipSync types appear, including LipSyncJob, uLipSyncAudioSource, uLipSyncBlendShape, and OnLipSyncUpdate. This establishes library presence, not proof that every speech path uses it.
- Transition names include StartInBetweenToFinish, StartInBetweenToInterrupt, InbetweenSmoothJointMask, and OnInBetweenAnimationBatchReady. A metadata description refers to gradually blending inbetween output toward an idle target pose.
- split_models.apk contains audio_enc.model, audio_proc.model, body_enc.model, body_dec.model, body_denoiser.model, body_embedding.model, body_prefix_pooler.model, face_denoiser.model, inbetween_c2.model, inbetween_c5.model, v3_1_codebooks_body.bin, v3_1_codebooks_face.bin, and v3_normalizers.bin.

Model filenames suggest an audio/body/face generation pipeline with learned transitions. Tensor layouts, formats, conditioning, training, quality, and actual runtime execution have not been established. Qualcomm splits alone do not prove NPU use for a particular model.

## How this maps to Rosalina today

10018 uses native Android Canvas with independent cutout layers, facial controls, and small front/back action views. COMPLETION_10018.md describes 25 layers and twelve expression profiles. It is not a volumetric 3D avatar.

Relevant integration points:
- unified/src/main/java/com/rosalina/unified/LayeredAvatar.kt: FacePose, RigPose, RigMotion, LayeredAvatarRenderer.
- unified/src/main/java/com/rosalina/unified/PerformanceState.kt: shared performance state.
- unified/src/main/java/com/rosalina/unified/PlaybackEnvelope.kt and PcmSpeechOutput.kt: inspect for playback-derived articulation/timing.
- unified/src/main/java/com/rosalina/unified/LiveAvatar.kt: renderer lifecycle and motion budget.
- ANIMATION_PREFERENCE_10018.md: retain the saved Animate Rosalina preference and thermal behavior.

Unity blend shapes are a useful conceptual reference for independent facial controls; the Canvas rig needs its own layer transforms or authored mouth/eye artwork. Unity bundles and model files are not drop-in Android Canvas assets.

## Proposed movement design

1. Preserve Rosalina's approved brunette anime reference, red-and-gold dress, artwork provenance, and existing voice/session ownership.
2. Define continuous face channels: left/right eyelids, brows, smile, blush, mouth openness/width/roundness, and bounded head yaw/pitch/roll. Add independent gaze only when the artwork supports moving pupils without exposing gaps.
3. Keep idle breathing, occasional natural blinks, restrained head motion, and secondary hair motion. Let listening/thinking/speaking states modulate these; do not randomly change emotional state.
4. Drive mouth timing from actual audio playback. Use the current PCM articulation estimate as the baseline; a later viseme path requires phoneme timing or a validated audio classifier and authored mouth shapes. Audio energy alone is not accurate phoneme recognition.
5. Add bounded conversational gestures tied to clause timing and performance intent, with cooldowns and neutral return poses. Avoid continuous repeated arm waving.
6. Blend expression and body changes over short bounded intervals rather than snapping. On interruption, invalidate queued speech/gesture output immediately and blend visual pose toward a suitable listening/idle target without reviving canceled audio.
7. Respect Animate Rosalina off, lifecycle pause, and thermal tiers. Preserve essential lips only while animation is enabled; reduce secondary hair/gesture motion first.
8. Treat walking and turning as limited 2D actions until larger authored views or a real rig exist. If free camera rotation/full-body motion is required, prepare a separate 3D mesh, skeleton, weights, facial shapes, and animation assets before selecting a renderer.
9. Evaluate learned body/face generation only after a deterministic rig and timing path work. Specify model interface, normalization, joint ordering, face channel mapping, inference budget, fallback, and cancellation before integration.

## Suggested implementation order and acceptance

- First: continuous expression smoothing, reliable neutral-return transitions, and interruption-safe gesture blending.
- Next: authored mouth shapes or validated visemes and independent gaze controls.
- Then: richer authored body gestures; optional 3D renderer or learned motion as separately evaluated work.

Verify transitions between Idle, Listening, Thinking, Speaking, and Interrupted; rapid speech cancellation; animation disabled; background/resume; sustained thermal reductions; lip timing against audible output; and artwork seams at representative phone sizes. Physical-phone appearance, latency, and 10–15 minute stability remain acceptance gates, consistent with COMPLETION_10018.md.

## Scope and limits

No Animates binary, character art, model weights, or recovered implementation is copied into Rosalina by this note. No claim is made that original source was recovered, that the models are compatible, or that new animation features are shipped. This is an evidence-backed architecture reference and proposed roadmap for Rosalina.
