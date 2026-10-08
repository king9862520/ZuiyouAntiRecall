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

/** Experimental diagnostic. No chat text, user IDs or raw requests logged. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "cn.xiaochuankeji.tieba";
    private static volatile Context appContext;
    private static final AtomicInteger networkSeen = new AtomicInteger();
    private static volatile long lastClickMs = 0;
    private static final Map<String, Long> lastEvents = new HashMap<>();
    private static volatile Integer previousRemaining = null;
    private static final AtomicInteger clickSequence = new AtomicInteger();
    private static volatile int activeClick = 0;
    private static final java.util.Set<String> responseSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    // Weak keys avoid retaining third-party requests or calls beyond their lifetime.
    private static final Map<Object, String> requestLinks = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, String>());
    private static final Map<Object, String> callLinks = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, String>());
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
        report("paperplane.v7.setup.begin");
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
                        activeClick = clickSequence.incrementAndGet();
                        report("paperplane.v7.click id=" + activeClick);
                    }
                }
            });
            report("paperplane.v7.click_hook.ready");
        } catch (Throwable t) { report("paperplane.v7.click_hook.failed"); }
        try {
            Class<?> builder = Class.forName("okhttp3.Request$Builder", false, loader);
            XposedBridge.hookAllMethods(builder, "build", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.getResult() == null) return;
                    try { observeRequest(p.getResult(), "builder"); }
                    catch (Throwable ignored) { }
                }
            });
            report("paperplane.v7.okhttp_builder.ready");
        } catch (Throwable t) { report("paperplane.v7.okhttp_builder.unavailable"); }
        try {
            // Covers the common execution point even if requests are built elsewhere.
            Class<?> call = Class.forName("okhttp3.RealCall", false, loader);
            for (Method m : call.getDeclaredMethods()) {
                if (!"execute".equals(m.getName()) && !"enqueue".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!setting("paper_enabled")) return;
                        try {
                            Object req = XposedHelpers.callMethod(p.thisObject, "request");
                            String link = captureLink(req);
                            callLinks.put(p.thisObject, link);
                            observeRequest(req, "call");
                        } catch (Throwable ignored) { reportOnce("call_failed", "paperplane.v7.call.inspect_failed", 10000); }
                    }
                });
            }
            report("paperplane.v7.okhttp_call.ready");
        } catch (Throwable t) { report("paperplane.v7.okhttp_call.unavailable"); }
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
                            catch (Throwable ignored) { reportOnce("response_error", "paperplane.v7.response.inspect_failed", 10000); }
                        }
                    });
                    responseHooks++;
                }
            } catch (Throwable ignored) { }
        }
        report("paperplane.v7.response_hooks=" + responseHooks);
        try {
            XposedBridge.hookAllMethods(WebView.class, "loadUrl", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.args.length == 0 || !(p.args[0] instanceof String)) return;
                    String u = ((String)p.args[0]).toLowerCase(Locale.ROOT);
                    if (u.contains("paperplane") || u.contains("paper_plane")) report("paperplane.v7.webview_navigation");
                }
            });
            report("paperplane.v7.webview_hook.ready");
        } catch (Throwable t) { report("paperplane.v7.webview_hook.unavailable"); }
        try {
            XposedBridge.hookAllMethods(Toast.class, "makeText", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.args.length < 2) return;
                    Object value = p.args[1];
                    try {
                        String message = value instanceof CharSequence ? value.toString() :
                                value instanceof Integer ? ((Context)p.args[0]).getString((Integer)value) : "";
                        if (isQuotaPrompt(message)) report("paperplane.v7.quota_prompt source=toast click=" + clickContext());
                    } catch (Throwable ignored) { }
                }
            });
            report("paperplane.v7.toast_hook.ready");
        } catch (Throwable t) { report("paperplane.v7.toast_hook.unavailable"); }
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.args.length == 0 || !(p.args[0] instanceof CharSequence)) return;
                    if (isQuotaPrompt(p.args[0].toString()))
                        reportOnce("quota_textview_" + activeClick, "paperplane.v7.quota_prompt source=textview click=" + clickContext(), 2000);
                }
            });
            report("paperplane.v7.text_hook.ready");
        } catch (Throwable t) { report("paperplane.v7.text_hook.unavailable"); }
        report("paperplane.v7.setup.end");
        setupQuotaTraceHooks(loader);
    }

    /** Read-only tracing at the application API and result handlers found in 7.3.19.2. */
    private static void setupQuotaTraceHooks(ClassLoader loader) {
        report("paperplane.v8.precise_setup.begin");
        try {
            Class<?> api = Class.forName("cn.xiaochuankeji.tieba.api.paperplane.PaperPlaneApi", false, loader);
            int hooked = 0;
            for (Method method : api.getDeclaredMethods()) {
                final String name = method.getName();
                final int parameters = method.getParameterTypes().length;
                if (!("c".equals(name) && parameters == 0) && !("g".equals(name) && parameters == 2)) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!setting("paper_enabled")) return;
                        if ("c".equals(name)) reportOnce("precise_count", "paperplane.v8.api.count_called", 500);
                        else reportOnce("precise_match", "paperplane.v8.api.match_called", 500);
                    }
                });
                hooked++;
            }
            report("paperplane.v8.api_hooks=" + hooked);
        } catch (Throwable t) {
            report("paperplane.v8.api_hooks.failed type=" + t.getClass().getSimpleName());
        }
        int countCallbacks = 0;
        for (String suffix : new String[] {"$l", "$a"}) {
            try {
                final String handler = "$l".equals(suffix) ? "refresh" : "opportunity";
                Class<?> callback = Class.forName(
                    "cn.xiaochuankeji.tieba.ui.home.page.second_page.friends.FriendsPaperPlaneHomePageActivity" + suffix,
                    false, loader);
                for (Method method : callback.getDeclaredMethods()) {
                    if (!"a".equals(method.getName()) || method.getParameterTypes().length != 1 ||
                        !"rq3".equals(method.getParameterTypes()[0].getName())) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!setting("paper_enabled") || p.args.length != 1 || p.args[0] == null) return;
                            try {
                                Object result = XposedHelpers.callMethod(p.args[0], "a");
                                if (!(result instanceof Number)) return;
                                long value = ((Number) result).longValue();
                                if (value < -1000000 || value > 1000000) return;
                                report("paperplane.v8.count_callback stage=" + handler + " value=" + value);
                            } catch (Throwable t) {
                                reportOnce("precise_callback_fail", "paperplane.v8.count_callback.read_failed", 15000);
                            }
                        }
                    });
                    countCallbacks++;
                }
            } catch (Throwable t) {
                report("paperplane.v8.count_callback.unavailable handler=" + ("$l".equals(suffix) ? "refresh" : "opportunity"));
            }
        }
        report("paperplane.v8.count_callbacks=" + countCallbacks);
        try {
            Class<?> activity = Class.forName(
                "cn.xiaochuankeji.tieba.ui.home.page.second_page.friends.FriendsPaperPlaneHomePageActivity", false, loader);
            XposedBridge.hookAllMethods(activity, "R3", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (setting("paper_enabled")) reportOnce("home_match", "paperplane.v8.home_match_attempt", 500);
                }
            });
            report("paperplane.v8.home_match_hook.ready");
        } catch (Throwable t) {
            report("paperplane.v8.home_match_hook.unavailable");
        }
        try {
            Class<?> callback = Class.forName(
                "cn.xiaochuankeji.tieba.ui.home.page.second_page.friends.FriendsPaperPlaneHomePageActivity$d", false, loader);
            int callbacks = 0;
            for (Method method : callback.getDeclaredMethods()) {
                String name = method.getName();
                if ("onFail".equals(name) && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!setting("paper_enabled")) return;
                            String errorType = (p.args.length > 0 && p.args[0] instanceof Throwable)
                                ? p.args[0].getClass().getSimpleName() : "unknown";
                            report("paperplane.v8.match_callback.failure type=" + errorType.replaceAll("[^A-Za-z0-9_]", "_"));
                        }
                    });
                    callbacks++;
                } else if ("a".equals(name) && method.getParameterTypes().length == 1 &&
                    method.getParameterTypes()[0].getName().endsWith("PaperPlaneMatchHttpResult")) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (setting("paper_enabled")) report("paperplane.v8.match_callback.success_path");
                        }
                    });
                    callbacks++;
                }
            }
            report("paperplane.v8.match_callbacks=" + callbacks);
        } catch (Throwable t) {
            report("paperplane.v8.match_callbacks.unavailable");
        }
        report("paperplane.v8.precise_setup.end");
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
                String link = captureLink(req);
                reportOnce("request_" + kind + "_" + link, "paperplane.v7.request kind=" + kind + " click=" + link, 1500);
            } else {
                long age = android.os.SystemClock.elapsedRealtime() - lastClickMs;
                if (lastClickMs > 0 && age >= 0 && age < 8000 && networkSeen.getAndIncrement() < 5)
                    reportOnce("network_click", "paperplane.v7.network_after_click id=" + activeClick, 2000);
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
        String link = callLinks.get(call);
        if (link == null && request != null) link = requestLinks.get(request);
        if (link == null) link = "unlinked";
        final String linkedClick = link;
        if (error != null || response == null) {
            reportOnce("response_fail_" + kind, "paperplane.v7.response kind=" + kind + " result=exception click=" + linkedClick, 1000);
            return;
        }
        int status;
        try { status = ((Number) XposedHelpers.callMethod(response, "code")).intValue(); }
        catch (Throwable ignored) { return; }
        // Multiple interception sites may report the same response. Deduplicate by identity.
        String identity = Integer.toHexString(System.identityHashCode(response));
        if (!responseSeen.add(identity)) return;
        if (responseSeen.size() > 800) responseSeen.clear();
        report("paperplane.v7.response kind=" + kind + " http=" + status + " click=" + linkedClick);
        // peekBody returns a copy; this never consumes or replaces the original response.
        // Inspect only allow-listed scalar metadata; don't write raw response, body or account data.
        if (!"match".equals(kind) && !"count".equals(kind) && !"match_fallback".equals(kind)) return;
        try {
            Object peek = XposedHelpers.callMethod(response, "peekBody", 4096L);
            String body = String.valueOf(XposedHelpers.callMethod(peek, "string"));
            JSONObject obj = new JSONObject(body);
            String business = findBusinessStatus(obj);
            if (business != null)
                report("paperplane.v7.business kind=" + kind + " code=" + business + " click=" + linkedClick);
            else
                reportOnce("missing_status_" + kind, "paperplane.v7.business.status_not_found kind=" + kind, 10000);
            if (!"count".equals(kind)) return;
            Integer remaining = findRemaining(obj, 0);
            if (remaining == null) {
                reportOnce("quota_unknown", "paperplane.v7.quota.fields_unavailable", 30000);
                return;
            }
            Integer before = previousRemaining;
            previousRemaining = remaining;
            if (before == null || !before.equals(remaining)) {
                String change = before == null ? "initial" : (remaining > before ? "increase" : "decrease");
                report("paperplane.v7.quota remaining=" + remaining + " trend=" + change);
            }
        } catch (Throwable ignored) {
            reportOnce("body_unparsed_" + kind, "paperplane.v7.body.unparsed kind=" + kind, 30000);
        }
    }
    private static String captureLink(Object request) {
        if (request == null) return "unlinked";
        String existing = requestLinks.get(request);
        if (existing != null) return existing;
        String link = clickContext();
        if ("none".equals(link)) link = "unlinked";
        requestLinks.put(request, link);
        return link;
    }
    private static String clickContext() {
        long age = android.os.SystemClock.elapsedRealtime() - lastClickMs;
        return (lastClickMs > 0 && age >= 0 && age <= 12000) ? String.valueOf(activeClick) : "none";
    }
    private static boolean isQuotaPrompt(String message) {
        if (message == null || message.length() > 140) return false;
        return message.contains("今天你找的人太多了") ||
               (message.contains("明天再来") && (message.contains("找的人") || message.contains("次数"))) ||
               (message.contains("次数") && (message.contains("用完") || message.contains("上限") || message.contains("不足")));
    }
    private static String findBusinessStatus(JSONObject data) {
        // Numeric status codes only. Never record message/error descriptions.
        for (String k : new String[]{"code", "status", "error_code", "errcode", "errno"}) {
            Object v = data.opt(k);
            if (v instanceof Number) return k + ":" + ((Number)v).longValue();
            if (v instanceof String && ((String)v).matches("-?[0-9]{1,9}")) return k + ":" + v;
        }
        JSONObject nested = data.optJSONObject("data");
        if (nested != null) {
            for (String k : new String[]{"code", "status", "error_code", "errcode", "errno"}) {
                Object v = nested.opt(k);
                if (v instanceof Number) return "data." + k + ":" + ((Number)v).longValue();
            }
        }
        return null;
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
