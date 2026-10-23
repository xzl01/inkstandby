package com.inkread.inkstandby;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
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

    private TextView status;
    private RadioGroup modeGroup;
    private RadioButton rbFile, rbUrl, rbText;
    private EditText filePath, url, textTemplate;
    private TextView tips;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        modeGroup = findViewById(R.id.modeGroup);
        rbFile = findViewById(R.id.rbFile);
        rbUrl = findViewById(R.id.rbUrl);
        rbText = findViewById(R.id.rbText);
        filePath = findViewById(R.id.filePath);
        url = findViewById(R.id.url);
        textTemplate = findViewById(R.id.textTemplate);
        tips = findViewById(R.id.tips);

        loadPrefs();
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> updateVisibility());
        updateVisibility();

        findViewById(R.id.pickFile).setOnClickListener(v -> pickImage());
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
            tips.setText("提示：需要先保证 /data/local/iflytek/wallpaper 可写（本机已设置过）。"
                    + "亮屏自动更新由后台服务完成，通知栏会有一个低优先级常驻通知。");
        } else {
            tips.setText("亮屏自动更新已启用；若更新失败请检查内容源和目录权限。");
        }

        startServiceCompat();
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText("上次：" + WallpaperApplier.lastStatus(this));
    }

    private void loadPrefs() {
        String mode = Prefs.mode(this);
        if (Prefs.MODE_FILE.equals(mode)) modeGroup.check(R.id.rbFile);
        else if (Prefs.MODE_URL.equals(mode)) modeGroup.check(R.id.rbUrl);
        else modeGroup.check(R.id.rbText);
        filePath.setText(Prefs.filePath(this));
        url.setText(Prefs.url(this));
        textTemplate.setText(Prefs.text(this));
        status.setText("上次：" + WallpaperApplier.lastStatus(this));
    }

    private void updateVisibility() {
        boolean f = rbFile.isChecked();
        boolean u = rbUrl.isChecked();
        filePath.setVisibility(f ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.pickFile).setVisibility(f ? android.view.View.VISIBLE : android.view.View.GONE);
        url.setVisibility(u ? android.view.View.VISIBLE : android.view.View.GONE);
        textTemplate.setVisibility((!f && !u) ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void savePrefs() {
        String mode = rbFile.isChecked() ? Prefs.MODE_FILE
                : (rbUrl.isChecked() ? Prefs.MODE_URL : Prefs.MODE_TEXT);
        Prefs.save(this, mode,
                filePath.getText().toString().trim(),
                url.getText().toString().trim(),
                textTemplate.getText().toString(),
                true);
    }

    private void applyNow() {
        status.setText("正在更新…");
        WallpaperApplier.applyAsync(this, (ok, msg) -> runOnUiThread(() -> {
            status.setText((ok ? "✓ " : "✗ ") + msg);
            toast(ok ? "已更新待机图" : "失败：" + msg);
        }));
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
