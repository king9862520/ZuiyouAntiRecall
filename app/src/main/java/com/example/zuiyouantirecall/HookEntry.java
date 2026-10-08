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
    // Weak keys ensure errors are not kept alive by the diagnostic module.
    private static final Map<Throwable, String> errorOrigins = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Throwable, String>());
    // V12: associate errors with the exact Retrofit response being converted on the same thread.
    // Store only an allow-listed endpoint token and numeric HTTP code, never a URL or body.
    private static final class V12ResponseMeta {
        final String endpoint;
        final int http;
        final String safeRoute;
        V12ResponseMeta(String endpoint, int http, String safeRoute) {
            this.endpoint = endpoint;
            this.http = http;
            this.safeRoute = safeRoute;
        }
    }
    private static final ThreadLocal<java.util.ArrayDeque<V12ResponseMeta>> v12ResponseStack =
        new ThreadLocal<java.util.ArrayDeque<V12ResponseMeta>>();
    private static final Map<Throwable, V12ResponseMeta> v12ErrorRequests =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Throwable, V12ResponseMeta>());
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
                    report("app.attach process=main module=v13");
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
                        if (isQuotaPrompt(message)) {
                            report("paperplane.v7.quota_prompt source=toast click=" + clickContext());
                            reportQuotaOrigin("toast");
                        }
                    } catch (Throwable ignored) { }
                }
            });
            report("paperplane.v7.toast_hook.ready");
        } catch (Throwable t) { report("paperplane.v7.toast_hook.unavailable"); }
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.args.length == 0 || !(p.args[0] instanceof CharSequence)) return;
                    if (isQuotaPrompt(p.args[0].toString())) {
                        reportOnce("quota_textview_" + activeClick, "paperplane.v7.quota_prompt source=textview click=" + clickContext(), 2000);
                        reportQuotaOrigin("textview");
                    }
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
        setupV9DecisionTrace(loader);
    }

    /** V9: targeted, read-only tracing of the known opportunity callback and error/display path. */
    private static void setupV9DecisionTrace(ClassLoader loader) {
        report("paperplane.v9.setup.begin");
        setupV12ResponseContext(loader);
        setupV10ExceptionConstructionTrace(loader);
        try {
            Class<?> cb = Class.forName(
                "cn.xiaochuankeji.tieba.ui.home.page.second_page.friends.FriendsPaperPlaneHomePageActivity$a",
                false, loader);
            int hooks = 0;
            for (Method method : cb.getDeclaredMethods()) {
                if (!"a".equals(method.getName()) || method.getParameterTypes().length != 1
                    || !"rq3".equals(method.getParameterTypes()[0].getName())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!setting("paper_enabled") || p.args.length == 0 || p.args[0] == null) return;
                        String value = "unavailable";
                        String messageKind = "unavailable";
                        try {
                            Object result = XposedHelpers.callMethod(p.args[0], "a");
                            if (result instanceof Number) {
                                long number = ((Number) result).longValue();
                                if (number >= -1000000 && number <= 1000000) value = String.valueOf(number);
                            }
                        } catch (Throwable ignored) { }
                        try {
                            Object hint = XposedHelpers.callMethod(p.args[0], "b");
                            if (hint instanceof String) {
                                messageKind = isQuotaPrompt((String) hint) ? "quota" : "other";
                            }
                        } catch (Throwable ignored) { }
                        // Report input classification only; no claims about server enforcement.
                        report("paperplane.v9.opportunity_callback value=" + value
                            + " message=" + messageKind + " click=" + clickContext());
                    }
                });
                hooks++;
            }
            report("paperplane.v9.opportunity_hooks=" + hooks);
        } catch (Throwable t) {
            report("paperplane.v9.opportunity_hooks.failed type=" + safeType(t));
        }
        try {
            Class<?> errorUtil = Class.forName("un6", false, loader);
            int hooks = 0;
            for (Method method : errorUtil.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!"c".equals(method.getName()) || args.length != 3
                    || !Context.class.isAssignableFrom(args[0])
                    || !Throwable.class.isAssignableFrom(args[1])
                    || args[2] != Boolean.TYPE) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!setting("paper_enabled") || "none".equals(clickContext())) return;
                        Throwable error = p.args.length > 1 && p.args[1] instanceof Throwable
                            ? (Throwable) p.args[1] : null;
                        String kind = error == null ? "unknown" : safeType(error);
                        boolean quotaText = error != null && isQuotaPrompt(error.getMessage());
                        reportOnce("v9_error_" + clickContext() + "_" + kind,
                            "paperplane.v9.error_dispatch source=un6.c click=" + clickContext()
                                + " type=" + kind + " quota_text=" + quotaText, 1500);
                        // Only classify the quota-related exception; no error message, user data, or request body.
                        if (quotaText) {
                            String source = errorOrigins.get(error);
                            if (source == null || "unresolved".equals(source))
                                source = limitedTrace(error.getStackTrace(), 5);
                            Throwable cause = error.getCause();
                            String origin = safeType(cause);
                            String dispatch = limitedTrace(Thread.currentThread().getStackTrace(), 5);
                            reportOnce("v10_dispatch_" + clickContext(),
                                "paperplane.v10.error_path click=" + clickContext()
                                    + " exception=" + kind + " cause=" + origin
                                    + " throw_site=" + source + " handler_path=" + dispatch, 1200);
                            // The exception itself supplies the business code. The endpoint comes
                            // only from the HTTP response active while this exception was created.
                            V12ResponseMeta meta = v12ErrorRequests.get(error);
                            String endpoint = meta == null ? "unresolved" : meta.endpoint;
                            String http = meta == null ? "unavailable" : String.valueOf(meta.http);
                            reportOnce("v12_business_" + clickContext(),
                                "paperplane.v12.business_error click=" + clickContext()
                                    + " endpoint=" + endpoint + " http=" + http
                                    + " ret=" + v12ErrorCode(error) + " source=exception", 1200);
                            // V13: report only an exact static API route compiled into the APK.
                            // Dynamic paths, URL parameters, hosts, and identifiers are never logged.
                            String safeRoute = meta == null ? "unresolved" : meta.safeRoute;
                            reportOnce("v13_route_" + clickContext(),
                                "paperplane.v13.quota_route click=" + clickContext()
                                    + " route=" + safeRoute + " endpoint=" + endpoint
                                    + " http=" + http + " ret=" + v12ErrorCode(error), 1200);
                        }
                    }
                });
                hooks++;
            }
            report("paperplane.v9.error_hooks=" + hooks);
        } catch (Throwable t) {
            report("paperplane.v9.error_hooks.failed type=" + safeType(t));
        }
        try {
            Class<?> tip = Class.forName("oo", false, loader);
            int hooks = 0;
            for (Method method : tip.getDeclaredMethods()) {
                if (!"e".equals(method.getName()) || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != String.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!setting("paper_enabled") || p.args.length == 0
                            || !(p.args[0] instanceof String) || !isQuotaPrompt((String) p.args[0])) return;
                        reportOnce("v9_tip_" + clickContext(),
                            "paperplane.v9.quota_display source=oo.e click=" + clickContext(), 1500);
                        reportQuotaOrigin("oo.e");
                    }
                });
                hooks++;
            }
            report("paperplane.v9.tip_hooks=" + hooks);
        } catch (Throwable t) {
            report("paperplane.v9.tip_hooks.failed type=" + safeType(t));
        }
        report("paperplane.v9.setup.end");
    }

    /** V12: hbf.c(okhttp3.Response) is the response parser observed in the V11 stack.
     *  The temporary thread stack prevents an unrelated later request from being
     *  attributed to a quota error. This hook never changes arguments or results. */
    private static void setupV12ResponseContext(ClassLoader loader) {
        try {
            Class<?> adapter = Class.forName("hbf", false, loader);
            int count = 0;
            for (Method m : adapter.getDeclaredMethods()) {
                if (!"c".equals(m.getName()) || m.getParameterTypes().length != 1 ||
                    !"okhttp3.Response".equals(m.getParameterTypes()[0].getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        // Push even if inspection fails, so nesting is always balanced.
                        V12ResponseMeta meta = new V12ResponseMeta("unresolved", -1, "unresolved");
                        if (setting("paper_enabled") && p.args.length > 0 && p.args[0] != null) {
                            try {
                                Object response = p.args[0];
                                Object request = XposedHelpers.callMethod(response, "request");
                                String endpoint = v12Endpoint(request);
                                int http = ((Number) XposedHelpers.callMethod(response, "code")).intValue();
                                String safeRoute = v13SafeRoute(request);
                                meta = new V12ResponseMeta(endpoint, http, safeRoute);
                            } catch (Throwable ignored) { }
                        }
                        java.util.ArrayDeque<V12ResponseMeta> stack = v12ResponseStack.get();
                        if (stack == null) {
                            stack = new java.util.ArrayDeque<V12ResponseMeta>();
                            v12ResponseStack.set(stack);
                        }
                        stack.push(meta);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        java.util.ArrayDeque<V12ResponseMeta> stack = v12ResponseStack.get();
                        if (stack != null) {
                            if (!stack.isEmpty()) stack.pop();
                            if (stack.isEmpty()) v12ResponseStack.remove();
                        }
                    }
                });
                count++;
            }
            report("paperplane.v12.response_hooks=" + count);
        } catch (Throwable t) {
            report("paperplane.v12.response_hooks.failed type=" + safeType(t));
        }
    }

    /** A coarse endpoint token only: never include host, query, ID, or raw URL. */
    private static String v12Endpoint(Object request) {
        if (request == null) return "unresolved";
        try {
            Object url = XposedHelpers.callMethod(request, "url");
            String path = String.valueOf(XposedHelpers.callMethod(url, "encodedPath"))
                .toLowerCase(Locale.ROOT);
            String section = "/paperplane/";
            int i = path.indexOf(section);
            if (i < 0) {
                section = "/paper_plane/";
                i = path.indexOf(section);
            }
            if (i < 0) return "non_paperplane";
            String segment = path.substring(i + section.length());
            // Reject trailing segments and any dynamic identifiers.
            if (!segment.matches("[a-z_]{2,48}")) return "paperplane_other";
            return "paperplane_" + segment;
        } catch (Throwable ignored) {
            return "unresolved";
        }
    }


    /** V13: immutable, exact-match allowlist extracted from STATIC APK endpoint literals.
     *  This is not a heuristic for user-generated or dynamic URL path segments. */
    private static final java.util.Set<String> V13_STATIC_ROUTES =
        java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(
            java.util.Arrays.asList(
            "/account/auth",
            "/account/avatar/id/",
            "/account/bind_phone",
            "/account/bind_phone_fast",
            "/account/check",
            "/account/check_user_enable",
            "/account/destroy",
            "/account/enable_editname",
            "/account/get_did",
            "/account/get_official_member",
            "/account/get_phone_login_code",
            "/account/get_phone_pw_code",
            "/account/guest_login",
            "/account/login",
            "/account/login_boot",
            "/account/login_by_verify_code",
            "/account/login_immediately",
            "/account/logout",
            "/account/modify_passwd",
            "/account/nonce",
            "/account/open_login",
            "/account/rebind_phone",
            "/account/register",
            "/account/register_guest",
            "/account/report_hemera",
            "/account/reset_password",
            "/account/set_avatar",
            "/account/set_location",
            "/account/set_school",
            "/account/social_bind",
            "/account/social_unbind",
            "/account/update",
            "/account/update_cover",
            "/account/update_name",
            "/account/update_phone",
            "/account/verifycode_login",
            "/attention/add",
            "/attention/att_list",
            "/attention/cancel",
            "/attention/follow_list",
            "/attention/get_my_attention_unvisible",
            "/attention/get_rec_users",
            "/attention/my_atts",
            "/attention/my_fans",
            "/attention/relation_list",
            "/attention/remove_fan",
            "/attention/suggest_v2",
            "/attention/update_my_attention_unvisible",
            "/attention/user_atts",
            "/attention/user_fans",
            "/chat/hide_message",
            "/chat/hide_messages",
            "/chat/hide_session",
            "/chat/hide_sessions",
            "/chat/hide_sessions_with_messages",
            "/chat/messages",
            "/chat/multi_read",
            "/chat/read",
            "/chat/read_all",
            "/chat/recall_message",
            "/chat/route",
            "/chat/say",
            "/chat/sessions",
            "/chat/top_session",
            "/common/bind",
            "/common/bindphone",
            "/common/change_sign",
            "/common/comment",
            "/common/commentpostsourceflow",
            "/common/common2",
            "/common/god",
            "/common/godbuildcomment",
            "/common/good",
            "/common/goodpainting",
            "/common/himaintqrt",
            "/common/hioperbatch",
            "/common/hioperqrt",
            "/common/hmshimaintqrt",
            "/common/hmshioperqrt",
            "/common/interest_collect",
            "/common/interest_collect_new",
            "/common/login",
            "/common/moment/tag",
            "/common/moment/tagdetail",
            "/common/newuser/unbind",
            "/common/post",
            "/common/posthotcomment",
            "/common/publish",
            "/common/publishpost",
            "/common/reedit",
            "/common/reeditpost",
            "/common/register",
            "/common/search",
            "/common/searchpostactivity",
            "/common/select",
            "/common/selecttransmember",
            "/common/setting",
            "/common/web",
            "/config/abtest",
            "/config/attitude_like",
            "/config/block_notification",
            "/config/district",
            "/config/flutter",
            "/config/get",
            "/config/get_banner",
            "/config/get_emoji_package_ids_by_scene",
            "/config/get_emoji_packages",
            "/config/get_fixed_post_list",
            "/config/get_interest_tag_dict",
            "/config/get_online_privacy",
            "/config/get_privacy_setting",
            "/config/get_user_beta_conf",
            "/config/gray",
            "/config/has_new_official",
            "/config/pre_chat_check",
            "/config/pre_chat_popup_ack",
            "/config/set_online_privacy",
            "/config/special_topic_like",
            "/config/tab",
            "/config/top_imgs",
            "/config/update_ai_xiaoyou_setting",
            "/config/update_privacy_setting",
            "/config/update_user_beta_conf",
            "/data/",
            "/data/anr/",
            "/data/anr/traces",
            "/data/app",
            "/data/app/",
            "/data/app/com",
            "/data/bluestacks",
            "/data/data",
            "/data/data/",
            "/data/data/com",
            "/data/lbe/",
            "/data/local",
            "/data/local/",
            "/data/local/bin/",
            "/data/local/bin/su",
            "/data/local/chrome-trace-config",
            "/data/local/su",
            "/data/local/tmp",
            "/data/local/tmp/exopackage/",
            "/data/local/xbin/",
            "/data/local/xbin/su",
            "/data/system",
            "/data/system/gatekeeper",
            "/data/system/gesture",
            "/data/system/password",
            "/data/user/",
            "/data/user/0/",
            "/data/youwave_id",
            "/dolphinapi/",
            "/dolphinapi/attention/my_atts",
            "/dolphinapi/attention/my_fans",
            "/dolphinapi/chat/bind_clientid",
            "/dolphinapi/chat/messages",
            "/dolphinapi/chat/read",
            "/dolphinapi/chat/route",
            "/dolphinapi/chat/say",
            "/dolphinapi/chat/sessions",
            "/dolphinapi/hall/zy_action",
            "/dolphinapi/hall/zy_data_refresh",
            "/dolphinapi/hall/zy_friends_invite",
            "/dolphinapi/hall/zy_friends_play",
            "/dolphinapi/hall/zy_invite_list",
            "/dolphinapi/hall/zy_my_current",
            "/dolphinapi/hall/zy_profile",
            "/dolphinapi/hall/zy_rec_rooms",
            "/dolphinapi/hall/zy_resource",
            "/dolphinapi/misc/get_user_relation",
            "/friends/invite",
            "/im/chat",
            "/mate/add_mate_history",
            "/mate/create_mate_session",
            "/mate/del_all_mate_history",
            "/mate/del_my_mate_card",
            "/mate/find_mate_card_list",
            "/mate/find_mate_create_conf",
            "/mate/find_mate_homepage",
            "/mate/find_real_post_by_mid",
            "/mate/get_liked_mate_cards",
            "/mate/get_mate_history_lists",
            "/mate/get_mate_user_last_online",
            "/mate/show_mate_session",
            "/mate/update_mate_card_status",
            "/message/notify/fans_friend",
            "/message/notify/like",
            "/misc/accomplish_new_user_task",
            "/misc/add_meme",
            "/misc/ai_drawing_by_brief_strokes",
            "/misc/ai_drawing_cancel",
            "/misc/app_sdk_authorize",
            "/misc/app_sdk_precheck",
            "/misc/award_new_user_task",
            "/misc/cancel_eyes_on",
            "/misc/captcha_verify",
            "/misc/certify_ali_check_v1",
            "/misc/change_dazi_room_status",
            "/misc/chat_save_paint",
            "/misc/check_age",
            "/misc/check_guide_display",
            "/misc/check_meme_cipher",
            "/misc/clear_user_notify",
            "/misc/collect_gender",
            "/misc/collect_interest",
            "/misc/create_eye_tag",
            "/misc/dazi_chat",
            "/misc/dazi_didi",
            "/misc/dazi_invite",
            "/misc/dazi_posts",
            "/misc/dazi_relation",
            "/misc/dazi_report",
            "/misc/dazi_team_up",
            "/misc/delete_meme",
            "/misc/emoji_download_record",
            "/misc/enter_dazi_room",
            "/misc/eyes_on",
            "/misc/eyeson_list",
            "/misc/eyeson_list_tidy",
            "/misc/free_flow",
            "/misc/get_audio_play_list",
            "/misc/get_daily_wallpaper_list",
            "/misc/get_dazi_time",
            "/misc/get_emoji_collection_detail",
            "/misc/get_emoji_collections",
            "/misc/get_flutter_app_detail",
            "/misc/get_flutter_framework",
            "/misc/get_sessions_by_tid",
            "/misc/get_store_coupon_url",
            "/misc/get_tp_info",
            "/misc/get_user_eye_tags",
            "/misc/get_vv_templates",
            "/misc/handle_dazi_apply",
            "/misc/kick_dazi",
            "/misc/my_dazi_posts",
            "/misc/query_meme",
            "/misc/recover_403_videos",
            "/misc/report_privacy_collection",
            "/misc/select_kol",
            "/misc/send_open_push_chat",
            "/misc/today_ai_drawing_count",
            "/misc/top_meme",
            "/misc/use_dazi_room",
            "/misc/user_rec_card",
            "/misc/voice_card_asr",
            "/nearby/around",
            "/nearby/auto_get",
            "/nearby/create_plane",
            "/nearby/data/create",
            "/nearby/data/delete",
            "/nearby/dislike",
            "/nearby/fetch",
            "/nearby/get_personal_tag_list",
            "/nearby/get_plane_tag_list",
            "/nearby/like",
            "/nearby/list",
            "/nearby/read_reset",
            "/nearby/refresh_plane",
            "/nearby/self_withdraw",
            "/nearby/stat_auto_get",
            "/nearby/update_user_space_status",
            "/nearby/update_user_tags",
            "/paperplane/answer_question",
            "/paperplane/backoff_opportunity",
            "/paperplane/edit_profile",
            "/paperplane/get_match_count",
            "/paperplane/get_schema",
            "/paperplane/get_user_role",
            "/paperplane/match",
            "/paperplane/match_fallback",
            "/paperplane/open_opportunity",
            "/paperplane/set_cp",
            "/paperplane/uncover_confirm",
            "/paperplane/unlock_fetter",
            "/paperplane/unlock_script",
            "/paperplane/update_status",
            "/paperplane/update_user_session_status",
            "/part/dating_detail",
            "/part/dating_edit",
            "/part/dating_edit_introduce",
            "/part/dating_homepage",
            "/part/dating_list",
            "/part/find",
            "/part/find_partner_my_post",
            "/part/find_partner_publish_post",
            "/part/find_partner_room",
            "/part/findresources",
            "/part/friend_tag_edit",
            "/part/friends_visitors",
            "/part/goshopping",
            "/part/paperplane_homepage",
            "/part/team_up",
            "/part/team_up_detail",
            "/part/team_up_edit_profile",
            "/part/team_up_match",
            "/part/team_up_message",
            "/record/cancel_friend_recommend",
            "/record/delete_friend",
            "/record/delete_friend_record",
            "/record/get_friend_alteration",
            "/record/get_friend_feed_unread",
            "/record/get_friend_requests",
            "/record/get_friends",
            "/record/get_records_in_detail",
            "/record/get_records_with_condition",
            "/record/handle_friend_request",
            "/record/homepage",
            "/record/send_friend_request",
            "/relation/video_cancel_dislike",
            "/relation/video_cancel_like",
            "/relation/video_dislike",
            "/relation/video_get_liked_members",
            "/relation/video_like",
            "/relation/videos_likeinfo",
            "/social/chat",
            "/social/chatreceivesetting",
            "/social/chatsearchimage",
            "/social/chatsearchpostreview",
            "/social/chatsearchresult",
            "/social/comment",
            "/social/commentprivacysetting",
            "/social/draw",
            "/social/drawguesstest",
            "/social/message",
            "/social/moment/at",
            "/social/moment/atselect",
            "/social/paper",
            "/social/paperplane/match",
            "/social/paperplane/session",
            "/social/privacy",
            "/social/privacysetting",
            "/social/push",
            "/social/pushsetting",
            "/social/session",
            "/social/sessiononlineprivacy",
            "/system/app/",
            "/system/app/bluestacks",
            "/system/bin/",
            "/system/bin/andro",
            "/system/bin/cat",
            "/system/bin/conbb",
            "/system/bin/cufaevdd",
            "/system/bin/cufsdosck",
            "/system/bin/cufsmgr",
            "/system/bin/dex2oat",
            "/system/bin/failsafe/",
            "/system/bin/failsafe/su",
            "/system/bin/get_andro",
            "/system/bin/logcat",
            "/system/bin/mount",
            "/system/bin/qemu-props",
            "/system/bin/sh",
            "/system/bin/su",
            "/system/build",
            "/system/etc/fallback_fonts",
            "/system/etc/fonts",
            "/system/etc/hosts",
            "/system/etc/init",
            "/system/etc/vold",
            "/system/fonts/",
            "/system/framework/",
            "/system/framework/amap",
            "/system/lib",
            "/system/lib/",
            "/system/lib/hw/audio",
            "/system/lib/hw/camera",
            "/system/lib/hw/gps",
            "/system/lib/hw/gralloc",
            "/system/lib/hw/sensors",
            "/system/lib/libc_malloc_debug_qemu",
            "/system/lib/modules/3",
            "/system/lib/vboxguest",
            "/system/lib/vboxsf",
            "/system/lib/vboxvideo",
            "/system/lib64",
            "/system/lib64/",
            "/system/sbin/",
            "/system/sd/xbin/",
            "/system/sd/xbin/su",
            "/system/usr/idc/andro",
            "/system/usr/keylayout/andro",
            "/system/usr/we-need-root/su",
            "/system/xbin/",
            "/system/xbin/conbb",
            "/system/xbin/cufaevdd",
            "/system/xbin/cufsdosck",
            "/system/xbin/cufsmgr",
            "/system/xbin/mount",
            "/system/xbin/su",
            "/system/xbin/which",
            "/user/atted_topics",
            "/user/block",
            "/user/check_index_flow",
            "/user/check_phone_bind",
            "/user/del_announce",
            "/user/erase_zone_footprint",
            "/user/follow_reviews",
            "/user/get_announce_visitors",
            "/user/get_user_announce",
            "/user/get_user_status",
            "/user/getblock",
            "/user/index_v2",
            "/user/is_authed",
            "/user/permission_info",
            "/user/personal_card",
            "/user/posts",
            "/user/profile",
            "/user/profile_v2",
            "/user/real_name_auth",
            "/user/relieve_zone_footprint",
            "/user/reviews",
            "/user/set_announce_status",
            "/user/stat_phone_bind",
            "/user/unblock",
            "/user/update_announce",
            "/user/update_role_medal",
            "/user/view_announce",
            "/user/ym_get_upw",
            "/user/ym_report_status"
            )));

    /** Output only known, fixed API route labels. No host, query, ID, or arbitrary path text. */
    private static String v13SafeRoute(Object request) {
        if (request == null) return "unresolved";
        try {
            Object url = XposedHelpers.callMethod(request, "url");
            String fullPath = String.valueOf(XposedHelpers.callMethod(url, "encodedPath"))
                .toLowerCase(Locale.ROOT);
            // Reject unexpected characters, encoding, and excessive length before matching.
            if (fullPath.length() > 100 || !fullPath.matches("/[a-z0-9_/-]{3,99}"))
                return "masked";
            String staticPath = fullPath;
            if (!V13_STATIC_ROUTES.contains(staticPath)) {
                // Support a non-personal API version prefix only for exact-known routes.
                staticPath = fullPath.replaceFirst("^/(?:api/)?v[0-9]{1,2}(?=/)", "");
                if (!V13_STATIC_ROUTES.contains(staticPath)) return "masked";
            }
            // Provider v11 allows dots/underscores/hyphens, but not slashes.
            return staticPath.substring(1).replace('/', '.').replace('-', '_');
        } catch (Throwable ignored) {
            return "unresolved";
        }
    }

    /** Read-only error code accessor confirmed in APK; never read errMessage or errData. */
    private static String v12ErrorCode(Throwable error) {
        if (error == null ||
            !"com.izuiyou.network.ClientErrorException".equals(error.getClass().getName()))
            return "unavailable";
        try {
            Object result = XposedHelpers.callMethod(error, "errCode");
            return result instanceof Number ? String.valueOf(((Number) result).intValue()) : "unavailable";
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    /** Only a top-level numeric ret: no messages, nested data, or response payloads. */
    private static String v12TopLevelRet(JSONObject obj) {
        Object value = obj.opt("ret");
        if (value instanceof Number) return String.valueOf(((Number) value).intValue());
        if (value instanceof String && ((String) value).matches("-?[0-9]{1,10}"))
            return (String) value;
        return null;
    }

    /** V10: constructor trace is observational; it never changes exception or response behavior. */
    private static void setupV10ExceptionConstructionTrace(ClassLoader loader) {
        try {
            Class<?> errorClass = Class.forName("com.izuiyou.network.ClientErrorException", false, loader);
            java.util.Set<de.robv.android.xposed.XC_MethodHook.Unhook> hooks =
                XposedBridge.hookAllConstructors(errorClass, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!setting("paper_enabled") || !(p.thisObject instanceof Throwable)) return;
                        Throwable error = (Throwable) p.thisObject;
                        // A constructor can call another constructor; retain the first observation.
                        if (errorOrigins.containsKey(error)) return;
                        String origin = limitedTrace(error.getStackTrace(), 5);
                        if ("unresolved".equals(origin))
                            origin = limitedTrace(Thread.currentThread().getStackTrace(), 5);
                        errorOrigins.put(error, origin);
                        java.util.ArrayDeque<V12ResponseMeta> stack = v12ResponseStack.get();
                        if (stack != null && !stack.isEmpty())
                            v12ErrorRequests.put(error, stack.peek());
                        // Logging is limited to the user's current paper-plane click and quota failures.
                        if (!"none".equals(clickContext()) && isQuotaPrompt(error.getMessage())) {
                            reportOnce("v10_construct_" + clickContext(),
                                "paperplane.v10.exception_created click=" + clickContext()
                                    + " origin=" + origin, 1200);
                        }
                    }
                });
            report("paperplane.v10.constructor_hooks=" + hooks.size());
        } catch (Throwable t) {
            report("paperplane.v10.constructor_hooks.failed type=" + safeType(t));
        }
    }

    /** Limit to package/class/method names: no raw exception messages, URLs, line args, IDs or content. */
    private static String limitedTrace(StackTraceElement[] elements, int maxFrames) {
        StringBuilder b = new StringBuilder();
        if (elements == null) return "unresolved";
        int count = 0;
        for (StackTraceElement frame : elements) {
            String type = frame.getClassName();
            if (type == null || type.contains("zuiyouantirecall") ||
                type.startsWith("de.robv.android.xposed") || type.startsWith("java.lang.reflect") ||
                type.startsWith("org.lsposed") || type.startsWith("java.lang.Thread")) continue;
            boolean target = type.startsWith("cn.xiaochuankeji.") || type.startsWith("com.izuiyou.") ||
                type.startsWith("okhttp3.") || type.startsWith("retrofit2.") ||
                (!type.contains(".") && type.length() <= 24);
            if (!target) continue;
            if (b.length() > 0) b.append(" > ");
            b.append(type.replaceAll("[^A-Za-z0-9_.$]", "_").substring(0, Math.min(95, type.length())))
                .append('.').append(frame.getMethodName().replaceAll("[^A-Za-z0-9_$]", "_"));
            if (++count >= maxFrames || b.length() > 450) break;
        }
        return b.length() == 0 ? "unresolved" : b.toString();
    }

    private static String safeType(Throwable t) {
        if (t == null) return "unknown";
        String s = t.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** Only class/method names near the quota prompt: no messages, IDs, URLs or payloads. */
    private static void reportQuotaOrigin(String displaySource) {
        if (!setting("paper_enabled")) return;
        String click = clickContext();
        StringBuilder chain = new StringBuilder();
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String type = e.getClassName();
                if (type.contains("zuiyouantirecall") || type.startsWith("de.robv.android.xposed")) continue;
                if (!type.startsWith("cn.xiaochuankeji.tieba.")
                    && !"un6".equals(type) && !"oo".equals(type)) continue;
                if (chain.length() > 0) chain.append(" > ");
                String simple = type.startsWith("cn.xiaochuankeji.tieba.")
                    ? type.substring("cn.xiaochuankeji.tieba.".length()) : type;
                chain.append(simple.replaceAll("[^A-Za-z0-9_.$]", "_") )
                    .append('.').append(e.getMethodName().replaceAll("[^A-Za-z0-9_$]", "_"));
                if (chain.length() > 380 || chain.toString().split(" > ").length >= 5) break;
            }
        } catch (Throwable ignored) { }
        reportOnce("v9_origin_" + displaySource + "_" + click,
            "paperplane.v9.prompt_origin source=" + displaySource + " click=" + click
                + " chain=" + (chain.length() == 0 ? "unresolved" : chain.toString()), 1500);
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
            String ret = v12TopLevelRet(obj);
            if (ret != null)
                report("paperplane.v12.response kind=" + kind + " http=" + status
                    + " ret=" + ret + " click=" + linkedClick);
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
        for (String k : new String[]{"ret", "code", "status", "error_code", "errcode", "errno"}) {
            Object v = data.opt(k);
            if (v instanceof Number) return k + ":" + ((Number)v).longValue();
            if (v instanceof String && ((String)v).matches("-?[0-9]{1,9}")) return k + ":" + v;
        }
        JSONObject nested = data.optJSONObject("data");
        if (nested != null) {
            for (String k : new String[]{"ret", "code", "status", "error_code", "errcode", "errno"}) {
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
