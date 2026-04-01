package com.monochrome.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MediaActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        // Forward to MainActivity via a local broadcast
        Intent local = new Intent("com.monochrome.app.MEDIA_ACTION");
        local.putExtra("action", action);
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(context).sendBroadcast(local);
    }
}