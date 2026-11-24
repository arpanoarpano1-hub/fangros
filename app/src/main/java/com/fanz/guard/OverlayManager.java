package com.fanz.guard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayManager {
    private final Context context;
    private final WindowManager wm;
    private View overlayView;
    private int topLeftTaps = 0;
    private int bottomRightTaps = 0;
    private long lastTapTime = 0;

    public OverlayManager(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(String mode, String lostMsg, String backupPhone) {
        if (!Settings.canDrawOverlays(context)) return;
        hide();

        FrameLayout root = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                return true;
            }
        };

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.OPAQUE
        );
        params.gravity = Gravity.CENTER;

        if ("STEALTH".equals(mode)) {
            root.setBackgroundColor(Color.BLACK);
        } else if ("LOCKDOWN".equals(mode)) {
            root.setBackgroundColor(Color.parseColor("#7F1D1D"));
            TextView tv = new TextView(context);
            tv.setText("PERANGKAT TERKUNCI!\nLOKASI & WAJAH PELAKU SEDANG DILACAK.");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(20);
            tv.setGravity(Gravity.CENTER);
            root.addView(tv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else if ("LOST_FOUND".equals(mode)) {
            root.setBackgroundColor(Color.parseColor("#09090B"));
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(48, 48, 48, 48);

            TextView tv = new TextView(context);
            tv.setText(lostMsg.isEmpty() ? "HP ini hilang. Hubungi pemilik." : lostMsg);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(18);
            tv.setGravity(Gravity.CENTER);
            box.addView(tv);

            if (!backupPhone.isEmpty()) {
                Button callBtn = new Button(context);
                callBtn.setText("HUBUNGI PEMILIK SEKARANG");
                callBtn.setBackgroundColor(Color.parseColor("#10B981"));
                callBtn.setTextColor(Color.BLACK);
                callBtn.setOnClickListener(v -> {
                    Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + backupPhone));
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(callIntent);
                });
                box.addView(callBtn);
            }
            root.addView(box);
        }

        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - lastTapTime > 3000) {
                    topLeftTaps = 0;
                    bottomRightTaps = 0;
                }
                lastTapTime = now;

                float x = event.getX();
                float y = event.getY();
                int w = v.getWidth();
                int h = v.getHeight();

                if (x < w * 0.25 && y < h * 0.25) {
                    topLeftTaps++;
                } else if (topLeftTaps >= 3 && x > w * 0.75 && y > h * 0.75) {
                    bottomRightTaps++;
                    if (bottomRightTaps >= 2) {
                        SirenPlayer.stop();
                        hide();
                    }
                }
            }
            return false;
        });

        overlayView = root;
        wm.addView(overlayView, params);
    }

    public void hide() {
        if (overlayView != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
    }
}
