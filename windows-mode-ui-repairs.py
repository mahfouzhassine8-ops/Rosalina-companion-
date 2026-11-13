#!/usr/bin/env python3
"""Windows Mode 7.1.10: scoped QGE input, keyboard-safe layout, Flex and OOBE repair.

Applies after windows-mode-source-repairs.py to the pinned QubeVM v7.1.4
checkout. Deliberately never opens or changes guest QCOW2/ISO files,
emulator CPU/disk parameters, machine configuration, or firmware.
"""
from pathlib import Path
import sys

checkout = Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
src = checkout / "qube-android-lib" / "src" / "main"
java = src / "java" / "com" / "max2idea" / "android" / "qube"

def patch(path, before, after, count=1):
    file = java / path
    content = file.read_text()
    found = content.count(before)
    if found != count:
        raise RuntimeError(f"Unexpected {path}: {before[:70]!r} occurs {found}, expected {count}")
    file.write_text(content.replace(before, after))
    print("SCOPED PATCH", path)

def patch_resource(path, before, after, count=1):
    file = src / path
    content = file.read_text()
    found = content.count(before)
    if found != count:
        raise RuntimeError(f"Unexpected {path}: {before[:70]!r} occurs {found}, expected {count}")
    file.write_text(content.replace(before, after))
    print("SCOPED RESOURCE", path)

# 1. Android's Samsung IME commits text via InputConnection, NOT necessarily
# KeyEvent. This real input editor forwards full committed strings to the guest,
# handles erase/Enter, and ignores transient composition (no duplicate typing).
patch("main/QubeQGESurface.java",
      '''import android.view.ViewConfiguration;''',
      '''import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.text.InputType;''')

patch("main/QubeQGESurface.java",
      '''    public void setFrameBitmap(Bitmap bitmap) {''',
      '''    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int cursorPosition) {
                if (text != null && text.length() > 0)
                    QGEActivity.sendImeText(text.toString());
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int cursorPosition) {
                // Samsung sends provisional candidates before a final commit.
                return true;
            }

            @Override
            public boolean finishComposingText() {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                if (before > 0)
                    QGEActivity.sendImeBackspace(Math.min(before, 128));
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event != null && (event.getAction() == KeyEvent.ACTION_DOWN
                        || event.getAction() == KeyEvent.ACTION_UP))
                    QGEActivity.onSendKeyEvent(event.getKeyCode(),
                            event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        };
    }

    public void setFrameBitmap(Bitmap bitmap) {''')

# A new screen height when Samsung keyboard appears must use a NEW transform
# immediately; previously taps inverted the matrix from the prior frame render.
patch("main/QubeQGESurface.java",
      '''    private float[] toFrameBufferPoint(float x, float y) {
        if (frameBitmap == null)
            return new float[]{x, y};
        Matrix inverse = new Matrix();
        frameMatrix.invert(inverse);
        float[] pt = new float[]{x, y};
        inverse.mapPoints(pt);
        return pt;
    }''',
      '''    private float[] toFrameBufferPoint(float x, float y) {
        if (frameBitmap == null || getWidth() <= 0 || getHeight() <= 0)
            return new float[]{x, y};
        float scale = Math.min(getWidth() / (float) frameBitmap.getWidth(),
                               getHeight() / (float) frameBitmap.getHeight());
        if (scale <= 0)
            return new float[]{x, y};
        float left = (getWidth() - frameBitmap.getWidth() * scale) / 2f;
        float top = (getHeight() - frameBitmap.getHeight() * scale) / 2f;
        return new float[]{(x - left) / scale, (y - top) / scale};
    }''')

# Pace committed IME strings on the single existing guest key queue; TCG guest
# keyboard input dropped rapidly delivered characters ('start ms' incident).
patch("main/QubeQGEActivity.java",
      '''    private void sendText(String string) {
        for (int i = 0; i < string.length(); i += Character.charCount(string.codePointAt(i))) {
            long keysym = KeySymMap.unicodeToKeysym(string.codePointAt(i));
            sendKeysym(keysym, true);
            sendKeysym(keysym, false);
        }
    }''',
      '''    private void sendText(String string) {
        if (string == null || string.isEmpty())
            return;
        keyEventsExecutor.submit(() -> {
            for (int i = 0; i < string.length();) {
                int codePoint = string.codePointAt(i);
                i += Character.charCount(codePoint);
                long keysym = KeySymMap.unicodeToKeysym(codePoint);
                if (!machineRunning || Thread.currentThread().isInterrupted())
                    return;
                if (keysym == 0)
                    continue;
                QubeInput.nativeSendKeyEvent(keysym, true);
                if (!pauseGuestKey(18))
                    return;
                QubeInput.nativeSendKeyEvent(keysym, false);
                if (!pauseGuestKey(72))
                    return;
            }
        });
    }

    public void sendImeText(String text) {
        sendText(text);
    }

    public void sendImeBackspace(int count) {
        keyEventsExecutor.submit(() -> {
            for (int i = 0; i < count && machineRunning; i++) {
                QubeInput.nativeSendKeyEvent(0xFF08L, true);
                if (!pauseGuestKey(18))
                    return;
                QubeInput.nativeSendKeyEvent(0xFF08L, false);
                if (!pauseGuestKey(72))
                    return;
            }
        });
    }

    private static boolean pauseGuestKey(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }''')

