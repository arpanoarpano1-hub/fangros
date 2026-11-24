package com.fanz.guard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaRecorder;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

public class EnvironmentScanner {
    public interface AudioCallback {
        void onAudioReady(String base64Audio);
    }

    @SuppressLint("MissingPermission")
    public static String scanNearbyWifi(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "Wi-Fi tidak tersedia";
            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) return "Tidak ada Wi-Fi terdeteksi";

            StringBuilder sb = new StringBuilder();
            int limit = Math.min(results.size(), 5);
            for (int i = 0; i < limit; i++) {
                ScanResult r = results.get(i);
                String ssid = (r.SSID == null || r.SSID.isEmpty()) ? "<Hidden>" : r.SSID;
                sb.append(ssid).append(" (").append(r.level).append("dBm)");
                if (i < limit - 1) sb.append(", ");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Gagal memindai Wi-Fi";
        }
    }

    public static void recordAmbientAudio(Context context, int seconds, AudioCallback callback) {
        try {
            File outFile = new File(context.getCacheDir(), "ambient_box.m4a");
            if (outFile.exists()) outFile.delete();

            MediaRecorder recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    recorder.stop();
                    recorder.release();
                    byte[] bytes = new byte[(int) outFile.length()];
                    try (FileInputStream fis = new FileInputStream(outFile)) {
                        fis.read(bytes);
                    }
                    callback.onAudioReady(Base64.encodeToString(bytes, Base64.NO_WRAP));
                } catch (Exception ignored) {}
            }, seconds * 1000L);
        } catch (Exception ignored) {}
    }
}
