package com.fanz.guard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/config.html");
        setContentView(webView);
    }

    public class Bridge {
        @JavascriptInterface
        public String loadConfig() {
            SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
            JSONObject obj = new JSONObject();
            try {
                obj.put("serverUrl", sp.getString("serverUrl", ""));
                obj.put("roomId", sp.getString("roomId", ""));
                obj.put("roomToken", sp.getString("roomToken", ""));
                obj.put("tgToken", sp.getString("tgToken", ""));
                obj.put("tgChatId", sp.getString("tgChatId", ""));
                obj.put("backupPhone", sp.getString("backupPhone", ""));
                obj.put("lostMsg", sp.getString("lostMsg", ""));
            } catch (Exception ignored) {}
            return obj.toString();
        }

        @JavascriptInterface
        public void saveConfig(String jsonStr) {
            try {
                JSONObject obj = new JSONObject(jsonStr);
                getSharedPreferences("aegis_cfg", MODE_PRIVATE).edit()
                        .putString("serverUrl", obj.optString("serverUrl"))
                        .putString("roomId", obj.optString("roomId"))
                        .putString("roomToken", obj.optString("roomToken"))
                        .putString("tgToken", obj.optString("tgToken"))
                        .putString("tgChatId", obj.optString("tgChatId"))
                        .putString("backupPhone", obj.optString("backupPhone"))
                        .putString("lostMsg", obj.optString("lostMsg"))
                        .apply();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Konfigurasi disimpan!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Gagal menyimpan", Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void requestAllPermissions() {
            String[] perms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.POST_NOTIFICATIONS
                    } :
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE
                    };
            ActivityCompat.requestPermissions(MainActivity.this, perms, 101);
        }

        @JavascriptInterface
        public void requestOverlay() {
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Izin Overlay sudah aktif", Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void requestDeviceAdmin() {
            ComponentName admin = new ComponentName(MainActivity.this, AdminReceiver.class);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Mencegah aplikasi dihapus paksa saat HP dicuri.");
            startActivity(intent);
        }

        @JavascriptInterface
        public void startGuard() {
            Intent serviceIntent = new Intent(MainActivity.this, GuardService.class);
            startForegroundService(serviceIntent);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Sistem Penjagaan Aktif!", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void stopGuard() {
            stopService(new Intent(MainActivity.this, GuardService.class));
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Penjagaan Dimatikan", Toast.LENGTH_SHORT).show());
        }
    }
}
