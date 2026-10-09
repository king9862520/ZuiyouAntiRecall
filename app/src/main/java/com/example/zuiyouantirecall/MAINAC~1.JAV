package com.example.zuiyouantirecall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** V17: anti-recall + independent module-private archive, no paper-plane feature. */
public final class MainActivity extends Activity {
    private static final int EXPORT = 501;
    private TextView status;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(44, 58, 44, 44);
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("最右防撤回 · V17");
        title.setTextSize(23);
        layout.addView(title);
        status = new TextView(this);
        status.setPadding(0, 18, 0, 18);
        layout.addView(status);

        Switch toggle = new Switch(this);
        toggle.setText("启用防撤回与本地原文保存");
        toggle.setChecked(getSharedPreferences(DiagnosticLogProvider.PREFS, 0)
            .getBoolean("enabled", false));
        toggle.setOnCheckedChangeListener((v, checked) -> {
            getSharedPreferences(DiagnosticLogProvider.PREFS, 0).edit()
                .putBoolean("enabled", checked).apply();
            Toast.makeText(this, checked ? "已开启，请重启最右" : "已关闭，请重启最右",
                Toast.LENGTH_SHORT).show();
        });
        layout.addView(toggle);

        Button show = new Button(this);
        show.setText("查看已保存的撤回消息");
        show.setOnClickListener(v -> showArchive());
        layout.addView(show);

        Button export = new Button(this);
        export.setText("导出诊断 TXT（不含聊天内容）");
        layout.addView(export);
        export.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, DiagnosticLogProvider.FILE);
            startActivityForResult(intent, EXPORT);
        });

        Button clear = new Button(this);
        clear.setText("清空诊断日志（不删除已保存消息）");
        layout.addView(clear);
        clear.setOnClickListener(v -> {
            File f = new File(getFilesDir(), DiagnosticLogProvider.FILE);
            File old = new File(getFilesDir(), "ZuiyouAntiRecall_log.old.txt");
            boolean ok = (!f.exists() || f.delete()) && (!old.exists() || old.delete());
            Toast.makeText(this, ok ? "日志已清空" : "日志清理不完整", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        Button wipe = new Button(this);
        wipe.setText("删除全部本地保存的撤回消息");
        layout.addView(wipe);
        wipe.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("确认删除所有保存的原文？")
            .setMessage("此操作不可恢复，不会影响最右本身的聊天数据库。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认删除", (dialog, which) -> {
                int removed = getContentResolver().delete(DiagnosticLogProvider.ARCHIVE_URI, null, null);
                Toast.makeText(this, "已删除 " + removed + " 条保存消息", Toast.LENGTH_SHORT).show();
            }).show());

        TextView info = new TextView(this);
        info.setPadding(0, 20, 0, 0);
        info.setText("V17 只处理私聊防撤回与本地保存。\n\n"
            + "收到撤回通知时，如果原消息还在当前聊天列表中，会先保存到模块私有数据库；"
            + "重新加载聊天列表时尝试还原相同消息编号的显示。"
            + "如果服务器已删除整个消息列表项，仍可在上面的保存箱查看副本。\n\n"
            + "注意：只有 V17 开始成功捕获的消息才能保存；后台撤回、特殊消息或早已消失的消息可能无法恢复。"
            + "日志不包含正文、账号 ID 或消息 ID。原文仅保存在本机模块数据中，最多保留最近 500 条。"
            + "请在双方同意的测试聊天中验证，勿分享他人私聊内容。");
        layout.addView(info);
        setContentView(scroll);
    }

    private void showArchive() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 16, 24, 16);
        scroll.addView(container);
        int count = 0;
        try (Cursor c = getContentResolver().query(DiagnosticLogProvider.ARCHIVE_URI,
            null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    // _id, session_id, message_id, content, msg_type, sent_at, saved_at
                    final String body = c.getString(3);
                    long savedAt = c.getLong(6);
                    String display = readable(body);
                    Button button = new Button(this);
                    String date = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(new Date(savedAt));
                    String preview = display.length() > 45 ? display.substring(0, 45) + "…" : display;
                    button.setAllCaps(false);
                    button.setText(date + " · " + preview);
                    button.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("已保存的撤回消息")
                        .setMessage(display)
                        .setPositiveButton("关闭", null)
                        .show());
                    container.addView(button);
                    count++;
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "读取保存箱失败", Toast.LENGTH_SHORT).show();
        }
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有成功保存的撤回消息。先打开防撤回开关并重启最右。 ");
            container.addView(empty);
        }
        new AlertDialog.Builder(this)
            .setTitle("本地保存箱 · " + count + " 条")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show();
    }

    /** Render common text-message JSON; never place private bodies into logs. */
    private static String readable(String body) {
        if (body == null || body.isEmpty()) return "（内容为空）";
        try {
            JSONObject object = new JSONObject(body);
            for (String key : new String[]{"text", "content", "msg", "message"}) {
                Object value = object.opt(key);
                if (value instanceof String && !((String) value).isEmpty()) return (String) value;
                if (value instanceof JSONObject) {
                    JSONObject child = (JSONObject) value;
                    for (String nested : new String[]{"text", "content"}) {
                        String text = child.optString(nested, "");
                        if (!text.isEmpty()) return text;
                    }
                }
            }
            return "（非标准文本格式，原始消息数据已在本地保存）";
        } catch (Exception ignored) {
            return body;
        }
    }

    private void refreshStatus() {
        File f = new File(getFilesDir(), DiagnosticLogProvider.FILE);
        status.setText("日志状态：" + (f.exists() ? f.length() + " 字节" : "尚无诊断事件"));
    }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        File file = new File(getFilesDir(), DiagnosticLogProvider.FILE);
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("No output stream");
            if (!file.exists()) {
                out.write("No V17 anti-recall diagnostic events.\n".getBytes("UTF-8"));
            } else {
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
            Toast.makeText(this, "TXT 导出成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
        }
    }
}
