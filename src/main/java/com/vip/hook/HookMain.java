package com.vip.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 番茄小说 / 红果短剧 / 酷安 的本地改造模块。
 *
 * <p>三块互不影响的逻辑：
 * <ul>
 *   <li>番茄 / 红果：VIP 与去广告（PrivilegeManager 整方法替换）+ 底部 tab 移除；
 *       两者内容侧同源，类路径与方法签名一致，按包名决定去掉哪个 tab。</li>
 *   <li>酷安：广告卡片识别与移除（其 APK 为网易易盾加固，无法静态枚举类名，
 *       因此走「按广告标识隐藏卡片」+ 运行时类名扫描两条路）。</li>
 * </ul>
 *
 * <p>每个 hook 独立 try/catch：某条在目标版本中不存在时只失效这一条，不影响其余。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "[viphook] ";

    /** logcat tag：root 可以直接 `logcat -s viphook` 抓，不依赖框架的日志通道。 */
    private static final String LOG_TAG = "viphook";

    /** 编译期开关：release 版直接短路，不产生任何 logcat 输出，也不写文件。 */
    private static final boolean LOG = Config.LOG;

    /** debug 版额外落盘：LSPosed 2.x 不一定把模块日志写进 logcat，落盘最可靠。 */
    private static File sLogFile;

    private static void plog(String msg) {
        if (!LOG) {
            return;
        }
        try {
            XposedBridge.log(msg);
        } catch (Throwable ignored) {
            // 某些框架版本下桥接日志会抛，别让它影响 hook
        }
        // logcat 是 root 能直接读的通道，比落盘更可靠
        try {
            android.util.Log.e(LOG_TAG, msg);
        } catch (Throwable ignored) {
        }
        File file = sLogFile;
        if (file == null) {
            return;
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
            out.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // 落盘失败不影响 hook
        }
    }

    /** 目标包名。 */
    private static final String PKG_FANQIE = "com.dragon.read";
    private static final String PKG_HONGGUO = "com.phoenix.read";
    private static final String PKG_COOLAPK = "com.coolapk.market";

    private static final String[] TARGET_PACKAGES = {
            PKG_FANQIE,
            PKG_HONGGUO,
            PKG_COOLAPK
    };

    /**
     * VIP 判定类的候选。老 UI 用 PrivilegeManager，番茄 7.4.x 的新 UI（KMP/Compose）
     * 换成了 kmp.vip.impl.privilege.v —— 两个都要挂。
     */
    private static final String[] PRIVILEGE_CLASSES = {
            "com.dragon.read.component.biz.impl.privilege.PrivilegeManager",
            "com.phoenix.read.component.biz.impl.privilege.PrivilegeManager",
            "com.dragon.read.kmp.vip.impl.privilege.v",
            "com.dragon.read.kmp.vip.impl.privilege.x"
    };

    /** 混淆类中的广告/权益判断，随版本改名，失配即静默跳过。 */
    private static final String[] AD_JUDGE_CLASSES = {
            "iv5.a"
    };

    /* ==================== 底部 tab ==================== */

    /** 底部 tab 的落库入口：番茄是 m5，红果是 b5；两者同名混淆类会撞名，按方法签名再校验。 */
    private static final String[] TAB_MANAGER_CLASSES = {
            "com.dragon.read.pages.main.m5",
            "com.dragon.read.pages.main.b5"
    };
    private static final String TAB_DATA_CLASS = "com.dragon.read.rpc.model.BookStoreAlignmentData";

    /** BottomTabBarItemType 中要移除的枚举名，按包名区分。 */
    private static final String TAB_LUCKY_BENEFIT = "LuckyBenefit"; // 赚钱
    private static final String TAB_SHOP_MALL = "ShopMall";         // 商城

    /**
     * 酷安广告卡片上的标识文案前缀。
     *
     * <p>实际角标不一定是纯「广告」，常见还有「广告·京东」「推广」「赞助」，
     * 所以按前缀匹配 + 长度兜底，避免误伤正文里的同一个词。
     */
    private static final String[] AD_LABEL_PREFIXES = {"广告", "推广", "赞助"};

    /** 英文角标只认独立单词（AD / Ad），避免命中 ADB 之类。 */
    private static final String[] AD_LABEL_WORDS = {"AD", "ADS"};

    /** 广告标识通常是小号角标，超过这个字号就不当作标识。 */
    private static final float AD_LABEL_MAX_SP = 13f;

    /** 隐藏失败后的重试次数（每帧 48ms，合计约 0.6 秒）。 */
    private static final int HIDE_RETRIES = 12;

    /** 酷安原生广告控件所在的包：这类容器不进 RecyclerView 就没有角标文字可匹配，只能按类隐藏。 */
    private static final String AD_VIEW_PACKAGE = "com.coolapk.market.view.ad.";

    /** 广告判定/推广卡相关类：debug 版对其中含 ad/promo 的方法做调用观察，用来定位「推广卡片」的判定点。 */
    private static final Set<String> AD_MODEL_CLASSES = new HashSet<>(java.util.Arrays.asList(
            "com.coolapk.market.view.ad.EntityAdHelper",
            "com.coolapk.market.view.ad.scope.ScopeAdManager",
            "com.coolapk.market.view.ad.CoolapkFeedAdType",
            "com.coolapk.market.model.Card"
    ));

    /* -------- 酷安广告页 / 列表相关 -------- */

    /** 开屏广告页：独立 Activity，Launcher 仍是 MainActivity。 */
    private static final String CLS_SPLASH_AD_ACTIVITY = "com.coolapk.market.view.splash.SplashAdActivity";

    /** 插屏广告页。 */
    private static final String CLS_INTERSTITIAL_AD_ACTIVITY =
            "com.coolapk.market.view.ad.interstitial.InterstitialAdActivity";

    /** 主界面。 */
    private static final String CLS_MAIN_ACTIVITY = "com.coolapk.market.view.main.MainActivity";

    /** androidx 列表适配器基类，用来在绑定前复位 item 可见性。 */
    private static final String CLS_RV_ADAPTER = "androidx.recyclerview.widget.RecyclerView$Adapter";

    /** 标记 RecyclerView item 根视图用的 tag key（任意整型，避开 App 自身资源 id）。 */
    private static final int TAG_ITEM_ROOT = 0x76697068;

    /** 记录 item 原始高度，用于隐藏后压成 0 再在下次绑定时还原。 */
    private static final int TAG_ORIG_H = 0x76697069;

    private static volatile boolean sAdapterHooked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !isTarget(lpparam.packageName)) {
            return;
        }

        ClassLoader cl = lpparam.classLoader;
        String pkg = lpparam.packageName;
        int hooked = 0;

        if (LOG) {
            try {
                android.content.pm.ApplicationInfo info =
                        (android.content.pm.ApplicationInfo) lpparam.appInfo;
                if (info != null && info.dataDir != null) {
                    sLogFile = new File(info.dataDir, "viphook.log");
                }
            } catch (Throwable ignored) {
                // 拿不到 dataDir 就只走 logcat
            }
        }

        if (PKG_COOLAPK.equals(pkg)) {
            hooked += hookCoolapkAds();
            plog(TAG + pkg + " 已加载，广告相关 hook 数=" + hooked);
            return;
        }

        Class<?> privilege = null;
        for (String name : PRIVILEGE_CLASSES) {
            Class<?> c = findFirstClass(cl, new String[]{name});
            if (c != null) {
                privilege = c;
                hooked += hookPrivilege(c);
            }
        }

        for (String name : AD_JUDGE_CLASSES) {
            Class<?> adJudge = findFirstClass(cl, new String[]{name});
            if (adJudge != null && hook(adJudge, "a", Boolean.TRUE, String.class)) {
                hooked++;
            }
        }

        hooked += hookTabRemoval(cl, pkg);

        plog(TAG + pkg + " 已加载，生效 hook 数=" + hooked
                + (privilege == null ? "（未找到 PrivilegeManager，版本可能不兼容）" : ""));
    }

    private static boolean isTarget(String packageName) {
        if (packageName == null) {
            return false;
        }
        for (String target : TARGET_PACKAGES) {
            if (target.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> findFirstClass(ClassLoader cl, String[] candidates) {
        for (String candidate : candidates) {
            try {
                return XposedHelpers.findClass(candidate, cl);
            } catch (Throwable ignored) {
                // 该类不在当前 App 中，继续尝试下一个候选
            }
        }
        return null;
    }

    /* ==================== 番茄 / 红果：底部 tab 移除 ==================== */

    /**
     * 从底部 tab 配置里剔除指定项。
     *
     * <p>配置来自服务端下发的 {@code BookStoreAlignmentData}，{@code tabBarList} 是
     * {@code BottomTabBarItemData} 列表（含 tabType），{@code tabBarTypes} 是兜底的枚举列表。
     * 在 {@code m5.p()} 落库之前改写，后续所有下游（角标、预加载、页面切换）都看不到被移除的 tab。
     */
    private static int hookTabRemoval(ClassLoader cl, String pkg) {
        String banned = PKG_HONGGUO.equals(pkg) ? TAB_SHOP_MALL : TAB_LUCKY_BENEFIT;

        if (findFirstClass(cl, new String[]{TAB_DATA_CLASS}) == null) {
            plog(TAG + pkg + " 未找到 BookStoreAlignmentData，跳过 tab 移除");
            return 0;
        }

        int hooked = 0;
        for (String name : TAB_MANAGER_CLASSES) {
            Class<?> manager;
            try {
                manager = XposedHelpers.findClass(name, cl);
            } catch (Throwable ignored) {
                continue;
            }
            // 同名混淆类可能只是 lambda 占位，必须按「入参是 BookStoreAlignmentData」再确认
            for (Method m : manager.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 1 || !TAB_DATA_CLASS.equals(types[0].getName())) {
                    continue;
                }
                try {
                    // 注意：varargs 里不能再塞 Class[]，否则会被当成单个数组元素传给框架，
                    // 参数类型解析失败 → hook 静默失效。这里显式拼一个 Object[]。
                    Object[] hookArgs = new Object[types.length + 1];
                    System.arraycopy(types, 0, hookArgs, 0, types.length);
                    hookArgs[types.length] = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                                return;
                            }
                            try {
                                int removed = dropTab(param.args[0], banned);
                                if (removed > 0) {
                                    plog(TAG + pkg + " 已移除底部 tab: " + banned);
                                }
                            } catch (Throwable t) {
                                plog(TAG + "tab 移除失败: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sanitizeStatic(manager, banned);
                        }
                    };
                    XposedHelpers.findAndHookMethod(manager, m.getName(), hookArgs);
                    hooked++;
                } catch (Throwable t) {
                    plog(TAG + "tab hook 失败 " + name + "#" + m.getName() + ": " + t);
                }
            }

            // m5/b5 的静态表是在 <clinit> 里写死的默认 tab 列表，q() 是 <clinit> 最后一步，
            // 服务端配置到达前 UI 读的就是它，必须在这里也清一遍。
            try {
                XposedHelpers.findAndHookMethod(manager, "q", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sanitizeStatic(manager, banned);
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 该版本没有 q()，只靠上面那层
            }
        }

        if (hooked == 0) {
            plog(TAG + pkg + " 未找到 tab 落库方法，跳过 tab 移除");
        }
        return hooked;
    }

    /** 过滤配置里的 tabBarList / tabBarTypes，返回移除条数。 */
    private static int dropTab(Object data, String banned) {
        int removed = 0;

        Object rawList = XposedHelpers.getObjectField(data, "tabBarList");
        if (rawList instanceof List) {
            List<Object> kept = new ArrayList<>();
            for (Object item : (List<?>) rawList) {
                if (item != null && banned.equals(tabTypeOf(item))) {
                    removed++;
                } else {
                    kept.add(item);
                }
            }
            if (removed > 0) {
                XposedHelpers.setObjectField(data, "tabBarList", kept);
            }
        }

        Object rawTypes = XposedHelpers.getObjectField(data, "tabBarTypes");
        if (rawTypes instanceof List) {
            List<Object> keptTypes = new ArrayList<>();
            for (Object type : (List<?>) rawTypes) {
                if (type != null && banned.equals(String.valueOf(type))) {
                    removed++;
                } else {
                    keptTypes.add(type);
                }
            }
            if (keptTypes.size() != ((List<?>) rawTypes).size()) {
                XposedHelpers.setObjectField(data, "tabBarTypes", keptTypes);
            }
        }

        return removed;
    }

    /**
     * 清掉类里的静态默认 tab 表（m5/b5 的 e、f）。
     *
     * <p>这两个表在 {@code <clinit>} 里就写死了「BookStore / BookCategory / LuckyBenefit / BookShelf / MyProfile」，
     * 服务端配置还没下发时 UI 读的就是它，所以必须在 {@code q()} 之后直接改内存里的列表。
     */
    private static void sanitizeStatic(Class<?> clazz, String banned) {
        for (String fieldName : new String[]{"e", "f"}) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(null);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) value;
                int before = list.size();
                Iterator<?> it = list.iterator();
                while (it.hasNext()) {
                    Object item = it.next();
                    if (item != null && banned.equals(String.valueOf(item))) {
                        it.remove();
                    }
                }
                if (list.size() != before) {
                    plog(TAG + "静态 tab 表(" + fieldName + ") 移除 " + banned
                            + "，剩余 " + list.size() + " 项");
                }
            } catch (Throwable ignored) {
                // 字段不存在或不可写，跳过
            }
        }
    }

    /** 取 BottomTabBarItemData.tabType 的枚举名。 */
    private static String tabTypeOf(Object item) {
        try {
            Object type = XposedHelpers.getObjectField(item, "tabType");
            return type == null ? null : String.valueOf(type);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ==================== 酷安：广告移除 ==================== */

    /**
     * 酷安 16.7.3 的 classes.dex 是网易易盾的壳（仅 30 个 com.netease.nis.wrapper 类），
     * 真实业务类在运行时才解密，无法静态枚举广告模块。因此这里做两件事：
     *
     * <ol>
     *   <li>按「广告」角标隐藏信息流卡片——这类广告卡片一定有标识，命中即折叠整条 item；</li>
     *   <li>挂 ClassLoader，把运行时加载到的 com.coolapk.market 广告相关类名打出来，
     *       供下一版做精确 hook。</li>
     * </ol>
     */
    private static int hookCoolapkAds() {
        int n = 0;
        n += b(hookAdActivities());
        n += b(hookAdLabelHiding());
        n += b(hookAdClassDiscovery());
        return n;
    }

    /** 开屏广告容器：MainActivity 里的全屏 FrameLayout，独立 Activity 那套管不到它。 */
    private static volatile int sSplashAdId = 0;
    private static volatile boolean sSplashIdResolved = false;

    /**
     * 开屏 / 插屏广告页拦截。
     *
     * <p>酷安有两套开屏：一套是独立 Activity（{@code SplashAdActivity}，Launcher 仍然是
     * {@code MainActivity}），另一套是直接挂在 MainActivity 布局里的全屏容器
     * {@code com.coolapk.market:id/main_splash_ad}——后者才是不定时出现的那种。
     * 所以这里两头都处理：广告 Activity 直接 finish，广告容器按资源 id 强制 GONE。
     */
    private static boolean hookAdActivities() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity activity = (Activity) param.thisObject;
                    try {
                        if (!sSplashIdResolved) {
                            int id = activity.getResources()
                                    .getIdentifier("main_splash_ad", "id", PKG_COOLAPK);
                            sSplashAdId = id;
                            sSplashIdResolved = true;
                        }
                        if (sSplashAdId != 0) {
                            hideViewLater(activity, sSplashAdId, 0);
                            hideViewLater(activity, sSplashAdId, 400);
                            hideViewLater(activity, sSplashAdId, 1500);
                        }
                    } catch (Throwable ignored) {
                        // 资源解析失败不影响下面的广告 Activity 拦截
                    }
                }

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity activity = (Activity) param.thisObject;
                    String name = activity.getClass().getName();
                    if (!CLS_SPLASH_AD_ACTIVITY.equals(name)
                            && !CLS_INTERSTITIAL_AD_ACTIVITY.equals(name)) {
                        return;
                    }
                    try {
                        boolean splash = CLS_SPLASH_AD_ACTIVITY.equals(name);
                        if (splash) {
                            // 开屏页可能把主界面自己 finish 掉，兜底把 MainActivity 拉起来
                            Intent back = new Intent(Intent.ACTION_MAIN);
                            back.setClassName(activity, CLS_MAIN_ACTIVITY);
                            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            activity.startActivity(back);
                        }
                        // 插屏只 finish：它下面压着用户正在看的页面，跳主页会把人踢走
                        activity.finish();
                        plog(TAG + "已跳过广告页: " + name);
                    } catch (Throwable t) {
                        plog(TAG + "跳过广告页失败 " + name + ": " + t);
                    }
                }
            });
            // 广告容器可能被 App 重新置为可见，这里盯住 setVisibility
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int splashId = sSplashAdId;
                    if (splashId == 0 || !(param.thisObject instanceof View)) {
                        return;
                    }
                    View view = (View) param.thisObject;
                    if (view.getId() != splashId) {
                        return;
                    }
                    if (param.args[0] instanceof Integer && (Integer) param.args[0] == View.GONE) {
                        return;
                    }
                    // 参数是 GONE 时不会进这个分支，所以不会递归
                    view.setVisibility(View.GONE);
                    plog(TAG + "已隐藏开屏广告容器");
                }
            });
            return true;
        } catch (Throwable t) {
            plog(TAG + "广告页 hook 失败: " + t);
            return false;
        }
    }

    private static void hideViewLater(final Activity activity, final int id, final long delayMs) {
        final Runnable hide = new Runnable() {
            @Override
            public void run() {
                try {
                    View view = activity.findViewById(id);
                    if (view != null && view.getVisibility() != View.GONE) {
                        view.setVisibility(View.GONE);
                        plog(TAG + "已隐藏开屏广告容器: " + view.getClass().getName());
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        if (delayMs <= 0) {
            activity.getWindow().getDecorView().post(hide);
        } else {
            activity.getWindow().getDecorView().postDelayed(hide, delayMs);
        }
    }

    /** 命中「广告」角标时，把所在的 RecyclerView item 折叠掉。 */
    private static boolean hookAdLabelHiding() {
        int n = 0;
        // TextView 的单参 setText 最终都会走两参版本（char[] / int 版本同理），
        // 所以挂两参就能覆盖数据绑定、自定义控件里显式传 BufferType 的调用——
        // 之前只挂单参，穿山甲那类 SDK 自己设文案的角标就漏了。
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setText",
                    CharSequence.class, TextView.BufferType.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof TextView && param.args[0] instanceof CharSequence) {
                                onPossibleAdLabel((TextView) param.thisObject, param.args[0].toString());
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            plog(TAG + "广告标识 hook 失败(setText/CharSequence): " + t);
        }
        return n > 0;
    }

    /** 角标文案命中后统一处理：判定 → 定位 → 隐藏。 */
    private static void onPossibleAdLabel(TextView label, String text) {
        if (text == null) {
            return;
        }
        // 推广卡片没有「广告」字样，只能靠 CTA 按钮识别：action_view 上的「去看看 / 立即领取」
        if (isPromoCta(label, text)) {
            if (!hideCardOf(label)) {
                scheduleHide(label, HIDE_RETRIES);
            }
            return;
        }
        if (!isAdLabel(text)) {
            if (LOG && looksLikeAdWord(text)) {
                plog(TAG + "疑似广告文案但未命中规则: [" + text + "] size="
                        + textSizeSp(label) + "sp " + label.getClass().getName());
            }
            return;
        }
        if (!isSmallLabel(label)) {
            if (LOG) {
                plog(TAG + "广告文案但字号过大已忽略: [" + text + "] " + textSizeSp(label)
                        + "sp（阈值 " + AD_LABEL_MAX_SP + "）");
            }
            return;
        }
        if (!hideCardOf(label)) {
            scheduleHide(label, HIDE_RETRIES);
        }
    }

    /** 推广卡 CTA 文案。 */
    private static final String[] PROMO_CTAS = {"去看看", "立即领取", "去领取", "去参与", "去看看 >"};

    /**
     * 酷安的「推广卡片」（帖子页里那种「酷安专属优惠 / 去看看」）没有广告角标，
     * 唯一的稳定特征是 {@code com.coolapk.market:id/action_view} 上的引导按钮，
     * 所以按「资源 id 名 + 文案」判定，避免误伤正文。
     */
    private static boolean isPromoCta(TextView label, String raw) {
        String text = raw.trim();
        if (text.length() > 8) {
            return false;
        }
        boolean cta = false;
        for (String candidate : PROMO_CTAS) {
            if (text.equals(candidate) || text.startsWith(candidate)) {
                cta = true;
                break;
            }
        }
        if (!cta) {
            return false;
        }
        return "action_view".equals(idName(label, label.getId()));
    }

    /** 资源 id 名缓存：getResourceEntryName 每次都查一遍太慢，按 id 缓存。 */
    private static final Map<Integer, String> ID_NAMES = new HashMap<>();

    private static String idName(TextView tv, int id) {
        if (id == 0) {
            return null;
        }
        synchronized (ID_NAMES) {
            if (ID_NAMES.containsKey(id)) {
                return ID_NAMES.get(id);
            }
        }
        String name = null;
        try {
            name = tv.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            // 非本包资源或已失效
        }
        synchronized (ID_NAMES) {
            ID_NAMES.put(id, name == null ? "" : name);
        }
        return name;
    }

    private static boolean looksLikeAdWord(String raw) {
        String text = raw.trim();
        if (text.isEmpty() || text.length() > 12) {
            return false;
        }
        return text.contains("广告") || text.contains("推广") || text.contains("赞助")
                || text.toUpperCase(Locale.US).contains("AD");
    }

    private static float textSizeSp(TextView tv) {
        try {
            Context ctx = tv.getContext();
            return ctx == null ? -1f : tv.getTextSize() / ctx.getResources().getDisplayMetrics().scaledDensity;
        } catch (Throwable t) {
            return -1f;
        }
    }

    /**
     * 广告角标判定：前缀匹配「广告 / 推广 / 赞助」，英文只认独立单词。
     * 长度限制是为了避免「广告位招租」这类正文被误伤。
     */
    private static boolean isAdLabel(String raw) {
        if (raw == null) {
            return false;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.length() > 8) {
            return false;
        }
        for (String prefix : AD_LABEL_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        String upper = text.toUpperCase(Locale.US);
        for (String word : AD_LABEL_WORDS) {
            if (upper.equals(word)) {
                return true;
            }
            if (upper.startsWith(word) && upper.length() > word.length()) {
                char next = upper.charAt(word.length());
                if (next == ' ' || next == '·' || next == '-' || next == '|' || next == ':' || next == '：') {
                    return true;
                }
            }
        }
        return false;
    }

    /** item 还没挂到 RecyclerView 上时，隔几帧再试，避免预取阶段漏掉。 */
    private static void scheduleHide(final View target, final int retries) {
        target.post(new Runnable() {
            @Override
            public void run() {
                if (hideCardOf(target)) {
                    return;
                }
                if (retries <= 0) {
                    if (LOG) {
                        plog(TAG + "广告视图定位失败，父链: " + describeChain(target));
                    }
                    return;
                }
                target.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scheduleHide(target, retries - 1);
                    }
                }, 48);
            }
        });
    }

    /** 日志用：把目标视图往上 12 层的类名拼出来，方便定位该藏哪一层。 */
    private static String describeChain(View view) {
        StringBuilder sb = new StringBuilder(view.getClass().getName());
        View current = view;
        for (int depth = 0; depth < 12; depth++) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                sb.append(" -> <end>");
                break;
            }
            current = (View) parent;
            sb.append(" -> ").append(current.getClass().getName());
        }
        return sb.toString();
    }

    /** 广告角标是小字，正文碰巧出现「广告」二字的不会被误判。 */
    private static boolean isSmallLabel(TextView tv) {
        Context ctx = tv.getContext();
        if (ctx == null) {
            return false;
        }
        float sp = tv.getTextSize() / ctx.getResources().getDisplayMetrics().scaledDensity;
        return sp <= AD_LABEL_MAX_SP;
    }

    /**
     * 从角标向上找到这条 item 的根视图并隐藏。
     *
     * <p>优先用 adapter 绑定阶段打上的标记直接命中 item 根视图（预取时 item 还没挂到
     * RecyclerView，父链是断的，光靠向上找 RecyclerView 会漏）；拿不到标记再退化成
     * 「向上找到 RecyclerView 的直接子 View」。只改可见性，不再动 LayoutParams——
     * 改高度会跟着复用的 holder 一起流转，导致普通卡片变空白。
     *
     * @return 是否成功定位并处理
     */
    private static boolean hideCardOf(View label) {
        try {
            View item = null;
            View current = label;
            for (int depth = 0; depth < 16 && current != null; depth++) {
                Object tag = current.getTag(TAG_ITEM_ROOT);
                if (tag instanceof View) {
                    item = (View) tag;
                    break;
                }
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) {
                    break;
                }
                View parentView = (View) parent;
                String parentName = parentView.getClass().getName();
                if (parentName.contains("RecyclerView") || parentName.contains("AbsListView")) {
                    item = current;
                    break;
                }
                current = parentView;
            }
            if (item == null || item == label) {
                return false;
            }
            if (item.getVisibility() == View.GONE) {
                return true;
            }
            item.setVisibility(View.GONE);
            // 只设 GONE 时，GridView / 部分列表仍会为该 item 保留原本的高度，
            // 表现为去掉了广告却留下大片空白。这里同时把高度压成 0，
            // 下次绑定时由 bindViewHolder 的 before 钩子还原（见 bind 钩子里的 TAG_ORIG_H）。
            try {
                ViewGroup.LayoutParams lp = item.getLayoutParams();
                if (lp != null) {
                    Object original = item.getTag(TAG_ORIG_H);
                    if (original == null) {
                        item.setTag(TAG_ORIG_H, lp.height);
                    }
                    lp.height = 0;
                    item.setLayoutParams(lp);
                }
            } catch (Throwable ignored) {
                // 拿不到 LayoutParams 时只保留 GONE
            }
            plog(TAG + "已隐藏广告卡片: " + item.getClass().getName());
            return true;
        } catch (Throwable t) {
            plog(TAG + "隐藏广告卡片失败: " + t);
            return false;
        }
    }

    /**
     * 运行时侦察 + 列表绑定钩子。
     *
     * <p>酷安是易盾壳，真实类在运行时才解密，所以只能在 {@code ClassLoader.loadClass}
     * 拿到类的那一刻再挂：用它对 {@code RecyclerView$Adapter} 做「绑定前复位」，
     * 保证被隐藏的 item 在回收复用后能恢复可见，不会把普通卡片一起吞掉。
     */
    private static boolean hookAdClassDiscovery() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object arg = param.args[0];
                            if (!(arg instanceof String)) {
                                return;
                            }
                            String name = (String) arg;
                            if (CLS_RV_ADAPTER.equals(name)) {
                                Object result = param.getResult();
                                if (result instanceof Class) {
                                    hookAdapterClass((Class<?>) result);
                                }
                                return;
                            }
                            if (name.startsWith(AD_VIEW_PACKAGE)) {
                                Object result = param.getResult();
                                if (result instanceof Class) {
                                    Class<?> clazz = (Class<?>) result;
                                    if (View.class.isAssignableFrom(clazz)) {
                                        hookAdViewClass(clazz);
                                    }
                                }
                                return;
                            }
                            if (LOG && AD_MODEL_CLASSES.contains(name)) {
                                Object result = param.getResult();
                                if (result instanceof Class) {
                                    hookAdModelClass((Class<?>) result);
                                }
                                return;
                            }
                            if (!LOG || !name.startsWith("com.coolapk.market")) {
                                return;
                            }
                            String lower = name.toLowerCase(Locale.US);
                            if (!lower.contains("ad") || lower.contains("adapter")
                                    || lower.contains("load") || lower.contains("shadow")
                                    || lower.contains("head") || lower.contains("read")) {
                                return;
                            }
                            Object result = param.getResult();
                            if (!(result instanceof Class)) {
                                return;
                            }
                            logAdClass((Class<?>) result);
                        }
                    });
            return true;
        } catch (Throwable t) {
            plog(TAG + "广告类扫描 hook 失败: " + t);
            return false;
        }
    }

    /**
     * 给 {@code RecyclerView.Adapter#bindViewHolder} 挂复位逻辑：
     * 绑定前把 item 根视图恢复可见并打上标记，广告角标命中时 {@link #hideCardOf} 再把它藏掉。
     * 复位放在绑定前，所以只会影响「本该显示」的卡片，不会残留上一轮的状态。
     *
     * <p>必须挂 {@code bindViewHolder} 而不是 {@code onBindViewHolder}：后者每个 Adapter
     * 子类都会覆写，挂在基类上永远不会被调用到；{@code bindViewHolder} 是 final 的公共入口，
     * 所有子类都从这里进。
     */
    private static void hookAdapterClass(Class<?> adapter) {
        if (sAdapterHooked) {
            return;
        }
        synchronized (HookMain.class) {
            if (sAdapterHooked) {
                return;
            }
            Method target = null;
            for (String name : new String[]{"bindViewHolder", "onBindViewHolder"}) {
                for (Method m : adapter.getDeclaredMethods()) {
                    if (!name.equals(m.getName())) {
                        continue;
                    }
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 2 && types[1] == int.class) {
                        target = m;
                        break;
                    }
                }
                if (target != null) {
                    break;
                }
            }
            if (target == null) {
                return;
            }
            try {
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object holder = param.args[0];
                            if (holder == null) {
                                return;
                            }
                            Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                            if (!(itemView instanceof View)) {
                                return;
                            }
                            View view = (View) itemView;
                            view.setTag(TAG_ITEM_ROOT, view);
                            if (view.getVisibility() != View.VISIBLE) {
                                view.setVisibility(View.VISIBLE);
                            }
                        } catch (Throwable ignored) {
                            // 单个 holder 复位失败不影响绑定
                        }
                    }
                });
                sAdapterHooked = true;
                plog(TAG + "列表绑定复位已挂载: " + target);
            } catch (Throwable t) {
                plog(TAG + "列表绑定复位挂载失败: " + t);
            }
        }
    }

    /**
     * 酷安原生广告控件的构造钩子。
     *
     * <p>穿山甲 / TopOn 的原生广告卡片由酷安自己的容器控件承载（`view.ad.*` 下的 View），
     * 里面那枚「广告 · 穿山甲·今日推荐」角标是 SDK 画的，不一定走 TextView，
     * 光靠文本匹配会整卡漏掉。这里在容器创建时就把它所在的列表 item 一起隐藏。
     */
    private static void hookAdViewClass(Class<?> clazz) {
        if (!HOOKED_AD_VIEWS.add(clazz.getName())) {
            return;
        }
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) {
                            return;
                        }
                        View view = (View) param.thisObject;
                        plog(TAG + "广告控件已创建: " + view.getClass().getName());
                        if (!hideCardOf(view)) {
                            scheduleHide(view, HIDE_RETRIES);
                        }
                    }
                });
            } catch (Throwable ignored) {
                // 单个构造函数挂不上不影响其它
            }
        }
    }

    private static final Set<String> HOOKED_AD_MODELS = new HashSet<>();

    private static int sModelLogCount = 0;

    /**
     * debug 版观察：把广告判定类里方法名含 ad / promo / sponsor 的调用连同返回值打到日志，
     * 用来找出「推广卡片」到底由哪一层判定（这些类都是运行时才解密出来的，只能这样反推）。
     */
    private static synchronized void hookAdModelClass(Class<?> clazz) {
        if (!HOOKED_AD_MODELS.add(clazz.getName())) {
            return;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            String lower = m.getName().toLowerCase(Locale.US);
            if (!lower.contains("ad") && !lower.contains("promo") && !lower.contains("sponsor")) {
                continue;
            }
            final String label = clazz.getSimpleName() + "#" + m.getName();
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        synchronized (HookMain.class) {
                            if (sModelLogCount > 300) {
                                return;
                            }
                            sModelLogCount++;
                        }
                        Object result = null;
                        try {
                            result = param.getResult();
                        } catch (Throwable ignored) {
                        }
                        plog(TAG + "观察 " + label + " -> " + result);
                    }
                });
            } catch (Throwable ignored) {
                // 单个方法挂不上不影响其它
            }
        }
    }

    private static final Set<String> HOOKED_AD_VIEWS = new HashSet<>();

    private static final Set<String> LOGGED_CLASSES = new HashSet<>();

    private static void logAdClass(Class<?> clazz) {
        synchronized (LOGGED_CLASSES) {
            if (!LOGGED_CLASSES.add(clazz.getName()) || LOGGED_CLASSES.size() > 200) {
                return;
            }
        }
        StringBuilder sb = new StringBuilder(TAG + "广告类: ").append(clazz.getName());
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                sb.append("\n    ").append(m.getName()).append("()");
            }
        } catch (Throwable ignored) {
        }
        plog(sb.toString());
    }

    /* ==================== 通用工具 ==================== */

    /** PrivilegeManager 上的全部 hook，返回成功条数。 */
    private static int hookPrivilege(Class<?> c) {
        int n = 0;

        // 会员身份
        n += b(hook(c, "isVip", Boolean.TRUE));
        n += b(hook(c, "isAnyVip", Boolean.TRUE));
        n += b(hook(c, "adVipAvailable", Boolean.TRUE));

        // 广告权益（全场景 / 短剧 / 阅读消耗 / 永久）
        n += b(hook(c, "hasNoAdPrivilege", Boolean.TRUE));
        n += b(hook(c, "hasNoAdFollAllScene", Boolean.TRUE));
        n += b(hook(c, "hasNoAdForShortSeries", Boolean.TRUE));
        n += b(hook(c, "hasNoAdReadConsumptionPrivilege", Boolean.TRUE));
        n += b(hook(c, "isForeverNoAd", Boolean.TRUE));

        // 按 key 查询的通用通道，一律放行
        n += b(hook(c, "hasPrivilege", Boolean.TRUE, String.class));
        n += b(hook(c, "isNoAd", Boolean.TRUE, String.class));
        n += b(hook(c, "isBookAdFree", Integer.valueOf(1), String.class));

        // 内容权益
        n += b(hook(c, "hasReadPaidBookPrivilege", Boolean.TRUE));
        n += b(hook(c, "hasVipShortSeriesPrivilege", Boolean.TRUE));
        n += b(hook(c, "canReadShortStory", Boolean.TRUE));

        // 付费引导与评论限制
        n += b(hook(c, "showPayVipEntranceInChapterEnd", Boolean.FALSE));
        n += b(hook(c, "checkCommentForbidden", Boolean.FALSE));

        return n;
    }

    /**
     * 用固定返回值替换方法体。
     *
     * @param clazz      目标类
     * @param method     方法名
     * @param result     固定返回值，类型需与原方法返回类型一致
     * @param paramTypes 方法参数类型（无参则省略）
     * @return 是否挂载成功
     */
    private static boolean hook(Class<?> clazz, String method, Object result, Class<?>... paramTypes) {
        Object[] args = new Object[paramTypes.length + 1];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = paramTypes[i];
        }
        args[paramTypes.length] = XC_MethodReplacement.returnConstant(result);
        try {
            XposedHelpers.findAndHookMethod(clazz, method, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int b(boolean value) {
        return value ? 1 : 0;
    }
}
