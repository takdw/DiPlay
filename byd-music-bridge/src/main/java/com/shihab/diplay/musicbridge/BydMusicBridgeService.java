package com.shihab.diplay.musicbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/** Bound only while the enabled DiPlay CarPlay session exists. No ADB, root, or vehicle API access. */
public final class BydMusicBridgeService extends Service {
    private static final String TAG = "DiPlay-BYD-Bridge";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private BridgeMediaSession mirror;
    private AudioFocusRequest focus;
    private boolean focusHeld;
    private MediaSession.Token token;
    private Messenger reply;
    private IBinder.DeathRecipient death;
    private int ownerUid = -1;

    @Override public IBinder onBind(Intent intent) { return receiver.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { release(); return false; }
    @Override public void onDestroy() { release(); super.onDestroy(); }

    private boolean authorized(int uid) {
        PackageManager pm = getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || pm.checkSignatures(uid, android.os.Process.myUid()) != PackageManager.SIGNATURE_MATCH) return false;
        for (String name : packages) if (BridgeProtocol.isDiPlay(name)) return true;
        return false;
    }

    private boolean receive(Message message) {
        if (!authorized(message.sendingUid)) return true;
        message.getData().setClassLoader(MediaSession.Token.class.getClassLoader());
        MediaSession.Token incoming = message.getData().getParcelable(BridgeProtocol.TOKEN);
        if (message.what == BridgeProtocol.ATTACH) {
            if (incoming == null || message.replyTo == null || message.arg1 != BridgeProtocol.VERSION) return true;
            MediaController source = new MediaController(this, incoming);
            if (!BridgeProtocol.isDiPlay(source.getPackageName())) return true;
            release();
            token = incoming;
            ownerUid = message.sendingUid;
            reply = message.replyTo;
            MediaSession.Token expected = token;
            death = () -> handler.post(() -> { if (expected.equals(token)) release(); });
            try {
                reply.getBinder().linkToDeath(death, 0);
                mirror = new BridgeMediaSession(this, source, handler, () -> {
                    notifyClient(BridgeProtocol.READY, 0);
                    release();
                });
                startNotification();
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (!expected.equals(token)) return;
                        if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false;
                        else if (change == AudioManager.AUDIOFOCUS_GAIN) focusHeld = true;
                        notifyClient(BridgeProtocol.FOCUS_CHANGED, change);
                    }, handler).build();
                regainFocus();
                notifyClient(BridgeProtocol.READY, focusHeld ? 1 : 0);
                if (!focusHeld) release();
            } catch (RuntimeException | RemoteException error) {
                Log.w(TAG, "bridge unavailable", error);
                notifyClient(BridgeProtocol.READY, 0);
                release();
            }
        } else if (message.sendingUid == ownerUid && token != null && token.equals(incoming)) {
            if (message.what == BridgeProtocol.REGAIN_FOCUS) regainFocus();
            else if (message.what == BridgeProtocol.DETACH) release();
        }
        return true;
    }

    private void regainFocus() {
        if (focus == null || focusHeld) return;
        focusHeld = getSystemService(AudioManager.class).requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (focusHeld) notifyClient(BridgeProtocol.FOCUS_CHANGED, AudioManager.AUDIOFOCUS_GAIN);
        Log.i(TAG, "focus granted=" + focusHeld);
    }

    private void startNotification() {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
            "music", "CarPlay dashboard music", NotificationManager.IMPORTANCE_LOW));
        startForeground(7101, new Notification.Builder(this, "music")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("CarPlay dashboard music")
            .setContentText("Following DiPlay")
            .setStyle(new Notification.MediaStyle().setMediaSession(mirror.session.getSessionToken()))
            .setOngoing(true).build());
    }

    private void notifyClient(int what, int value) {
        if (reply == null) return;
        Message message = Message.obtain(null, what, value, 0);
        message.getData().putParcelable(BridgeProtocol.TOKEN, token);
        try { reply.send(message); } catch (RemoteException error) { release(); }
    }

    private void release() {
        token = null;
        ownerUid = -1;
        if (reply != null && death != null) {
            try { reply.getBinder().unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
        }
        reply = null;
        death = null;
        if (mirror != null) mirror.close();
        mirror = null;
        if (focus != null) getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
        focus = null;
        focusHeld = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
}
