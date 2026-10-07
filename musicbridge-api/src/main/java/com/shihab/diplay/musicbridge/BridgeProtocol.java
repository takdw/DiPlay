package com.shihab.diplay.musicbridge;

/** Versioned, signature-protected Messenger protocol. Only a MediaSession token crosses IPC. */
public final class BridgeProtocol {
    private BridgeProtocol() {}
    // This ID is in the tested DiLink 5.0 media controller's fixed allowlist.
    public static final String PACKAGE = "app.podcast.cosmos";
    public static final String SERVICE = "com.shihab.diplay.musicbridge.BydMusicBridgeService";
    public static final String PERMISSION = "com.shihab.diplay.permission.BYD_MUSIC_BRIDGE";
    public static final String VERSION_META = "com.shihab.diplay.musicbridge.VERSION";
    public static final int VERSION = 1;
    public static final String TOKEN = "session_token";
    public static final int ATTACH = 1;
    public static final int REGAIN_FOCUS = 2;
    public static final int DETACH = 3;
    public static final int READY = 4;
    public static final int FOCUS_CHANGED = 5;
    public static boolean isDiPlay(String packageName) {
        return "com.shihab.diplay".equals(packageName) || "com.shihab.diplay.hudtest".equals(packageName);
    }
}
