package com.teams.imespoof;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * IMESpoof —— 让 Teams 的「Approved keyboards」检测接受本机实际使用的输入法。
 *
 * 设计要点：
 *   1) 不谎报成 Google 输入法（本机没装，谎报会触发「请安装批准的键盘」）；
 *   2) 把本机所有已启用的输入法（含豆包）追加进策略白名单 ApprovedKeyboards；
 *   3) 把 KeyboardsRestricted 强制为 false；
 *   4) 兜底：hook Intune SDK 里所有键盘相关的布尔方法，按其语义强制通过；
 *   5) 全程写 debug 日志，落到 Download/IMESpoof/ 与 Android/data/com.microsoft.teams/files/IMESpoof/。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "IMESpoof";
    private static final String VERSION = "0.3-debug";
    private static final String[] TARGETS = {
            "com.microsoft.teams",
            "com.microsoft.office.outlook",
            "com.microsoft.outlooklite",
    };

    /** 是否把读取到的输入法谎报成某个指定的组件名。空 = 不谎报，只让白名单接受你现有的输入法。 */
    private static final String SPOOF_AS = Config.SPOOF_AS;

    /** release 版关掉日志：不建文件、不打 logcat、不抓调用栈 */
    private static final boolean LOG_ENABLED = Config.LOG_ENABLED;

    /** 把策略里的 KeyboardsRestricted 强制为 false */
    private static final boolean FORCE_POLICY_OFF = true;

    /** 把本机已启用的输入法追加进 ApprovedKeyboards 白名单 */
    private static final boolean PATCH_ALLOWLIST = true;

    /** 兜底：hook SDK 里名字带 keyboard/inputmethod 的布尔方法 */
    private static final boolean FORCE_SDK_METHODS = true;

    private static final int MAX_HITS_KEEP = 40;

    private static final Object LOG_LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final List<File> FILES = new ArrayList<File>();
    private static final Map<String, Integer> HITS = new HashMap<String, Integer>();
    private static final Set<String> INSPECTED = new HashSet<String>();

    private static volatile OutputStream msStream;
    private static volatile String sPkg = "?";
    private static volatile String sProcess = "?";
    private static volatile Context sCtx;
    private static volatile boolean spoofEnabled = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!isTarget(lp.packageName)) return;
        sPkg = lp.packageName;
        sProcess = lp.processName == null ? lp.packageName : lp.processName;
        raw("loaded: " + lp.packageName + " / " + sProcess);

        hookAttach();
        hookClassLoading();
        hookSettingsReads();
        hookImeLists();
        watchPolicyJson();
    }

    /* ======================= 初始化 ======================= */

    private void hookAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                init((Context) param.args[0]);
                            } catch (Throwable t) {
                                raw("init fail: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            raw("attach hook fail: " + t);
        }
    }

    private static void init(Context ctx) {
        sCtx = ctx;
        if (!LOG_ENABLED) return;

        prepareSinks(ctx);

        log("================ IMESpoof " + VERSION + " ================");
        log("package=" + sPkg + " process=" + sProcess + " uid=" + android.os.Process.myUid()
                + " android=" + Build.VERSION.RELEASE + "/" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + " " + Build.MODEL);
        log("app version = " + pkgVersion(ctx, sPkg));

        String realIme = readIme(ctx);
        log("real default IME = " + realIme);
        log("enabled IMEs: " + describe(enabledImes(ctx)));
        log("installed IMEs: " + describe(installedImes(ctx)));
        log("plan: policyOff=" + FORCE_POLICY_OFF + " patchAllowlist=" + PATCH_ALLOWLIST
                + " sdkMethods=" + FORCE_SDK_METHODS + " spoofAs=" + SPOOF_AS);
        for (File f : FILES) log("log file: " + f.getAbsolutePath());
    }

    /* ======================= 日志 ======================= */

    private static void raw(String msg) {
        if (!LOG_ENABLED) return;
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void log(String msg) {
        if (!LOG_ENABLED) return;
        String line;
        synchronized (LOG_LOCK) {
            line = TS.format(new Date()) + " [" + sProcess + "] " + msg;
        }
        XposedBridge.log(TAG + ": " + msg);
        synchronized (FILES) {
            for (File f : FILES) {
                try {
                    FileOutputStream fos = new FileOutputStream(f, true);
                    fos.write((line + "\n").getBytes("UTF-8"));
                    fos.close();
                } catch (Throwable ignored) {
                    // 单个 sink 失败不影响其它
                }
            }
        }
        OutputStream ms = msStream;
        if (ms != null) {
            try {
                ms.write((line + "\n").getBytes("UTF-8"));
                ms.flush();
            } catch (Throwable t) {
                msStream = null;
            }
        }
    }

    private static boolean allow(String key, int limit) {
        synchronized (HITS) {
            Integer n = HITS.get(key);
            int v = n == null ? 1 : n.intValue() + 1;
            HITS.put(key, Integer.valueOf(v));
            return v <= limit;
        }
    }

    private static String stack(int max) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String cn = e.getClassName();
            if (cn.startsWith("com.teams.imespoof") || "java.lang.Thread".equals(cn)) continue;
            sb.append("\n      at ").append(e);
            if (++shown >= max) break;
        }
        return sb.toString();
    }

    /* ======================= 日志落盘（Download + Android/data） ======================= */

    private static void prepareSinks(Context ctx) {
        if (!LOG_ENABLED) return;
        String fname = "imespoof-" + shortName(sPkg) + "-" + sProcess.replace(':', '_') + ".log";

        File ext = null;
        try {
            ext = ctx.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (ext != null) addFileSink(new File(new File(ext, "IMESpoof"), fname));

        try {
            addFileSink(new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "IMESpoof"), fname));
        } catch (Throwable ignored) {
        }

        try {
            addFileSink(new File(new File(ctx.getFilesDir(), "IMESpoof"), fname));
        } catch (Throwable ignored) {
        }

        try {
            addMediaStoreSink(ctx, fname);
        } catch (Throwable t) {
            raw("mediastore sink fail: " + t);
        }
    }

    private static void addFileSink(File f) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return;
            if (f.exists() && f.length() > 1024 * 1024) {
                File old = new File(f.getAbsolutePath() + ".old");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write("#".getBytes("UTF-8"));
            fos.close();
            FILES.add(f);
        } catch (Throwable t) {
            raw("file sink fail " + f + " : " + t);
        }
    }

    /** 通过 MediaStore 写进公共下载目录：/sdcard/Download/IMESpoof/xxx.log（Android 10+ 无需权限） */
    private static void addMediaStoreSink(Context ctx, String fname) throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        ContentResolver cr = ctx.getContentResolver();
        Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

        Cursor c = null;
        try {
            c = cr.query(col, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{fname}, null);
            while (c != null && c.moveToNext()) {
                cr.delete(ContentUris.withAppendedId(col, c.getLong(0)), null, null);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/IMESpoof");
        Uri uri = cr.insert(col, v);
        if (uri == null) return;
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "wa");
        if (pfd == null) return;
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        msStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
        raw("mediastore sink: " + uri);
    }

    /* ======================= 读输入法（观察 + 可选谎报） ======================= */

    private void hookSettingsReads() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    onSettingsRead(p);
                } catch (Throwable t) {
                    log("onSettingsRead: " + t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString",
                    ContentResolver.class, String.class, hook);
        } catch (Throwable t) {
            log("hook Secure.getString fail: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getStringForUser",
                    ContentResolver.class, String.class, int.class, hook);
        } catch (Throwable t) {
            log("hook Secure.getStringForUser fail: " + t);
        }
    }

    private static void onSettingsRead(XC_MethodHook.MethodHookParam p) {
        if (!spoofEnabled) return;
        if (p.args.length < 2 || !(p.args[1] instanceof String)) return;
        String key = (String) p.args[1];
        if (!"default_input_method".equals(key) && !"enabled_input_methods".equals(key)) return;

        Object real = p.getResult();
        String fake = SPOOF_AS;
        if (fake != null) p.setResult(fake);

        if (LOG_ENABLED && allow("read:" + key, 15)) {
            log("READ " + key + " = " + real + (fake == null ? "" : "  ->  " + fake) + stack(10));
        }
    }

    private void hookImeLists() {
        hookImeList("getEnabledInputMethodList");
        hookImeList("getInputMethodList");
    }

    private void hookImeList(final String name) {
        try {
            Method m = XposedHelpers.findMethodExact(InputMethodManager.class, name);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (!(r instanceof List)) return;
                    if (!LOG_ENABLED || !allow("list:" + name, 8)) return;
                    log(name + " -> " + describe((List<?>) r) + stack(8));
                }
            });
            log("hooked: " + name);
        } catch (Throwable t) {
            log("hook " + name + " fail: " + t);
        }
    }

    /* ======================= 策略 JSON：白名单放行 + 关闭检查 ======================= */

    private void watchPolicyJson() {
        hookJson("optBoolean", String.class);
        hookJson("optBoolean", String.class, boolean.class);
        hookJson("optInt", String.class);
        hookJson("optInt", String.class, int.class);
        hookJson("optString", String.class);
        hookJson("optString", String.class, String.class);
        hookJson("optJSONArray", String.class);
    }

    private void hookJson(final String method, Class<?>... params) {
        try {
            Object[] pt = new Object[params.length];
            for (int i = 0; i < params.length; i++) pt[i] = params[i];
            Method m = XposedHelpers.findMethodExact("org.json.JSONObject", null, method, pt);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        onPolicyValue(method, p);
                    } catch (Throwable t) {
                        log("onPolicyValue(" + method + "): " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("json hook " + method + " fail: " + t);
        }
    }

    private static void onPolicyValue(String method, XC_MethodHook.MethodHookParam p) {
        if (p.args.length == 0 || !(p.args[0] instanceof String)) return;
        String key = (String) p.args[0];
        String low = key.toLowerCase(Locale.US);
        if (low.indexOf("keyboard") < 0 && low.indexOf("input_method") < 0) return;

        Object v = p.getResult();

        if (PATCH_ALLOWLIST && "ApprovedKeyboards".equals(key) && v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            int added = addImePackages(arr);
            if (LOG_ENABLED && allow("policy-add", 20)) log("POLICY ApprovedKeyboards += " + added + " -> " + arr);
            return;
        }

        if (FORCE_POLICY_OFF && "KeyboardsRestricted".equals(key) && v instanceof Boolean) {
            if (((Boolean) v).booleanValue()) {
                p.setResult(Boolean.FALSE);
                if (LOG_ENABLED && allow("policy-off", 20)) log("POLICY KeyboardsRestricted: true -> false");
            } else if (LOG_ENABLED && allow("policy-off-keep", 5)) {
                log("POLICY KeyboardsRestricted already false");
            }
            return;
        }

        if (LOG_ENABLED && allow("policy:" + key, 10)) log("POLICY " + key + " = " + v);
    }

    private static int addImePackages(JSONArray arr) {
        int added = 0;
        Set<String> want = new HashSet<String>();
        Context ctx = sCtx;
        if (ctx != null) {
            for (InputMethodInfo imi : enabledImes(ctx)) want.add(imi.getPackageName());
            String cur = readIme(ctx);
            if (cur != null) {
                int slash = cur.indexOf('/');
                want.add(slash > 0 ? cur.substring(0, slash) : cur);
            }
        }
        for (String pkg : want) {
            if (pkg == null || pkg.length() == 0) continue;
            boolean has = false;
            for (int i = 0; i < arr.length(); i++) {
                if (pkg.equals(arr.optString(i))) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                arr.put(pkg);
                added++;
            }
        }
        return added;
    }

    /* ======================= 兜底：hook SDK 里键盘相关的布尔方法 ======================= */

    private void hookClassLoading() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!FORCE_SDK_METHODS) return;
                            if (p.hasThrowable()) return;
                            Object a = p.args[0];
                            if (!(a instanceof String)) return;
                            String n = (String) a;
                            if (n.indexOf("com.microsoft.intune") != 0) return;
                            Object r = p.getResult();
                            if (r instanceof Class) {
                                try {
                                    inspect((Class<?>) r);
                                } catch (Throwable t) {
                                    raw("inspect fail: " + t);
                                }
                            }
                        }
                    });
            log("hooked: ClassLoader.loadClass");
        } catch (Throwable t) {
            log("hook loadClass fail: " + t);
        }
    }

    private static void inspect(Class<?> c) {
        String name = c.getName();
        synchronized (INSPECTED) {
            if (!INSPECTED.add(name)) return;
        }
        String classLow = name.toLowerCase(Locale.US);
        boolean classKeyboardish = classLow.indexOf("keyboard") >= 0 || classLow.indexOf("inputmethod") >= 0;

        Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (Method m : methods) {
            if (m.isSynthetic() || m.isBridge()) continue;
            String mn = m.getName().toLowerCase(Locale.US);
            boolean methodKeyboardish = mn.indexOf("keyboard") >= 0 || mn.indexOf("inputmethod") >= 0
                    || mn.endsWith("ime") || mn.indexOf("imepkg") >= 0 || mn.indexOf("imeid") >= 0;
            if (!classKeyboardish && !methodKeyboardish) continue;

            Class<?> rt = m.getReturnType();
            if (LOG_ENABLED) log("SDK hit: " + name + "#" + m.getName() + " : " + rt.getSimpleName());
            try {
                if (rt == boolean.class) {
                    if (mn.indexOf("approve") >= 0 || mn.indexOf("allow") >= 0
                            || mn.indexOf("valid") >= 0 || mn.indexOf("permit") >= 0) {
                        forceBool(m, Boolean.TRUE);
                    } else if (mn.indexOf("restrict") >= 0 || mn.indexOf("block") >= 0
                            || mn.indexOf("require") >= 0) {
                        forceBool(m, Boolean.FALSE);
                    }
                }
            } catch (Throwable t) {
                log("SDK hook fail " + name + "#" + m.getName() + ": " + t);
            }
        }
    }

    private static void forceBool(final Method m, final Object value) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(value);
                if (LOG_ENABLED && allow("force:" + m.getName(), MAX_HITS_KEEP)) {
                    log("FORCE " + m.getDeclaringClass().getSimpleName() + "#" + m.getName()
                            + " -> " + value + stack(6));
                }
            }
        });
    }

    /* ======================= 小工具 ======================= */

    private static String readIme(Context ctx) {
        if (ctx == null) return null;
        boolean old = spoofEnabled;
        spoofEnabled = false;
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), "default_input_method");
        } catch (Throwable t) {
            return null;
        } finally {
            spoofEnabled = old;
        }
    }

    private static List<InputMethodInfo> enabledImes(Context ctx) {
        List<InputMethodInfo> out = new ArrayList<InputMethodInfo>();
        try {
            InputMethodManager imm =
                    (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> l = imm.getEnabledInputMethodList();
            if (l != null) out.addAll(l);
        } catch (Throwable t) {
            log("enabledImes: " + t);
        }
        return out;
    }

    private static List<InputMethodInfo> installedImes(Context ctx) {
        List<InputMethodInfo> out = new ArrayList<InputMethodInfo>();
        try {
            InputMethodManager imm =
                    (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> l = imm.getInputMethodList();
            if (l != null) out.addAll(l);
        } catch (Throwable t) {
            log("installedImes: " + t);
        }
        return out;
    }

    private static String describe(List<?> list) {
        if (list == null) return "null";
        StringBuilder sb = new StringBuilder("(" + list.size() + ") ");
        for (Object o : list) {
            if (o instanceof InputMethodInfo) {
                InputMethodInfo i = (InputMethodInfo) o;
                sb.append(i.getPackageName()).append("|").append(i.getId()).append("  ");
            } else {
                sb.append(o).append("  ");
            }
        }
        return sb.toString();
    }

    private static String pkgVersion(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean isTarget(String pkg) {
        if (pkg == null) return false;
        for (String t : TARGETS) {
            if (pkg.startsWith(t)) return true;
        }
        return false;
    }

    private static String shortName(String pkg) {
        if (pkg == null) return "unknown";
        if (pkg.startsWith("com.microsoft.teams")) return "teams";
        if (pkg.startsWith("com.microsoft.outlooklite")) return "outlooklite";
        if (pkg.startsWith("com.microsoft.office.outlook")) return "outlook";
        return pkg;
    }
}
