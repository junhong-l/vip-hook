# viphook

一个约 16 KB 的 Xposed 模块，让**番茄小说**与**红果短剧**在本地表现为「已开通 VIP、全场景无广告、付费内容可读」。它不修改、不重打包、不重签名目标 App，只在运行时把 App 自己的会员/广告判断方法替换成固定返回值。

| 项 | 值 |
|---|---|
| 包名 | `com.vip.hook`（模块自身） |
| 作用目标 | `com.dragon.read`（番茄小说）、`com.phoenix.read`（红果短剧） |
| 产物 | `viphook.apk`，md5 `0b90132130b94295c204b3baafe1aab7` |
| 运行环境 | 已 Root + LSPosed / EdXposed，Xposed API ≥ 54 |

## 项目做什么

目标 App 的会员态与广告开关并不是散落在各处的判断，而是集中在 `PrivilegeManager` 这一个类里：界面问它「是不是 VIP」「这个场景有没有免广告权益」「章节末要不要显示开通入口」，它回答是或否。

本项目做的就是**改写这一层的回答**：凡是「有没有权益」的询问都答「有」，凡是「要不要展示付费引导」的询问都答「不要」。App 自己的业务代码、UI、播放逻辑一行都不用改，它们照常按「用户是 VIP」的分支走。

因此它只改变客户端的判断结果，**不改变服务端**：VIP 专享内容的下发、播放鉴权、CDN 签名仍按真实账号权益执行。

### 与原版 App 的行为对比

| 场景 | 原版 App | 模块生效后 |
|---|---|---|
| 会员身份（`isVip` / `isAnyVip`） | 读取登录账号的会员状态与下发结果 | 恒为「是」 |
| 广告判断（`hasNoAdPrivilege` 等各场景开关） | 按账号权益逐项判断，无权益则拉广告 | 各场景一律「有免广告权益」 |
| 按 key 查询权益（`hasPrivilege` / `isNoAd`） | 查本地权益表，未命中返回 false | 不论 key 一律 true |
| 书籍广告状态（`isBookAdFree`） | 返回状态码，0 表示有广告 | 恒返回 `1` |
| 章节末开通入口（`showPayVipEntranceInChapterEnd`） | 非会员时展示付费引导 | 不展示 |
| 付费书 / 会员短剧 / 短篇 | 无权益时提示购买或试读 | 按有权益放行 |
| 评论限制（`checkCommentForbidden`） | 按账号状态限制 | 不限制 |
| 服务端下发的 VIP 内容 | 按真实账号权益 | **不变**（这是模块的边界） |

## 代码构成

```
fanqie/
├── viphook.apk                  # 构建产物（可直接安装）
├── build.sh                     # 一键构建
├── AndroidManifest.xml          # 模块声明
├── assets/xposed_init           # 模块入口类名
└── src/
    ├── main/java/com/vip/hook/HookMain.java        # 全部 hook 逻辑
    └── stubs/java/de/robv/android/xposed/          # 编译期 API 占位
        ├── IXposedHookLoadPackage.java
        ├── XposedHelpers.java
        ├── XposedBridge.java
        ├── XC_MethodHook.java
        ├── XC_MethodReplacement.java
        └── callbacks/XC_LoadPackage.java
```

各部分职责：

- **`AndroidManifest.xml`** — 声明自己是一个 Xposed 模块：`xposedmodule=true`、`xposedminversion=54`、模块名与描述。没有 Activity、没有权限、没有资源。
- **`assets/xposed_init`** — 一行文本 `com.vip.hook.HookMain`，框架加载模块时读的就是它。
- **`HookMain.java`** — 全项目唯一的业务代码，约 170 行。实现 `IXposedHookLoadPackage`，在目标 App 进程启动时被调用一次。
- **`src/stubs/`** — Xposed API 的**空壳**，只为让 `javac` 编译通过。它们不会进 dex（`build.sh` 喂给 `d8` 的只有 `com/vip/hook/*.class`），运行期由 LSPosed 提供真实实现。
- **`build.sh`** — `javac` → `d8` → `aapt2 link` → `zipalign` → `apksigner` 五步出包。

