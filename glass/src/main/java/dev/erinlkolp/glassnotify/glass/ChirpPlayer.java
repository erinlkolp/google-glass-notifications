package dev.erinlkolp.glassnotify.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Plays the chirp through the bone conduction transducer.
 *
 * Glass has no vibration motor and the transducer produces no tactile
 * sensation below its speech band, so this alert is heard rather than felt.
 * Spec section 3.1.
 *
 * Every failure path here is silent and non-fatal: an audio fault must never
 * take down the link service or stop the interrupt card from showing.
 */
public final class ChirpPlayer {

    private static final String TAG = "GlassNotify";

    /** Written once per install, then never again, so a later manual change sticks. */
    static final String KEY_VOLUME_INITIALIZED = "chirp_volume_initialized";

    /**
     * Of a maximum of 7. The device ships at 7, so this is a deliberate step
     * down from the full-scale tones auditioned on hardware. Spec section 6.5.
     */
    static final int INITIAL_VOLUME_INDEX = 5;

    private final AudioManager audioManager;
    private final short[] pcm;

    public ChirpPlayer(Context context, SharedPreferences prefs) {
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        this.pcm = ChirpTone.renderDefault();
        initializeVolumeOnce(prefs);
    }

    /**
     * Plays the chirp if this tier calls for one. Returns immediately; playback
     * runs on its own thread, because AudioTrack.write() blocks and both
     * callers are on the main thread with a card to draw.
     */
    public void playIfNeeded(Tier tier) {
        if (tier == null || !tier.chirps()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                play();
            }
        }, "glassnotify-chirp").start();
    }

    private void play() {
        AudioTrack track = null;
        try {
            track = new AudioTrack(AudioManager.STREAM_NOTIFICATION, ChirpTone.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    pcm.length * 2, AudioTrack.MODE_STATIC);

            if (track.getState() == AudioTrack.STATE_UNINITIALIZED) {
                Log.w(TAG, "chirp: AudioTrack would not initialize");
                return;
            }

            track.write(pcm, 0, pcm.length);
            track.play();

            // Releasing a MODE_STATIC track mid-playback truncates the tone, so
            // wait out its length plus a margin before letting finally run.
            Thread.sleep(ChirpTone.DURATION_MS + 100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "chirp: playback failed", e);
        } finally {
            if (track != null) {
                track.release();
            }
        }
    }

    /**
     * The Glass volume keys are unmapped on this AOSP build, so without this
     * there is no way to set the level at all. Written once and then left
     * alone, so "adb shell settings put system volume_notification N" sticks.
     */
    private void initializeVolumeOnce(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_VOLUME_INITIALIZED, false)) {
            return;
        }
        try {
            audioManager.setStreamVolume(
                    AudioManager.STREAM_NOTIFICATION, INITIAL_VOLUME_INDEX, 0);
            Log.i(TAG, "chirp: set initial notification volume to " + INITIAL_VOLUME_INDEX);
        } catch (RuntimeException e) {
            Log.w(TAG, "chirp: could not set initial notification volume", e);
        }
        prefs.edit().putBoolean(KEY_VOLUME_INITIALIZED, true).apply();
    }
}
