package com.fanz.guard;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;

public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.getCurrentFailedPasswordAttempts() >= 2) {
            Intent alertIntent = new Intent(context, GuardService.class);
            alertIntent.setAction("TRIGGER_INTRUDER_PIN");
            context.startForegroundService(alertIntent);
        }
    }
}
