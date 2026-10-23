package com.inkread.inkstandby;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    public static void applyAsync(final Context ctx, final Callback cb) {
        new Thread(() -> {
            try {
                String msg = applySync(ctx);
                if (cb != null) cb.done(true, msg);
            } catch (Throwable t) {
                Log.e(TAG, "apply failed", t);
                if (cb != null) cb.done(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "inkstandby-apply").start();
    }

    /** 同步生成并写入，返回摘要信息；失败抛异常。 */
    public static String applySync(Context ctx) throws Exception {
        long t0 = System.currentTimeMillis();
        Bitmap portrait = loadPortrait(ctx);
        if (portrait == null) throw new IllegalStateException("无法得到内容图片");

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
        Bitmap src = null;
        if (Prefs.MODE_FILE.equals(mode)) {
            String p = Prefs.filePath(ctx);
            if (p == null || p.isEmpty()) throw new IllegalStateException("未选择本地图片");
            src = BitmapFactory.decodeFile(p);
            if (src == null) throw new IllegalStateException("无法解码图片: " + p);
        } else if (Prefs.MODE_URL.equals(mode)) {
            src = loadFromUrl(ctx);
        } else {
            src = renderText(ctx);
        }
        return fitToPortrait(src);
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
            conn.setRequestProperty("User-Agent", "InkStandby/1.0");
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

    private static Bitmap renderText(Context ctx) {
        String text = Prefs.text(ctx);
        if (text == null) text = "";
        text = text.replace("{date}", new SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(new Date()))
                .replace("{time}", new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()))
                .replace("{weekday}", new SimpleDateFormat("EEEE", Locale.CHINA).format(new Date()))
                .replace("{battery}", batteryPercent(ctx) + "%");

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        p.setTextSize(72f);
        p.setSubpixelText(true);

        int margin = 110;
        int maxWidth = W - margin * 2;
        // 自动缩字号，最多 200，最小 40
        float size = 72f;
        List<String> lines = null;
        while (size >= 40f) {
            p.setTextSize(size);
            lines = wrap(text, p, maxWidth);
            int lineH = (int) (size * 1.55f);
            if (lines.size() * lineH <= H - margin * 2 - 240) break;
            size -= 6f;
        }
        if (lines == null) lines = new ArrayList<>();
        int lineH = (int) (p.getTextSize() * 1.55f);
        int totalH = lines.size() * lineH;
        int y = (H - totalH) / 2 + lineH - (int) (p.getTextSize() * 0.25f);

        Paint.FontMetrics fm = p.getFontMetrics();
        for (String line : lines) {
            float w = p.measureText(line);
            float x = (W - w) / 2f;
            c.drawText(line, x, y - fm.ascent, p);
            y += lineH;
        }

        // 底部小字
        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        small.setColor(Color.BLACK);
        small.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        small.setTextSize(34f);
        String footer = "墨读 · " + new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(new Date());
        c.drawText(footer, margin, H - margin / 2f, small);
        return bmp;
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
                    out.add(line.toString());
                    line.setLength(0);
                }
                line.append(ch);
            }
            out.add(line.toString());
        }
        return out;
    }

    private static int batteryPercent(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Throwable ignored) {}
        return -1;
    }

    // ------------------------------------------------------------ bitmap

    private static Bitmap fitToPortrait(Bitmap src) {
        if (src.getWidth() == W && src.getHeight() == H) return src;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        float scale = Math.min((float) W / src.getWidth(), (float) H / src.getHeight());
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
