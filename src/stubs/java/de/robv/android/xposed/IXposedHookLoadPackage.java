package de.robv.android.xposed;

/** 编译期 stub，运行期由 Xposed/LSPosed 提供真实实现。 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam)
            throws Throwable;
}
