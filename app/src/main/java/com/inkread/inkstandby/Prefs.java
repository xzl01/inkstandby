package com.inkread.inkstandby;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** 极简配置存储。 */
final class Prefs {
    static final String MODE_FILE = "file";
    static final String MODE_URL = "url";
    static final String MODE_TEXT = "text";

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("inkstandby", Context.MODE_PRIVATE);
    }

    static String mode(Context c) { return sp(c).getString("mode", MODE_TEXT); }
    static String filePath(Context c) { return sp(c).getString("file", ""); }
    static String url(Context c) { return sp(c).getString("url", ""); }
    static String text(Context c) { return sp(c).getString("text", "墨读\n\n静心阅读，专注 RSS"); }
    static boolean enabled(Context c) { return sp(c).getBoolean("enabled", true); }
    static boolean firstLaunch(Context c) { return sp(c).getBoolean("first_launch", true); }
    static void setFirstLaunchDone(Context c) { sp(c).edit().putBoolean("first_launch", false).apply(); }

    static void save(Context c, String mode, String file, String url, String text, boolean enabled) {
        sp(c).edit()
                .putString("mode", mode)
                .putString("file", file)
                .putString("url", url)
                .putString("text", text)
                .putBoolean("enabled", enabled)
                .apply();
    }
}