# 2. Make QGE's *actual view* fit above Samsung keyboard. Moving only virtual
# keys, as 7.1.9 did, left the Windows framebuffer hidden behind the IME.
patch("main/QubeQGEActivity.java",
      '''    private int imeBottomInset = 0;''',
      '''    private int imeBottomInset = 0;
    private int appliedImeBottomInset = -1;
    private FlexTouchpadView flexTouchpad;
    private boolean flexHalfOpen = false;
    private androidx.window.java.layout.WindowInfoTrackerCallbackAdapter foldTracker;
    private androidx.core.util.Consumer<androidx.window.layout.WindowLayoutInfo> foldListener;''')

patch("main/QubeQGEActivity.java",
      '''        mGap = findViewById(R.id.gap);
        mTopLayout = findViewById(R.id.top_layout);''',
      '''        mGap = findViewById(R.id.gap);
        mTopLayout = findViewById(R.id.top_layout);
        flexTouchpad = findViewById(R.id.flex_touchpad);''')

patch("main/QubeQGEActivity.java",
      '''        updateLayout(getResources().getConfiguration().orientation);
    }

    // Pad mTopLayout bottom''',
      '''        updateLayout(getResources().getConfiguration().orientation);
        initFoldTracking();
    }

    // Pad mTopLayout bottom''')

patch("main/QubeQGEActivity.java",
      '''    private void applyKeyboardInset(int bottom) {
        if (mVirtualKeysContainer != null)
            mVirtualKeysContainer.setTranslationY(-bottom);
    }''',
      '''    private void applyKeyboardInset(int bottom) {
        int inset = Math.max(0, bottom);
        if (mTopLayout == null || appliedImeBottomInset == inset)
            return;
        appliedImeBottomInset = inset;
        mTopLayout.setPadding(mTopLayout.getPaddingLeft(), mTopLayout.getPaddingTop(),
                mTopLayout.getPaddingRight(), inset);
        // Virtual keys now sit within the resized content; translating them
        // again would overlap the Windows screen or Samsung keyboard.
        if (mVirtualKeysContainer != null)
            mVirtualKeysContainer.setTranslationY(0);
        updateFlexTouchpadVisibility();
        if (mSurface != null) {
            mSurface.requestLayout();
            mSurface.invalidate();
        }
    }

    private void updateFlexTouchpadVisibility() {
        if (flexTouchpad != null)
            flexTouchpad.setVisibility(flexHalfOpen && imeBottomInset == 0
                    ? View.VISIBLE : View.GONE);
    }

    private void initFoldTracking() {
        // Reuse androidx.window already shipped by the pinned upstream APK.
        // Observes activity posture without stopping QEMU or resizing the disk.
        try {
            foldTracker = new androidx.window.java.layout.WindowInfoTrackerCallbackAdapter(
                    androidx.window.layout.WindowInfoTracker.Companion.getOrCreate(this));
            foldListener = info -> {
                boolean halfOpenHorizontal = false;
                for (androidx.window.layout.DisplayFeature feature : info.getDisplayFeatures()) {
                    if (feature instanceof androidx.window.layout.FoldingFeature) {
                        androidx.window.layout.FoldingFeature fold =
                                (androidx.window.layout.FoldingFeature) feature;
                        if (fold.getState() == androidx.window.layout.FoldingFeature.State.HALF_OPENED
                                && fold.getOrientation()
                                        == androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL) {
                            halfOpenHorizontal = true;
                            break;
                        }
                    }
                }
                flexHalfOpen = halfOpenHorizontal;
                updateFlexTouchpadVisibility();
                if (mSurface != null) {
                    mSurface.requestLayout();
                    mSurface.invalidate();
                }
            };
            foldTracker.addWindowLayoutInfoListener(this,
                    androidx.core.content.ContextCompat.getMainExecutor(this), foldListener);
        } catch (RuntimeException | LinkageError ex) {
            android.util.Log.w(TAG, "Flex posture listener unavailable on this device", ex);
        }
    }''')

