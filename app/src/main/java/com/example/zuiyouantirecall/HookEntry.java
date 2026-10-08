package com.example.zuiyouantirecall;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.widget.Toast;
import android.widget.TextView;
import android.view.View;
import android.webkit.WebView;
import android.app.Activity;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import android.database.Cursor;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Experimental hook. Never records private message text. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "cn.xiaochuankeji.tieba";
    private static volatile Context appContext;
    private static final AtomicInteger networkSeen = new AtomicInteger();
    private static volatile long lastClickMs = 0;
    private static final Map<String, Long> lastEvents = new HashMap<>();
    private static volatile Integer previousRemaining = null;
    private static synchronized void reportOnce(String key, String msg, long intervalMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = lastEvents.get(key);
        if (last != null && now >= last && now - last < intervalMs) return;
        lastEvents.put(key, now);
        report(msg);
    }
    private static void report(String message) {
        Context context = appContext;
        if (context == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put("event", message);
            context.getContentResolver().insert(DiagnosticLogProvider.URI, values);
        } catch (Throwable ignored) { }
    }
    private static boolean setting(String key) {
        Context context = appContext;
        if (context == null) return false;
        try (Cursor c = context.getContentResolver().query(DiagnosticLogProvider.SETTING_URI, new String[] {key}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(0) == 1;
        } catch (Throwable ignored) { return false; }
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName) || !TARGET.equals(lpparam.processName)) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    appContext = (Context) p.args[0];
                    report("app.attach process=main");
                }
            });
            Class<?> activity = Class.forName("cn.xiaochuankeji.tieba.ui.chat.ChatActivity", false, lpparam.classLoader);
            int count = 0;
            for (Method m : activity.getDeclaredMethods()) {
                if (!"chatRevoke".equals(m.getName())) continue;
                final boolean voidReturn = m.getReturnType() == Void.TYPE;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        boolean enabled = setting("enabled");
                        // Only intercept void-return methods: returning null from non-void methods can crash the app.
                        if (enabled && voidReturn) {
                            report("chatRevoke.blocked args=" + p.args.length);
                            p.setResult(null);
                        } else {
                            report("chatRevoke.observed args=" + p.args.length + " mode=" + (enabled ? "unsupported_return" : "off"));
                        }
                    }
                });
                count++;
            }
            report("hook.setup variants=" + count);
            setupPaperPlaneDiagnostics(lpparam.classLoader);
        } catch (Throwable t) {
            report("hook.setup.error type=" + t.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_"));
        }
    }
    /** Observe only coarse diagnostic events, never request payloads, URLs, identities or messages. */
    private static void setupPaperPlaneDiagnostics(ClassLoader loader) {
        report("paperplane.v5.setup.begin");
        try {
            // This marks the actual button click if it is implemented as a TextView/Button.
            XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled")) return;
                    if (!(p.thisObject instanceof TextView)) return;
                    CharSequence label = ((TextView)p.thisObject).getText();
                    if (label != null && label.toString().trim().contains("去聊天")) {
                        lastClickMs = android.os.SystemClock.elapsedRealtime();
                        networkSeen.set(0);
                        report("paperplane.chat_button.clicked");
                    }
                }
            });
            report("paperplane.v5.click_hook.ready");
        } catch (Throwable t) { report("paperplane.v5.click_hook.failed"); }
        try {
            Class<?> builder = Class.forName("okhttp3.Request$Builder", false, loader);
            XposedBridge.hookAllMethods(builder, "build", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.getResult() == null) return;
                    try { observeRequest(p.getResult(), "builder"); }
                    catch (Throwable ignored) { }
                }
            });
            report("paperplane.v5.okhttp_builder.ready");
        } catch (Throwable t) { report("paperplane.v5.okhttp_builder.unavailable"); }
        try {
            // Covers the common execution point even if requests are built elsewhere.
            Class<?> call = Class.forName("okhttp3.RealCall", false, loader);
            for (Method m : call.getDeclaredMethods()) {
                if (!"execute".equals(m.getName()) && !"enqueue".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!setting("paper_enabled")) return;
                        try { observeRequest(XposedHelpers.callMethod(p.thisObject, "request"), "call"); }
                        catch (Throwable ignored) { report("paperplane.v5.call_observed"); }
                    }
                });
            }
            report("paperplane.v5.okhttp_call.ready");
        } catch (Throwable t) { report("paperplane.v5.okhttp_call.unavailable"); }
        // RealCall response point is shared by synchronous and asynchronous calls in common OkHttp versions.
        // Only inspect metadata and a capped peek of quota responses; never consume the real response body.
        int responseHooks = 0;
        for (String name : new String[] {"okhttp3.RealCall", "okhttp3.internal.connection.RealCall"}) {
            try {
                Class<?> realCall = Class.forName(name, false, loader);
                for (Method method : realCall.getDeclaredMethods()) {
                    if (!"getResponseWithInterceptorChain".equals(method.getName())) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!setting("paper_enabled")) return;
                            try { observeResponse(p.thisObject, p.getResult(), p.getThrowable()); }
                            catch (Throwable ignored) { reportOnce("response_error", "paperplane.v5.response.inspect_failed", 10000); }
                        }
                    });
                    responseHooks++;
                }
            } catch (Throwable ignored) { }
        }
        report("paperplane.v5.response_hooks=" + responseHooks);
        try {
            XposedBridge.hookAllMethods(WebView.class, "loadUrl", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.args.length == 0 || !(p.args[0] instanceof String)) return;
                    String u = ((String)p.args[0]).toLowerCase(Locale.ROOT);
                    if (u.contains("paperplane") || u.contains("paper_plane")) report("paperplane.v5.webview_navigation");
                }
            });
            report("paperplane.v5.webview_hook.ready");
        } catch (Throwable t) { report("paperplane.v5.webview_hook.unavailable"); }
        try {
            XposedBridge.hookAllMethods(Toast.class, "makeText", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.args.length < 2) return;
                    Object value = p.args[1];
                    try {
                        String message = value instanceof CharSequence ? value.toString() :
                                value instanceof Integer ? ((Context)p.args[0]).getString((Integer)value) : "";
                        if (message.contains("次数") && (message.contains("用完") || message.contains("上限") || message.contains("不足"))) report("paperplane.quota_prompt.observed");
                    } catch (Throwable ignored) { }
                }
            });
            report("paperplane.v5.toast_hook.ready");
        } catch (Throwable t) { report("paperplane.v5.toast_hook.unavailable"); }
        report("paperplane.v5.setup.end");
    }
    private static void observeRequest(Object req, String origin) {
        if (req == null) return;
        try {
            Object url = XposedHelpers.callMethod(req, "url");
            String path = String.valueOf(XposedHelpers.callMethod(url, "encodedPath")).toLowerCase(Locale.ROOT);
            if (path.contains("paperplane") || path.contains("paper_plane")) {
                String kind = "other";
                if (path.contains("get_match_count")) kind = "count";
                else if (path.contains("match_fallback")) kind = "match_fallback";
                else if (path.endsWith("/match")) kind = "match";
                else if (path.contains("opportunity")) kind = "opportunity";
                reportOnce("request_" + kind, "paperplane.v5.request kind=" + kind, 1500);
            } else {
                long age = android.os.SystemClock.elapsedRealtime() - lastClickMs;
                if (lastClickMs > 0 && age >= 0 && age < 8000 && networkSeen.getAndIncrement() < 5)
                    reportOnce("network_click", "paperplane.v5.network_after_click", 2000);
            }
        } catch (Throwable ignored) { }
    }
    private static String requestKind(Object request) {
        if (request == null) return null;
        try {
            Object url = XposedHelpers.callMethod(request, "url");
            String path = String.valueOf(XposedHelpers.callMethod(url, "encodedPath")).toLowerCase(Locale.ROOT);
            if (!path.contains("paperplane") && !path.contains("paper_plane")) return null;
            if (path.contains("get_match_count")) return "count";
            if (path.contains("match_fallback")) return "match_fallback";
            if (path.endsWith("/match")) return "match";
            if (path.contains("opportunity")) return "opportunity";
            return "other";
        } catch (Throwable ignored) { return null; }
    }
    private static void observeResponse(Object call, Object response, Throwable error) {
        Object request = null;
        try { request = XposedHelpers.callMethod(call, "request"); } catch (Throwable ignored) { }
        if (request == null && response != null) {
            try { request = XposedHelpers.callMethod(response, "request"); } catch (Throwable ignored) { }
        }
        String kind = requestKind(request);
        if (kind == null) return;
        if (error != null || response == null) {
            reportOnce("response_fail_" + kind, "paperplane.v5.response kind=" + kind + " result=exception", 1000);
            return;
        }
        int status;
        try { status = ((Number) XposedHelpers.callMethod(response, "code")).intValue(); }
        catch (Throwable ignored) { return; }
        reportOnce("response_" + kind + "_" + status,
            "paperplane.v5.response kind=" + kind + " http=" + status, 1200);
        if (!"count".equals(kind)) return;
        // Best-effort: limited to 4096 bytes and explicit quota field names; never log raw JSON.
        try {
            Object peek = XposedHelpers.callMethod(response, "peekBody", 4096L);
            String body = String.valueOf(XposedHelpers.callMethod(peek, "string"));
            Integer remaining = findRemaining(new JSONObject(body), 0);
            if (remaining == null) {
                reportOnce("quota_unknown", "paperplane.v5.quota.fields_unavailable", 30000);
                return;
            }
            Integer before = previousRemaining;
            previousRemaining = remaining;
            if (before == null || !before.equals(remaining)) {
                String change = before == null ? "initial" : (remaining - before > 0 ? "increase" : "decrease");
                report("paperplane.v5.quota remaining=" + remaining + " trend=" + change);
            }
        } catch (Throwable ignored) {
            reportOnce("quota_unparsed", "paperplane.v5.quota.unparsed_or_encoded", 30000);
        }
    }
    private static Integer findRemaining(JSONObject obj, int depth) {
        if (depth > 3) return null;
        String[] names = {"remaining_count", "remain_count", "remaining_times", "remain_times", "left_count", "left_times", "match_count_left", "remaining", "remain"};
        for (String key : names) {
            Object val = obj.opt(key);
            if (val instanceof Number) return ((Number) val).intValue();
            if (val instanceof String && ((String) val).matches("[0-9]{1,6}")) return Integer.parseInt((String) val);
        }
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            Object child = obj.opt(keys.next());
            if (child instanceof JSONObject) {
                Integer found = findRemaining((JSONObject) child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

}
