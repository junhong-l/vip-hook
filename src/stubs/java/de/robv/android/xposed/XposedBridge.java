package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** 编译期 stub，运行期由框架提供。 */
public final class XposedBridge {

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    /** 给运行时才解密出来的类挂 hook 用（加固 App 只能走这条路）。 */
    public static Set<XC_MethodHook.Unhook> hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }
}