### `HookMain` 的内部结构

代码分四层，由外到内：

| 方法 | 职责 |
|---|---|
| `handleLoadPackage` | 唯一入口：判定目标 → 解析类 → 注册 hook → 写日志 |
| `isTarget` | 包名白名单，只放行 `com.dragon.read` 与 `com.phoenix.read` |
| `findFirstClass` | 按候选数组依次 `XposedHelpers.findClass`，返回第一个命中的类 |
| `hookPrivilege` | 逐个注册 16 个 `PrivilegeManager` 上的 hook |
| `hook` | 单条注册：拼参数数组 → `returnConstant` → `findAndHookMethod`，异常吞掉并返回是否成功 |

两个设计上的取舍：

1. **类路径探测而非写死**。两个 App 同源但包名不同，内部类可能叫 `com.dragon.read.*` 也可能叫 `com.phoenix.read.*`；先试前者、失败再试后者，命中即用，省掉两套重复的注册逻辑。
2. **逐条 try/catch**。某个方法在目标版本中改名或消失时，只失效这一条，不影响其余，也不会让宿主崩溃——代价是失败静默，所以加载完会写一行日志。

### 注册逻辑长什么样

```java
if (!isTarget(lpparam.packageName)) return;                  // 包名闸门
Class<?> pm = findFirstClass(cl, PRIVILEGE_CLASSES);         // 解析目标类
hook(pm, "isVip", Boolean.TRUE);                             // 无参方法
hook(pm, "isBookAdFree", Integer.valueOf(1), String.class);  // 带参方法
XposedBridge.log("[viphook] " + pkg + " 已加载，生效 hook 数=" + n);
```

`hook()` 的可变参数是**方法参数类型**，`returnConstant` 作为回调追加在末尾。返回值类型必须与方法原型严格一致——`isBookAdFree` 是 int，就返回 `Integer 1`，写成 `Boolean` 会静默失配。

## Hook 清单

### `PrivilegeManager`（16 条）

| # | 方法 | 参数 | 固定返回 | 作用 |
|---|---|---|---|---|
| 1 | `isVip` | — | `true` | 基础会员判断 |
| 2 | `isAnyVip` | — | `true` | 任一会员类型判断 |
| 3 | `isForeverNoAd` | — | `true` | 永久免广告标记 |
| 4 | `hasNoAdPrivilege` | — | `true` | 免广告权益 |
| 5 | `hasNoAdFollAllScene` | — | `true` | 全场景免广告 |
| 6 | `hasNoAdForShortSeries` | — | `true` | 短剧免广告 |
| 7 | `hasNoAdReadConsumptionPrivilege` | — | `true` | 阅读消耗场景免广告 |
| 8 | `hasPrivilege` | `String` | `true` | 通用权益查询一律放行 |
| 9 | `isNoAd` | `String` | `true` | 按场景 key 的免广告查询 |
| 10 | `isBookAdFree` | `String` | `Integer 1` | 书籍广告状态码（int，不是 boolean） |
| 11 | `showPayVipEntranceInChapterEnd` | — | `false` | 章节末付费入口不展示 |
| 12 | `adVipAvailable` | — | `true` | 广告型 VIP 可用性 |
| 13 | `hasReadPaidBookPrivilege` | — | `true` | 付费书可读 |
| 14 | `hasVipShortSeriesPrivilege` | — | `true` | 会员短剧权益 |
| 15 | `canReadShortStory` | — | `true` | 短篇可读 |
| 16 | `checkCommentForbidden` | — | `false` | 解除评论限制 |

### 混淆类（1 条）

| 类#方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `iv5.a#a` | `String` | `true` | 广告/权益判断。类名随版本混淆变化；在番茄 7.4.5.32 中 `a` 是字段而非方法，此条会静默失效，保留仅为兼容其他版本 |

## 构建

```bash
./build.sh                # 输出 viphook.apk
./build.sh /tmp/test.apk  # 指定输出路径
```

