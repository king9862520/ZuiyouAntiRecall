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
        title.setText("最右工具 · V3 诊断版");
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
        Switch paper = new Switch(this);
        paper.setText("纸飞机次数诊断（只记录，不修改次数）");
        paper.setChecked(getSharedPreferences(DiagnosticLogProvider.PREFS, 0).getBoolean("paper_enabled", false));
        paper.setOnCheckedChangeListener((v, checked) -> {
            getSharedPreferences(DiagnosticLogProvider.PREFS, 0).edit().putBoolean("paper_enabled", checked).apply();
            Toast.makeText(this, checked ? "纸飞机诊断已开启，请重启最右" : "纸飞机诊断已关闭", Toast.LENGTH_SHORT).show();
        });
        layout.addView(paper);

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
        info.setText("防撤回 V2 实验功能保持不变。\n\n纸飞机诊断：开启开关、重启最右，然后进入纸飞机详情点击“去聊天”，再导出 TXT。只观察指定网络请求路径和次数不足提示；无法保证覆盖所有网络框架，也无法单凭日志确定服务器具体响应。\n\n不会修改匹配次数或绕过限制。日志不记录聊天正文、完整网址和用户标识。");
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
