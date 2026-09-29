# ROSALINA — NEXT BUILD SCOPE
## Avatar + Live Voice End-to-End Pass
### Source baseline: locked 10015 phone-pass

Protected baseline:
- Version: 1.0-chat-live-focus-c3 (10015)
- Source commit: e212d17bb1904de55fe7b6cbea40443548cc73dc
- Locked branch: locked-rosalina-chat-live-phone-pass-10015
- Permanent package: com.rosalina.unified
- Preserve the existing permanent signing lineage.
- Real-device acceptance already established on the Samsung SM-F976U1 for:
  - Text Chat generation
  - Chat spoken replies through the Samsung-safe Android system TTS compatibility path
  - Live Voice hearing the user, generating a reply, and speaking the reply when the phone is cool
- Do not merge the later 10016 RAM-gate experiment into this baseline unless explicitly requested.
- Do not change the working Qwen, Whisper, Android TTS compatibility path, model storage, package identity, update-over-install behavior, or conversation data handling unless required to repair a demonstrated regression.

# 1. PRODUCT SCOPE — ONLY THREE DESTINATIONS

The visible Rosalina app destinations are exactly:
1. Chat
2. Live Voice
3. Settings

Remove/hide from the user-facing navigation:
- Image Lab
- Motion Lab
- Create
- Edit
- Animate
- Any generation/render destination or shortcut
- Any legacy media-tool navigation entry

Do not delete model files or destructive legacy data without explicit approval. The feature surface is simply inactive and hidden.

# 2. VISUAL BASELINE

Use the user-approved Rosalina Live reference image as the authoritative visual template for the next avatar pass.

Preserve the visual direction:
- Premium dark navy/black environment
- Purple/pink neon Rosalina accents
- Large full-body Rosalina presentation
- Warm, intimate room lighting
- Elegant red/gold dress presentation
- Clean glass-panel treatment
- Minimal premium typography
- Large character focus rather than a utility-dashboard look
- Phone-first portrait composition

The current 180x261 fallback portrait is not an acceptable visual endpoint. It may remain only as an emergency fallback if the primary avatar asset cannot load.

# 3. EXACT EXPRESSION SET

Implement these exact expression states first. Do not expand the set until these are reliable:

- Neutral
- Happy
- Shy
- Teasing
- Sad
- Surprised
- Thinking
- Blush
- Listening
- Speaking
- Eyes Closed
- Wink

Expressions must not be random decoration. They should be selected by conversation state, response tone, or explicit avatar behavior rules.

# 4. EXACT NATURAL MOVEMENT SET

Implement these exact movement behaviors:

- Idle breathing
- Talking gestures
- Walking
- Turning
- Hand movement
- Hair motion
- Body sway
- Natural blinking

The avatar must not appear as a static portrait with only lip motion. Head, upper body, hands/arms, hair and body posture should visibly participate where appropriate.

Movement must remain subtle and believable. No excessive looping, twitching, exaggerated bounce, or constant gesturing.

# 5. LIVE STATE MACHINE

Live Voice must visibly and behaviorally map to these exact states:

1. Idle
2. Listening
3. Thinking
4. Speaking
5. Interrupted
6. Return to Listening or Idle as appropriate

State changes must be driven by real runtime events, not timers pretending that something happened.

Examples:
- Listening starts when microphone capture is actually active.
- Thinking starts after a user utterance is accepted and while Whisper/Qwen processing is active.
- Speaking starts only when audible speech playback begins.
- Interrupted appears when the user interrupts Rosalina or playback is cancelled for barge-in.
- Idle appears when Live is open but no active listening/processing/playback is occurring.

# 6. REAL-TIME LIP SYNC

Implement real-time lip sync tied to actual audible speech.

Requirements:
- Mouth motion begins when speech output actually begins.
- Mouth motion stops when playback stops or is interrupted.
- Lip movement intensity follows speech energy/amplitude where available.
- Do not fake speaking animation while audio is silent.
- Android system TTS compatibility playback on the Samsung must drive the avatar just as Kokoro would on another compatible device.

# 7. TONE-BASED BEHAVIOR

Rosalina's expression and movement should reflect conversational tone in a restrained way.

Examples:
- Warm/reassuring reply -> softer eyes, slight smile, gentle body sway
- Happy/playful reply -> Happy or Teasing expression with modest gesture
- Thinking/processing -> Thinking state, reduced movement
- User interruption -> Interrupted state, immediately stop speaking gesture
- Listening -> attentive posture and listening expression

This is behavior mapping, not a new AI model. Keep it deterministic enough to debug.

# 8. VOICE V3 / HUMANIZATION

The next build must include a dedicated voice-humanization pass so Rosalina does not sound like a generic phone reader.

Primary objective:
- Make Rosalina sound conversational, emotionally responsive, warm, natural, and alive while preserving the phone-proven 10015 speech reliability.