patch("main/QubeQGEActivity.java",
      '''        quit = true;
        stopGfxLoop();
        removeListeners();''',
      '''        quit = true;
        stopGfxLoop();
        if (foldTracker != null && foldListener != null)
            foldTracker.removeWindowLayoutInfoListener(foldListener);
        removeListeners();''')

# Flex layout is inert on ordinary flat phones, unfolded flat screens, and DeX.
# In a true horizontal half-open posture, the content occupies the upper pane
# and a proper relative touchpad takes the lower pane.
patch_resource("res/layout/qube_qge.xml",
      '''        android:id="@+id/top_layout"
        android:layout_width="match_parent"
        android:layout_height="match_parent"''',
      '''        android:id="@+id/top_layout"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"''')
patch_resource("res/layout/qube_qge.xml",
      '''        <include layout="@layout/virtual_keys" />
    </RelativeLayout>
</LinearLayout>''',
      '''        <include layout="@layout/virtual_keys" />
    </RelativeLayout>

    <com.max2idea.android.qube.main.FlexTouchpadView
        android:id="@+id/flex_touchpad"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:visibility="gone"
        android:contentDescription="Flex mode touchpad" />
</LinearLayout>''')

pad_file = java / "main/FlexTouchpadView.java"
if pad_file.exists():
    raise RuntimeError("Refusing to overwrite preexisting FlexTouchpadView")
pad_file.write_text(r'''/*
 * Copyright (C) 2026 Windows Mode contributors
 * QubeVM GPL-3.0 derivative. Flex-mode input only; no guest changes.
 */
package com.max2idea.android.qube.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

public final class FlexTouchpadView extends View {
    private final QubeQGEActivity guest;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float slop;
    private float x, y, totalMovement, scrollY;
    private long startMs;
    private boolean usedTwoFingers;

    public FlexTouchpadView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        guest = (QubeQGEActivity) context;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff20242b);
        paint.setColor(0xff555e6c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density);
        float margin = 12f * getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(margin, margin, getWidth() - margin,
                getHeight() - margin, margin, margin, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffdce7f1);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("Touchpad  •  Tap: click  •  Two fingers: scroll / right-click",
                getWidth() / 2f, Math.max(24f, getHeight() / 2f), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                x = event.getX();
                y = event.getY();
                totalMovement = 0;
                usedTwoFingers = false;
                startMs = event.getEventTime();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    usedTwoFingers = true;
                    scrollY = (event.getY(0) + event.getY(1)) / 2f;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    float newScrollY = (event.getY(0) + event.getY(1)) / 2f;
                    float change = newScrollY - scrollY;
                    scrollY = newScrollY;
                    totalMovement += Math.abs(change);
                    if (Math.abs(change) > 3f)
                        guest.sendScroll(-change / 36f);
                } else if (!usedTwoFingers) {
                    float dx = event.getX() - x;
                    float dy = event.getY() - y;
                    totalMovement += Math.abs(dx) + Math.abs(dy);
                    guest.sendRelativeMove(dx * 1.25f, dy * 1.25f);
                    x = event.getX();
                    y = event.getY();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_UP:
                if (event.getEventTime() - startMs <= 350
                        && totalMovement < slop * 2f) {
                    int button = usedTwoFingers ? Config.MOUSE_BUTTON_RIGHT
                            : Config.MOUSE_BUTTON_LEFT;
                    guest.sendMouseEvent(button, MotionEvent.ACTION_DOWN,
                            MotionEvent.TOOL_TYPE_FINGER, event.getX(), event.getY());
                    guest.sendMouseEvent(button, MotionEvent.ACTION_UP,
                            MotionEvent.TOOL_TYPE_FINGER, event.getX(), event.getY());
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
''')
print("NEW JAVA main/FlexTouchpadView.java: touchpad active only in horizontal half-open Flex")

# 3. One-tap locally typed offline-account command for Windows 11 ARM OOBE.
# Earlier Android Send Text truncated to "start ms", while the HMP paced key
# route already worked and was visually confirmed with two WinPE reg commands.
patch("virtualkeys/VirtualKey.java",
      '''    SetupBypass(null, "Win11 Bypass", 0, "Type TPM/Secure Boot setup registry commands", false),''',
      '''    SetupBypass(null, "Win11 Bypass", 0, "Type TPM/Secure Boot setup registry commands", false),
    OfflineSetup(null, "Offline Account", 0, "Type Windows 11 local account setup command", false),''')
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.LeftCtrl,''',
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.OfflineSetup, VirtualKey.LeftCtrl,''')
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass)''',
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.OfflineSetup)''')

