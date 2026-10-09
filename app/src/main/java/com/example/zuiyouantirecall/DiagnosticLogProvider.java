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
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** V18: V17-compatible private media and recall archive; no captured message body is written to diagnostic TXT. */
public final class DiagnosticLogProvider extends ContentProvider {
    static final String AUTHORITY = "com.example.zuiyouantirecall.log";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/event");
    static final Uri SETTING_URI = Uri.parse("content://" + AUTHORITY + "/setting");
    static final Uri ARCHIVE_URI = Uri.parse("content://" + AUTHORITY + "/archive");
    static final Uri MEDIA_URI = Uri.parse("content://" + AUTHORITY + "/media");
    static final String PREFS = "settings";
    static final String TARGET = "cn.xiaochuankeji.tieba";
    static final String FILE = "ZuiyouAntiRecall_log.txt";
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 1024L * 1024L;
    private ArchiveDb archive;

    private static final class ArchiveDb extends SQLiteOpenHelper {
        ArchiveDb(android.content.Context context) { super(context, "recall_archive_v17.db", null, 2); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE saved (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL, message_id INTEGER NOT NULL," +
                "content TEXT NOT NULL, msg_type INTEGER NOT NULL," +
                "status INTEGER NOT NULL, view_type INTEGER NOT NULL," +
                "sent_at INTEGER NOT NULL, saved_at INTEGER NOT NULL," +
                "media_kind TEXT NOT NULL DEFAULT 'none'," +
                "media_bytes INTEGER NOT NULL DEFAULT 0," +
                "media_status TEXT NOT NULL DEFAULT 'none'," +
                "UNIQUE(session_id,message_id))");
            db.execSQL("CREATE INDEX ix_saved_session ON saved(session_id)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE saved ADD COLUMN media_kind TEXT NOT NULL DEFAULT 'none'");
                db.execSQL("ALTER TABLE saved ADD COLUMN media_bytes INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE saved ADD COLUMN media_status TEXT NOT NULL DEFAULT 'none'");
            }
        }
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
        if (event == null || !event.matches("antirecall\\.v18\\.[A-Za-z0-9_.$:=> -]{1,500}")) return false;
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
                pruneMedia(db);
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
            new String[]{"_id", "session_id", "message_id", "content", "msg_type", "sent_at", "saved_at",
                "media_kind", "media_bytes", "media_status"},
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
        int n = archive.getWritableDatabase().delete("saved", null, null);
        removeMediaFiles();
        return n;
    }


    private File mediaDir() {
        File dir = new File(getContext().getFilesDir(), "recall_media_v18");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    // Only fixed numeric IDs from an existing row; no caller-controlled file paths.
    private long[] idFor(List<String> parts, String root, int wanted) {
        if (parts.size() != wanted || !root.equals(parts.get(0))) return null;
        try {
            if (!parts.get(1).matches("[1-9][0-9]{0,18}") ||
                !parts.get(2).matches("[1-9][0-9]{0,18}")) return null;
            long sid = Long.parseLong(parts.get(1));
            long mid = Long.parseLong(parts.get(2));
            if (sid <= 0 || mid <= 0) return null;
            return new long[]{sid, mid};
        } catch (Exception e) { return null; }
    }

    private boolean hasRecord(long sid, long mid) {
        try (Cursor c = archive.getReadableDatabase().query("saved", new String[]{"_id"},
                "session_id=? AND message_id=?", new String[]{String.valueOf(sid),
                String.valueOf(mid)}, null, null, null, "1")) {
            return c != null && c.moveToFirst();
        }
    }

    private File mediaFile(long sid, long mid, String kind, boolean part) {
        String ext = "image".equals(kind) ? "jpg" : "audio";
        return new File(mediaDir(), sid + "_" + mid + "." + ext + (part ? ".part" : ""));
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
        throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no_context");
        List<String> parts = uri.getPathSegments();
        if (parts.size() == 4 && "media_write".equals(parts.get(0)) && "w".equals(mode)) {
            if (!isAllowedCaller() || !isTargetCaller()) throw new FileNotFoundException("denied");
            long[] ids = idFor(parts, "media_write", 4);
            String kind = parts.get(3);
            if (ids == null || !("image".equals(kind) || "voice".equals(kind)) ||
                !hasRecord(ids[0], ids[1]) || !isEnabled())
                throw new FileNotFoundException("invalid_capture");
            File temp = mediaFile(ids[0], ids[1], kind, true);
            return ParcelFileDescriptor.open(temp,
                ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE |
                    ParcelFileDescriptor.MODE_TRUNCATE);
        }
        if (parts.size() == 3 && "media".equals(parts.get(0)) && "r".equals(mode)) {
            if (Binder.getCallingUid() != Process.myUid()) throw new FileNotFoundException("denied");
            long[] ids = idFor(parts, "media", 3);
            if (ids == null) throw new FileNotFoundException("invalid_id");
            String kind = null;
            try (Cursor c = archive.getReadableDatabase().query("saved", new String[]{"media_kind"},
                    "session_id=? AND message_id=?", new String[]{String.valueOf(ids[0]),
                    String.valueOf(ids[1])}, null, null, null, "1")) {
                if (c != null && c.moveToFirst()) kind = c.getString(0);
            }
            if (!("image".equals(kind) || "voice".equals(kind)))
                throw new FileNotFoundException("not_saved");
            File f = mediaFile(ids[0], ids[1], kind, false);
            if (!f.isFile()) throw new FileNotFoundException("not_found");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException("invalid_mode");
    }

    private boolean isEnabled() {
        return getContext().getSharedPreferences(PREFS, 0).getBoolean("enabled", false);
    }

    private boolean isTargetCaller() {
        String[] pkgs = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (pkgs == null) return false;
        for (String pkg : pkgs) if (TARGET.equals(pkg)) return true;
        return false;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        if (getContext() == null || !isAllowedCaller() || !isTargetCaller() || !isEnabled() ||
            selection != null || args != null) return 0;
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 4) return 0;
        if ("media_status".equals(parts.get(0))) {
            long[] ids = idFor(parts, "media_status", 4);
            String status = values == null ? null : values.getAsString("status");
            if (ids == null || !"set".equals(parts.get(3)) || status == null ||
                !status.matches("[a-zA-Z0-9_]{1,80}")) return 0;
            ContentValues safe = new ContentValues(); safe.put("media_status", status);
            return archive.getWritableDatabase().update("saved", safe,
                "session_id=? AND message_id=? AND media_kind='none'", new String[]{String.valueOf(ids[0]),
                String.valueOf(ids[1])});
        }
        if (!"media_commit".equals(parts.get(0))) return 0;
        long[] ids = idFor(parts, "media_commit", 4);
        String kind = parts.get(3);
        if (ids == null || !("image".equals(kind) || "voice".equals(kind)) ||
            !hasRecord(ids[0], ids[1])) return 0;
        File src = mediaFile(ids[0], ids[1], kind, true);
        long size = src.length();
        long max = "image".equals(kind) ? 5L * 1024L * 1024L : 12L * 1024L * 1024L;
        if (!src.isFile() || size < 20 || size > max || !validMedia(src, kind)) {
            src.delete(); return 0;
        }
        File dest = mediaFile(ids[0], ids[1], kind, false);
        // Never replace an existing, already completed media snapshot.
        if (!dest.exists() && !src.renameTo(dest)) return 0;
        if (dest.exists()) src.delete();
        ContentValues update = new ContentValues();
        update.put("media_kind", kind);
        update.put("media_bytes", dest.length());
        update.put("media_status", "saved");
        int changed = archive.getWritableDatabase().update("saved", update,
            "session_id=? AND message_id=?", new String[]{String.valueOf(ids[0]),
            String.valueOf(ids[1])});
        if (changed == 1) enforceMediaBudget(archive.getWritableDatabase());
        return changed;
    }

    private static boolean validMedia(File src, String kind) {
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] h = new byte[16];
            if (in.read(h) < 12) return false;
            if ("image".equals(kind)) {
                return ((h[0] & 255) == 0xff && (h[1] & 255) == 0xd8) ||
                    ((h[0] & 255) == 137 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G');
            }
            return (h[0] == 'I' && h[1] == 'D' && h[2] == '3') ||
                (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') ||
                (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') ||
                (h[0] == '#' && h[1] == '!' && h[2] == 'A' && h[3] == 'M' && h[4] == 'R') ||
                (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') ||
                ((h[0] & 255) == 255 && ((h[1] & 224) == 224)) ||
                (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' &&
                    h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E');
        } catch (IOException e) { return false; }
    }

    /** Keep at most 256 MiB of captured media; evict oldest and mark the record accurately. */
    private void enforceMediaBudget(SQLiteDatabase db) {
        final long budget = 256L * 1024L * 1024L;
        ArrayList<long[]> items = new ArrayList<>();
        long total = 0;
        try (Cursor c = db.query("saved", new String[]{"session_id", "message_id", "media_bytes"},
                "media_kind!=?", new String[]{"none"}, null, null, "saved_at ASC", "500")) {
            while (c != null && c.moveToNext()) {
                long size = Math.max(0, c.getLong(2));
                total += size;
                items.add(new long[]{c.getLong(0), c.getLong(1), size});
            }
        }
        if (total <= budget) return;
        for (long[] item : items) {
            if (total <= budget) break;
            long sid = item[0], mid = item[1];
            String kind = "none";
            try (Cursor c = db.query("saved", new String[]{"media_kind"},
                    "session_id=? AND message_id=?", new String[]{String.valueOf(sid),
                    String.valueOf(mid)}, null, null, null, "1")) {
                if (c != null && c.moveToFirst()) kind = c.getString(0);
            }
            if (!("image".equals(kind) || "voice".equals(kind))) continue;
            File f = mediaFile(sid, mid, kind, false);
            if (f.exists() && !f.delete()) continue;
            ContentValues changed = new ContentValues();
            changed.put("media_kind", "none");
            changed.put("media_bytes", 0);
            changed.put("media_status", "media_evicted_storage_limit");
            db.update("saved", changed, "session_id=? AND message_id=?",
                new String[]{String.valueOf(sid), String.valueOf(mid)});
            total -= item[2];
        }
    }

    private void pruneMedia(SQLiteDatabase db) {
        File dir = mediaDir();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        java.util.Set<String> retain = new java.util.HashSet<>();
        try (Cursor c = db.query("saved", new String[]{"session_id", "message_id", "media_kind"},
                null, null, null, null, "_id DESC", "500")) {
            while (c != null && c.moveToNext()) {
                String kind = c.getString(2);
                if ("image".equals(kind) || "voice".equals(kind))
                    retain.add(c.getLong(0) + "_" + c.getLong(1) + "." +
                        ("image".equals(kind) ? "jpg" : "audio"));
            }
        }
        for (File f : files) {
            if (f.isFile() && !f.getName().endsWith(".part") && !retain.contains(f.getName()))
                f.delete();
        }
    }

    private void removeMediaFiles() {
        File[] files = mediaDir().listFiles();
        if (files != null) for (File f : files) if (f.isFile()) f.delete();
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

}
