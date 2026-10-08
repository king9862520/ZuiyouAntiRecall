package com.example.zuiyouantirecall;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.widget.Toast;
import java.util.Locale;
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
    /** Read-only observation: never log user IDs, query values, message bodies or full URLs. */
    private static void setupPaperPlaneDiagnostics(ClassLoader loader) {
        try {
            Class<?> builder = Class.forName("okhttp3.Request$Builder", false, loader);
            Method build = builder.getDeclaredMethod("build");
            XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || p.getResult() == null) return;
                    try {
                        Object req = p.getResult();
                        Object url = XposedHelpers.callMethod(req, "url");
                        String path = String.valueOf(XposedHelpers.callMethod(url, "encodedPath")).toLowerCase(Locale.ROOT);
                        String kind = null;
                        if (path.endsWith("/paperplane/get_match_count")) kind = "count";
                        else if (path.endsWith("/paperplane/match")) kind = "match";
                        else if (path.endsWith("/paperplane/match_fallback")) kind = "match_fallback";
                        else if (path.endsWith("/paperplane/open_opportunity")) kind = "open_opportunity";
                        else if (path.endsWith("/paperplane/backoff_opportunity")) kind = "backoff_opportunity";
                        if (kind != null) report("paperplane.request type=" + kind);
                    } catch (Throwable ignored) { }
                }
            });
            report("paperplane.http_observer ready");
        } catch (Throwable ignored) { report("paperplane.http_observer unavailable"); }
        try {
            XposedHelpers.findAndHookMethod(Toast.class, "makeText", Context.class, CharSequence.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled") || !(p.args[1] instanceof CharSequence)) return;
                    reportQuotaToast(p.args[1].toString());
                }
            });
            XposedHelpers.findAndHookMethod(Toast.class, "makeText", Context.class, int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!setting("paper_enabled")) return;
                    try { reportQuotaToast(((Context)p.args[0]).getString((Integer)p.args[1])); }
                    catch (Throwable ignored) { }
                }
            });
            report("paperplane.toast_observer ready");
        } catch (Throwable ignored) { report("paperplane.toast_observer unavailable"); }
    }
    private static void reportQuotaToast(String message) {
        if (message == null) return;
        if ((message.contains("次数") && (message.contains("用完") || message.contains("上限") || message.contains("不足")))
                || (message.contains("明天") && message.contains("再来"))) {
            report("paperplane.quota_prompt observed");
        }
    }

}
