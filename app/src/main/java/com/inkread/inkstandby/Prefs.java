package com.inkread.inkstandby;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简配置存储。 */
final class Prefs {
    static final String MODE_FILE = "file";
    static final String MODE_URL = "url";
    static final String MODE_TEXT = "text";
    static final String MODE_QUOTES = "quotes";

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("inkstandby", Context.MODE_PRIVATE);
    }

    static String mode(Context c) { return sp(c).getString("mode", MODE_TEXT); }
    static String filePath(Context c) { return sp(c).getString("file", ""); }
    static String url(Context c) { return sp(c).getString("url", ""); }
    static String text(Context c) { return sp(c).getString("text", "墨读\n\n静心阅读，专注 RSS"); }
    static String quotes(Context c) {
        return sp(c).getString("quotes",
                "书读百遍，其义自见。\n一页有声，世界便慢。\n读书不觉已春深，一寸光阴一寸金。\n心静，则万物皆静。");
    }
    static int fontScale(Context c) { return sp(c).getInt("font_scale", 1); } // 0小 1中 2大
    static String align(Context c) { return sp(c).getString("align", "center"); }
    static boolean footer(Context c) { return sp(c).getBoolean("footer", true); }
    static String fit(Context c) { return sp(c).getString("fit", "fit"); } // fit/crop/stretch
    static String bg(Context c) { return sp(c).getString("bg", "white"); } // white/black
    static boolean skipUnchanged(Context c) { return sp(c).getBoolean("skip_unchanged", true); }
    static String lastHash(Context c) { return sp(c).getString("last_hash", ""); }
    static void setLastHash(Context c, String hash) { sp(c).edit().putString("last_hash", hash).apply(); }

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

    static void saveOptions(Context c, String quotes, int fontScale, String align,
                            boolean footer, String fit, String bg, boolean skipUnchanged) {
        sp(c).edit()
                .putString("quotes", quotes)
                .putInt("font_scale", fontScale)
                .putString("align", align)
                .putBoolean("footer", footer)
                .putString("fit", fit)
                .putString("bg", bg)
                .putBoolean("skip_unchanged", skipUnchanged)
                .apply();
    }
}
