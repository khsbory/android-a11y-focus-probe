package com.artemis.helpermin;

// Portions adapted from Google Artemis under Apache-2.0. Modified for Focus Probe; see NOTICE.

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * In-memory session token, set by an adb-only broadcast:
 * adb shell am broadcast -n com.artemis.helpermin/.TokenReceiver
 *     -a com.artemis.helpermin.SET_TOKEN --es token REPLACE_WITH_A_FRESH_LOCAL_TEST_TOKEN
 * Empty token clears it. Lost when the process dies (push it again).
 */
public class TokenReceiver extends BroadcastReceiver {

    private static volatile String token;

    public static boolean isSet() {
        return token != null;
    }

    public static boolean matches(String candidate) {
        String cur = token;
        if (cur == null || candidate == null) return false;
        return MessageDigest.isEqual(cur.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"com.artemis.helpermin.SET_TOKEN".equals(intent.getAction())) return;
        String v = intent.getStringExtra("token");
        token = (v == null || v.isEmpty()) ? null : v;
        Log.i("HelperMin", token != null ? "Token set" : "Token cleared");
    }
}
