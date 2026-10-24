package com.inkread.inkstandby;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** 常驻服务：监听亮屏事件，退出待机时更新一次待机图。 */
public class UpdateService extends Service {
    private static final String TAG = "InkStandby";
    private static final String CHANNEL = "inkstandby";
    private static final int NOTIFY_ID = 1001;
    static final String ACTION_APPLY_NOW = "com.inkread.inkstandby.APPLY_NOW";
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastApplyAt = 0;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            Log.i(TAG, "screen event: " + a);
            apply("screen:" + a, false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFY_ID, buildNotification());
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);
        receiverRegistered = true;
        apply("service-create", true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean force = intent != null && ACTION_APPLY_NOW.equals(intent.getAction());
        apply(force ? "manual" : "start-command", force);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void apply(String reason, boolean force) {
        if (!Prefs.enabled(this)) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastApplyAt < 2000) return;
        lastApplyAt = now;
        Log.i(TAG, "apply: " + reason + (force ? " (force)" : ""));
        WallpaperApplier.applyAsync(this, force, (ok, msg) -> Log.i(TAG, "apply done ok=" + ok + " " + msg));
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "待机壁纸", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("亮屏时更新锁屏/待机图");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent apply = new Intent(this, UpdateService.class).setAction(ACTION_APPLY_NOW);
        PendingIntent applyPi = PendingIntent.getService(this, 1, apply,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("墨屏待机")
                .setContentText("亮屏时更新待机图")
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "立即更新", applyPi).build())
                .setOngoing(true)
                .build();
    }
}
