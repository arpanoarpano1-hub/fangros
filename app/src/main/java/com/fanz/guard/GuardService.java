package com.fanz.guard;

import android.annotation.SuppressLint;
import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.hardware.*;
import android.location.Location;
import android.location.LocationManager;
import android.os.*;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import androidx.core.app.NotificationCompat;
import okhttp3.*;
import org.json.JSONObject;

public class GuardService extends Service implements SensorEventListener {
    private WebSocket webSocket;
    private OverlayManager overlayManager;
    private SensorManager sensorManager;
    private KeyguardManager keyguardManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isPocketCovered = false;
    private boolean lastGaspTriggered = false;
    private boolean sentryModeActive = false;
    private JSONObject activeRules = new JSONObject();

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();

            if (Intent.ACTION_POWER_DISCONNECTED.equals(action) && locked && isRuleEnabled("CHARGER_UNPLUG")) {
                triggerGraceAlert("CHARGER_DISCONNECTED", "Charger dicabut paksa saat terkunci!");
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) && locked && isRuleEnabled("TWS_DISCONNECT")) {
                triggerGraceAlert("TWS_DISCONNECTED", "Perangkat Bluetooth (TWS/Jam) terputus!");
            } else if ("android.intent.action.SIM_STATE_CHANGED".equals(action) && locked && isRuleEnabled("SIM_EJECT")) {
                String state = intent.getStringExtra("ss");
                if ("ABSENT".equals(state)) {
                    executeEmergency("Kartu SIM dicabut paksa!", true, "STEALTH");
                }
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                boolean usbPc = (plugged == BatteryManager.BATTERY_PLUGGED_USB);

                if (usbPc && locked && isRuleEnabled("USB_PC_CONNECT")) {
                    executeEmergency("HP dicolok ke kabel data PC/Laptop!", false, "STEALTH");
                }
                if (level > 0 && level <= 5 && !lastGaspTriggered && isRuleEnabled("LAST_GASP_BATTERY")) {
                    lastGaspTriggered = true;
                    executeEmergency("PROTOKOL NAPAS TERAKHIR: Baterai tersisa " + level + "%", false, null);
                    sendEmergencySms("DARURAT BATERAI " + level + "%!");
                }
                sendTelemetry(level, plugged > 0, usbPc, locked);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        overlayManager = new OverlayManager(this);
        keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        Sensor prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (prox != null) sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL);
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
        if (linear != null) sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_NORMAL);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction("android.intent.action.SIM_STATE_CHANGED");
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(systemReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if ("TRIGGER_INTRUDER_PIN".equals(act) && isRuleEnabled("WRONG_PIN")) {
                executeEmergency("Percobaan salah PIN/Pola berulang kali!", false, "STEALTH");
            } else if ("TRIGGER_FAKE_POWEROFF".equals(act)) {
                overlayManager.showCustom("Mematikan daya...", "Menyimpan pengaturan sistem...", "#000000", "");
                mainHandler.postDelayed(() -> overlayManager.showCustom("", "", "#000000", ""), 2200);
                EvidenceCollector.captureAndDispatch(this, "Pelaku mencoba mematikan daya HP!", this::sendPacket);
            } else if ("SMS_REMOTE_CMD".equals(act)) {
                String cmd = intent.getStringExtra("cmd");
                String sender = intent.getStringExtra("sender");
                if ("AEGIS#LOCK".equals(cmd)) {
                    executeEmergency("Perintah Kunci via SMS dari " + sender, true, "LOCKDOWN");
                } else if ("AEGIS#LOC".equals(cmd)) {
                    sendEmergencySmsTo(sender, "Lokasi GPS Aegis Guard:");
                }
            }
        } else {
            connectWebSocket();
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "aegis_guard_channel";
        NotificationChannel channel = new NotificationChannel(channelId, "Aegis Security Monitor", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aegis Guard Aktif")
                .setContentText("Menyambungkan ke server...")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build();
        startForeground(1, notification);
    }

    private void updateNotif(String statusText) {
        Notification notification = new NotificationCompat.Builder(this, "aegis_guard_channel")
                .setContentTitle("Aegis Guard Aktif")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, notification);
    }

    private void connectWebSocket() {
        mainHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            try { webSocket.close(1000, "Reconnect"); } catch (Exception ignored) {}
            webSocket = null;
        }

        SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
        String url = sp.getString("serverUrl", "ws://panel-free1.duckdns.top:4145");
        String code = sp.getString("roomId", "");
        if (code.isEmpty()) {
            updateNotif("Kode koneksi belum diatur");
            return;
        }

        updateNotif("Menghubungkan kode " + code + "...");
        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder().url(url).build();

        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                try {
                    JSONObject reg = new JSONObject();
                    reg.put("type", "REGISTER");
                    reg.put("role", "guard");
                    reg.put("roomId", code);
                    reg.put("token", code);
                    ws.send(reg.toString());
                    EvidenceCollector.flushOfflineVault(GuardService.this, GuardService.this::sendPacket);
                    updateNotif("Terhubung (Kode: " + code + ")");
                } catch (Exception ignored) {}
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject msg = new JSONObject(text);
                    if ("ERROR".equals(msg.optString("type"))) {
                        updateNotif("Kode sedang dipakai perangkat lain");
                    } else if ("SYNC_RULES".equals(msg.optString("type"))) {
                        activeRules = msg.getJSONObject("rules");
                        sentryModeActive = activeRules.optBoolean("SENTRY_MODE", false);
                    } else if ("COMMAND".equals(msg.optString("type"))) {
                        handleRemoteCommand(msg);
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public void onClosed(WebSocket ws, int codeClose, String reason) {
                updateNotif("Koneksi terputus, menyambung ulang...");
                mainHandler.postDelayed(() -> connectWebSocket(), 4000);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                updateNotif("Menunggu jaringan untuk konek...");
                mainHandler.postDelayed(() -> connectWebSocket(), 4000);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void handleRemoteCommand(JSONObject msg) {
        String action = msg.optString("action");
        SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
        String lostMsg = sp.getString("lostMsg", "");
        String backupPhone = sp.getString("backupPhone", "");

        mainHandler.post(() -> {
            switch (action) {
                case "LOCKDOWN":
                    lockDeviceScreen();
                    SirenPlayer.start(this);
                    overlayManager.show("LOCKDOWN", lostMsg, backupPhone);
                    EvidenceCollector.captureAndDispatch(this, "Kunci Paksa dari Controller", this::sendPacket);
                    break;
                case "CUSTOM_LOCK":
                    lockDeviceScreen();
                    if (msg.optBoolean("playSiren", false)) SirenPlayer.start(this);
                    else SirenPlayer.stop();
                    overlayManager.showCustom(
                            msg.optString("title", "PERANGKAT TERKUNCI"),
                            msg.optString("message", lostMsg),
                            msg.optString("bgColor", "#4A1C1C"),
                            msg.optBoolean("showCall", true) ? backupPhone : ""
                    );
                    break;
                case "SPEAK_TTS":
                    VoiceAnnouncer.speakText(this, msg.optString("text", ""));
                    break;
                case "PLAY_VOICE":
                    VoiceAnnouncer.playAudioBase64(this, msg.optString("audioBase64", ""));
                    break;
                case "RECORD_AMBIENT":
                    EnvironmentScanner.recordAmbientAudio(this, 10, b64 -> {
                        try {
                            JSONObject pkt = new JSONObject();
                            pkt.put("type", "AMBIENT_AUDIO");
                            pkt.put("audioBase64", b64);
                            sendPacket(pkt);
                        } catch (Exception ignored) {}
                    });
                    break;
                case "UNLOCK":
                    SirenPlayer.stop();
                    overlayManager.hide();
                    break;
                case "MODE_STEALTH":
                    lockDeviceScreen();
                    overlayManager.show("STEALTH", lostMsg, backupPhone);
                    break;
                case "MODE_LOST":
                    lockDeviceScreen();
                    SirenPlayer.stop();
                    overlayManager.show("LOST_FOUND", lostMsg, backupPhone);
                    break;
                case "CAPTURE_NOW":
                    EvidenceCollector.captureAndDispatch(this, "Permintaan Lacak & Radar Wi-Fi Manual", this::sendPacket);
                    break;
                case "SIREN_OFF":
                    SirenPlayer.stop();
                    break;
            }
        });
    }

    private void triggerGraceAlert(String code, String reason) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE));

        mainHandler.postDelayed(() -> {
            if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                executeEmergency(reason, true, "LOCKDOWN");
            }
        }, 3500);
    }

    private void executeEmergency(String reason, boolean playSiren, String screenMode) {
        mainHandler.post(() -> {
            SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
            if (playSiren) SirenPlayer.start(this);
            if (screenMode != null) {
                lockDeviceScreen();
                overlayManager.show(screenMode, sp.getString("lostMsg", ""), sp.getString("backupPhone", ""));
            }
            EvidenceCollector.captureAndDispatch(this, reason, this::sendPacket);
        });
    }

    @SuppressLint("MissingPermission")
    private void sendEmergencySms(String prefix) {
        SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
        String phone = sp.getString("backupPhone", "");
        if (!phone.isEmpty()) sendEmergencySmsTo(phone, prefix);
    }

    @SuppressLint("MissingPermission")
    private void sendEmergencySmsTo(String phone, String prefix) {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            String link = loc != null ? "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude() : "GPS belum terkunci";
            SmsManager.getDefault().sendTextMessage(phone, null, prefix + " " + link, null, null);
        } catch (Exception ignored) {}
    }

    private void lockDeviceScreen() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(admin)) dpm.lockNow();
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void sendTelemetry(int battery, boolean charging, boolean usbPc, boolean locked) {
        if (webSocket == null) return;
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            boolean simReady = tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY;

            double lat = 0, lng = 0;
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc != null) { lat = loc.getLatitude(); lng = loc.getLongitude(); }

            JSONObject data = new JSONObject();
            data.put("battery", battery);
            data.put("charging", charging);
            data.put("usbPc", usbPc);
            data.put("locked", locked);
            data.put("simReady", simReady);
            data.put("lat", lat);
            data.put("lng", lng);

            JSONObject packet = new JSONObject();
            packet.put("type", "TELEMETRY");
            packet.put("data", data);
            sendPacket(packet);
        } catch (Exception ignored) {}
    }

    private void sendPacket(JSONObject obj) {
        if (webSocket != null) {
            boolean sent = webSocket.send(obj.toString());
            if (!sent && "ALERT".equals(obj.optString("type"))) {
                EvidenceCollector.saveToOfflineVault(this, obj);
            }
        }
    }

    private boolean isRuleEnabled(String key) {
        return activeRules.optBoolean(key, true);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked()) return;

        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            isPocketCovered = event.values[0] < event.sensor.getMaximumRange();
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && !isPocketCovered && isRuleEnabled("POCKET_SNATCH")) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            double gForce = Math.sqrt(x * x + y * y + z * z);
            if (gForce > 26.0) {
                triggerGraceAlert("POCKET_SNATCH", "Tarikan saku mendadak terdeteksi!");
            }
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION && sentryModeActive) {
            float lx = event.values[0], ly = event.values[1], lz = event.values[2];
            double move = Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (move > 2.2) {
                sentryModeActive = false;
                triggerGraceAlert("SENTRY_MOVED", "Mode Penjaga Meja: Perangkat digeser!");
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        unregisterReceiver(systemReceiver);
        sensorManager.unregisterListener(this);
        SirenPlayer.stop();
        overlayManager.hide();
        super.onDestroy();
    }
}
