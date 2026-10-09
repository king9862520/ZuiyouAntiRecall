package com.example.zuiyouantirecall;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.net.Uri;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.NavigableSet;
import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** V18: private-chat anti-recall and local media snapshots only. No paper-plane code, network interception or database mutation. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "cn.xiaochuankeji.tieba";
    private static final String CHAT_ACTIVITY = "cn.xiaochuankeji.tieba.ui.chat.ChatActivity";
    private static final String CHAT_ADAPTER = "cn.xiaochuankeji.tieba.ui.chat.adapter.ChatAdapter";
    private static volatile Context appContext;
    private static final AtomicInteger saved = new AtomicInteger();
    private static volatile int revokeHookCount = 0;
    private static volatile int historyHookCount = 0;
    private static final AtomicInteger blocked = new AtomicInteger();
    private static final AtomicInteger restored = new AtomicInteger();
    // ImageHolder bind is the only source of an image association; update on view recycling.
    private static final Map<Long, WeakReference<ImageView>> imageViews =
        Collections.synchronizedMap(new HashMap<Long, WeakReference<ImageView>>());
    private static final Map<ImageView, Long> imageBindings =
        Collections.synchronizedMap(new WeakHashMap<ImageView, Long>());
    private static final Set<Long> voiceBindings = Collections.synchronizedSet(new HashSet<Long>());
    private static volatile int imageHooks = 0;
    private static volatile int voiceHooks = 0;
    private static final int MAX_IMAGE_EDGE = 1600;
    private static final long MAX_AUDIO_BYTES = 12L * 1024L * 1024L;

    private static void report(String message) {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put("event", "antirecall.v18." + message);
            ctx.getContentResolver().insert(DiagnosticLogProvider.URI, values);
        } catch (Throwable ignored) { }
    }

    private static boolean enabled() {
        Context ctx = appContext;
        if (ctx == null) return false;
        try (Cursor c = ctx.getContentResolver().query(
            DiagnosticLogProvider.SETTING_URI, new String[]{"enabled"}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(0) == 1;
        } catch (Throwable ignored) { return false; }
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName) || !TARGET.equals(lp.processName)) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    appContext = (Context) p.args[0];
                    report("attach module=v18 revoke_hooks=" + revokeHookCount
                        + " history_hooks=" + historyHookCount);
                }
            });
        } catch (Throwable t) { XposedBridge.log("AntiRecall V18 attach failed: " + safeType(t)); }
        installRecallBlock(lp.classLoader);
        installHistoryRestore(lp.classLoader);
        installMediaBinding(lp.classLoader);
    }

    /**
     * chatRevoke(e91) is called after the app has reconciled storage. The visible ChatAdapter
     * still contains the pre-withdrawal b81 for a currently open chat. Capture it first,
     * in independent module-owned storage, then preserve the original UI behavior of V15.
     * Never intercept arbitrary database writes or rewrite the target's SQLite DB.
     */
    private static void installRecallBlock(ClassLoader loader) {
        try {
            Class<?> cls = Class.forName(CHAT_ACTIVITY, false, loader);
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"chatRevoke".equals(m.getName())) continue;
                final boolean voidReturn = m.getReturnType() == Void.TYPE;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled() || !voidReturn) return;
                        String outcome = saveBeforeUiRecall(p);
                        report("capture result=" + outcome);
                        report("revoke_blocked total=" + blocked.incrementAndGet());
                        p.setResult(null);
                    }
                });
                n++;
            }
            revokeHookCount = n;
            report("hook_revoke count=" + n);
        } catch (Throwable t) { report("hook_revoke_failed type=" + safeType(t)); }
    }

    private static String saveBeforeUiRecall(XC_MethodHook.MethodHookParam p) {
        if (appContext == null || p.args.length != 1 || p.args[0] == null) return "missing_context";
        try {
            Object event = p.args[0];
            if (!"e91".equals(event.getClass().getName())) return "unexpected_event";
            long sessionId = XposedHelpers.getLongField(event, "a");
            long messageId = XposedHelpers.getLongField(event, "b");
            if (sessionId <= 0 || messageId <= 0) return "invalid_id";
            Object adapter = XposedHelpers.getObjectField(p.thisObject, "J");
            if (adapter == null || !CHAT_ADAPTER.equals(adapter.getClass().getName())) return "no_visible_adapter";
            Object message = XposedHelpers.callMethod(adapter, "C", messageId);
            if (message == null || !"b81".equals(message.getClass().getName())) return "not_in_loaded_history";
            if (XposedHelpers.getLongField(message, "k") != messageId) return "id_mismatch";
            Object raw = XposedHelpers.getObjectField(message, "g");
            if (!(raw instanceof String)) return "no_original_content";
            String content = (String) raw;
            if (content.length() == 0 || content.length() > 16000) return "unsupported_content";
            int msgType = XposedHelpers.getIntField(message, "h");
            int status = XposedHelpers.getIntField(message, "i");
            int viewType = XposedHelpers.getIntField(message, "j");
            long sentAt = XposedHelpers.getLongField(message, "l");
            ContentValues values = new ContentValues();
            values.put("session_id", sessionId);
            values.put("message_id", messageId);
            values.put("content", content);
            values.put("msg_type", msgType);
            values.put("status", status);
            values.put("view_type", viewType);
            values.put("sent_at", sentAt);
            android.net.Uri result = appContext.getContentResolver().insert(
                DiagnosticLogProvider.ARCHIVE_URI, values);
            if (result == null) return "storage_rejected";
            saved.incrementAndGet();
            String mediaState;
            if (isBoundImage(messageId)) {
                mediaState = captureImage(sessionId, messageId);
            } else if (voiceBindings.contains(messageId)) {
                mediaState = captureVoice(sessionId, messageId, content);
            } else {
                mediaState = "not_bound";
            }
            markMediaStatus(sessionId, messageId, mediaState);
            report("media_capture kind=" + (isBoundImage(messageId) ? "image" :
                voiceBindings.contains(messageId) ? "voice" : "unknown") + " result=" + mediaState);
            return "saved";
        } catch (Throwable t) {
            return "failed_" + safeType(t);
        }
    }

    /**
     * On history replace / prepend / append, restore the pre-recall b81 fields in a COPY of
     * an existing row with the same message id. We do not mutate the original row, reorder
     * messages, create phantom rows, alter the official database or contact the server.
     * A saved record without a corresponding current row remains viewable in the module UI.
     */
    private static void installHistoryRestore(ClassLoader loader) {
        try {
            Class<?> adapterClass = Class.forName(CHAT_ADAPTER, false, loader);
            int n = 0;
            for (Method m : adapterClass.getDeclaredMethods()) {
                if (!("V".equals(m.getName()) || "J".equals(m.getName()) || "K".equals(m.getName()))
                    || m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != List.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled() || p.args.length != 1 || !(p.args[0] instanceof List)) return;
                        try {
                            @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) p.args[0];
                            if (rows.isEmpty()) return;
                            Object session = XposedHelpers.getObjectField(p.thisObject, "b");
                            if (session == null) return;
                            long sid = XposedHelpers.getLongField(session, "session_id");
                            if (sid <= 0) return;
                            Map<Long, Snapshot> known = loadSaved(sid);
                            if (known.isEmpty()) return;
                            ArrayList<Object> patched = null;
                            int changes = 0;
                            for (int i = 0; i < rows.size(); i++) {
                                Object row = rows.get(i);
                                if (row == null || !"b81".equals(row.getClass().getName())) continue;
                                long mid = XposedHelpers.getLongField(row, "k");
                                Snapshot snapshot = known.get(mid);
                                if (snapshot == null) continue;
                                Object current = XposedHelpers.getObjectField(row, "g");
                                int currentType = XposedHelpers.getIntField(row, "h");
                                int currentView = XposedHelpers.getIntField(row, "j");
                                if (snapshot.content.equals(current) && currentType == snapshot.msgType
                                    && currentView == snapshot.viewType
                                    && XposedHelpers.getIntField(row, "i") == snapshot.status) continue;
                                Constructor<?> copyCtor = row.getClass().getDeclaredConstructor(row.getClass());
                                copyCtor.setAccessible(true);
                                Object copy = copyCtor.newInstance(row);
                                XposedHelpers.setObjectField(copy, "g", snapshot.content);
                                XposedHelpers.setIntField(copy, "h", snapshot.msgType);
                                XposedHelpers.setIntField(copy, "i", snapshot.status);
                                XposedHelpers.setIntField(copy, "j", snapshot.viewType);
                                if (patched == null) patched = new ArrayList<>(rows);
                                patched.set(i, copy);
                                changes++;
                            }
                            if (patched != null) {
                                p.args[0] = patched;
                                restored.addAndGet(changes);
                                report("history_restored rows=" + changes + " total=" + restored.get());
                            }
                        } catch (Throwable t) { report("restore_failed type=" + safeType(t)); }
                    }
                });
                n++;
            }
            historyHookCount = n;
            report("hook_history count=" + n);
        } catch (Throwable t) { report("hook_history_failed type=" + safeType(t)); }
    }

    private static final class Snapshot {
        final String content;
        final int msgType, status, viewType;
        Snapshot(String body, int type, int statusValue, int view) {
            content = body; msgType = type; status = statusValue; viewType = view;
        }
    }

    private static Map<Long, Snapshot> loadSaved(long sid) {
        Map<Long, Snapshot> snapshots = new HashMap<>();
        Context ctx = appContext;
        if (ctx == null) return snapshots;
        try (Cursor c = ctx.getContentResolver().query(DiagnosticLogProvider.ARCHIVE_URI,
                new String[]{"message_id", "content", "msg_type", "status", "view_type"},
                "restore", new String[]{String.valueOf(sid)}, null)) {
            if (c == null) return snapshots;
            while (c.moveToNext()) {
                long mid = c.getLong(0);
                String content = c.getString(1);
                if (mid > 0 && content != null) snapshots.put(mid,
                    new Snapshot(content, c.getInt(2), c.getInt(3), c.getInt(4)));
            }
        } catch (Throwable t) { report("archive_query_failed type=" + safeType(t)); }
        return snapshots;
    }


    /** Image/voice binding is read-only. No message bodies or URLs in logs. */
    private static void installMediaBinding(ClassLoader loader) {
        imageHooks = hookHolder(loader,
            "cn.xiaochuankeji.tieba.ui.chat.holder.ImageHolder", true);
        voiceHooks = hookHolder(loader,
            "cn.xiaochuankeji.tieba.ui.chat.holder.ChatVoiceHolder", false);
        report("media_hooks image=" + imageHooks + " voice=" + voiceHooks);
    }

    private static int hookHolder(ClassLoader loader, String holderName, boolean image) {
        try {
            Class<?> cls = Class.forName(holderName, false, loader);
            int count = 0;
            for (Method method : cls.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (!"O".equals(method.getName()) || p.length != 2 ||
                    !"b81".equals(p[0].getName()) || p[1] != Integer.TYPE) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!enabled() || param.args.length == 0 || param.args[0] == null) return;
                        try {
                            long mid = XposedHelpers.getLongField(param.args[0], "k");
                            if (mid <= 0) return;
                            if (image) {
                                Object view = XposedHelpers.getObjectField(param.thisObject, "image");
                                if (!(view instanceof ImageView)) return;
                                ImageView iv = (ImageView) view;
                                imageViews.put(mid, new WeakReference<>(iv));
                                imageBindings.put(iv, mid);
                            } else {
                                voiceBindings.add(mid);
                            }
                        } catch (Throwable ignored) { }
                    }
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            report("media_hook_error kind=" + (image ? "image" : "voice") +
                " type=" + safeType(t));
            return 0;
        }
    }

    private static boolean isBoundImage(long mid) {
        WeakReference<ImageView> ref = imageViews.get(mid);
        ImageView view = ref == null ? null : ref.get();
        return view != null && Long.valueOf(mid).equals(imageBindings.get(view));
    }

    /** Snapshot ONLY the drawable currently bound to this exact message. Never fetch URLs. */
    private static String captureImage(long sid, long mid) {
        WeakReference<ImageView> ref = imageViews.get(mid);
        ImageView view = ref == null ? null : ref.get();
        if (view == null || !Long.valueOf(mid).equals(imageBindings.get(view))) return "image_recycled";
        if (!view.isShown()) return "image_not_visible";
        try {
            Drawable d = view.getDrawable();
            if (!(d instanceof BitmapDrawable)) return "image_not_loaded";
            Bitmap original = ((BitmapDrawable) d).getBitmap();
            if (original == null || original.isRecycled() || original.getWidth() < 120 ||
                original.getHeight() < 120) return "image_not_loaded";
            int w = original.getWidth(), h = original.getHeight();
            double ratio = Math.min(1.0, (double) MAX_IMAGE_EDGE / Math.max(w, h));
            Bitmap bounded = ratio == 1.0 ? original :
                Bitmap.createScaledBitmap(original, Math.max(1, (int)(w * ratio)),
                    Math.max(1, (int)(h * ratio)), true);
            Uri write = mediaUri("media_write", sid, mid, "image");
            boolean encoded = false;
            try (OutputStream dest = appContext.getContentResolver().openOutputStream(write)) {
                if (dest == null) return "media_open_failed";
                encoded = bounded.compress(Bitmap.CompressFormat.JPEG, 90, dest);
                dest.flush();
            } finally { if (bounded != original) bounded.recycle(); }
            if (!encoded) return "image_encode_failed";
            return commitMedia(sid, mid, "image") ? "image_saved" : "image_commit_failed";
        } catch (Throwable t) { return "image_" + safeType(t); }
    }

    /** Audio is saved only if ExoPlayer has a fully cached, length-verified stream. */
    private static String captureVoice(long sid, long mid, String content) {
        try {
            String url = null;
            // APK-confirmed ct1.b(String,long) parses the chat voice JSON into b41.
            // b41.a may be a local playable file or ExoPlayer URL; no raw value is logged.
            try {
                Class<?> decoder = Class.forName("ct1", false, appContext.getClassLoader());
                Object voice = XposedHelpers.callStaticMethod(decoder, "b", content, mid);
                if (voice != null) {
                    Object rawPath = XposedHelpers.getObjectField(voice, "a");
                    if (rawPath instanceof String) url = (String)rawPath;
                }
            } catch (Throwable ignored) { }
            if (url == null || url.isEmpty()) url = mediaUrl(new JSONObject(content), 0);
            if (url == null) return "voice_url_unavailable";
            File local = secureVoiceFile(url);
            if (local != null) {
                if (local.length() < 20 || local.length() > MAX_AUDIO_BYTES)
                    return "voice_length_unknown";
                Uri write = mediaUri("media_write", sid, mid, "voice");
                try (FileInputStream input = new FileInputStream(local);
                     OutputStream output = appContext.getContentResolver().openOutputStream(write)) {
                    if (output == null) return "media_open_failed";
                    byte[] buf = new byte[16384]; int n;
                    while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
                    output.flush();
                }
                return commitMedia(sid, mid, "voice") ? "voice_saved" : "voice_commit_failed";
            }
            if (!(url.startsWith("https://") || url.startsWith("http://")))
                return "voice_not_local";
            Class<?> cls = Class.forName(
                "com.google.android.exoplayer2.ext.okhttp.DataSourceCache", false,
                appContext.getClassLoader());
            Object cacheManager = XposedHelpers.callStaticMethod(cls, "getInstance");
            if (cacheManager == null) return "voice_cache_missing";
            Object rawSpans = XposedHelpers.callMethod(cacheManager, "getCachedSpans", url);
            if (!(rawSpans instanceof NavigableSet)) return "voice_cache_missing";
            @SuppressWarnings("unchecked") NavigableSet<Object> spans = (NavigableSet<Object>)rawSpans;
            if (spans.isEmpty()) return "voice_cache_missing";
            // ExoPlayer CacheUtil returns (total length, cached bytes).
            // Both must match before exporting; partial playback is NOT enough.
            Object cached = XposedHelpers.callMethod(cacheManager, "getCached", url, null);
            if (cached == null) return "voice_length_unknown";
            Object totalRaw = XposedHelpers.getObjectField(cached, "first");
            Object presentRaw = XposedHelpers.getObjectField(cached, "second");
            if (!(totalRaw instanceof Number) || !(presentRaw instanceof Number))
                return "voice_length_unknown";
            long expected = ((Number) totalRaw).longValue();
            long available = ((Number) presentRaw).longValue();
            if (expected < 20 || expected > MAX_AUDIO_BYTES) return "voice_length_unknown";
            if (available < expected) return "voice_cache_partial";
            long next = 0;
            ArrayList<File> files = new ArrayList<>();
            ArrayList<Long> lengths = new ArrayList<>();
            for (Object span : spans) {
                long pos = ((Number)XposedHelpers.getObjectField(span, "position")).longValue();
                long length = ((Number)XposedHelpers.getObjectField(span, "length")).longValue();
                File part = (File)XposedHelpers.getObjectField(span, "file");
                if (pos != next || length <= 0 || part == null ||
                    !part.isFile() || part.length() < length) return "voice_cache_partial";
                if (next + length > expected) return "voice_cache_partial";
                files.add(part); lengths.add(length); next += length;
                if (next == expected) break;
            }
            if (next != expected) return "voice_cache_partial";
            Uri write = mediaUri("media_write", sid, mid, "voice");
            try (OutputStream output = appContext.getContentResolver().openOutputStream(write)) {
                if (output == null) return "media_open_failed";
                byte[] buf = new byte[16384];
                for (int i = 0; i < files.size(); i++) {
                    long remain = lengths.get(i);
                    try (FileInputStream input = new FileInputStream(files.get(i))) {
                        while (remain > 0) {
                            int n = input.read(buf, 0, (int)Math.min(buf.length, remain));
                            if (n <= 0) return "voice_cache_partial";
                            output.write(buf, 0, n); remain -= n;
                        }
                    }
                }
                output.flush();
            }
            return commitMedia(sid, mid, "voice") ? "voice_saved" : "voice_commit_failed";
        } catch (Throwable t) { return "voice_" + safeType(t); }
    }


    /** Only existing local, app-owned files; no arbitrary filesystem path traversal. */
    private static File secureVoiceFile(String source) {
        try {
            String path = source;
            if (source.startsWith("file://")) path = Uri.parse(source).getPath();
            if (path == null || !path.startsWith("/")) return null;
            File file = new File(path).getCanonicalFile();
            if (!file.isFile()) return null;
            ArrayList<File> roots = new ArrayList<>();
            roots.add(appContext.getFilesDir());
            roots.add(appContext.getCacheDir());
            File extraFiles = appContext.getExternalFilesDir(null);
            File extraCache = appContext.getExternalCacheDir();
            if (extraFiles != null) roots.add(extraFiles);
            if (extraCache != null) roots.add(extraCache);
            for (File root : roots) {
                if (root == null) continue;
                String trusted = root.getCanonicalPath() + File.separator;
                if (file.getCanonicalPath().startsWith(trusted)) return file;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // Discover URL keys solely inside this already loaded message's own JSON.
    // Raw addresses are never written to the module log or archive.
    private static String mediaUrl(Object node, int depth) {
        if (depth > 5) return null;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject)node;
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object value = obj.opt(k);
                String lower = k.toLowerCase(java.util.Locale.ROOT);
                if (value instanceof String && (lower.contains("url") || lower.contains("audio") ||
                    lower.contains("voice") || lower.contains("src") || lower.contains("play"))) {
                    String candidate = (String)value;
                    if (candidate.length() < 2048 &&
                        (candidate.startsWith("https://") || candidate.startsWith("http://")))
                        return candidate;
                }
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    String result = mediaUrl(value, depth + 1);
                    if (result != null) return result;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray)node;
            for (int i = 0; i < Math.min(arr.length(), 12); i++) {
                String result = mediaUrl(arr.opt(i), depth + 1);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static Uri mediaUri(String segment, long sid, long mid, String kind) {
        return Uri.parse("content://" + DiagnosticLogProvider.AUTHORITY + "/" + segment +
            "/" + sid + "/" + mid + "/" + kind);
    }
    private static boolean commitMedia(long sid, long mid, String kind) {
        Uri uri = mediaUri("media_commit", sid, mid, kind);
        return appContext.getContentResolver().update(uri, new ContentValues(), null, null) == 1;
    }
    private static void markMediaStatus(long sid, long mid, String status) {
        try {
            ContentValues v = new ContentValues();
            v.put("status", status);
            appContext.getContentResolver().update(mediaUri("media_status", sid, mid, "set"),
                v, null, null);
        } catch (Throwable ignored) { }
    }

    private static String safeType(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
    }
}
