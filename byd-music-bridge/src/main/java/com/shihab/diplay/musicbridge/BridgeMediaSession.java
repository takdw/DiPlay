package com.shihab.diplay.musicbridge;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Mirrors DiPlay's session; controls always go back to that session, never to a second player. */
final class BridgeMediaSession {
    private final Handler handler;
    private final MediaController source;
    final MediaSession session;
    private PlaybackState playback;
    private long duration;
    private boolean closed;
    final MediaSession.Callback controls;

    BridgeMediaSession(Context context, MediaController source, Handler handler, Runnable sourceEnded) {
        this.source = source;
        this.handler = handler;
        session = new MediaSession(context, "DiPlay BYD Music Bridge");
        controls = new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                return !closed && key != null && source.dispatchMediaButtonEvent(key);
            }
            @Override public void onPlay() { if (!closed) source.getTransportControls().play(); }
            @Override public void onPause() { if (!closed) source.getTransportControls().pause(); }
            @Override public void onSkipToNext() { if (!closed) source.getTransportControls().skipToNext(); }
            @Override public void onSkipToPrevious() { if (!closed) source.getTransportControls().skipToPrevious(); }
            @Override public void onSeekTo(long position) { if (!closed) source.getTransportControls().seekTo(position); }
        };
        session.setCallback(controls, handler);
        callback = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata metadata) { if (!closed) publishMetadata(metadata); }
            @Override public void onPlaybackStateChanged(PlaybackState state) { if (!closed) { playback = state; publishPlayback(); } }
            @Override public void onSessionDestroyed() { if (!closed) sourceEnded.run(); }
        };
        source.registerCallback(callback, handler);
        publishMetadata(source.getMetadata());
        playback = source.getPlaybackState();
        publishPlayback();
        session.setActive(true);
        handler.postDelayed(tick, 1000);
    }

    private final MediaController.Callback callback;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (playback != null && playback.getState() == PlaybackState.STATE_PLAYING) publishPlayback();
            handler.postDelayed(this, 1000);
        }
    };

    private void publishMetadata(MediaMetadata metadata) {
        // Explicitly clearing an item must also remove its cover from BYD's native provider.
        if (metadata == null || metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == null ||
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE).trim().isEmpty()) {
            metadata = new MediaMetadata.Builder().build();
        }
        duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        // The source already bounds artwork to 384px and publishes it only when metadata changes.
        session.setMetadata(metadata);
    }

    private void publishPlayback() {
        if (playback == null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0).build());
            return;
        }
        long now = SystemClock.elapsedRealtime();
        session.setPlaybackState(new PlaybackState.Builder(playback)
            .setState(playback.getState(), position(playback, duration, now), playback.getPlaybackSpeed(), now).build());
    }

    static long position(PlaybackState state, long duration, long now) {
        long position = state.getPosition();
        if (position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return position;
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) (Math.max(0, now - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        position = Math.max(0, position);
        return duration > 0 ? Math.min(duration, position) : position;
    }

    void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacks(tick);
        source.unregisterCallback(callback);
        session.setMetadata(new MediaMetadata.Builder().build());
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0).build());
        session.setActive(false);
        session.release();
    }
}
