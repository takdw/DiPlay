package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.shihab.diplay.musicbridge.BridgeProtocol
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class BydMusicBridgeStartupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: Activity
    private lateinit var packages: PackageManager
    private lateinit var companion: ApplicationInfo
    private val initializer = "com.shihab.diplay.musicbridge.BridgeStartupActivity"

    @Before fun setup() {
        ReflectionHelpers.setField(BydMusicBridgeStartup, "launched", false)
        app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE).edit().clear().commit()
        BydOutputSettings.setClusterSong(app, true)
        BydOutputSettings.setClusterSongArtwork(app, true)
        activity = mock(Activity::class.java)
        packages = mock(PackageManager::class.java)
        `when`(activity.packageManager).thenReturn(packages)
        `when`(activity.packageName).thenReturn(app.packageName)
        `when`(activity.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE))
            .thenReturn(app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE))
        companion = ApplicationInfo().apply {
            metaData = Bundle().apply {
                putInt(BridgeProtocol.VERSION_META, BridgeProtocol.VERSION)
                putString(BridgeProtocol.AUTO_START_META, initializer)
            }
        }
        @Suppress("DEPRECATION")
        `when`(packages.getApplicationInfo(BridgeProtocol.PACKAGE, PackageManager.GET_META_DATA))
            .thenReturn(companion)
        `when`(packages.checkSignatures(app.packageName, BridgeProtocol.PACKAGE))
            .thenReturn(PackageManager.SIGNATURE_MATCH)
    }

    @Test fun trustedCompanionLaunchesOnceWithoutOpeningItsMenuOrStartingAnotherTask() {
        BydMusicBridgeStartup.initialize(activity)
        BydMusicBridgeStartup.initialize(activity) // Returning from the invisible initializer.
        val intent = ArgumentCaptor.forClass(Intent::class.java)
        verify(activity, times(1)).startActivity(intent.capture())
        assertEquals(BridgeProtocol.PACKAGE, intent.value.component!!.packageName)
        assertEquals(initializer, intent.value.component!!.className)
        assertEquals(0, intent.value.flags)
    }

    @Test fun disabledOutputModesDoNotLaunchAndEnablingArtworkCanStartItLater() {
        BydOutputSettings.setClusterSong(app, false)
        BydMusicBridgeStartup.initialize(activity)
        BydOutputSettings.setClusterSong(app, true)
        BydOutputSettings.setClusterSongArtwork(app, false)
        BydMusicBridgeStartup.initialize(activity)
        BydOutputSettings.setClusterSongArtwork(app, true)
        BydOutputSettings.setClusterSongOnChange(app, true)
        BydMusicBridgeStartup.initialize(activity)
        verify(activity, never()).startActivity(any(Intent::class.java))
        BydOutputSettings.setClusterSongOnChange(app, false)
        BydMusicBridgeStartup.initialize(activity)
        verify(activity).startActivity(any(Intent::class.java))
    }

    @Test fun olderCompanionDoesNotOpenTheManualLauncher() {
        companion.metaData.remove(BridgeProtocol.AUTO_START_META)
        BydMusicBridgeStartup.initialize(activity)
        verify(activity, never()).startActivity(any(Intent::class.java))
    }

    @Test fun unsupportedProtocolAndUntrustedSignatureCannotLaunch() {
        companion.metaData.putInt(BridgeProtocol.VERSION_META, BridgeProtocol.VERSION + 1)
        BydMusicBridgeStartup.initialize(activity)
        companion.metaData.putInt(BridgeProtocol.VERSION_META, BridgeProtocol.VERSION)
        `when`(packages.checkSignatures(app.packageName, BridgeProtocol.PACKAGE))
            .thenReturn(PackageManager.SIGNATURE_NO_MATCH)
        BydMusicBridgeStartup.initialize(activity)
        verify(activity, never()).startActivity(any(Intent::class.java))
    }

    @Test fun installingTheCompanionAfterOpeningDiPlayAllowsALaterAttempt() {
        @Suppress("DEPRECATION")
        `when`(packages.getApplicationInfo(BridgeProtocol.PACKAGE, PackageManager.GET_META_DATA))
            .thenThrow(PackageManager.NameNotFoundException()).thenReturn(companion)
        BydMusicBridgeStartup.initialize(activity)
        verify(activity, never()).startActivity(any(Intent::class.java))
        BydMusicBridgeStartup.initialize(activity)
        verify(activity).startActivity(any(Intent::class.java))
    }

    @Test fun rejectedLaunchCanRetryOnTheNextResume() {
        doThrow(SecurityException()).doNothing().`when`(activity).startActivity(any(Intent::class.java))
        BydMusicBridgeStartup.initialize(activity)
        BydMusicBridgeStartup.initialize(activity)
        BydMusicBridgeStartup.initialize(activity)
        verify(activity, times(2)).startActivity(any(Intent::class.java))
    }
}
