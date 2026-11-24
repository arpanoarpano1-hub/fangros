package com.fanz.guard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
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
        if ("STEALTH".equals(mode)) {
            showCustom("", "", "#000000", "");
        } else if ("LOCKDOWN".equals(mode)) {
            showCustom("PERANGKAT TERKUNCI!", "Lokasi GPS & foto kamera depan sedang dikirim ke pemilik.", "#7F1D1D", "");
        } else if ("LOST_FOUND".equals(mode)) {
            showCustom("PERANGKAT INI HILANG", lostMsg.isEmpty() ? "Mohon hubungi pemilik untuk mengembalikan perangkat ini." : lostMsg, "#090B10", backupPhone);
        }
    }

    public void showCustom(String titleText, String bodyText, String bgHex, String backupPhone) {
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

        int parsedColor = Color.parseColor("#7F1D1D");
        try {
            if (bgHex != null && !bgHex.isEmpty()) parsedColor = Color.parseColor(bgHex);
        } catch (Exception ignored) {}
        root.setBackgroundColor(parsedColor);

        if ((titleText != null && !titleText.isEmpty()) || (bodyText != null && !bodyText.isEmpty())) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(56, 56, 56, 56);

            if (titleText != null && !titleText.isEmpty()) {
                TextView titleView = new TextView(context);
                titleView.setText(titleText);
                titleView.setTextColor(Color.WHITE);
                titleView.setTextSize(22);
                titleView.setTypeface(null, Typeface.BOLD);
                titleView.setGravity(Gravity.CENTER);
                titleView.setPadding(0, 0, 0, 24);
                box.addView(titleView);
            }

            if (bodyText != null && !bodyText.isEmpty()) {
                TextView bodyView = new TextView(context);
                bodyView.setText(bodyText);
                bodyView.setTextColor(Color.parseColor("#E5E7EB"));
                bodyView.setTextSize(16);
                bodyView.setGravity(Gravity.CENTER);
                bodyView.setPadding(0, 0, 0, 40);
                box.addView(bodyView);
            }

            if (backupPhone != null && !backupPhone.trim().isEmpty()) {
                Button callBtn = new Button(context);
                callBtn.setText("HUBUNGI PEMILIK SEKARANG");
                callBtn.setBackgroundColor(Color.parseColor("#10B981"));
                callBtn.setTextColor(Color.BLACK);
                callBtn.setTypeface(null, Typeface.BOLD);
                callBtn.setPadding(40, 24, 40, 24);
                callBtn.setOnClickListener(v -> {
                    Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + backupPhone.trim()));
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(callIntent);
                });
                box.addView(callBtn);
            }

            root.addView(box, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
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
