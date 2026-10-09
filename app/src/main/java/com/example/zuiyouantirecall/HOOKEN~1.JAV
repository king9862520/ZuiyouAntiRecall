package com.example.zuiyouantirecall;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
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

/** V17: private-chat anti-recall only. No paper-plane code, network interception or database mutation. */
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

    private static void report(String message) {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put("event", "antirecall.v17." + message);
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
                    report("attach module=v17 revoke_hooks=" + revokeHookCount
                        + " history_hooks=" + historyHookCount);
                }
            });
        } catch (Throwable t) { XposedBridge.log("AntiRecall V17 attach failed: " + safeType(t)); }
        installRecallBlock(lp.classLoader);
        installHistoryRestore(lp.classLoader);
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

    private static String safeType(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
    }
}
