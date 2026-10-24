package com.inkread.inkstandby;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 把内容渲染成 1264x1680 的待机图，并调用厂商隐藏 API
 * android.util.EinkUtil.setWallpaper(which, rotation, bitmap) 写入待机壁纸。
 *
 * which: SLEEP=0, SLEEP_CHARGE=4, SLEEP_LOW_POWER=5
 * rotation: 0 / 90
 */
public final class WallpaperApplier {
    private static final String TAG = "InkStandby";
    public static final String WALLPAPER_DIR = "/data/local/iflytek/wallpaper/";
    public static final int W = 1264;
    public static final int H = 1680;

    private static final Object LOCK = new Object();
    private static long lastInvokeAt = 0;

    public interface Callback {
        void done(boolean ok, String message);
    }

    private WallpaperApplier() {}

    public static boolean canWriteDir() {
        File d = new File(WALLPAPER_DIR);
        if (!d.isDirectory() || !d.canWrite()) return false;
        try {
            File t = new File(d, ".inkstandby_test");
            if (t.exists() || t.createNewFile()) {
                boolean ok = t.delete();
                return ok || true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void applyAsync(final Context ctx, final boolean force, final Callback cb) {
        new Thread(() -> {
            try {
                String msg = applySync(ctx, force);
                if (cb != null) cb.done(true, msg);
            } catch (Throwable t) {
                Log.e(TAG, "apply failed", t);
                if (cb != null) cb.done(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "inkstandby-apply").start();
    }

    public static void applyAsync(final Context ctx, final Callback cb) {
        applyAsync(ctx, false, cb);
    }

    /** 同步生成并写入，返回摘要信息；失败抛异常。force=true 时忽略「内容未变」和防抖。 */
    public static String applySync(Context ctx) throws Exception {
        return applySync(ctx, false);
    }

    public static String applySync(Context ctx, boolean force) throws Exception {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (!force && now - lastInvokeAt < 1200) return "跳过（防抖）";
            lastInvokeAt = now;
        }
        long t0 = System.currentTimeMillis();
        Bitmap portrait = loadPortrait(ctx);
        if (portrait == null) throw new IllegalStateException("无法得到内容图片");

        ByteArrayOutputStream probe = new ByteArrayOutputStream();
        portrait.compress(Bitmap.CompressFormat.PNG, 100, probe);
        String hash = md5(probe.toByteArray());
        if (!force && Prefs.skipUnchanged(ctx) && hash.equals(Prefs.lastHash(ctx))) {
            Prefs.sp(ctx).edit()
                    .putLong("last_time", System.currentTimeMillis())
                    .putString("last_result", "内容未变化，已跳过")
                    .apply();
            return "内容未变化，已跳过";
        }

        Bitmap land = rotate90(portrait);
        StringBuilder sb = new StringBuilder();
        int okCount = 0;
        // 只写 rotation=0（竖屏）；rotation=90 会被厂商写成同一个文件且内容旋转，不能混用
        int[] whichs = {0, 4, 5};
        for (int which : whichs) {
            boolean okFile = setEink(which, 0, portrait);
            boolean okLive = setViaIFinkManager(ctx, which, 0, portrait);
            if (okFile || okLive) okCount++;
            sb.append(whichName(which)).append(okLive ? "✓ " : (okFile ? "~ " : "✗ "));
        }
        // 兜底：直接写文件（含横屏和 sleep_rotation_*）
        savePng(portrait, WALLPAPER_DIR + "standby.png");
        savePng(portrait, WALLPAPER_DIR + "standby_charge.png");
        savePng(portrait, WALLPAPER_DIR + "standby_lowpower.png");
        savePng(portrait, WALLPAPER_DIR + "sleep_rotation_0.png");
        savePng(land, WALLPAPER_DIR + "standby_90.png");
        savePng(land, WALLPAPER_DIR + "standby_charge_90.png");
        savePng(land, WALLPAPER_DIR + "standby_lowpower_90.png");
        savePng(land, WALLPAPER_DIR + "sleep_rotation_90.png");

        // 保存最后一张，供预览
        try (FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), "last.png"))) {
            portrait.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }

        Prefs.setLastHash(ctx, hash);
        Prefs.sp(ctx).edit()
                .putLong("last_time", System.currentTimeMillis())
                .putString("last_result", "成功 " + okCount + "/3 · " + sb)
                .apply();

        return okCount + "/3 写入成功（" + (System.currentTimeMillis() - t0) + "ms）";
    }

    private static String whichName(int which) {
        switch (which) {
            case 0: return "待机";
            case 4: return "充电";
            case 5: return "低电量";
            default: return "?" + which;
        }
    }

    // ------------------------------------------------------------ source

    private static Bitmap loadPortrait(Context ctx) throws Exception {
        String mode = Prefs.mode(ctx);
        Bitmap src;
        if (Prefs.MODE_FILE.equals(mode)) {
            String p = Prefs.filePath(ctx);
            if (p == null || p.isEmpty()) throw new IllegalStateException("未选择本地图片");
            src = BitmapFactory.decodeFile(p);
            if (src == null) throw new IllegalStateException("无法解码图片: " + p);
        } else if (Prefs.MODE_URL.equals(mode)) {
            src = loadFromUrl(ctx);
        } else if (Prefs.MODE_QUOTES.equals(mode)) {
            src = renderText(ctx, pickQuote(ctx), true);
        } else {
            src = renderText(ctx, Prefs.text(ctx), false);
        }
        return fitToPortrait(src, Prefs.fit(ctx), Prefs.bg(ctx));
    }

    private static String pickQuote(Context ctx) {
        String[] arr = Prefs.quotes(ctx).split("\n");
        List<String> list = new ArrayList<>();
        for (String s : arr) {
            String t = s.trim();
            if (!t.isEmpty()) list.add(t);
        }
        if (list.isEmpty()) return "心静，则万物皆静。";
        return list.get(new Random().nextInt(list.size()));
    }

    private static Bitmap loadFromUrl(Context ctx) throws Exception {
        String u = Prefs.url(ctx);
        if (u == null || u.isEmpty()) throw new IllegalStateException("未设置图片 URL");
        File cache = new File(ctx.getFilesDir(), "url_cache.png");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "InkStandby/1.1");
            int code = conn.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(cache)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Bitmap b = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (b == null) throw new IllegalStateException("下载内容不是有效图片");
            return b;
        } catch (Throwable t) {
            if (cache.exists()) {
                Bitmap b = BitmapFactory.decodeFile(cache.getAbsolutePath());
                if (b != null) {
                    Log.w(TAG, "url failed, use cache: " + t);
                    return b;
                }
            }
            throw t;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 测试 URL 是否能下载并解码为图片。 */
    public static void testUrlAsync(final Context ctx, final Callback cb) {
        new Thread(() -> {
            try {
                Bitmap b = loadFromUrl(ctx);
                if (b == null) throw new IllegalStateException("无法解码");
                if (cb != null) cb.done(true, "图片有效：" + b.getWidth() + "×" + b.getHeight());
            } catch (Throwable t) {
                if (cb != null) cb.done(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "inkstandby-url-test").start();
    }

    private static Bitmap renderText(Context ctx, String raw, boolean quoteMode) {
        String text = substitute(ctx, raw == null ? "" : raw);

        boolean dark = "black".equals(Prefs.bg(ctx));
        int bgColor = dark ? Color.BLACK : Color.WHITE;
        int fgColor = dark ? Color.WHITE : Color.BLACK;

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(bgColor);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(fgColor);
        p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        p.setSubpixelText(true);

        int scale = Prefs.fontScale(ctx);
        float factor = scale == 0 ? 0.82f : (scale == 2 ? 1.25f : 1f);
        float base = (quoteMode ? 84f : 72f) * factor;

        int margin = 110;
        int maxWidth = W - margin * 2;
        boolean footer = Prefs.footer(ctx);
        int bottomReserve = footer ? 240 : 110;

        float size = base;
        List<String> lines = null;
        while (size >= 34f) {
            p.setTextSize(size);
            lines = wrap(text, p, maxWidth);
            int lineH = (int) (size * 1.55f);
            if (lines.size() * lineH <= H - margin * 2 - bottomReserve) break;
            size -= 5f;
        }
        if (lines == null) lines = new ArrayList<>();
        int lineH = (int) (p.getTextSize() * 1.55f);
        int totalH = lines.size() * lineH;
        int y = (H - totalH) / 2 + lineH - (int) (p.getTextSize() * 0.25f);

        boolean left = "left".equals(Prefs.align(ctx));
        Paint.FontMetrics fm = p.getFontMetrics();
        for (String line : lines) {
            float w = p.measureText(line);
            float x = left ? margin : (W - w) / 2f;
            c.drawText(line, x, y - fm.ascent, p);
            y += lineH;
        }

        if (footer) {
            Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
            small.setColor(fgColor);
            small.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            small.setTextSize(34f);
            String footerText = "墨读 · "
                    + new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(new Date());
            c.drawText(footerText, margin, H - margin / 2f, small);
        }
        return bmp;
    }

    private static String substitute(Context ctx, String text) {
        int battery = batteryPercent(ctx);
        return text
                .replace("{date}", new SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(new Date()))
                .replace("{time}", new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()))
                .replace("{weekday}", new SimpleDateFormat("EEEE", Locale.CHINA).format(new Date()))
                .replace("{battery}", battery >= 0 ? battery + "%" : "--");
    }

    private static List<String> wrap(String text, Paint paint, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            if (para.isEmpty()) { out.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                String trial = line.toString() + ch;
                if (paint.measureText(trial) > maxWidth && line.length() > 0) {
                    // 标点悬挂：避免中文句号/逗号被挤到下一行，形成「孤字」
                    if (isClosingPunctuation(ch)) {
                        line.append(ch);
                        out.add(line.toString());
                        line.setLength(0);
                        continue;
                    }
                    out.add(line.toString());
                    line.setLength(0);
                }
                line.append(ch);
            }
            out.add(line.toString());
        }
        return out;
    }

    private static boolean isClosingPunctuation(char ch) {
        return "。，、；：！？）》」』】”’…—,.!?;:)]}".indexOf(ch) >= 0;
    }

    private static int batteryPercent(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Throwable ignored) {}
        return -1;
    }

    // ------------------------------------------------------------ bitmap

    private static Bitmap fitToPortrait(Bitmap src, String fit, String bg) {
        if (src == null) return null;
        int bgColor = "black".equals(bg) ? Color.BLACK : Color.WHITE;
        if (src.getWidth() == W && src.getHeight() == H && !"stretch".equals(fit)) return src;

        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(bgColor);

        if ("stretch".equals(fit)) {
            Bitmap stretched = Bitmap.createScaledBitmap(src, W, H, true);
            c.drawBitmap(stretched, 0f, 0f, null);
            return out;
        }

        float sx = (float) W / src.getWidth();
        float sy = (float) H / src.getHeight();
        float scale = "crop".equals(fit) ? Math.max(sx, sy) : Math.min(sx, sy);
        int dw = Math.max(1, Math.round(src.getWidth() * scale));
        int dh = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, dw, dh, true);
        c.drawBitmap(scaled, (W - dw) / 2f, (H - dh) / 2f, null);
        return out;
    }

    private static Bitmap rotate90(Bitmap src) {
        Matrix m = new Matrix();
        m.postRotate(90);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private static String md5(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(data.length);
        }
    }

    // ------------------------------------------------------------ hidden API

    /** 通过 ifink 服务实时更新（让系统立即刷新休眠壁纸，而不是等下次开机）。 */
    private static boolean setViaIFinkManager(Context ctx, int which, int rotation, Bitmap bmp) {
        try {
            Class<?> c = Class.forName("android.iflyink.IFinkManager");
            Object fm = c.getConstructor(Context.class).newInstance(ctx);
            java.lang.reflect.Method m = c.getMethod("setWallpaper", int.class, int.class, Bitmap.class);
            Object r = m.invoke(fm, which, rotation, bmp);
            boolean ok = (r instanceof Boolean) && (Boolean) r;
            if (!ok) Log.w(TAG, "IFinkManager.setWallpaper " + which + "/" + rotation + " -> " + r);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "IFinkManager.setWallpaper " + which + "/" + rotation + " failed: " + t);
            return false;
        }
    }

    private static boolean setEink(int which, int rotation, Bitmap bmp) {
        try {
            Class<?> c = Class.forName("android.util.EinkUtil");
            java.lang.reflect.Method m = c.getMethod("setWallpaper", int.class, int.class, Bitmap.class);
            Object r = m.invoke(null, which, rotation, bmp);
            boolean ok = (r instanceof Integer) && ((Integer) r) != 0;
            if (!ok) Log.w(TAG, "EinkUtil.setWallpaper " + which + "/" + rotation + " -> " + r);
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "EinkUtil.setWallpaper " + which + "/" + rotation + " failed", t);
            return false;
        }
    }

    private static void savePng(Bitmap b, String path) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (Throwable e) {
            Log.w(TAG, "savePng failed " + path + ": " + e);
        }
    }

    // ------------------------------------------------------------ status

    static String lastStatus(Context ctx) {
        long t = Prefs.sp(ctx).getLong("last_time", 0);
        String r = Prefs.sp(ctx).getString("last_result", "还没更新过");
        if (t <= 0) return r;
        String when = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(t));
        return when + " · " + r;
    }

    static Bitmap lastBitmap(Context ctx) {
        File f = new File(ctx.getFilesDir(), "last.png");
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }
}
