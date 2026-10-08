package com.example.zuiyouantirecall;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** One-way diagnostic sink: target app may append sanitized events; only module UI reads log. */
public final class DiagnosticLogProvider extends ContentProvider {
    static final String AUTHORITY = "com.example.zuiyouantirecall.log";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/event");
    static final String TARGET = "cn.xiaochuankeji.tieba";
    static final String FILE = "ZuiyouAntiRecall_log.txt";
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 1024 * 1024;

    @Override public boolean onCreate() { return true; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        if (!URI.equals(uri) || getContext() == null || !isAllowedCaller()) return null;
        String event = values == null ? null : values.getAsString("event");
        if (event == null || !event.matches("[A-Za-z0-9_.:= -]{1,160}")) return null;
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + event + "\n";
        synchronized (LOCK) {
            File log = new File(getContext().getFilesDir(), FILE);
            if (log.length() > MAX_BYTES) {
                File old = new File(getContext().getFilesDir(), "ZuiyouAntiRecall_log.old.txt");
                if (old.exists()) old.delete();
                log.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { return null; }
        }
        return uri;
    }
    private boolean isAllowedCaller() {
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String pkg : packages) if (TARGET.equals(pkg) || getContext().getPackageName().equals(pkg)) return true;
        return false;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
