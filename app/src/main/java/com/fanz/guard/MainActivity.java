package com.fanz.guard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
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
import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private static final String DEFAULT_WS = "ws://panel-free1.duckdns.top:4145";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
        if (sp.getString("roomId", "").isEmpty()) {
            String autoCode = String.valueOf(100000 + new Random().nextInt(900000));
            sp.edit()
                    .putString("serverUrl", DEFAULT_WS)
                    .putString("roomId", autoCode)
                    .putString("roomToken", autoCode)
                    .apply();
        }

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
        public void openAccessibility() {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }

        @JavascriptInterface
        public void copyCode(String code) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Aegis Code", code));
                toast("Kode " + code + " disalin");
            }
        }

        @JavascriptInterface
        public String getState() {
            SharedPreferences sp = getSharedPreferences("aegis_cfg", MODE_PRIVATE);
            try {
                JSONObject obj = new JSONObject();
                obj.put("code", sp.getString("roomId", ""));
                obj.put("backupPhone", sp.getString("backupPhone", ""));
                obj.put("lostMsg", sp.getString("lostMsg", "HP ini dilindungi Aegis Guard. Mohon hubungi pemilik untuk mengembalikan."));
                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void connectAndStart(String code, String backupPhone, String lostMsg) {
            String cleanCode = code == null ? "" : code.trim();
            if (cleanCode.isEmpty()) {
                toast("Isi kode koneksi terlebih dahulu");
                return;
            }
            getSharedPreferences("aegis_cfg", MODE_PRIVATE).edit()
                    .putString("serverUrl", DEFAULT_WS)
                    .putString("roomId", cleanCode)
                    .putString("roomToken", cleanCode)
                    .putString("backupPhone", backupPhone == null ? "" : backupPhone.trim())
                    .putString("lostMsg", lostMsg == null ? "" : lostMsg.trim())
                    .apply();

            Intent svc = new Intent(MainActivity.this, GuardService.class);
            ContextCompat.startForegroundService(MainActivity.this, svc);
            toast("Menghubungkan dengan kode " + cleanCode + "...");
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
