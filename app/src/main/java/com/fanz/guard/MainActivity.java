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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/config.html");
        setContentView(webView);
    }

    public class Bridge {
        @JavascriptInterface
        public void requestBasicPerms() {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.CAMERA);
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            perms.add(Manifest.permission.RECORD_AUDIO);
            perms.add(Manifest.permission.READ_PHONE_STATE);
            perms.add(Manifest.permission.CALL_PHONE);
            perms.add(Manifest.permission.SEND_SMS);
            perms.add(Manifest.permission.RECEIVE_SMS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            ActivityCompat.requestPermissions(MainActivity.this, perms.toArray(new String[0]), 101);
        }

        @JavascriptInterface
        public void requestOverlayPerm() {
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                toast("Izin Tampil di Atas Aplikasi sudah aktif");
            }
        }

        @JavascriptInterface
        public void requestDeviceAdmin() {
            ComponentName admin = new ComponentName(MainActivity.this, AdminReceiver.class);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Melindungi Aegis Guard agar tidak bisa dihapus paksa.");
            startActivity(intent);
        }

        @JavascriptInterface
        public void saveConfig(String url, String roomId, String token, String botToken, String chatId, String backupPhone, String lostMsg) {
            getSharedPreferences("aegis_cfg", MODE_PRIVATE).edit()
                    .putString("serverUrl", url.trim())
                    .putString("roomId", roomId.trim())
                    .putString("roomToken", token.trim())
                    .putString("botToken", botToken.trim())
                    .putString("chatId", chatId.trim())
                    .putString("backupPhone", backupPhone.trim())
                    .putString("lostMsg", lostMsg.trim())
                    .apply();
            toast("Konfigurasi disimpan");
        }

        @JavascriptInterface
        public String getConfig() {
            SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
            try {
                JSONObject obj = new JSONObject();
                obj.put("serverUrl", sp.getString("serverUrl", "ws://panel-free1.duckdns.top:4145"));
                obj.put("roomId", sp.getString("roomId", ""));
                obj.put("roomToken", sp.getString("roomToken", ""));
                obj.put("botToken", sp.getString("botToken", ""));
                obj.put("chatId", sp.getString("chatId", ""));
                obj.put("backupPhone", sp.getString("backupPhone", ""));
                obj.put("lostMsg", sp.getString("lostMsg", "HP ini hilang. Tekan tombol di bawah untuk menghubungi pemilik."));
                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void startGuard() {
            Intent svc = new Intent(MainActivity.this, GuardService.class);
            ContextCompat.startForegroundService(MainActivity.this, svc);
            toast("Memulai layanan penjagaan...");
        }

        @JavascriptInterface
        public void stopGuard() {
            stopService(new Intent(MainActivity.this, GuardService.class));
            toast("Penjagaan dimatikan");
        }

        private void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
}