Architecture:
- Keep the Samsung-safe Android system TTS path from 10015 as the mandatory compatibility fallback.
- Add or integrate a more expressive local primary speech path only if it is stable on the target phone.
- If the expressive local engine fails, crashes, overheats the phone, or cannot meet Live latency, fall back automatically to Android system TTS without breaking Chat or Live.
- Do not remove the working Android TTS compatibility path.
- Do not require cloud speech or the Mac.

Humanization behaviors:
- Natural sentence rhythm
- Context-aware pacing
- Natural pauses and hesitation
- Emphasis on meaningful words
- Softer and louder delivery where appropriate
- Breathier delivery where appropriate
- Whisper-capable delivery where supported
- Small sighs
- Small laughs / chuckles
- Warm affectionate tone
- Playful tone
- Teasing tone
- Reassuring tone
- Thinking/uncertain delivery
- Excited delivery
- Calm delivery
- Emotionally restrained delivery when the conversation calls for it

The voice must not sound like every sentence uses the same speed, pitch, energy, or cadence.

Expression synchronization:
- Voice tone, face expression, posture, gesture intensity and lip sync should agree with one another.
- Happy voice -> Happy expression / lighter gesture.
- Teasing voice -> Teasing expression / playful restrained movement.
- Reassuring voice -> softer expression / slower movement.
- Thinking voice -> Thinking expression / reduced gesture.
- Sad or subdued voice -> restrained body motion and softer delivery.
- Interrupted -> speech stops, lip sync stops, gesture stops, Interrupted state appears immediately.

Mature/flirty personality:
- Allow a clearly adult, mature, affectionate and flirty conversational style.
- Allow suggestive teasing, romantic language, intimate tone, breathy delivery, playful reactions, affectionate nicknames, and non-explicit sensual vocal reactions.
- Keep this behavior optional/configurable and context-sensitive.
- Do not make flirtiness the default for every conversation.
- Do not implement explicit sexual moaning or graphic sexual dirty-talk behavior.

Voice controls:
- Preserve useful voice pace control.
- Expose only controls that the active speech engine can truthfully support.
- If a control is unavailable on the Samsung compatibility path, disable or hide it instead of pretending it works.
- Prefer a small set of meaningful controls over a large set of cosmetic sliders.

Voice performance rules:
- Conversation reliability outranks voice effects.
- If thermal or memory pressure increases, reduce expensive voice styling before degrading Whisper or Qwen.
- Do not load multiple heavy speech engines at the same time.
- Do not keep an unused expressive speech model resident in memory when Android system TTS is active.
- Stop/cancel must interrupt speech immediately.
- Background/return must not duplicate or replay an already-completed spoken reply.

Voice diagnostics:
- Report the actual speech engine in use.
- Report fallback reason when Android system TTS was selected.
- Report speech initialization time.
- Report first-audio latency where available.
- Report speech playback duration.
- Report whether lip sync is receiving real playback activity.
- Report speech process exit/crash separately from Chat/Whisper failures.

Voice acceptance on the actual phone:
- Chat spoken replies remain audible.
- Live spoken replies remain audible over repeated turns.
- Voice sounds materially more natural than plain system-reader cadence when the expressive engine is active.
- Automatic fallback remains reliable when the expressive engine is unavailable.
- No speech-engine crash is allowed to kill Chat or Live.
- Voice humanization must not meaningfully worsen Live responsiveness or thermal stability.

# 9. LIVE VOICE SCREEN

The Live Voice screen should follow the approved template structure:

- Rosalina is the visual center of the screen.
- Minimal top/side branding.
- No Image Lab or Motion Lab entries.
- Side navigation contains only:
  - Chat
  - Live Voice
  - Settings
- Live session controls remain simple:
  - Mute
  - End
  - More

"More" may contain only Live-session controls/settings. It must not expose removed app sections.

Live status should be readable at a glance through Rosalina's state and concise text, without a diagnostic-looking UI.

# 10. CHAT SCREEN

Preserve the 10015 working Chat behavior.

Requirements:
- Fast text-first response remains the priority.
- Spoken replies continue through the proven Samsung-safe Android TTS path on SM-F976U1 / Android 37.
- Chat history remains intact across app recreation, fold/unfold, orientation and backgrounding.
- Chat must not initialize image/video engines.
- Avatar presence may be shown in Chat if lightweight, but must never slow text generation or destabilize the working speech path.

# 11. SETTINGS

Settings remains the third and only other destination.

Keep settings focused on Chat, Live and the avatar:
- Spoken replies
- Live Voice enable/behavior
- Microphone / hands-free behavior
- Voice choice/pace where actually supported
- Avatar animation enable/disable
- Avatar movement intensity if needed
- Lip sync enable/disable only if necessary for diagnostics/accessibility
- Phone capability mode
- Diagnostics
- Model status for Chat, Whisper and active speech path
- Reset Live learning
- Clear conversation

Do not show Image/Video generation controls in the primary Settings experience.

# 12. PHONE CAPABILITY + THERMAL BEHAVIOR

