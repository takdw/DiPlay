package com.shihab.diplay.musicbridge;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowTransportControls;
import org.robolectric.shadows.ShadowMediaSession;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadow.api.Shadow;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, shadows = BridgeMediaSessionTest.RecordingMediaSession.class)
public class BridgeMediaSessionTest {
    @Implements(MediaSession.class)
    public static class RecordingMediaSession extends ShadowMediaSession {
        MediaMetadata metadata;
        @Implementation protected void setMetadata(MediaMetadata metadata) { this.metadata = metadata; }
    }
    private PlaybackState state(int status, long position) {
        return new PlaybackState.Builder().setState(status, position, status == PlaybackState.STATE_PLAYING ? 1f : 0f, 1000).build();
    }

    @Test public void progressUsesOriginalTimestampAndHandlesPauseSeekAndUnknownPosition() {
        assertEquals(25000, BridgeMediaSession.position(state(PlaybackState.STATE_PLAYING, 20000), 180000, 6000));
        assertEquals(20000, BridgeMediaSession.position(state(PlaybackState.STATE_PAUSED, 20000), 180000, 6000));
        assertEquals(180000, BridgeMediaSession.position(state(PlaybackState.STATE_PLAYING, 179000), 180000, 6000));
        assertEquals(-1, BridgeMediaSession.position(state(PlaybackState.STATE_PLAYING, -1), 180000, 6000));
        assertEquals(5000, BridgeMediaSession.position(state(PlaybackState.STATE_PLAYING, 5000), 0, 1000));
    }

    @Test public void mirrorsTrackChangesAndArtThenClearsItAndUnsubscribes() {
        MediaController source = mock(MediaController.class);
        Handler handler = new Handler(Looper.getMainLooper());
        BridgeMediaSession mirror = new BridgeMediaSession(RuntimeEnvironment.getApplication(), source, handler, () -> {});
        try {
            ArgumentCaptor<MediaController.Callback> updates = ArgumentCaptor.forClass(MediaController.Callback.class);
            verify(source).registerCallback(updates.capture(), eq(handler));
            Bitmap art = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888);
            updates.getValue().onMetadataChanged(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "New track")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
            // Record publication at the Android API boundary; Robolectric has no system_server binder.
            RecordingMediaSession output = Shadow.extract(mirror.session);
            MediaMetadata metadata = output.metadata;
            assertEquals("New track", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            assertEquals("Artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));
            updates.getValue().onMetadataChanged(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
            MediaMetadata cleared = output.metadata;
            assertNull(cleared.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));
            mirror.close();
            verify(source).unregisterCallback(updates.getValue());
            updates.getValue().onMetadataChanged(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Stale").build());
            assertFalse(mirror.session.isActive());
        } finally { mirror.close(); }
    }

    @Test public void wheelAndTransportCommandsReturnToDiPlayAndStopAfterClose() {
        MediaController source = mock(MediaController.class);
        MediaSession target = new MediaSession(RuntimeEnvironment.getApplication(), "Source controls");
        MediaController.TransportControls controls = target.getController().getTransportControls();
        ShadowTransportControls commands = shadowOf(controls);
        when(source.getTransportControls()).thenReturn(controls);
        BridgeMediaSession mirror = new BridgeMediaSession(RuntimeEnvironment.getApplication(), source,
            new Handler(Looper.getMainLooper()), () -> {});
        try {
            KeyEvent key = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            mirror.controls.onMediaButtonEvent(new Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, key));
            verify(source).dispatchMediaButtonEvent(key);
            mirror.controls.onPlay(); assertEquals(PlaybackState.ACTION_PLAY, commands.getLastPerformedAction());
            mirror.controls.onPause(); assertEquals(PlaybackState.ACTION_PAUSE, commands.getLastPerformedAction());
            mirror.controls.onSkipToNext(); assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT, commands.getLastPerformedAction());
            mirror.controls.onSkipToPrevious(); assertEquals(PlaybackState.ACTION_SKIP_TO_PREVIOUS, commands.getLastPerformedAction());
            mirror.close();
            clearInvocations(source);
            mirror.controls.onPlay(); mirror.controls.onSkipToNext();
            verifyNoInteractions(source);
            assertEquals(PlaybackState.ACTION_SKIP_TO_PREVIOUS, commands.getLastPerformedAction());
        } finally { mirror.close(); target.release(); }
    }
}
