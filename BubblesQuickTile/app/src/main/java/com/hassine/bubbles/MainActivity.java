package com.hassine.bubbles;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int BACK = Color.rgb(16,17,23);
    private static final int CARD = Color.rgb(36,37,46);
    private static final int ROSE = Color.rgb(241,151,169);
    private static final int WHITE = Color.rgb(244,239,245);
    private static final int MUTED = Color.rgb(189,184,196);
    private LinearLayout content;
    private TextView status;
    private Button onButton;
    private Button offButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BACK);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        content.setPadding(pad,dp(36),pad,dp(40));
        scroller.addView(content);
        setContentView(scroller);

        TextView title = label("Bubbles", 32, WHITE, true);
        content.addView(title);
        TextView sub = label("Your small Quick Settings circle.", 15, MUTED, false);
        sub.setPadding(0,dp(4),0,dp(23));
        content.addView(sub);

        LinearLayout card = card();
        status = label("Checking your Bubbles setting…", 17, WHITE, true);
        card.addView(status);
        TextView hint = label(
                "One tap in your Quick Panel switches Android notification bubbles. " +
                "Your Samsung theme decides whether the circle is pink or gray.",
                14, MUTED, false);
        hint.setPadding(0,dp(9),0,0);
        card.addView(hint);

        button("ADD BUBBLES TO QUICK PANEL", () -> addTile(), true);
        TextView how = label("If Samsung does not display an Add prompt: swipe down, " +
                "tap the pencil, edit Quick Settings, and drag Bubbles into your circle row.",
                14, MUTED, false);
        how.setPadding(0,dp(4),0,dp(21));
        content.addView(how);

        TextView step = label("One-time permission", 20, WHITE, true);
        content.addView(step);
        TextView help = label("Android protects system notification settings. " +
                "For the circle to switch bubbles directly, grant WRITE_SECURE_SETTINGS once using ADB. " +
                "There is no normal permission popup for this.",
                14, MUTED, false);
        help.setPadding(0,dp(7),0,dp(12));
        content.addView(help);

        LinearLayout commandCard = card();
        TextView command = label(BubbleState.GRANT_COMMAND, 14, WHITE, false);
        command.setTextIsSelectable(true);
        commandCard.addView(command);
        button("COPY ADB COMMAND", () -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Bubbles setup", BubbleState.GRANT_COMMAND));
            Toast.makeText(this,"Command copied",Toast.LENGTH_SHORT).show();
        },false);

        TextView adbSteps = label(
                "On a computer with Android Platform Tools: enable USB debugging, connect your Fold, " +
                "approve the connection, and run the copied command. Then return here. " +
                "Do not use 'adb shell' if you're already inside an ADB shell.",
                13,MUTED,false);
        adbSteps.setPadding(0,0,0,dp(18));
        content.addView(adbSteps);

        onButton = button("BUBBLES ON", () -> change(true), false);
        offButton = button("BUBBLES OFF", () -> change(false), false);
        button("OPEN NOTIFICATION SETTINGS", this::openNotificationSettings, false);

        TextView note = label(
                "Important: This uses Android's system-wide Bubbles switch. " +
                "Samsung also offers a separate Smart pop-up view mode, which this app does not toggle. " +
                "If the circle says On but floating messages still do not appear, visit " +
                "Settings > Notifications > Advanced settings > Floating notifications, " +
                "select Bubbles, and check the messaging app's own bubble permissions.",
                13, MUTED, false);
        note.setPadding(0,dp(18),0,0);
        content.addView(note);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;
        boolean authorized = BubbleState.hasWritePermission(this);
        int saved = BubbleState.read(this);
        int effective = BubbleState.effective(this);
        if (!authorized) {
            status.setText("Setup required • Special permission not granted");
            status.setTextColor(ROSE);
        } else if (effective == 1) {
            status.setText("Bubbles enabled • System reports ON");
            status.setTextColor(ROSE);
        } else if (effective == 0) {
            status.setText("Bubbles disabled • System reports OFF");
            status.setTextColor(WHITE);
        } else {
            status.setText("Permission granted • Bubble state unknown");
            status.setTextColor(WHITE);
        }
        if (authorized && saved >= 0 && saved != effective) {
            status.append("\nSamsung's effective setting differs from the stored value.");
        }
        onButton.setEnabled(authorized);
        offButton.setEnabled(authorized);
    }

    private void change(boolean enable) {
        boolean ok = BubbleState.set(this, enable);
        updateStatus();
        if (!ok) {
            Toast.makeText(this,
                    "The protected setting did not change. Check permission / Samsung settings.",
                    Toast.LENGTH_LONG).show();
        } else if (BubbleState.effective(this) != (enable ? 1 : 0)) {
            Toast.makeText(this,
                    "Saved, but Samsung still reports another state; check Floating notifications.",
                    Toast.LENGTH_LONG).show();
        }
        TileUpdate.request(this);
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_SETTINGS));
        } catch (Exception ex) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this,"Use the Quick Panel pencil to add Bubbles.",Toast.LENGTH_LONG).show();
            return;
        }
        StatusBarManager manager = getSystemService(StatusBarManager.class);
        if (manager == null) {
            Toast.makeText(this,"Open Quick Settings and add the Bubbles tile manually.",Toast.LENGTH_LONG).show();
            return;
        }
        manager.requestAddTileService(
                new ComponentName(this,BubblesTileService.class), "Bubbles",
                Icon.createWithResource(this,R.drawable.ic_bubbles),getMainExecutor(), result -> {
                    String message;
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                        message="Bubbles added to your Quick Panel";
                    } else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        message="Bubbles is already in your Quick Panel";
                    } else {
                        message="Please add Bubbles manually using Quick Settings Edit.";
                    }
                    Toast.makeText(this,message,Toast.LENGTH_LONG).show();
                });
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p=dp(16);
        box.setPadding(p,p,p,p);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(18));
        box.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin=dp(18);
        content.addView(box,params);
        return box;
    }

    private Button button(String text, Runnable clicked, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setTextColor(primary?BACK:WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?ROSE:CARD));
        button.setOnClickListener(v->clicked.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(52));
        params.bottomMargin=dp(10);
        content.addView(button,params);
        return button;
    }

    private TextView label(String text,int size,int color,boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        view.setGravity(Gravity.START);
        return view;
    }

    private int dp(float d) {
        return (int)(d*getResources().getDisplayMetrics().density+0.5f);
    }
}
