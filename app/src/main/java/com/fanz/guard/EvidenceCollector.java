package com.fanz.guard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationManager;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;

public class EvidenceCollector {
    public interface Callback {
        void onComplete(JSONObject payload);
    }

    @SuppressLint("MissingPermission")
    public static void captureAndDispatch(Context context, String reason, Callback callback) {
        new Thread(() -> {
            double lat = 0.0, lng = 0.0;
            try {
                LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    lat = loc.getLatitude();
                    lng = loc.getLongitude();
                }
            } catch (Exception ignored) {}

            String wifiList = EnvironmentScanner.scanNearbyWifi(context);
            String photoBase64 = snapFrontCamera();

            try {
                JSONObject data = new JSONObject();
                data.put("reason", reason);
                data.put("lat", lat);
                data.put("lng", lng);
                data.put("wifi", wifiList);
                data.put("photoBase64", photoBase64);
                data.put("timestamp", System.currentTimeMillis());

                JSONObject packet = new JSONObject();
                packet.put("type", "ALERT");
                packet.put("data", data);

                saveToOfflineVault(context, packet);
                callback.onComplete(packet);
            } catch (Exception ignored) {}
        }).start();
    }

    public static void saveToOfflineVault(Context context, JSONObject packet) {
        try {
            SharedPreferences sp = context.getSharedPreferences("aegis_vault", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("pending", "[]"));
            arr.put(packet);
            if (arr.length() > 10) arr.remove(0);
            sp.edit().putString("pending", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void flushOfflineVault(Context context, Callback sender) {
        try {
            SharedPreferences sp = context.getSharedPreferences("aegis_vault", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("pending", "[]"));
            if (arr.length() == 0) return;
            for (int i = 0; i < arr.length(); i++) {
                sender.onComplete(arr.getJSONObject(i));
            }
            sp.edit().remove("pending").apply();
        } catch (Exception ignored) {}
    }

    private static String snapFrontCamera() {
        Camera camera = null;
        try {
            int frontId = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontId = i;
                    break;
                }
            }
            if (frontId == -1) return "";

            camera = Camera.open(frontId);
            SurfaceTexture dummy = new SurfaceTexture(0);
            camera.setPreviewTexture(dummy);
            camera.startPreview();

            final byte[][] captured = new byte[1][];
            camera.takePicture(null, null, (data, cam) -> captured[0] = data);

            long wait = 0;
            while (captured[0] == null && wait < 2500) {
                Thread.sleep(100);
                wait += 100;
            }
            if (captured[0] != null) {
                Bitmap bmp = BitmapFactory.decodeByteArray(captured[0], 0, captured[0].length);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, 480, 640, true);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 65, out);
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Exception ignored) {
        } finally {
            if (camera != null) {
                try { camera.stopPreview(); camera.release(); } catch (Exception ignored) {}
            }
        }
        return "";
    }
}
