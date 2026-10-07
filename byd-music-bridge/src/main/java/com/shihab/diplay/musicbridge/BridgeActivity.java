package com.shihab.diplay.musicbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Gives BYD firmware a launchable application and lets the owner initialize it after installing. */
public final class BridgeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);
        TextView text = new TextView(this);
        text.setText("DiPlay BYD Music Bridge\n\nEnable Album art on the dashboard in DiPlay.\nThe bridge follows CarPlay while that setting is on.");
        text.setTextSize(22);
        text.setGravity(Gravity.CENTER);
        layout.addView(text);
        Button open = new Button(this);
        open.setText("Open DiPlay");
        open.setOnClickListener(view -> {
            for (String name : new String[] { "com.shihab.diplay.hudtest", "com.shihab.diplay" }) {
                if (getPackageManager().checkSignatures(getPackageName(), name) != PackageManager.SIGNATURE_MATCH) continue;
                Intent intent = getPackageManager().getLaunchIntentForPackage(name);
                if (intent != null) { startActivity(intent); finish(); return; }
            }
            text.setText("Install the matching DiPlay build, then enable Album art on the dashboard.");
        });
        layout.addView(open);
        setContentView(layout);
    }
}
