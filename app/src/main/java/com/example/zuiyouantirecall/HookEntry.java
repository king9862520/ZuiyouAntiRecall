package com.example.zuiyouantirecall;

import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Diagnostic only: preserves normal app behavior. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "cn.xiaochuankeji.tieba";
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        try {
            Class<?> activity = Class.forName("cn.xiaochuankeji.tieba.ui.chat.ChatActivity", false, lpparam.classLoader);
            int count = 0;
            for (Method m : activity.getDeclaredMethods()) {
                if (!"chatRevoke".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        // Intentionally do NOT log private message content or user identifiers.
                        XposedBridge.log("[ZuiyouAntiRecall] chatRevoke invoked; args=" + p.args.length);
                    }
                });
                count++;
            }
            XposedBridge.log("[ZuiyouAntiRecall] ChatActivity.chatRevoke hooked variants=" + count);
        } catch (Throwable t) {
            XposedBridge.log("[ZuiyouAntiRecall] hook setup failed: " + t.getClass().getName());
        }
    }
}
