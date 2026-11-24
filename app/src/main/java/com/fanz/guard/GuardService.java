package com.fanz.guard;

import android.annotation.SuppressLint;
import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.hardware.*;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.telephony.TelephonyManager;
import androidx.core.app.NotificationCompat;
import okhttp3.*;
import org.json.JSONObject;

public class GuardService extends Service implements SensorEventListener {
    private WebSocket webSocket;
    private OverlayManager overlayManager;
    private SensorManager sensorManager;
    private KeyguardManager keyguardManager;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isPocketCovered = false;
    private boolean lastGaspTriggered = false;
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
        if (prox != null) sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL);
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction("android.intent.action.SIM_STATE_CHANGED");
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(systemReceiver, filter);

        connectWebSocket();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "TRIGGER_INTRUDER_PIN".equals(intent.getAction()) && isRuleEnabled("WRONG_PIN")) {
            executeEmergency("Percobaan salah PIN/Pola berulang kali!", false, "STEALTH");
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "aegis_guard_channel";
        NotificationChannel channel = new NotificationChannel(channelId, "Aegis Security Monitor", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aegis Guard Aktif")
                .setContentText("Perlindungan sensor & anti-pencurian berjalan.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build();
        startForeground(1, notification);
    }

    private void connectWebSocket() {
        SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
        String url = sp.getString("serverUrl", "");
        String roomId = sp.getString("roomId", "");
        String token = sp.getString("roomToken", "");
        if (url.isEmpty() || roomId.isEmpty() || token.isEmpty()) return;

        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                try {
                    JSONObject reg = new JSONObject();
                    reg.put("type", "REGISTER");
                    reg.put("role", "guard");
                    reg.put("roomId", roomId);
                    reg.put("token", token);
                    ws.send(reg.toString());
                } catch (Exception ignored) {}
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject msg = new JSONObject(text);
                    if ("SYNC_RULES".equals(msg.optString("type"))) {
                        activeRules = msg.getJSONObject("rules");
                    } else if ("COMMAND".equals(msg.optString("type"))) {
                        handleRemoteCommand(msg.optString("action"));
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                mainHandler.postDelayed(() -> connectWebSocket(), 4000);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                mainHandler.postDelayed(() -> connectWebSocket(), 4000);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void handleRemoteCommand(String action) {
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
                    EvidenceCollector.captureAndDispatch(this, "Permintaan Lacak Manual", this::sendPacket);
                    break;
                case "SIREN_OFF":
                    SirenPlayer.stop();
                    break;
                case "CALL_BACKUP":
                    if (!backupPhone.isEmpty()) {
                        Intent call = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + backupPhone));
                        call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(call);
                    }
                    break;
            }
        });
    }

    private void triggerGraceAlert(String code, String reason) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));

        mainHandler.postDelayed(() -> {
            if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                executeEmergency(reason, true, "LOCKDOWN");
            }
        }, 4000);
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
        if (webSocket != null) webSocket.send(obj.toString());
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
