package com.vip.hook;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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

    /** 编译期开关：release 版直接短路，不产生任何 logcat 输出，也不写文件。 */
    private static final boolean LOG = Config.LOG;

    private static void plog(String msg) {
        if (LOG) {
            XposedBridge.log(msg);
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

    /** 酷安广告卡片上的标识文案（精确匹配，避免误伤正文）。 */
    private static final Set<String> AD_LABELS = new HashSet<>(Arrays.asList(
            "广告", "推广", "赞助", "AD", "ad", "Ad"
    ));

    /** 广告标识通常是小号角标，超过这个字号就不当作标识。 */
    private static final float AD_LABEL_MAX_SP = 13f;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !isTarget(lpparam.packageName)) {
            return;
        }

        ClassLoader cl = lpparam.classLoader;
        String pkg = lpparam.packageName;
        int hooked = 0;

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
        n += b(hookAdLabelHiding());
        n += b(hookAdClassDiscovery());
        return n;
    }

    /** 命中「广告」角标时，把所在的 RecyclerView item 折叠掉。 */
    private static boolean hookAdLabelHiding() {
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0) {
                                return;
                            }
                            Object arg = param.args[0];
                            if (!(arg instanceof CharSequence)) {
                                return;
                            }
                            String text = arg.toString().trim();
                            if (!AD_LABELS.contains(text)) {
                                return;
                            }
                            if (!(param.thisObject instanceof TextView)) {
                                return;
                            }
                            final TextView label = (TextView) param.thisObject;
                            if (!isSmallLabel(label)) {
                                return;
                            }
                            label.post(new Runnable() {
                                @Override
                                public void run() {
                                    hideCardOf(label);
                                }
                            });
                        }
                    });
            return true;
        } catch (Throwable t) {
            plog(TAG + "广告标识 hook 失败: " + t);
            return false;
        }
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

    /** 从角标向上找到列表 item 根视图，折叠成 0 高度。 */
    private static void hideCardOf(View label) {
        try {
            View item = null;
            View current = label;
            for (int depth = 0; depth < 12; depth++) {
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
            if (item == null) {
                return;
            }
            ViewGroup.LayoutParams lp = item.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                item.setLayoutParams(lp);
            }
            item.setVisibility(View.GONE);
            plog(TAG + "已折叠广告卡片: " + item.getClass().getName());
        } catch (Throwable t) {
            plog(TAG + "折叠广告卡片失败: " + t);
        }
    }

    /** 运行时把 com.coolapk.market 下广告相关类名记录下来。 */
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
                            if (!name.startsWith("com.coolapk.market")) {
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
