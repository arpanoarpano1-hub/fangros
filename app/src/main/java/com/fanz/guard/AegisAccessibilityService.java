package com.fanz.guard;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class AegisAccessibilityService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) return;

        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        String pkgStr = pkg != null ? pkg.toString() : "";
        String clsStr = cls != null ? cls.toString().toLowerCase() : "";

        // Tutup panel notifikasi/Quick Settings jika ditarik saat terkunci
        if ("com.android.systemui".equals(pkgStr) && (clsStr.contains("shade") || clsStr.contains("statusbar"))) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        }

        // Deteksi menu Power Off / Matikan Daya saat HP masih terkunci
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            boolean powerMenuDetected = containsPowerText(root);
            if (powerMenuDetected) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                Intent intent = new Intent(this, GuardService.class);
                intent.setAction("TRIGGER_FAKE_POWEROFF");
                startService(intent);
            }
        }
    }

    private boolean containsPowerText(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String combined = ((text != null ? text.toString() : "") + " " + (desc != null ? desc.toString() : "")).toLowerCase();

        if (combined.contains("matikan daya") || combined.contains("power off") || combined.contains("mulai ulang") || combined.contains("restart")) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsPowerText(node.getChild(i))) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {}
}
