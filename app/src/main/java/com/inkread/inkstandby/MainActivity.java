package com.inkread.inkstandby;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 42;

    private TextView status, tips;
    private ImageView preview;
    private RadioGroup modeGroup, fontGroup, alignGroup, fitGroup, bgGroup;
    private RadioButton rbFile, rbUrl, rbText, rbQuotes, rbInkQuote;
    private RadioButton rbFontSmall, rbFontNormal, rbFontLarge;
    private RadioButton rbAlignCenter, rbAlignLeft;
    private RadioButton rbFit, rbCrop, rbStretch;
    private RadioButton rbBgWhite, rbBgBlack;
    private CheckBox cbFooter, cbSkip;
    private EditText filePath, url, textTemplate, quotesText;
    private LinearLayout inkquoteBox;
    private EditText inkquoteUrl;
    private RadioGroup inkquoteCategoryGroup;
    private RadioButton rbInkAll, rbInkBook, rbInkInternet, rbInkAnime, rbInkMovie;
    private CheckBox cbInkVerified, cbInkDaily;
    private TextView inkquoteCacheInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        tips = findViewById(R.id.tips);
        preview = findViewById(R.id.preview);

        modeGroup = findViewById(R.id.modeGroup);
        rbFile = findViewById(R.id.rbFile);
        rbUrl = findViewById(R.id.rbUrl);
        rbText = findViewById(R.id.rbText);
        rbQuotes = findViewById(R.id.rbQuotes);
        rbInkQuote = findViewById(R.id.rbInkQuote);
        inkquoteBox = findViewById(R.id.inkquoteBox);
        inkquoteUrl = findViewById(R.id.inkquoteUrl);
        inkquoteCategoryGroup = findViewById(R.id.inkquoteCategoryGroup);
        rbInkAll = findViewById(R.id.rbInkAll);
        rbInkBook = findViewById(R.id.rbInkBook);
        rbInkInternet = findViewById(R.id.rbInkInternet);
        rbInkAnime = findViewById(R.id.rbInkAnime);
        rbInkMovie = findViewById(R.id.rbInkMovie);
        cbInkVerified = findViewById(R.id.cbInkVerified);
        cbInkDaily = findViewById(R.id.cbInkDaily);
        inkquoteCacheInfo = findViewById(R.id.inkquoteCacheInfo);

        filePath = findViewById(R.id.filePath);
        url = findViewById(R.id.url);
        textTemplate = findViewById(R.id.textTemplate);
        quotesText = findViewById(R.id.quotesText);

        fontGroup = findViewById(R.id.fontGroup);
        rbFontSmall = findViewById(R.id.rbFontSmall);
        rbFontNormal = findViewById(R.id.rbFontNormal);
        rbFontLarge = findViewById(R.id.rbFontLarge);

        alignGroup = findViewById(R.id.alignGroup);
        rbAlignCenter = findViewById(R.id.rbAlignCenter);
        rbAlignLeft = findViewById(R.id.rbAlignLeft);

        fitGroup = findViewById(R.id.fitGroup);
        rbFit = findViewById(R.id.rbFit);
        rbCrop = findViewById(R.id.rbCrop);
        rbStretch = findViewById(R.id.rbStretch);

        bgGroup = findViewById(R.id.bgGroup);
        rbBgWhite = findViewById(R.id.rbBgWhite);
        rbBgBlack = findViewById(R.id.rbBgBlack);

        cbFooter = findViewById(R.id.cbFooter);
        cbSkip = findViewById(R.id.cbSkip);

        loadPrefs();
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> updateVisibility());
        updateVisibility();

        findViewById(R.id.pickFile).setOnClickListener(v -> pickImage());
        findViewById(R.id.testUrl).setOnClickListener(v -> testUrl());
        findViewById(R.id.testInkQuote).setOnClickListener(v -> {
            savePrefs();
            status.setText("正在从 InkQuote 获取…");
            applyNow();
        });
        findViewById(R.id.refreshInkQuoteCache).setOnClickListener(v -> {
            savePrefs();
            status.setText("正在缓存整批数据…");
            WallpaperApplier.refreshInkQuotePoolAsync(this, (ok, msg) -> runOnUiThread(() -> {
                status.setText((ok ? "✓ " : "✗ ") + msg);
                updateInkQuoteCacheInfo();
                toast(ok ? msg : "缓存失败：" + msg);
            }));
        });
        findViewById(R.id.applyNow).setOnClickListener(v -> {
            savePrefs();
            applyNow();
        });
        findViewById(R.id.save).setOnClickListener(v -> {
            savePrefs();
            toast("已保存");
        });

        if (Prefs.firstLaunch(this)) {
            Prefs.setFirstLaunchDone(this);
            tips.setText("提示：亮屏自动更新由后台服务完成，通知栏有一个低优先级常驻通知。"
                    + "「立即应用（强制刷新）」可手动覆盖当前待机图。");
        } else {
            tips.setText("亮屏自动更新已启用；内容不变时会自动跳过，减少墨水屏刷新。"
                    + "「引据 InkQuote」会自动带上作者《作品》·章节出处。");
        }

        startServiceCompat();
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText("上次：" + WallpaperApplier.lastStatus(this));
        loadPreview();
        updateInkQuoteCacheInfo();
    }

    private void updateInkQuoteCacheInfo() {
        inkquoteCacheInfo.setText(WallpaperApplier.inkquoteCacheInfo(this));
    }

    private void loadPrefs() {
        String mode = Prefs.mode(this);
        if (Prefs.MODE_FILE.equals(mode)) modeGroup.check(R.id.rbFile);
        else if (Prefs.MODE_URL.equals(mode)) modeGroup.check(R.id.rbUrl);
        else if (Prefs.MODE_QUOTES.equals(mode)) modeGroup.check(R.id.rbQuotes);
        else if (Prefs.MODE_INKQUOTE.equals(mode)) modeGroup.check(R.id.rbInkQuote);
        else modeGroup.check(R.id.rbText);

        filePath.setText(Prefs.filePath(this));
        url.setText(Prefs.url(this));
        textTemplate.setText(Prefs.text(this));
        quotesText.setText(Prefs.quotes(this));

        int scale = Prefs.fontScale(this);
        fontGroup.check(scale == 0 ? R.id.rbFontSmall : (scale == 2 ? R.id.rbFontLarge : R.id.rbFontNormal));
        alignGroup.check("left".equals(Prefs.align(this)) ? R.id.rbAlignLeft : R.id.rbAlignCenter);
        cbFooter.setChecked(Prefs.footer(this));

        String fit = Prefs.fit(this);
        fitGroup.check("stretch".equals(fit) ? R.id.rbStretch : ("crop".equals(fit) ? R.id.rbCrop : R.id.rbFit));
        bgGroup.check("black".equals(Prefs.bg(this)) ? R.id.rbBgBlack : R.id.rbBgWhite);
        cbSkip.setChecked(Prefs.skipUnchanged(this));

        inkquoteUrl.setText(Prefs.inkquoteUrl(this));
        String inkCategory = Prefs.inkquoteCategory(this);
        int categoryId = R.id.rbInkAll;
        if ("book".equals(inkCategory)) categoryId = R.id.rbInkBook;
        else if ("internet".equals(inkCategory)) categoryId = R.id.rbInkInternet;
        else if ("anime".equals(inkCategory)) categoryId = R.id.rbInkAnime;
        else if ("movie".equals(inkCategory)) categoryId = R.id.rbInkMovie;
        inkquoteCategoryGroup.check(categoryId);
        cbInkVerified.setChecked(Prefs.inkquoteVerified(this));
        cbInkDaily.setChecked(Prefs.inkquoteDaily(this));
        updateInkQuoteCacheInfo();

        status.setText("上次：" + WallpaperApplier.lastStatus(this));
    }

    private void updateVisibility() {
        boolean f = rbFile.isChecked();
        boolean u = rbUrl.isChecked();
        boolean t = rbText.isChecked();
        boolean q = rbQuotes.isChecked();
        boolean ink = rbInkQuote.isChecked();

        filePath.setVisibility(f ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.pickFile).setVisibility(f ? android.view.View.VISIBLE : android.view.View.GONE);
        url.setVisibility(u ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.testUrl).setVisibility(u ? android.view.View.VISIBLE : android.view.View.GONE);
        textTemplate.setVisibility(t ? android.view.View.VISIBLE : android.view.View.GONE);
        quotesText.setVisibility(q ? android.view.View.VISIBLE : android.view.View.GONE);
        inkquoteBox.setVisibility(ink ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void savePrefs() {
        String mode = rbFile.isChecked() ? Prefs.MODE_FILE
                : (rbUrl.isChecked() ? Prefs.MODE_URL
                : (rbQuotes.isChecked() ? Prefs.MODE_QUOTES
                : (rbInkQuote.isChecked() ? Prefs.MODE_INKQUOTE : Prefs.MODE_TEXT)));
        Prefs.save(this, mode,
                filePath.getText().toString().trim(),
                url.getText().toString().trim(),
                textTemplate.getText().toString(),
                true);

        int scale = rbFontSmall.isChecked() ? 0 : (rbFontLarge.isChecked() ? 2 : 1);
        String align = rbAlignLeft.isChecked() ? "left" : "center";
        String fit = rbStretch.isChecked() ? "stretch" : (rbCrop.isChecked() ? "crop" : "fit");
        String bg = rbBgBlack.isChecked() ? "black" : "white";
        Prefs.saveOptions(this, quotesText.getText().toString(), scale, align,
                cbFooter.isChecked(), fit, bg, cbSkip.isChecked());
        String inkCategory = "";
        if (rbInkBook.isChecked()) inkCategory = "book";
        else if (rbInkInternet.isChecked()) inkCategory = "internet";
        else if (rbInkAnime.isChecked()) inkCategory = "anime";
        else if (rbInkMovie.isChecked()) inkCategory = "movie";
        Prefs.saveInkQuote(this, inkquoteUrl.getText().toString().trim(), inkCategory,
                cbInkVerified.isChecked(), cbInkDaily.isChecked());
    }

    private void applyNow() {
        status.setText("正在更新…");
        WallpaperApplier.applyAsync(this, true, (ok, msg) -> runOnUiThread(() -> {
            status.setText((ok ? "✓ " : "✗ ") + msg);
            loadPreview();
            updateInkQuoteCacheInfo();
            toast(ok ? "已更新待机图" : "失败：" + msg);
        }));
    }

    private void testUrl() {
        savePrefs();
        status.setText("正在测试 URL…");
        WallpaperApplier.testUrlAsync(this, (ok, msg) -> runOnUiThread(() -> {
            status.setText((ok ? "✓ " : "✗ ") + msg);
            toast(msg);
        }));
    }

    private void loadPreview() {
        Bitmap bmp = WallpaperApplier.lastBitmap(this);
        if (bmp != null) preview.setImageBitmap(bmp);
        else preview.setImageDrawable(null);
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        File dst = new File(getFilesDir(), "source_local.png");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            filePath.setText(dst.getAbsolutePath());
            rbFile.setChecked(true);
            updateVisibility();
            savePrefs();
            toast("已导入图片");
        } catch (Throwable t) {
            toast("导入失败：" + t);
        }
    }

    private void startServiceCompat() {
        try {
            Intent i = new Intent(this, UpdateService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            toast("启动后台服务失败：" + t);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
