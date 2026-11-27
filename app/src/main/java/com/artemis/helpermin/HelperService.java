package com.artemis.helpermin;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Accessibility service shell: only hosts the loopback server. */
public class HelperService extends AccessibilityService {

    static final int PORT = 18889;
    private static volatile HelperService instance;
    static volatile String lastWinPkg;
    static volatile String lastWinCls;
    static volatile int winSeq;
    private Server server;

    static HelperService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        promoteToForeground();
        server = new Server(PORT);
        server.start();
        Log.i("HelperMin", "Service connected, server on 127.0.0.1:" + PORT);
    }

    /**
     * Samsung's freezer (Freecess "reason: Bg") freezes background processes a few seconds after
     * they go idle, which stalls the loopback server until an accessibility binder call thaws it.
     * A foreground service is exempt, so keep an ongoing low-importance notification.
     */
    private void promoteToForeground() {
        try {
            final String channelId = "helpermin_keepalive";
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        channelId, "Artemis Helper", android.app.NotificationManager.IMPORTANCE_MIN));
                b = new android.app.Notification.Builder(this, channelId);
            } else {
                b = new android.app.Notification.Builder(this);
            }
            android.app.Notification n = b
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Artemis Helper running")
                    .setOngoing(true)
                    .build();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
            Log.i("HelperMin", "foreground keepalive started");
        } catch (Throwable t) {
            Log.w("HelperMin", "foreground keepalive failed: " + t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWinPkg = event.getPackageName() == null ? null : event.getPackageName().toString();
            lastWinCls = event.getClassName() == null ? null : event.getClassName().toString();
            winSeq++;
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        if (server != null) server.shutdown();
        super.onDestroy();
    }
}
