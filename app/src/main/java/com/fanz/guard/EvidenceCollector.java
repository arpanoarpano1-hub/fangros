package com.fanz.guard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.*;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

public class EvidenceCollector {
    public interface PacketCallback {
        void onPacketReady(JSONObject packet);
    }

    @SuppressLint("MissingPermission")
    public static void captureAndDispatch(Context context, String reason, PacketCallback callback) {
        String wifiList = EnvironmentScanner.scanNearbyWifi(context);

        double lat = 0.0, lng = 0.0;
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc != null) {
                    lat = loc.getLatitude();
                    lng = loc.getLongitude();
                }
            }
        } catch (Exception ignored) {}

        final double finalLat = lat;
        final double finalLng = lng;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            dispatchAlert(context, reason + " (Izin kamera belum aktif)", finalLat, finalLng, wifiList, "", callback);
            return;
        }

        HandlerThread thread = new HandlerThread("AegisCamThread");
        thread.start();
        Handler bgHandler = new Handler(thread.getLooper());

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                thread.quitSafely();
                dispatchAlert(context, reason, finalLat, finalLng, wifiList, "", callback);
                return;
            }

            String targetCamId = null;
            int sensorOrientation = 270;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    targetCamId = id;
                    Integer orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (orient != null) sensorOrientation = orient;
                    break;
                }
            }
            if (targetCamId == null && manager.getCameraIdList().length > 0) {
                targetCamId = manager.getCameraIdList()[0];
                CameraCharacteristics chars = manager.getCameraCharacteristics(targetCamId);
                Integer orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orient != null) sensorOrientation = orient;
            }
            if (targetCamId == null) {
                thread.quitSafely();
                dispatchAlert(context, reason, finalLat, finalLng, wifiList, "", callback);
                return;
            }

            final int rotationDeg = sensorOrientation;
            ImageReader reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
            final CameraDevice[] camHolder = new CameraDevice[1];
            final boolean[] finished = new boolean[]{false};

            reader.setOnImageAvailableListener(r -> {
                if (finished[0]) return;
                finished[0] = true;
                String b64 = "";
                try (Image img = r.acquireLatestImage()) {
                    if (img != null) {
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] raw = new byte[buf.remaining()];
                        buf.get(raw);
                        b64 = compressToBase64(raw, rotationDeg);
                    }
                } catch (Exception ignored) {}

                cleanup(camHolder[0], r, thread);
                dispatchAlert(context, reason, finalLat, finalLng, wifiList, b64, callback);
            }, bgHandler);

            CameraDevice.StateCallback stateCb = new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    camHolder[0] = camera;
                    try {
                        camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            req.addTarget(reader.getSurface());
                                            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                            session.capture(req.build(), null, bgHandler);
                                        } catch (Exception e) {
                                            if (!finished[0]) {
                                                finished[0] = true;
                                                cleanup(camera, reader, thread);
                                                dispatchAlert(context, reason + " (Gagal memotret)", finalLat, finalLng, wifiList, "", callback);
                                            }
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        if (!finished[0]) {
                                            finished[0] = true;
                                            cleanup(camera, reader, thread);
                                            dispatchAlert(context, reason + " (Sesi kamera gagal)", finalLat, finalLng, wifiList, "", callback);
                                        }
                                    }
                                }, bgHandler);
                    } catch (Exception e) {
                        if (!finished[0]) {
                            finished[0] = true;
                            cleanup(camera, reader, thread);
                            dispatchAlert(context, reason + " (Kamera sibuk)", finalLat, finalLng, wifiList, "", callback);
                        }
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    if (!finished[0]) {
                        finished[0] = true;
                        cleanup(camera, reader, thread);
                        dispatchAlert(context, reason, finalLat, finalLng, wifiList, "", callback);
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    if (!finished[0]) {
                        finished[0] = true;
                        cleanup(camera, reader, thread);
                        dispatchAlert(context, reason + " (Kamera ditahan sistem: " + error + ")", finalLat, finalLng, wifiList, "", callback);
                    }
                }
            };

            manager.openCamera(targetCamId, stateCb, bgHandler);
        } catch (Exception e) {
            thread.quitSafely();
            dispatchAlert(context, reason, finalLat, finalLng, wifiList, "", callback);
        }
    }

    private static String compressToBase64(byte[] raw, int rotationDeg) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bmp == null) return Base64.encodeToString(raw, Base64.NO_WRAP);
            Matrix m = new Matrix();
            m.postRotate(rotationDeg);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            rotated.compress(Bitmap.CompressFormat.JPEG, 55, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        }
    }

    private static void cleanup(CameraDevice cam, ImageReader reader, HandlerThread thread) {
        try { if (cam != null) cam.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { thread.quitSafely(); } catch (Exception ignored) {}
    }

    private static void dispatchAlert(Context context, String reason, double lat, double lng, String wifi, String photoBase64, PacketCallback callback) {
        try {
            JSONObject data = new JSONObject();
            data.put("reason", reason);
            data.put("lat", lat);
            data.put("lng", lng);
            data.put("wifi", wifi);
            data.put("photoBase64", photoBase64);
            data.put("receivedAt", System.currentTimeMillis());

            JSONObject packet = new JSONObject();
            packet.put("type", "ALERT");
            packet.put("data", data);
            if (callback != null) callback.onPacketReady(packet);
        } catch (Exception ignored) {}
    }

    public static void saveToOfflineVault(Context context, JSONObject packet) {
        try {
            SharedPreferences sp = context.getSharedPreferences("aegis_vault", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("queue", "[]"));
            if (arr.length() < 10) {
                arr.put(packet);
                sp.edit().putString("queue", arr.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    public static void flushOfflineVault(Context context, PacketCallback callback) {
        try {
            SharedPreferences sp = context.getSharedPreferences("aegis_vault", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("queue", "[]"));
            if (arr.length() == 0) return;
            sp.edit().remove("queue").apply();
            for (int i = 0; i < arr.length(); i++) {
                if (callback != null) callback.onPacketReady(arr.getJSONObject(i));
            }
        } catch (Exception ignored) {}
    }
}