依赖：JDK、`/opt/android-sdk/build-tools/36.0.0`、`/opt/android-sdk/platforms/android-34/android.jar`。

流程：

1. `javac --release 11` 编译 `src/` 下全部 Java；
2. 把 stub 单独打成 `build/stubs.jar`，仅供 `d8` 解析符号；
3. `d8 --min-api 21 --lib build/stubs.jar` 只汇编 `com/vip/hook/*.class`，stub 不进 dex；
4. `aapt2 link` 生成二进制 manifest，`zip` 追加 `classes.dex` 与 `assets/xposed_init`；
5. `zipalign` 对齐，`apksigner` 签名。首次运行会生成 `viphook.keystore`（口令 `viphook`），之后复用同一把 key。

校验产物：

```bash
md5sum viphook.apk                          # 0b90132130b94295c204b3baafe1aab7
unzip -p viphook.apk classes.dex | md5sum   # bb15e4c3ab49b5ba0fc1b6fcd54dda47
unzip -l viphook.apk                        # manifest + resources.arsc + classes.dex + assets + 签名
```

> 本机 `apktool 2.9.3` 在 JDK 25 下会在 aapt2 阶段抛 `NullPointerException`，且其内置的 smali 汇编器没有 CLI 入口，因此项目不使用 apktool，改走 Java + `d8` 的构建链。

## 安装

1. 设备已 Root 并安装 LSPosed。
2. `adb install viphook.apk`。
3. LSPosed 中启用模块，作用域将**番茄小说与红果短剧都勾上**（模块自己按包名分流）。
4. 强制停止目标 App 再打开（模块在进程启动时加载，热重启无效）。
5. 看 LSPosed 日志，应出现：

```
[viphook] com.dragon.read 已加载，生效 hook 数=16
```

数字即成功挂上的条数。若为 0 或提示「未找到 PrivilegeManager」，说明该版本的类路径或方法签名变了。

## 适配新版本

类名、方法名、参数个数任一变化都会导致失配（静默）。排查思路：

```bash
BT=/opt/android-sdk/build-tools/36.0.0
$BT/aapt2 dump badging target.apk | head -1        # 确认包名
unzip -o -q target.apk 'classes*.dex' -d dex && cd dex
for f in classes*.dex; do
  strings -a "$f" | grep -q "privilege/PrivilegeManager" && echo "$f"
done
# 列出该类全部方法签名
$BT/dexdump classesN.dex | awk -v RS="Class #" \
  '/Class descriptor  : .L.*privilege\/PrivilegeManager;/' | grep -E "name|type"
```

拿到真实类路径后改 `HookMain.PRIVILEGE_CLASSES`，方法名有变则同步改 `hookPrivilege`；若是新包名，再往 `TARGET_PACKAGES` 里加一条。

## 2.0 新增

| 能力 | 目标 | 实现 |
|---|---|---|
| 移除底部「赚钱」tab | 番茄小说 | hook `com.dragon.read.pages.main.m5` 中入参为 `BookStoreAlignmentData` 的方法（`p`），在落库前从 `tabBarList` / `tabBarTypes` 里剔除 `BottomTabBarItemType.LuckyBenefit` |
| 移除底部「商城」tab | 红果短剧 | 同一套逻辑；红果的落库类是 `com.dragon.read.pages.main.b5`（同名 `m5` 只是 lambda），剔除 `ShopMall` |
| 酷安去广告 | 酷安 | 见下节 |
| 作用域预置 | 三个 App | `res/values/arrays.xml` 的 `xposedscope`：`com.dragon.read` / `com.phoenix.read` / `com.coolapk.market` |

tab 的移除点选在配置落库那一层；但 `<clinit>` 里还写死了一份默认 tab 表（`m5.e` / `b5.e`），
服务端配置未下发时 `MainFragmentActivity` 读的就是这份，所以在 `q()` 之后还要清一次静态表。

