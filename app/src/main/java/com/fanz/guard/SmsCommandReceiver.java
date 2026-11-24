package com.fanz.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

public class SmsCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;
        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
            String body = sms.getMessageBody();
            String sender = sms.getOriginatingAddress();
            if (body != null && body.trim().toUpperCase().startsWith("AEGIS#")) {
                Intent svc = new Intent(context, GuardService.class);
                svc.setAction("SMS_REMOTE_CMD");
                svc.putExtra("cmd", body.trim().toUpperCase());
                svc.putExtra("sender", sender);
                context.startForegroundService(svc);
            }
        }
    }
}
