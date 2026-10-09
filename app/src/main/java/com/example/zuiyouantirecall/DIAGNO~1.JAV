package com.example.zuiyouantirecall;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** V17: module-private recall archive; no captured message body is written to diagnostic TXT. */
public final class DiagnosticLogProvider extends ContentProvider {
    static final String AUTHORITY = "com.example.zuiyouantirecall.log";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/event");
    static final Uri SETTING_URI = Uri.parse("content://" + AUTHORITY + "/setting");
    static final Uri ARCHIVE_URI = Uri.parse("content://" + AUTHORITY + "/archive");
    static final String PREFS = "settings";
    static final String TARGET = "cn.xiaochuankeji.tieba";
    static final String FILE = "ZuiyouAntiRecall_log.txt";
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 1024L * 1024L;
    private ArchiveDb archive;

    private static final class ArchiveDb extends SQLiteOpenHelper {
        ArchiveDb(android.content.Context context) { super(context, "recall_archive_v17.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE saved (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL, message_id INTEGER NOT NULL," +
                "content TEXT NOT NULL, msg_type INTEGER NOT NULL," +
                "status INTEGER NOT NULL, view_type INTEGER NOT NULL," +
                "sent_at INTEGER NOT NULL, saved_at INTEGER NOT NULL," +
                "UNIQUE(session_id,message_id))");
            db.execSQL("CREATE INDEX ix_saved_session ON saved(session_id)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    }

    @Override public boolean onCreate() {
        archive = new ArchiveDb(getContext());
        return true;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        if (getContext() == null || !isAllowedCaller()) return null;
        if (URI.equals(uri)) return appendLog(values) ? URI : null;
        if (ARCHIVE_URI.equals(uri)) return saveRecall(values);
        return null;
    }

    private boolean appendLog(ContentValues values) {
        String event = values == null ? null : values.getAsString("event");
        if (event == null || !event.matches("antirecall\\.v17\\.[A-Za-z0-9_.$:=> -]{1,500}")) return false;
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(new Date()) + " " + event + "\n";
        synchronized (LOCK) {
            File log = new File(getContext().getFilesDir(), FILE);
            if (log.length() > MAX_BYTES) {
                File old = new File(getContext().getFilesDir(), "ZuiyouAntiRecall_log.old.txt");
                if (old.exists()) old.delete();
                log.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { return false; }
        }
        return true;
    }

    /** Only the explicit, currently enabled anti-recall hook can create new archive rows. */
    private Uri saveRecall(ContentValues values) {
        if (values == null || !getContext().getSharedPreferences(PREFS, 0)
                .getBoolean("enabled", false)) return null;
        try {
            Long sid = values.getAsLong("session_id");
            Long mid = values.getAsLong("message_id");
            String body = values.getAsString("content");
            Integer type = values.getAsInteger("msg_type");
            Integer status = values.getAsInteger("status");
            Integer view = values.getAsInteger("view_type");
            Long sent = values.getAsLong("sent_at");
            if (sid == null || sid <= 0 || mid == null || mid <= 0 || body == null ||
                body.isEmpty() || body.length() > 16000 || type == null || status == null ||
                view == null || sent == null) return null;
            ContentValues safe = new ContentValues();
            safe.put("session_id", sid);
            safe.put("message_id", mid);
            safe.put("content", body);
            safe.put("msg_type", type);
            safe.put("status", status);
            safe.put("view_type", view);
            safe.put("sent_at", sent);
            safe.put("saved_at", System.currentTimeMillis());
            synchronized (LOCK) {
                SQLiteDatabase db = archive.getWritableDatabase();
                long id = db.insertWithOnConflict("saved", null, safe, SQLiteDatabase.CONFLICT_IGNORE);
                if (id == -1) {
                    // Already captured this particular message; never replace the original snapshot.
                    return ARCHIVE_URI;
                }
                // Hard cap prevents unbounded sensitive-message accumulation.
                db.execSQL("DELETE FROM saved WHERE _id NOT IN (SELECT _id FROM saved ORDER BY _id DESC LIMIT 500)");
                return Uri.withAppendedPath(ARCHIVE_URI, String.valueOf(id));
            }
        } catch (Throwable ignored) { return null; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] selectionArgs, String sortOrder) {
        if (getContext() == null || !isAllowedCaller()) return null;
        if (SETTING_URI.equals(uri)) {
            if (projection == null || projection.length != 1 || !"enabled".equals(projection[0])) return null;
            MatrixCursor c = new MatrixCursor(new String[]{"enabled"});
            c.addRow(new Object[]{getContext().getSharedPreferences(PREFS, 0)
                .getBoolean("enabled", false) ? 1 : 0});
            return c;
        }
        if (!ARCHIVE_URI.equals(uri)) return null;
        if ("restore".equals(selection) && selectionArgs != null && selectionArgs.length == 1) {
            // Narrow, fixed projection. The target app cannot read the entire archive.
            if (!getContext().getSharedPreferences(PREFS, 0).getBoolean("enabled", false)) return null;
            String[] fixed = {"message_id", "content", "msg_type", "status", "view_type"};
            if (!same(projection, fixed)) return null;
            try {
                long sid = Long.parseLong(selectionArgs[0]);
                if (sid <= 0) return null;
                return archive.getReadableDatabase().query("saved", fixed, "session_id=?",
                    new String[]{String.valueOf(sid)}, null, null, "_id DESC", "500");
            } catch (Exception ignored) { return null; }
        }
        // Full-text archive may only be read by the module's own UID, never the target app.
        if (Binder.getCallingUid() != Process.myUid() || projection != null ||
            selection != null || selectionArgs != null) return null;
        return archive.getReadableDatabase().query("saved",
            new String[]{"_id", "session_id", "message_id", "content", "msg_type", "sent_at", "saved_at"},
            null, null, null, null, "_id DESC", "200");
    }

    private static boolean same(String[] a, String[] b) {
        if (a == null || a.length != b.length) return false;
        for (int i = 0; i < b.length; i++) if (!b[i].equals(a[i])) return false;
        return true;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!ARCHIVE_URI.equals(uri) || getContext() == null ||
            Binder.getCallingUid() != Process.myUid() || selection != null ||
            selectionArgs != null) return 0;
        return archive.getWritableDatabase().delete("saved", null, null);
    }

    private boolean isAllowedCaller() {
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String pkg : packages) {
            if (TARGET.equals(pkg) || getContext().getPackageName().equals(pkg)) return true;
        }
        return false;
    }

    @Override public String getType(Uri u) { return null; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
