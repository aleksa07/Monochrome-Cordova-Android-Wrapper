package com.monochrome.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private MediaSessionCompat mediaSession;
    private static final String CHANNEL_ID = "media_playback";
    private Handler positionHandler;
    private Runnable positionPoller;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        WebView webView = getBridge().getWebView();
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // Create native MediaSession
        mediaSession = new MediaSessionCompat(this, "MonochromeSession");
        mediaSession.setActive(true);

        // Wire native buttons back to JS handlers
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { triggerMediaSessionAction("play"); }
            @Override
            public void onPause() { triggerMediaSessionAction("pause"); }
            @Override
            public void onSkipToNext() { triggerMediaSessionAction("nexttrack"); }
            @Override
            public void onSkipToPrevious() { triggerMediaSessionAction("previoustrack"); }
            @Override
            public void onStop() { triggerMediaSessionAction("stop"); }
            @Override
            public void onSeekTo(long pos) {
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

        // Inject JS bridge
        webView.addJavascriptInterface(new MediaSessionBridge(), "AndroidMediaSession");

        // Inject shim after page loads
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
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
                        // evaluateJavascript callback is on main thread already
                        if (value == null) return;
                        // Remove surrounding quotes that evaluateJavascript adds
                        String raw = value.replaceAll("^\"|\"$", "");
                        if (raw.isEmpty()) return;
                        try {
                            String[] parts = raw.split("\\|");
                            if (parts.length < 4) return;
                            double duration = Double.parseDouble(parts[0]);
                            double position = Double.parseDouble(parts[1]);
                            float rate     = Float.parseFloat(parts[2]);
                            boolean paused = parts[3].equals("1");

                            int state = paused
                                ? PlaybackStateCompat.STATE_PAUSED
                                : PlaybackStateCompat.STATE_PLAYING;

                            PlaybackStateCompat pbState = new PlaybackStateCompat.Builder()
                                .setState(state, (long)(position * 1000), rate)
                                .setActions(
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                    PlaybackStateCompat.ACTION_SEEK_TO |
                                    PlaybackStateCompat.ACTION_STOP)
                                .build();
                            mediaSession.setPlaybackState(pbState);

                            // Also update duration in metadata if needed
                            MediaMetadataCompat current = mediaSession.getController().getMetadata();
                            if (current != null && current.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) != (long)(duration * 1000)) {
                                mediaSession.setMetadata(new MediaMetadataCompat.Builder(current)
                                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (long)(duration * 1000))
                                    .build());
                            }
                        } catch (Exception e) {
                            // ignore parse errors
                        }
                    }
                );
                positionHandler.postDelayed(this, 1000);
            }
        };
        positionHandler.postDelayed(positionPoller, 1000);
    }

    private void triggerMediaSessionAction(String action) {
        runOnUiThread(() ->
            getBridge().getWebView().evaluateJavascript(
                "if(window._mediaSessionHandlers&&window._mediaSessionHandlers['" + action + "']){" +
                "  window._mediaSessionHandlers['" + action + "']({action:'" + action + "'});" +
                "}",
                null
            )
        );
    }

    private void showMediaNotification(String title, String artist) {
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
            .addAction(android.R.drawable.ic_media_pause, "Pause",
                createMediaAction("pause"))
            .addAction(android.R.drawable.ic_media_next, "Next",
                createMediaAction("nexttrack"));

        NotificationManagerCompat.from(this).notify(1, builder.build());
    }

    private android.app.PendingIntent createMediaAction(String action) {
        android.content.Intent intent = new android.content.Intent(this, MediaActionReceiver.class);
        intent.setAction(action);
        return android.app.PendingIntent.getBroadcast(this, action.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    private void injectMediaSessionShim(WebView view) {
        String js =
            "javascript:(function() {" +
            "  if(window._mediaSessionShimInstalled) return;" +
            "  window._mediaSessionShimInstalled = true;" +
            "  window.MediaMetadata = function(data) {" +
            "    this.title = data.title || '';" +
            "    this.artist = data.artist || '';" +
            "    this.album = data.album || '';" +
            "    this.artwork = data.artwork || [];" +
            "  };" +
            "  let _metadata = new MediaMetadata({});" +
            "  let _playbackState = 'none';" +
            "  let handlers = {};" +
            "  Object.defineProperty(navigator, 'mediaSession', {" +
            "    value: {" +
            "      set metadata(m) {" +
            "        _metadata = m || new MediaMetadata({});" +
            "        AndroidMediaSession.setMetadata(" +
            "          (_metadata.title || '')," +
            "          (_metadata.artist || '')," +
            "          (_metadata.album || '')," +
            "          (_metadata.artwork && _metadata.artwork[0] ? _metadata.artwork[0].src : '')" +
            "        );" +
            "      }," +
            "      get metadata() { return _metadata; }," +
            "      set playbackState(s) {" +
            "        _playbackState = s || 'none';" +
            "        AndroidMediaSession.setPlaybackState(_playbackState);" +
            "      }," +
            "      get playbackState() { return _playbackState; }," +
            "      setPositionState(s) {" +
            "        if(s && s.duration) {" +
            "          AndroidMediaSession.setPositionState(s.duration, s.position||0, s.playbackRate||1);" +
            "        }" +
            "      }," +
            "      setActionHandler(action, handler) {" +
            "        handlers[action] = handler;" +
            "        window._mediaSessionHandlers = handlers;" +
            "      }" +
            "    }," +
            "    writable: false, configurable: false" +
            "  });" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    class MediaSessionBridge {
        @JavascriptInterface
        public void setMetadata(String title, String artist, String album, String artworkUrl) {
            MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .build();
            mediaSession.setMetadata(metadata);
            runOnUiThread(() -> showMediaNotification(title, artist));

            if (artworkUrl != null && !artworkUrl.isEmpty()) {
                final String fTitle = title, fArtist = artist, fAlbum = album;
                new Thread(() -> {
                    try {
                        java.net.URL url = new java.net.URL(artworkUrl);
                        android.graphics.Bitmap bitmap =
                            android.graphics.BitmapFactory.decodeStream(url.openStream());
                        if (bitmap != null) {
                            MediaMetadataCompat withArt = new MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, fTitle)
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, fArtist)
                                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, fAlbum)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                                .build();
                            mediaSession.setMetadata(withArt);
                            runOnUiThread(() -> showMediaNotification(fTitle, fArtist));
                        }
                    } catch (Exception e) { /* artwork failed, no big deal */ }
                }).start();
            }
        }

        @JavascriptInterface
        public void setPlaybackState(String state) {
            int pbState = state.equals("playing")
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(pbState, 0, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackStateCompat.ACTION_SEEK_TO |
                    PlaybackStateCompat.ACTION_STOP)
                .build());
        }

        @JavascriptInterface
        public void setPositionState(double duration, double position, double playbackRate) {
            int state = mediaSession.getController().getPlaybackState() != null
                ? mediaSession.getController().getPlaybackState().getState()
                : PlaybackStateCompat.STATE_PAUSED;
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, (long)(position * 1000), (float) playbackRate)
                .setActions(
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
        super.onDestroy();
        if (positionHandler != null) positionHandler.removeCallbacks(positionPoller);
        if (mediaSession != null) mediaSession.release();
    }
}