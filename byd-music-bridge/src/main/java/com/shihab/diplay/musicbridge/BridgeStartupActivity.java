package com.shihab.diplay.musicbridge;

import android.app.Activity;
import android.os.Bundle;

/** Starts the companion package on BYD firmware, then returns to the calling DiPlay screen. */
public final class BridgeStartupActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        finish();
    }
}
