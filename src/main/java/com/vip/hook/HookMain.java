package com.vip.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 番茄小说 / 红果短剧 VIP 与去广告模块。
 *
 * <p>两个 App 内容侧同源、共用一套代码基，PrivilegeManager 的类路径与方法签名一致，
 * 因此同一份 hook 逻辑对两者都适用，只需分别放行包名、并兼容两种类路径。
 *
 * <p>所有 hook 都是「整方法替换」（returnConstant），只影响本地判断，服务端权益不变。
 * 每个 hook 独立 try/catch：某个方法在目标版本中不存在时只失效这一条，不影响其余。
 */
public class HookMain implements IXposedHookLoadPackage {

    /** 目标包名：番茄免费小说、红果免费短剧。 */
    private static final String[] TARGET_PACKAGES = {
            "com.dragon.read",
            "com.phoenix.read"
    };

    /** PrivilegeManager 的候选类路径，按顺序探测。 */
    private static final String[] PRIVILEGE_CLASSES = {
            "com.dragon.read.component.biz.impl.privilege.PrivilegeManager",
            "com.phoenix.read.component.biz.impl.privilege.PrivilegeManager"
    };

    /** 混淆类中的广告/权益判断，随版本改名，失配即静默跳过。 */
    private static final String[] AD_JUDGE_CLASSES = {
            "iv5.a"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !isTarget(lpparam.packageName)) {
            return;
        }

        ClassLoader cl = lpparam.classLoader;
        int hooked = 0;

        Class<?> privilege = findFirstClass(cl, PRIVILEGE_CLASSES);
        if (privilege != null) {
            hooked += hookPrivilege(privilege);
        }

        for (String name : AD_JUDGE_CLASSES) {
            Class<?> adJudge = findFirstClass(cl, new String[]{name});
            if (adJudge != null && hook(adJudge, "a", Boolean.TRUE, String.class)) {
                hooked++;
            }
        }

        XposedBridge.log("[viphook] " + lpparam.packageName
                + " 已加载，生效 hook 数=" + hooked
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