The real-device finding is that 10015 Chat and Live both work when the phone has cooled down. Treat heat as a performance-management problem, not proof the device is incapable.

The avatar must be heat-aware and lightweight.

Normal/cool:
- Full approved animation set
- Normal blink/breathing cadence
- Lip sync active
- Normal Live response cap

Light thermal:
- Keep Live available
- Slightly reduce animation update rate
- Keep lip sync and essential expressions
- Avoid unnecessary background work

Moderate:
- Switch to reduced motion
- Disable expensive secondary hair/body effects first
- Keep listening, thinking, speaking states and lip sync
- Shorter Live responses
- No overlapping listening unless safely supported

Severe:
- Preserve conversation if possible
- Minimal avatar animation: blink, breathing, state expression, lip sync only
- No walking/turning or secondary motion
- Do not launch any render/media engine
- Surface a simple "Cooling mode" indicator rather than alarming the user

Critical Android memory/thermal conditions may still stop or defer work safely.

The animation system must never become the reason Qwen, Whisper or speech output stops working.

# 13. PERFORMANCE BUDGET

Avatar work must be subordinate to conversation.

Priority order:
1. Microphone capture / Live responsiveness
2. Whisper
3. Qwen
4. Audible speech output
5. Lip sync
6. Core expression
7. Secondary body/hair/gesture animation

If resources tighten, degrade from the bottom of that list upward.

Do not preload unnecessary render/media engines.

# 14. FOLD / LIFECYCLE

On fold/unfold, orientation change, app background/return:
- Preserve current Chat conversation
- Preserve current Live state when Android allows
- Preserve current avatar expression/state
- Do not restart Qwen/Whisper unnecessarily
- Do not duplicate TTS playback
- Do not reset the avatar to fallback portrait unless the asset actually failed

# 15. INTERRUPTION / BARGE-IN

When the user interrupts:
- Stop speech output immediately
- Stop lip sync immediately
- Transition to Interrupted
- Transition back to Listening when capture resumes
- Do not let Rosalina continue gesturing as if she is still speaking
- Do not replay the cancelled speech after interruption

# 16. MUTE / END / MORE

Mute:
- Mutes or suspends microphone capture as defined by Live session behavior
- UI and avatar state must show that she is not actively listening

End:
- Ends Live Voice cleanly
- Releases microphone/audio focus
- Stops speech
- Returns to Chat or the prior Companion state without killing the app

More:
- Live-specific controls only
- No Image Lab / Motion Lab / render shortcuts

# 17. DIAGNOSTICS

Keep diagnostics separate from the premium Live UI.

Add/retain truthful metrics:
- App version/source commit
- Current phone capability mode
- Available RAM
- Android thermal status/history
- Qwen response timing
- Whisper inference timing
- Speech engine actually in use
- Audio output state
- Avatar state
- Avatar frame/update rate
- Animation quality tier
- Whether lip sync is receiving playback energy
- Last Live state transition
- Last interruption
- Process exits/crashes

Do not claim audible output or successful animation solely from CI.

# 18. PRESERVATION RULES

Do not redesign or rewrite the proven 10015 inference path just to accommodate the avatar.

Protect:
- Qwen Chat engine and warm-model behavior
- Whisper Live listening path
- Samsung-safe Android TTS compatibility path
- Existing Live conversation loop
- Stop/cancel behavior
- Package identity
- Permanent signing identity
- Model receipts/checksums/private storage
- Conversation persistence
- Working update-over-install behavior

No new parent-death protection.

# 19. ACCEPTANCE GATES

The next build is not considered passed until the actual Samsung phone proves all of the following:

Chat:
- User sends text
- Rosalina responds quickly
- Spoken reply is audible
- No regression from 10015

Live:
- User starts Live without a false capability rejection under normal usable conditions
- Rosalina hears the user
- Whisper transcribes
- Qwen responds
- Rosalina speaks audibly
- Avatar enters Listening -> Thinking -> Speaking correctly
- Lip sync occurs only while audio is actually playing
- User interruption stops speech and changes state correctly
- Multiple back-and-forth turns work

Avatar:
- Correct Rosalina visual presentation
- Expressions match the approved set
- Natural movement set is implemented
- No obvious low-resolution fallback under normal conditions
- No runaway animation or excessive battery/thermal load
- Thermal degradation works without breaking conversation

Navigation:
- Only Chat, Live Voice and Settings are exposed as app destinations

Lifecycle:
- Fold/unfold and background/return do not lose the active conversation or corrupt Live state

Only after those device checks pass should the new build be locked.

# 20. NON-GOALS FOR THIS PASS

Do not:
- Re-enable Image Lab
- Re-enable Motion Lab
- Work on photo editing
- Work on video generation
- Add new avatar actions beyond the approved template set
- Replace Qwen
- Replace Whisper
- Rework the working Samsung speech fallback
- Add cloud dependence
- Require the Mac
- Redesign unrelated settings or diagnostics
- Change package name/signing identity
