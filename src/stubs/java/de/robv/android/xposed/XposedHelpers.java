package de.robv.android.xposed;

import java.lang.reflect.Method;

/** 编译期 stub，运行期由 Xposed/LSPosed 提供真实实现。 */
public final class XposedHelpers {

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        return null;
    }

    public static Method findMethodExact(
            String className, ClassLoader classLoader, String methodName, Object... parameterTypes) {
        return null;
    }

    public static Method findMethodExact(
            Class<?> clazz, String methodName, Object... parameterTypes) {
        return null;
    }
}