构建时 `LOG_ENABLED=true ./build.sh` 可打开调试日志：除了 `XposedBridge.log`，还会用
`android.util.Log.e("viphook", …)` 输出到 logcat——LSPosed 2.x 不一定会把模块日志写进 logcat，
`adb logcat -s viphook` 是设备上最可靠的观察通道（需要 root 才能读全）。
默认 release 版 `LOG=false` 是编译期常量，不产生任何日志输出、也不写文件。

### 酷安：加固下的做法

酷安 16.7.4 的 `classes.dex` 是**网易易盾加固**的壳：dex 头里只有 30 个 `com.netease.nis.wrapper.*` 类，
真实业务代码在运行时才解密，静态分析拿不到任何方法体。但壳里保留了原 dex 的字符串池，
**类名是全的**（约 3758 个 `com.coolapk.market.*`），所以依然可以按类名做精确 hook：

| 目标 | 实现 |
|---|---|
| 开屏广告（独立页） | 挂 `Activity.onCreate` before，类名是 `com.coolapk.market.view.splash.SplashAdActivity` 时 `finish()` 并拉回 `MainActivity` |
| 开屏广告（MainActivity 内） | 酷安还有一套开屏是直接挂在 `MainActivity` 布局里的全屏容器 `com.coolapk.market:id/main_splash_ad`——不出现时是空的，出现时不定期。按资源 id 解析后强制 `GONE`，并挂 `View.setVisibility` 盯住它被重新置为可见 |
| 插屏广告 | 命中 `com.coolapk.market.view.ad.interstitial.InterstitialAdActivity`，只 `finish()`，保留它下面的页面 |
| 原生广告卡片（穿山甲 / TopOn） | 挂 `TextView.setText(CharSequence, BufferType)` 按角标文案兜底；同时给 `com.coolapk.market.view.ad.*` 下所有 View 的构造函数挂钩，容器一创建就隐藏所在列表 item |
| 推广卡片（「酷安专属优惠 / 去看看」） | 这类卡片没有任何「广告」字样，唯一稳定特征是按 `com.coolapk.market:id/action_view`（按资源 id 名匹配）出现的 CTA 文案，命中后隐藏所在 item |
| 列表 item 定位与留白 | `RecyclerView.Adapter#bindViewHolder`（final，所有子类必经）在绑定前给 item 根视图打 tag；隐藏时按 tag 命中，同时把高度压成 0（GridView / 部分列表只设 GONE 会留下整块空白），下次绑定前还原高度 |

信息流这块的坑，都是「时灵时不灵」的来源：

- 角标文案不一定是纯「广告」，还可能是「广告·京东」「推广」，早期版本的精确匹配会漏；
- 只挂单参 `setText` 会漏掉显式传 `BufferType` 的调用，现在挂两参（单参最终都走它）；
- 列表预取时 item 根视图还没挂到 RecyclerView 上，父链是断的，向上找 RecyclerView 会失败；
- 折叠只改 `visibility`，不改 `LayoutParams.height`：height 会跟着复用的 holder 流转，
  导致普通卡片变成空白行。

运行时侦察仍然保留（`LOG_ENABLED=true` 构建）：挂 `ClassLoader.loadClass` 记录加载到的广告相关类，
用于后续把信息流广告也换成数据层过滤。

要彻底定位信息流广告的数据来源，需要在已 root 的设备上 dump 解密后的 dex（如 frida-dexdump）后再分析。

## 限制与风险

- **仅客户端生效**。VIP 专享内容下发、播放鉴权、CDN 签名仍按真实账号权益，典型表现是「显示 VIP 但内容打不开 / 仍提示购买」。
- **整方法替换有副作用**。`returnConstant` 让原方法体完全不执行，其中的初始化、缓存写入一并被跳过，可能出现空指针或界面状态异常；闪退时优先怀疑返回 true 的状态查询。
- **版本脆弱**。混淆类最先失效；升级 App 后需重新核对方法签名。
- **合规风险**。修改会员/广告逻辑通常违反目标 App 用户协议，账号可能被风控或封禁。仅供个人学习研究，请勿分发或商用。
