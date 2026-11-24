package com.fanz.guard;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

public class VoiceAnnouncer {
    private static TextToSpeech tts;
    private static MediaPlayer mediaPlayer;

    private static void forceMaxAlarmVolume(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }
        } catch (Exception ignored) {}
    }

    public static void speakText(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return;
        forceMaxAlarmVolume(context);

        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }

        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                int res = tts.setLanguage(new Locale("id", "ID"));
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());

                Bundle params = new Bundle();
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "aegis_voice_tts");
            }
        });
    }

    public static void playAudioBase64(Context context, String base64Audio) {
        if (base64Audio == null || base64Audio.isEmpty()) return;
        forceMaxAlarmVolume(context);

        try {
            byte[] audioBytes = Base64.decode(base64Audio, Base64.DEFAULT);
            File tempFile = new File(context.getCacheDir(), "remote_voice.webm");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioBytes);
            }

            if (mediaPlayer != null) {
                try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.start();
        } catch (Exception ignored) {}
    }
}
