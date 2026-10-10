package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.shihab.diplay.musicbridge.BridgeProtocol
import com.shilapi.xcertplay.hud.BydOutputSettings

/** Launch from a visible DiPlay screen, before BYD needs the companion's bound service. */
internal object BydMusicBridgeStartup {
    private var launched = false

    fun initialize(activity: Activity) {
        if (launched || !BydOutputSettings.clusterSong(activity) ||
            !BydOutputSettings.clusterSongArtwork(activity) || BydOutputSettings.clusterSongOnChange(activity)) return
        val pm = activity.packageManager
        @Suppress("DEPRECATION")
        val app = runCatching {
            pm.getApplicationInfo(BridgeProtocol.PACKAGE, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return
        if (app.metaData?.getInt(BridgeProtocol.VERSION_META) != BridgeProtocol.VERSION ||
            pm.checkSignatures(activity.packageName, BridgeProtocol.PACKAGE) != PackageManager.SIGNATURE_MATCH) return
        // Older companions only have a manual launcher screen; do not leave the driver there.
        val initializer = app.metaData?.getString(BridgeProtocol.AUTO_START_META) ?: return
        runCatching {
            activity.startActivity(Intent().setComponent(ComponentName(BridgeProtocol.PACKAGE, initializer)))
            launched = true
        }.onFailure { Log.w("DiPlay-BYD-Bridge", "companion initialization failed", it) }
    }
}
