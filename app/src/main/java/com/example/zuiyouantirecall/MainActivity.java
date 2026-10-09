package com.example.zuiyouantirecall;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.media.MediaPlayer;
import android.net.Uri;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** V18: image and voice snapshot + private recall archive, no paper-plane feature. */
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
        title.setText("最右防撤回 · V18（图片＋语音）");
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
        show.setText("查看撤回保存箱（照片与语音）");
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
        wipe.setText("删除全部撤回记录和媒体文件");
        layout.addView(wipe);
        wipe.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("确认删除全部撤回记录和媒体？")
            .setMessage("将永久删除模块保存的图片、语音和消息记录，不影响最右原数据库。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认删除", (dialog, which) -> {
                int removed = getContentResolver().delete(DiagnosticLogProvider.ARCHIVE_URI, null, null);
                Toast.makeText(this, "已删除 " + removed + " 条保存消息", Toast.LENGTH_SHORT).show();
            }).show());

        TextView info = new TextView(this);
        info.setPadding(0, 20, 0, 0);
        info.setText("V18 只做私聊防撤回，重点保留照片和语音，不含纸飞机。\n\n"
            + "图片仅在当前聊天中已加载、能取得有效位图时保存显示副本；可能低于原图画质。"
            + "语音只从最右已完整缓存且长度可校验的音频中保存副本，不重新请求服务器。"
            + "请先把照片打开、把语音播放完整，再让双方测试账号进行撤回。\n\n"
            + "保存箱区分成功和失败：有消息记录不代表媒体文件一定存在。"
            + "媒体保存于本机模块私有空间；诊断 TXT 不含聊天内容、URL 或用户 ID。"
            + "V17 已保存的记录会保留并自动升级数据库，不可通过卸载模块来更新。"
            + "请仅在双方同意的测试聊天中验证。");
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
                    // V18 appends: media_kind, media_bytes, media_status
                    final long sid = c.getLong(1), mid = c.getLong(2);
                    final String body = c.getString(3);
                    long savedAt = c.getLong(6);
                    final String kind = c.getString(7);
                    final long bytes = c.getLong(8);
                    final String mediaStatus = c.getString(9);
                    String label = "image".equals(kind) ? "照片已保存" :
                        "voice".equals(kind) ? "语音已保存" : "仅消息记录";
                    Button button = new Button(this);
                    String date = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(new Date(savedAt));
                    button.setAllCaps(false);
                    button.setText(date + " · " + label + " · " +
                        ("image".equals(kind) || "voice".equals(kind) ?
                            bytes / 1024 + " KB" : (mediaStatus == null ? "未保存媒体" : mediaStatus)));
                    button.setOnClickListener(v -> {
                        if ("image".equals(kind)) showImage(sid, mid);
                        else if ("voice".equals(kind)) playVoice(sid, mid);
                        else new AlertDialog.Builder(this)
                            .setTitle("只保存了消息记录")
                            .setMessage("媒体文件未保存，原因：" + mediaStatus +
                                "\n\n" + readable(body))
                            .setPositiveButton("关闭", null).show();
                    });
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

    private Uri mediaUri(long sid, long mid) {
        return Uri.parse("content://" + DiagnosticLogProvider.AUTHORITY +
            "/media/" + sid + "/" + mid);
    }

    private void showImage(long sid, long mid) {
        try (InputStream input = getContentResolver().openInputStream(mediaUri(sid, mid))) {
            if (input == null) throw new IllegalStateException("missing_file");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) throw new IllegalStateException("invalid_image");
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setImageBitmap(bitmap);
            new AlertDialog.Builder(this)
                .setTitle("已保留的图片副本")
                .setView(image)
                .setPositiveButton("关闭", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开图片：" + e.getClass().getSimpleName(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void playVoice(long sid, long mid) {
        final MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(this, mediaUri(sid, mid));
            player.setOnPreparedListener(mp -> mp.start());
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("已保留的语音")
                .setMessage("正在准备播放本地语音，关闭对话框可停止播放。")
                .setPositiveButton("停止并关闭", null)
                .create();
            dialog.setOnDismissListener(d -> {
                try { player.release(); } catch (Exception ignored) { }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "本地语音无法解码", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return true;
            });
            dialog.show();
            player.prepareAsync();
        } catch (Exception e) {
            player.release();
            Toast.makeText(this, "无法打开语音：" + e.getClass().getSimpleName(),
                Toast.LENGTH_LONG).show();
        }
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
                out.write("No V18 anti-recall diagnostic events.\n".getBytes("UTF-8"));
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
