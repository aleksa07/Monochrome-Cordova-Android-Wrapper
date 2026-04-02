package com.monochrome.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MonoMediaSession";
    private MediaSessionCompat mediaSession;
    private static final String CHANNEL_ID = "media_playback";
    private Handler positionHandler;
    private Runnable positionPoller;
    private volatile boolean isPlaying = false;
    private volatile long lastPosition = 0;
    private volatile float lastRate = 1.0f;
    private volatile android.graphics.Bitmap currentArtwork = null;
    private volatile String currentTitle = "";
    private volatile String currentArtist = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate started");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting notification permission");
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                Log.d(TAG, "Notification permission already granted");
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            Log.d(TAG, "Notification channel created");
        }

        WebView webView = getBridge().getWebView();
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        mediaSession = new MediaSessionCompat(this, "MonochromeSession");
        mediaSession.setActive(true);
        Log.d(TAG, "MediaSession created and active");

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                Log.d(TAG, "MediaSession callback: onPlay");
                triggerMediaSessionAction("play");
            }
            @Override
            public void onPause() {
                Log.d(TAG, "MediaSession callback: onPause");
                triggerMediaSessionAction("pause");
            }
            @Override
            public void onSkipToNext() {
                Log.d(TAG, "MediaSession callback: onSkipToNext");
                triggerMediaSessionAction("nexttrack");
            }
            @Override
            public void onSkipToPrevious() {
                Log.d(TAG, "MediaSession callback: onSkipToPrevious");
                triggerMediaSessionAction("previoustrack");
            }
            @Override
            public void onStop() {
                Log.d(TAG, "MediaSession callback: onStop");
                triggerMediaSessionAction("stop");
            }
            @Override
            public boolean onMediaButtonEvent(android.content.Intent mediaButtonIntent) {
                android.view.KeyEvent keyEvent = mediaButtonIntent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT);
                if (keyEvent != null && keyEvent.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    switch (keyEvent.getKeyCode()) {
                        case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                            Log.d(TAG, "MediaButton: PLAY");
                            triggerMediaSessionAction("play");
                            return true;
                        case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                            Log.d(TAG, "MediaButton: PAUSE");
                            triggerMediaSessionAction("pause");
                            return true;
                        case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            Log.d(TAG, "MediaButton: PLAY_PAUSE toggle, isPlaying=" + isPlaying);
                            triggerMediaSessionAction(isPlaying ? "pause" : "play");
                            return true;
                        case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                            Log.d(TAG, "MediaButton: NEXT");
                            triggerMediaSessionAction("nexttrack");
                            return true;
                        case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            Log.d(TAG, "MediaButton: PREVIOUS");
                            triggerMediaSessionAction("previoustrack");
                            return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
            public void onSeekTo(long pos) {
                Log.d(TAG, "MediaSession callback: onSeekTo pos=" + (pos / 1000.0) + "s");
                runOnUiThread(() ->
                    getBridge().getWebView().evaluateJavascript(
                        "if(window._mediaSessionHandlers && window._mediaSessionHandlers['seekto']){" +
                        "  window._mediaSessionHandlers['seekto']({action:'seekto',seekTime:" + (pos / 1000.0) + "});" +
                        "}",
                        null
                    )
                );
            }
        });

        webView.addJavascriptInterface(new MediaSessionBridge(), "AndroidMediaSession");
        Log.d(TAG, "JS bridge registered");

        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                Log.d(TAG, "Page finished loading: " + url);
                injectMediaSessionShim(view);
                startPositionPoller();
            }
        });
    }

    private void startPositionPoller() {
        if (positionHandler != null) {
            positionHandler.removeCallbacks(positionPoller);
        }
        positionHandler = new Handler(Looper.getMainLooper());
        positionPoller = new Runnable() {
            @Override
            public void run() {
                getBridge().getWebView().evaluateJavascript(
                    "(function(){" +
                    "  var a=document.getElementById('audio-player');" +
                    "  if(a&&!isNaN(a.duration)&&isFinite(a.duration)&&a.duration>0){" +
                    "    return a.duration+'|'+a.currentTime+'|'+a.playbackRate+'|'+(a.paused?1:0);" +
                    "  } return '';" +
                    "})()",
                    value -> {
                        if (value == null) return;
                        String raw = value.replaceAll("^\"|\"$", "");
                        if (raw.isEmpty()) return;
                        try {
                            String[] parts = raw.split("\\|");
                            if (parts.length < 4) return;
                            double duration = Double.parseDouble(parts[0]);
                            double position = Double.parseDouble(parts[1]);
                            float rate      = Float.parseFloat(parts[2]);
                            boolean paused  = parts[3].equals("1");

                            // Update shared state FIRST before anything else
                            lastRate = rate;
                            isPlaying = !paused;
                            if (position > 0) {
                                lastPosition = (long)(position * 1000);
                            }

                            long durMs = (long)(duration * 1000);

                            Log.d(TAG, "Poller: pos=" + String.format("%.1f", position) +
                                "s dur=" + String.format("%.1f", duration) +
                                "s paused=" + paused +
                                " lastPos=" + (lastPosition / 1000) + "s");

                            // Update duration in metadata only when it actually changes
                            MediaMetadataCompat current = mediaSession.getController().getMetadata();
                            if (current != null && durMs > 0) {
                                long existingDur = current.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
                                if (existingDur != durMs) {
                                    Log.d(TAG, "Poller duration update: durMs=" + durMs + " current=" + existingDur);
                                    mediaSession.setMetadata(new MediaMetadataCompat.Builder(current)
                                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durMs)
                                        .build());
                                }
                            }

                            // Set playback state AFTER lastPosition is updated and duration is set
                            int state = paused
                                ? PlaybackStateCompat.STATE_PAUSED
                                : PlaybackStateCompat.STATE_PLAYING;

                            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                                .setState(state, lastPosition, paused ? 0f : rate)
                                .setActions(
                                    PlaybackStateCompat.ACTION_PLAY |
                                    PlaybackStateCompat.ACTION_PAUSE |
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                    PlaybackStateCompat.ACTION_SEEK_TO |
                                    PlaybackStateCompat.ACTION_STOP)
                                .build());

                        } catch (Exception e) {
                            Log.e(TAG, "Poller parse error: " + e.getMessage());
                        }
                    }
                );
                positionHandler.postDelayed(this, 1000);
            }
        };
        positionHandler.postDelayed(positionPoller, 1000);
        Log.d(TAG, "Position poller started");
    }

    private void triggerMediaSessionAction(String action) {
        Log.d(TAG, "Triggering JS action: " + action);
        runOnUiThread(() ->
            getBridge().getWebView().evaluateJavascript(
                "if(window._mediaSessionHandlers&&window._mediaSessionHandlers['" + action + "']){" +
                "  window._mediaSessionHandlers['" + action + "']({action:'" + action + "'});" +
                "} else { 'no-handler'; }",
                result -> Log.d(TAG, "JS action '" + action + "' result: " + result)
            )
        );
    }

    private void showMediaNotification(String title, String artist) {
        Log.d(TAG, "showMediaNotification: title=" + title + " artist=" + artist +
            " isPlaying=" + isPlaying + " hasArtwork=" + (currentArtwork != null));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(true)
            .setSilent(true)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous",
                createMediaAction("previoustrack"))
            .addAction(isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play",
                createMediaAction(isPlaying ? "pause" : "play"))
            .addAction(android.R.drawable.ic_media_next, "Next",
                createMediaAction("nexttrack"));

        if (currentArtwork != null) {
            builder.setLargeIcon(currentArtwork);
        }

        NotificationManagerCompat.from(this).notify(1, builder.build());
    }

    private byte[] readStream(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, len);
        }
        return buffer.toByteArray();
    }

    private android.app.PendingIntent createMediaAction(String action) {
        android.content.Intent intent = new android.content.Intent(this, MediaActionReceiver.class);
        intent.setAction(action);
        return android.app.PendingIntent.getBroadcast(this, action.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    private void injectMediaSessionShim(WebView view) {
        Log.d(TAG, "Injecting mediaSession shim");
        String js = """
            javascript:(function() {
            if(window._mediaSessionShimInstalled) { console.log('[Mono] shim already installed'); return; }
            window._mediaSessionShimInstalled = true;
            console.log('[Mono] installing mediaSession shim');
            window.MediaMetadata = function(data) {
                this.title = data.title || '';
                this.artist = data.artist || '';
                this.album = data.album || '';
                this.artwork = data.artwork || [];
            };
            let _metadata = new MediaMetadata({});
            let _playbackState = 'none';
            let handlers = {};
            Object.defineProperty(navigator, 'mediaSession', {
                value: {
                set metadata(m) {
                    if (!m) { console.log('[Mono] metadata set to null, ignoring'); return; }
                    _metadata = m;
                    console.log('[Mono] setMetadata: ' + _metadata.title + ' - ' + _metadata.artist);
                    AndroidMediaSession.setMetadata(
                    (_metadata.title || ''),
                    (_metadata.artist || ''),
                    (_metadata.album || ''),
                    (_metadata.artwork && _metadata.artwork[0] ? _metadata.artwork[0].src : '')
                    );
                },
                get metadata() { return _metadata; },
                set playbackState(s) {
                    _playbackState = s || 'none';
                    console.log('[Mono] setPlaybackState: ' + _playbackState);
                    AndroidMediaSession.setPlaybackState(_playbackState);
                },
                get playbackState() { return _playbackState; },
                setPositionState(s) {
                    if(s && s.duration) {
                    console.log('[Mono] setPositionState: pos=' + s.position + ' dur=' + s.duration);
                    AndroidMediaSession.setPositionState(s.duration, s.position||0, s.playbackRate||1);
                    }
                },
                setActionHandler(action, handler) {
                    console.log('[Mono] setActionHandler: ' + action);
                    handlers[action] = handler;
                    window._mediaSessionHandlers = handlers;
                }
                },
                writable: false, configurable: false
            });
            console.log('[Mono] shim installed successfully');
            })();
            """;
        view.evaluateJavascript(js, result ->
            Log.d(TAG, "Shim injection result: " + result));
    }

    class MediaSessionBridge {
        @JavascriptInterface
        public void setMetadata(String title, String artist, String album, String artworkUrl) {

            // Only reset position if it's actually a new track, not a null→real metadata cycle
            boolean isNewTrack = !title.isEmpty() && !title.equals(currentTitle);
            if (isNewTrack) lastPosition = 0;
            currentTitle = title;
            currentArtist = artist;

            // Preserve existing duration — don't reset to 0
            long existingDuration = 0;
            MediaMetadataCompat existing = mediaSession.getController().getMetadata();
            if (existing != null) {
                existingDuration = existing.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
            }

            // If title is empty (null metadata call), just skip entirely
            if (title.isEmpty()) {
                Log.d(TAG, "setMetadata called with empty title, skipping");
                return;
            }

            Log.d(TAG, "setMetadata: title=" + title + " preserving duration=" + existingDuration);

            MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, existingDuration) // preserve!
                .build();
            mediaSession.setMetadata(metadata);
            runOnUiThread(() -> showMediaNotification(title, artist));

            if (artworkUrl != null && !artworkUrl.isEmpty()) {
                final String fTitle = title, fArtist = artist, fAlbum = album;
                final long fDuration = existingDuration;
                new Thread(() -> {
                    Log.d(TAG, "Fetching artwork from: " + artworkUrl);
                    try {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL(artworkUrl).openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(10000);
                        conn.connect();
                        byte[] bytes = readStream(conn.getInputStream());
                        conn.disconnect();
                        Log.d(TAG, "Artwork downloaded: " + bytes.length + " bytes");

                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inSampleSize = 1;
                        android.graphics.Bitmap bitmap =
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);

                        if (bitmap == null) {
                            Log.w(TAG, "Bitmap decode failed with inSampleSize=1, retrying with 2");
                            opts.inSampleSize = 2;
                            bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                        }

                        if (bitmap != null && fTitle.equals(currentTitle)) {
                            Log.d(TAG, "Bitmap decoded: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                            bitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, 512, 512, true);
                            currentArtwork = bitmap;
                            final android.graphics.Bitmap finalBitmap = bitmap;

                            // Get latest duration at time of artwork load
                            long latestDuration = fDuration;
                            MediaMetadataCompat cur = mediaSession.getController().getMetadata();
                            if (cur != null) {
                                latestDuration = cur.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
                            }

                            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, fTitle)
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, fArtist)
                                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, fAlbum)
                                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, latestDuration)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, finalBitmap)
                                .build());
                            runOnUiThread(() -> showMediaNotification(fTitle, fArtist));
                            Log.d(TAG, "Artwork applied to notification");
                        } else if (!fTitle.equals(currentTitle)) {
                            Log.d(TAG, "Artwork discarded - track changed during fetch");
                        } else {
                            Log.w(TAG, "Bitmap decode failed completely");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Artwork fetch failed: " + e.getMessage());
                    }
                }).start();
            }
        }

        @JavascriptInterface
        public void setPlaybackState(String state) {
            Log.d(TAG, "setPlaybackState: " + state);
            isPlaying = state.equals("playing");
            int pbState = isPlaying
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(pbState, lastPosition, isPlaying ? lastRate : 0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY |
                    PlaybackStateCompat.ACTION_PAUSE |
                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackStateCompat.ACTION_SEEK_TO |
                    PlaybackStateCompat.ACTION_STOP)
                .build());
        }

        @JavascriptInterface
        public void setPositionState(double duration, double position, double playbackRate) {
            Log.d(TAG, "setPositionState: pos=" + position + " dur=" + duration + " rate=" + playbackRate);
            int state = mediaSession.getController().getPlaybackState() != null
                ? mediaSession.getController().getPlaybackState().getState()
                : PlaybackStateCompat.STATE_PAUSED;
            lastPosition = (long)(position * 1000);
            lastRate = (float) playbackRate;
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, lastPosition, lastRate)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY |
                    PlaybackStateCompat.ACTION_PAUSE |
                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackStateCompat.ACTION_SEEK_TO |
                    PlaybackStateCompat.ACTION_STOP)
                .build());
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        if (positionHandler != null) positionHandler.removeCallbacks(positionPoller);
        if (mediaSession != null) mediaSession.release();
    }
}