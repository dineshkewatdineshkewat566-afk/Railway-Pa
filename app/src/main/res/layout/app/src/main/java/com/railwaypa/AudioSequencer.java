package com.railwaypa;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AudioSequencer {

    public interface Listener {
        void onStarted();
        void onFinished();
        void onMessage(String msg);
    }

    public static class Segment {
        final String clip;
        final String text;
        final Locale locale;
        final int pauseMs;

        private Segment(String clip, String text, Locale locale, int pauseMs) {
            this.clip = clip;
            this.text = text;
            this.locale = locale;
            this.pauseMs = pauseMs;
        }

        public static Segment speech(String clip, String text, Locale locale) {
            return new Segment(clip, text, locale, 0);
        }

        public static Segment clipOnly(String clip) {
            return new Segment(clip, null, null, 0);
        }

        public static Segment pause(int ms) {
            return new Segment(null, null, null, ms);
        }
    }

    private static final int LOOP_GAP_MS = 3000;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;
    private String currentUtterance = null;

    private MediaPlayer player;
    private List<Segment> queue = new ArrayList<Segment>();
    private int index = 0;
    private boolean playing = false;
    private boolean loop = false;
    private int token = 0;

    public AudioSequencer(Context context, Listener l) {
        this.ctx = context.getApplicationContext();
        this.listener = l;
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                } else {
                    ttsFailed = true;
                    msg("Text-to-speech unavailable; only recorded clips will play");
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }

            @Override public void onDone(final String id) { advanceFromTts(id); }

            @Override public void onError(final String id) { advanceFromTts(id); }
        });
    }

    private void advanceFromTts(final String id) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (id != null && id.equals(currentUtterance)) {
                    currentUtterance = null;
                    next(token);
                }
            }
        });
    }

    public void play(List<Segment> segments, boolean loopIt) {
        stopInternal();
        if (segments == null || segments.isEmpty()) {
            return;
        }
        queue = new ArrayList<Segment>(segments);
        loop = loopIt;
        index = 0;
        playing = true;
        if (listener != null) listener.onStarted();
        next(token);
    }

    public void stop() {
        boolean was = playing;
        stopInternal();
        if (was && listener != null) listener.onFinished();
    }

    public boolean isPlaying() {
        return playing;
    }

    public void release() {
        stopInternal();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    public boolean rawExists(String name) {
        return name != null && ctx.getResources()
                .getIdentifier(name, "raw", ctx.getPackageName()) != 0;
    }

    private void stopInternal() {
        playing = false;
        token++;
        currentUtterance = null;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        if (tts != null) tts.stop();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                if (player.isPlaying()) player.stop();
            } catch (Exception e) {
                // ignore
            }
            player.release();
            player = null;
        }
    }

    private void finish() {
        playing = false;
        releasePlayer();
        if (listener != null) listener.onFinished();
    }

    private void postNext(final int t, int delayMs) {
        main.postDelayed(new Runnable() {
            @Override public void run() { next(t); }
        }, delayMs);
    }

    private void next(final int t) {
        if (t != token || !playing) return;

        if (index >= queue.size()) {
            if (loop) {
                index = 0;
                postNext(t, LOOP_GAP_MS);
            } else {
                finish();
            }
            return;
        }

        Segment s = queue.get(index++);

        if (s.pauseMs > 0) {
            postNext(t, s.pauseMs);
            return;
        }

        int resId = (s.clip == null) ? 0 : ctx.getResources()
                .getIdentifier(s.clip, "raw", ctx.getPackageName());

        if (resId != 0 && playClip(resId, t)) {
            return;
        }
        if (s.text != null) {
            speak(s, t);
        } else {
            next(t);
        }
    }

    private boolean playClip(int resId, final int t) {
        try {
            MediaPlayer mp = MediaPlayer.create(ctx, resId);
            if (mp == null) return false;
            player = mp;
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    releasePlayer();
                    next(t);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    releasePlayer();
                    next(t);
                    return true;
                }
            });
            mp.start();
            return true;
        } catch (Exception e) {
            releasePlayer();
            return false;
        }
    }

    private void speak(Segment s, int t) {
        if (ttsFailed || tts == null) {
            next(t);
            return;
        }
        if (!ttsReady) {
            index--;
            postNext(t, 300);
            return;
        }
        int r = tts.setLanguage(s.locale);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            msg("Voice not installed for " + s.locale.getDisplayLanguage()
                    + " (install it in Settings > Text-to-speech)");
            next(t);
            return;
        }
        tts.setSpeechRate(0.85f);
        tts.setPitch(0.9f);
        currentUtterance = "seg" + t + "_" + index;
        int res = tts.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, currentUtterance);
        if (res != TextToSpeech.SUCCESS) {
            currentUtterance = null;
            next(t);
        }
    }

    private void msg(String m) {
        if (listener != null) listener.onMessage(m);
    }
      }package com.railwaypa;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AudioSequencer {

    public interface Listener {
        void onStarted();
        void onFinished();
        void onMessage(String msg);
    }

    public static class Segment {
        final String clip;
        final String text;
        final Locale locale;
        final int pauseMs;

        private Segment(String clip, String text, Locale locale, int pauseMs) {
            this.clip = clip;
            this.text = text;
            this.locale = locale;
            this.pauseMs = pauseMs;
        }

        public static Segment speech(String clip, String text, Locale locale) {
            return new Segment(clip, text, locale, 0);
        }

        public static Segment clipOnly(String clip) {
            return new Segment(clip, null, null, 0);
        }

        public static Segment pause(int ms) {
            return new Segment(null, null, null, ms);
        }
    }

    private static final int LOOP_GAP_MS = 3000;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;
    private String currentUtterance = null;

    private MediaPlayer player;
    private List<Segment> queue = new ArrayList<Segment>();
    private int index = 0;
    private boolean playing = false;
    private boolean loop = false;
    private int token = 0;

    public AudioSequencer(Context context, Listener l) {
        this.ctx = context.getApplicationContext();
        this.listener = l;
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                } else {
                    ttsFailed = true;
                    msg("Text-to-speech unavailable; only recorded clips will play");
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }

            @Override public void onDone(final String id) { advanceFromTts(id); }

            @Override public void onError(final String id) { advanceFromTts(id); }
        });
    }

    private void advanceFromTts(final String id) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (id != null && id.equals(currentUtterance)) {
                    currentUtterance = null;
                    next(token);
                }
            }
        });
    }

    public void play(List<Segment> segments, boolean loopIt) {
        stopInternal();
        if (segments == null || segments.isEmpty()) {
            return;
        }
        queue = new ArrayList<Segment>(segments);
        loop = loopIt;
        index = 0;
        playing = true;
        if (listener != null) listener.onStarted();
        next(token);
    }

    public void stop() {
        boolean was = playing;
        stopInternal();
        if (was && listener != null) listener.onFinished();
    }

    public boolean isPlaying() {
        return playing;
    }

    public void release() {
        stopInternal();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    public boolean rawExists(String name) {
        return name != null && ctx.getResources()
                .getIdentifier(name, "raw", ctx.getPackageName()) != 0;
    }

    private void stopInternal() {
        playing = false;
        token++;
        currentUtterance = null;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        if (tts != null) tts.stop();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                if (player.isPlaying()) player.stop();
            } catch (Exception e) {
                // ignore
            }
            player.release();
            player = null;
        }
    }

    private void finish() {
        playing = false;
        releasePlayer();
        if (listener != null) listener.onFinished();
    }

    private void postNext(final int t, int delayMs) {
        main.postDelayed(new Runnable() {
            @Override public void run() { next(t); }
        }, delayMs);
    }

    private void next(final int t) {
        if (t != token || !playing) return;

        if (index >= queue.size()) {
            if (loop) {
                index = 0;
                postNext(t, LOOP_GAP_MS);
            } else {
                finish();
            }
            return;
        }

        Segment s = queue.get(index++);

        if (s.pauseMs > 0) {
            postNext(t, s.pauseMs);
            return;
        }

        int resId = (s.clip == null) ? 0 : ctx.getResources()
                .getIdentifier(s.clip, "raw", ctx.getPackageName());

        if (resId != 0 && playClip(resId, t)) {
            return;
        }
        if (s.text != null) {
            speak(s, t);
        } else {
            next(t);
        }
    }

    private boolean playClip(int resId, final int t) {
        try {
            MediaPlayer mp = MediaPlayer.create(ctx, resId);
            if (mp == null) return false;
            player = mp;
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    releasePlayer();
                    next(t);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    releasePlayer();
                    next(t);
                    return true;
                }
            });
            mp.start();
            return true;
        } catch (Exception e) {
            releasePlayer();
            return false;
        }
    }

    private void speak(Segment s, int t) {
        if (ttsFailed || tts == null) {
            next(t);
            return;
        }
        if (!ttsReady) {
            index--;
            postNext(t, 300);
            return;
        }
        int r = tts.setLanguage(s.locale);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            msg("Voice not installed for " + s.locale.getDisplayLanguage()
                    + " (install it in Settings > Text-to-speech)");
            next(t);
            return;
        }
        tts.setSpeechRate(0.85f);
        tts.setPitch(0.9f);
        currentUtterance = "seg" + t + "_" + index;
        int res = tts.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, currentUtterance);
        if (res != TextToSpeech.SUCCESS) {
            currentUtterance = null;
            next(t);
        }
    }

    private void msg(String m) {
        if (listener != null) listener.onMessage(m);
    }
  }