patch("virtualkeys/VirtualKeys.java",
      '''            } else if (vk == VirtualKey.SetupBypass) {''',
      '''            } else if (vk == VirtualKey.OfflineSetup) {
                view.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle("Windows 11 offline account")
                        .setMessage("At the Windows 11 internet requirement screen, first open Shift+F10 "
                                + "and tap the black Command Prompt to focus it. This shortcut types "
                                + "'start ms-cxh:localonly' through the tested paced QEMU keyboard path. "
                                + "It does not format or modify your VM disk settings. "
                                + "Windows may not support this command in every build.")
                        .setPositiveButton("Type offline command", (dialog, which) -> {
                            com.max2idea.android.qube.machine.Machine vm =
                                    com.max2idea.android.qube.machine.MachineController
                                            .getInstance().getMachine();
                            String iso = vm == null ? "" : String.valueOf(vm.getCdImagePath());
                            String lower = iso.toLowerCase(java.util.Locale.ROOT);
                            if (com.max2idea.android.qube.main.QubeApplication.arch
                                        != com.max2idea.android.qube.main.Config.Arch.arm64
                                    || !(lower.contains("win11") || lower.contains("windows11"))
                                    || !lower.endsWith(".iso")) {
                                ToastUtils.toastLong(activity, "Available only for a Windows 11 ARM64 VM");
                                return;
                            }
                            ToastUtils.toastLong(activity, "Typing offline command: "
                                    + "leave the guest Command Prompt focused.");
                            new Thread(() -> {
                                boolean ok = typeSetupCommand("start ms-cxh:localonly");
                                activity.runOnUiThread(() ->
                                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                                .setTitle(ok ? "Offline command sent" : "Typing interrupted")
                                                .setMessage(ok
                                                        ? "Verify the Command Prompt shows the entire "
                                                        + "'start ms-cxh:localonly' line, then check "
                                                        + "whether Windows opens local account creation. "
                                                        + "The app cannot verify that this Windows build "
                                                        + "accepts the shortcut."
                                                        : "The QEMU keyboard path was interrupted. "
                                                        + "No Windows installation files were altered.")
                                                .setPositiveButton("OK", null)
                                                .show());
                            }, "QubeOfflineAccount").start();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
            } else if (vk == VirtualKey.SetupBypass) {''')

patch("virtualkeys/VirtualKeys.java",
      '''            } else if (ch == '_') {
                keyName = "shift-minus";
            } else {''',
      '''            } else if (ch == '_') {
                keyName = "shift-minus";
            } else if (ch == '-') {
                keyName = "minus";
            } else if (ch == ':') {
                keyName = "shift-semicolon";
            } else {''')

# Read and assert key gates. Do not silently proceed on a changed upstream tree.
checks = {
 "main/QubeQGEActivity.java": [
  "new androidx.window.java.layout.WindowInfoTrackerCallbackAdapter",
  "setPadding(mTopLayout.getPaddingLeft()", "sendImeText(String text)",
  "sendImeBackspace(int count)", "flexHalfOpen",
 ],
 "main/QubeQGESurface.java": [
  "onCreateInputConnection(EditorInfo info)", "commitText(CharSequence text",
  "toFrameBufferPoint(float x, float y)", "TYPE_TEXT_FLAG_NO_SUGGESTIONS",
 ],
 "virtualkeys/VirtualKey.java": ["OfflineSetup(null"],
 "virtualkeys/VirtualKeyLayoutConfig.java": ["VirtualKey.OfflineSetup"],
 "virtualkeys/VirtualKeys.java": [
  "start ms-cxh:localonly", "shift-semicolon", 'keyName = "minus"',
 ],
 "main/FlexTouchpadView.java": [
  "sendRelativeMove", "sendScroll", "MOUSE_BUTTON_RIGHT",
 ]
}
for rel, keywords in checks.items():
    data = (java / rel).read_text()
    for keyword in keywords:
        assert keyword in data, f"Missing {keyword!r} from {rel}"
xml = (src / "res/layout/qube_qge.xml").read_text()
assert '@+id/flex_touchpad' in xml and 'android:layout_weight="1"' in xml
assert 'qcow2' not in (java / "main/FlexTouchpadView.java").read_text().lower()
print("UI/FLEX/OOBE SOURCE GATES PASS; original QCOW2, ISO, native QEMU unchanged")
