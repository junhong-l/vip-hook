package de.robv.android.xposed;

import java.lang.reflect.Member;

/** 编译期 stub，运行期由 Xposed/LSPosed 提供真实实现。 */
public final class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        return null;
    }
}
