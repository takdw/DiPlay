package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.shihab.diplay.musicbridge.BridgeProtocol

/** The optional companion follows a session token, so bitmap updates need no extra app-level queue. */
internal class BydMusicBridgeClient(
    private val context: Context,
    private val token: MediaSession.Token,
    private val onFocus: (Int) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    var active = false
        private set
    private val timeout = Runnable { fail("companion did not become ready") }
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        message.data.classLoader = MediaSession.Token::class.java.classLoader
        @Suppress("DEPRECATION")
        val incoming = message.data.getParcelable<MediaSession.Token>(BridgeProtocol.TOKEN)
        val uid = runCatching { context.packageManager.getApplicationInfo(BridgeProtocol.PACKAGE, 0).uid }.getOrNull()
        if (!closed && incoming == token && message.sendingUid == uid) {
            when (message.what) {
                BridgeProtocol.READY -> {
                    handler.removeCallbacks(timeout)
                    active = message.arg1 == 1
                    if (!active) fail("companion could not hold audio focus")
                }
                BridgeProtocol.FOCUS_CHANGED -> onFocus(message.arg1)
            }
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            remote = Messenger(binder)
            send(BridgeProtocol.ATTACH)
        }
        override fun onServiceDisconnected(name: ComponentName) = fail("companion disconnected")
        override fun onBindingDied(name: ComponentName) = fail("companion binding ended")
        override fun onNullBinding(name: ComponentName) = fail("companion refused binding")
    }

    fun connect(): Boolean {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val app = runCatching { pm.getApplicationInfo(BridgeProtocol.PACKAGE, PackageManager.GET_META_DATA) }.getOrNull()
        if (app?.metaData?.getInt(BridgeProtocol.VERSION_META) != BridgeProtocol.VERSION ||
            pm.checkSignatures(context.packageName, BridgeProtocol.PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "compatible companion with matching signing key is not installed")
            return false
        }
        bound = runCatching {
            context.bindService(Intent().setComponent(ComponentName(BridgeProtocol.PACKAGE, BridgeProtocol.SERVICE)),
                connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        }.getOrElse { Log.w(TAG, "companion binding failed", it); false }
        if (bound) handler.postDelayed(timeout, 5_000)
        return bound
    }

    fun regainFocus() { if (!closed && active) send(BridgeProtocol.REGAIN_FOCUS) }

    private fun send(what: Int) {
        val message = Message.obtain(null, what, BridgeProtocol.VERSION, 0).apply {
            data.putParcelable(BridgeProtocol.TOKEN, token)
            replyTo = receiver
        }
        runCatching { remote?.send(message) }.onFailure { fail("companion IPC failed") }
    }

    private fun fail(reason: String) {
        if (closed) return
        Log.w(TAG, reason)
        close()
        onUnavailable()
    }

    fun close() {
        if (closed) return
        closed = true
        active = false
        handler.removeCallbacks(timeout)
        send(BridgeProtocol.DETACH)
        remote = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }

    private companion object { const val TAG = "DiPlay-BYD-Bridge" }
}
