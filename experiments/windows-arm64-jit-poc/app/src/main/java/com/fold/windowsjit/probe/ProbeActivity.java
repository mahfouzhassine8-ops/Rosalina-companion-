/* SPDX-License-Identifier: GPL-2.0-or-later
 * Standalone native Android ARM64 translator/JIT feasibility harness.
 * Not an installed-Windows runner; cannot access Windows Mode's data.
 */
package com.fold.windowsjit.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProbeActivity extends Activity {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static String loadError = null;

    static {
        try {
            System.loadLibrary("androidjitprobe");
        } catch (Throwable ex) {
            loadError = ex.toString();
        }
    }

    private static native String runNativeProbe();

    private TextView report;
    private Button run;
    private String lastReport = "Not run. No VM, Windows disk or firmware will be accessed.";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        layout.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("Windows ARM64 — Native JIT Feasibility");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView notice = new TextView(this);
        notice.setText("ISOLATED EXPERIMENT • NO WINDOWS BOOT\n"
                + "Compiles the pinned WindowsiOS ARM64 instruction classifier and "
                + "tests a fixed AArch64 code block under Android's executable-memory "
                + "policy. Separate app, no access to your Windows disk or VM.\n\n"
                + "A PASS is only a prerequisite. It does NOT mean the complete "
                + "WindowsiOS translator can boot Windows on Android.");
        notice.setPadding(0,pad/2,0,pad/2);
        layout.addView(notice);

        run = new Button(this);
        run.setText("Run native engine checks");
        layout.addView(run);

        Button copy = new Button(this);
        copy.setText("Copy result");
        layout.addView(copy);

        ScrollView scroller = new ScrollView(this);
        report = new TextView(this);
        report.setTextIsSelectable(true);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(13);
        report.setText(lastReport);
        scroller.addView(report);
        layout.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(layout);

        run.setOnClickListener(v -> {
            run.setEnabled(false);
            report.setText("Running local decoder and AArch64 executable-memory checks...");
            EXECUTOR.execute(() -> {
                String result;
                if (loadError != null) {
                    result = "Native library failed to load: " + loadError;
                } else {
                    try {
                        result = runNativeProbe();
                    } catch (Throwable ex) {
                        result = "Native engine probe failed: " + ex;
                    }
                }
                final String text = result;
                UI.post(() -> {
                    lastReport = text;
                    report.setText(text);
                    run.setEnabled(true);
                });
            });
        });

        copy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null)
                cb.setPrimaryClip(ClipData.newPlainText("Android JIT probe",lastReport));
        });
    }
}
