# Bubbles Quick Tile for Samsung / Android

A tiny native Android Quick Settings tile: one circular Bubbles control, no Modes and Routines.

## Scope
- Android Quick Settings TileService (system controls tile shape and on/off color).
- Reads Android Secure setting "notification_bubbles"; writes it only after a special permission grant.
- On Android 12+, also reads NotificationManager.areBubblesEnabled() to show effective OS state.
- Prompts to add tile on Android 13+; manual Quick Panel edit works if Samsung rejects the prompt.
- No root, internet, tracking, overlay, accessibility, notification read permissions, or system modifications.

## Setup
1. Install APK on phone and open Bubbles Toggle.
2. Tap ADD BUBBLES TO QUICK PANEL; if not supported, edit Quick Settings and add Bubbles.
3. On a computer with ADB, with USB debugging enabled and the phone connected, run:
   adb shell pm grant com.hassine.bubbles android.permission.WRITE_SECURE_SETTINGS
4. Return to app; permission should say granted. Tap the circle to toggle.
5. Verify under Samsung Settings > Notifications > Advanced settings > Floating notifications.

WARNING: Without the special permission the tile opens setup, it does not pretend to toggle.

Important limitation: Samsung Smart pop-up view is distinct from Android's Bubbles switch. This tile toggles notification_bubbles only; Samsung may use an additional internal mode that this app cannot control on all software versions. A per-app messaging notification must also allow bubbles.

Build using Gradle 8.10.2, Android Gradle Plugin 8.7.3, Java 17, Android SDK 35:
gradle :app:assembleRelease
Release APK may be unsigned until signed separately. No fake permission grant is built in.

Package identity: com.hassine.bubbles; versionCode 1. Keep signing certificate unchanged for future upgrades.
