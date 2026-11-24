package com.fanz.guard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.view.Surface;
import okhttp3.*;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.Collections;

public class EvidenceCollector {
    private static final OkHttpClient httpClient = new OkHttpClient();

    public interface Callback {
        void onCollected(JSONObject alertPayload);
    }

    @SuppressLint("MissingPermission")
    public static void captureAndDispatch(Context context, String reason, Callback callback) {
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

        final double finalLat = lat;
        final double finalLng = lng;

        captureFrontCamera(context, (jpegBytes) -> {
            String base64 = jpegBytes != null ? Base64.encodeToString(jpegBytes, Base64.NO_WRAP) : "";
            try {
                JSONObject data = new JSONObject();
                data.put("reason", reason);
                data.put("lat", finalLat);
                data.put("lng", finalLng);
                data.put("photoBase64", base64);

                JSONObject packet = new JSONObject();
                packet.put("type", "ALERT");
                packet.put("data", data);

                if (callback != null) callback.onCollected(packet);
                sendToTelegram(context, reason, finalLat, finalLng, jpegBytes);
            } catch (Exception ignored) {}
        });
    }

    private interface PhotoCallback {
        void onPhoto(byte[] jpegBytes);
    }

    @SuppressLint("MissingPermission")
    private static void captureFrontCamera(Context context, PhotoCallback cb) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String frontCamId = null;
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCamId = id;
                    break;
                }
            }
            if (frontCamId == null) { cb.onPhoto(null); return; }

            HandlerThread thread = new HandlerThread("CamThread");
            thread.start();
            Handler handler = new Handler(thread.getLooper());

            ImageReader reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(r -> {
                try (Image img = r.acquireLatestImage()) {
                    if (img != null) {
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        cb.onPhoto(bytes);
                    }
                } catch (Exception e) {
                    cb.onPhoto(null);
                } finally {
                    thread.quitSafely();
                }
            }, handler);

            manager.openCamera(frontCamId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    try {
                        Surface surface = reader.getSurface();
                        CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        req.addTarget(surface);
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                        camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                try {
                                    session.capture(req.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override
                                        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                                            camera.close();
                                        }
                                    }, handler);
                                } catch (Exception e) {
                                    camera.close();
                                    cb.onPhoto(null);
                                }
                            }
                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                camera.close();
                                cb.onPhoto(null);
                            }
                        }, handler);
                    } catch (Exception e) {
                        camera.close();
                        cb.onPhoto(null);
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); cb.onPhoto(null); }
            }, handler);
        } catch (Exception e) {
            cb.onPhoto(null);
        }
    }

    private static void sendToTelegram(Context context, String reason, double lat, double lng, byte[] jpegBytes) {
        SharedPreferences sp = context.getSharedPreferences("aegis_cfg", Context.MODE_PRIVATE);
        String botToken = sp.getString("tgToken", "");
        String chatId = sp.getString("tgChatId", "");
        if (botToken.isEmpty() || chatId.isEmpty()) return;

        String caption = "AEGIS ALERT: " + reason + "\nLokasi: https://maps.google.com/?q=" + lat + "," + lng;
        if (jpegBytes != null && jpegBytes.length > 0) {
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("caption", caption)
                    .addFormDataPart("photo", "intruder.jpg", RequestBody.create(jpegBytes, MediaType.parse("image/jpeg")))
                    .build();
            httpClient.newCall(new Request.Builder()
                    .url("https://api.telegram.org/bot" + botToken + "/sendPhoto")
                    .post(body).build()).enqueue(new CallbackAdapter());
        } else {
            RequestBody body = new FormBody.Builder()
                    .add("chat_id", chatId)
                    .add("text", caption)
                    .build();
            httpClient.newCall(new Request.Builder()
                    .url("https://api.telegram.org/bot" + botToken + "/sendMessage")
                    .post(body).build()).enqueue(new CallbackAdapter());
        }
    }

    private static class CallbackAdapter implements okhttp3.Callback {
        @Override public void onFailure(Call call, java.io.IOException e) {}
        @Override public void onResponse(Call call, Response response) { response.close(); }
    }
}
