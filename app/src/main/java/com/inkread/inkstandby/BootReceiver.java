package com.inkread.inkstandby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.enabled(context)) return;
        Log.i("InkStandby", "boot, start service");
        try {
            Intent i = new Intent(context, UpdateService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable t) {
            Log.w("InkStandby", "start service failed: " + t);
        }
    }
}
