package com.example.zuiyouantirecall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    private static final int EXPORT = 501;
    private TextView status;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 64, 48, 32);
        TextView title = new TextView(this);
        title.setText("最右防撤回 · 实验测试版");
        title.setTextSize(22);
        layout.addView(title);
        status = new TextView(this);
        status.setPadding(0, 28, 0, 28);
        layout.addView(status);
        Switch toggle = new Switch(this);
        toggle.setText("尝试阻止私聊撤回（实验功能）");
        toggle.setChecked(getSharedPreferences(DiagnosticLogProvider.PREFS, 0).getBoolean("enabled", false));
        toggle.setOnCheckedChangeListener((v, checked) -> {
            getSharedPreferences(DiagnosticLogProvider.PREFS, 0).edit().putBoolean("enabled", checked).apply();
            Toast.makeText(this, checked ? "实验拦截已开启" : "实验拦截已关闭", Toast.LENGTH_SHORT).show();
        });
        layout.addView(toggle);
        Button export = new Button(this);
        export.setText("导出 TXT 日志");
        layout.addView(export);
        export.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, DiagnosticLogProvider.FILE);
            startActivityForResult(intent, EXPORT);
        });
        TextView info = new TextView(this);
        info.setPadding(0, 32, 0, 0);
        info.setText("默认关闭拦截。启用后，让测试账号发送文字并撤回，确认原文字是否保留；再导出 TXT。\n\n这是实验版：只尝试阻止 ChatActivity.chatRevoke 的 void 方法调用，可能仍被其他机制撤回。若聊天异常，请立即关闭开关并重启最右。日志不记录聊天正文或用户标识。");
        layout.addView(info);
        setContentView(layout);
    }
    @Override protected void onResume() { super.onResume(); File f = new File(getFilesDir(), DiagnosticLogProvider.FILE); status.setText("日志状态：" + (f.exists() ? (f.length() + " 字节") : "尚无事件记录")); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        File file = new File(getFilesDir(), DiagnosticLogProvider.FILE);
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("No output stream");
            if (!file.exists()) {
                out.write("No diagnostic events recorded yet.\n".getBytes("UTF-8"));
            } else {
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
            Toast.makeText(this, "TXT 导出成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "导出失败：" + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
    }
}
