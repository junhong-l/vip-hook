# viphook — 番茄小说 / 红果短剧 VIP 解锁模块

一个约 16 KB 的 Xposed 模块，**同一份安装包同时作用于两个 App**：把会员与广告判断方法整体替换成固定返回值，本地表现为「已开通 VIP、全场景免广告、隐藏付费入口、付费书/短剧可读」。

| 项 | 值 |
|---|---|
| 当前产物 | `viphook.apk`（v2，md5 `0b90132130b94295c204b3baafe1aab7`） |
| 上一版 | `viphook_v1.apk`（仅番茄，smali 实现，md5 `466a044b5cdb3151665de56e41fc19b2`，留作回滚） |
| 包名 | `com.vip.hook`，versionCode 2 / versionName 1.1 |
| 签名 | `viphook.keystore`，alias `viphook`，口令 `viphook` |

> ⚠️ v2 换了签名密钥，**覆盖安装会失败**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），必须先卸载 v1 再装 v2。

---

## 1. 目标应用

| App | 包名 | 核对情况 |
|---|---|---|
| 番茄免费小说 | `com.dragon.read` | ✅ 已反编译本仓库提供的 7.4.5.32 包，逐方法核对 |
| 红果免费短剧 | `com.phoenix.read` | ⚠️ 未取得 apk，按「同源、代码基共用」假定类路径一致 |

两者内容侧同源：番茄 7.4.5.32 中 `com.dragon.read.component.biz.impl.privilege.PrivilegeManager` 的方法名与签名，和 v1 模块 hook 的 11 个点完全一致（含 `isBookAdFree(String)I` 这种返回 int 的细节），因此同一套 hook 逻辑对两者通用。

模块对两个包名都放行，并**先按 `com.dragon.read.*` 路径找类，找不到再试 `com.phoenix.read.*`**——若红果内部仍沿用 dragon 命名（同源代码库常见），第一条就会命中。

## 2. 模块声明

`AndroidManifest.xml`：

| 项 | 值 |
|---|---|
| package | `com.vip.hook` |
| label | 番茄/红果 VIP Hook |
| `xposeddescription` | 番茄小说 & 红果短剧：VIP、免广告、隐藏付费入口 |
| `xposedmodule` / `xposedminversion` | `true` / 54 |
| minSdk / targetSdk | 21 / 34 |

入口 `assets/xposed_init` → `com.vip.hook.HookMain`。

## 3. 执行流程

```
handleLoadPackage(lpparam)
 ├─ packageName ∈ {com.dragon.read, com.phoenix.read} ?  否 → return（无副作用）
 ├─ 解析目标类：findClass("com.dragon.read...PrivilegeManager")
 │               失败 → findClass("com.phoenix.read...PrivilegeManager")
 │               都失败 → log 提示后 return
 ├─ 16 个 PrivilegeManager 方法：findAndHookMethod + returnConstant
 ├─ 1 个混淆类判断：iv5.a#a(String)
 └─ XposedBridge.log("[viphook] <包名> 已加载，生效 hook 数=N")
```

设计要点：

- **整方法替换**：`XC_MethodReplacement.returnConstant(...)`，原方法体一行不执行。
- **逐条 try/catch**：某方法在目标版本不存在时只失效这一条，不影响其余，也不会让宿主崩溃。
- **可观测**：加载后写 LSPosed 日志并带上生效条数，装完能立刻判断模块是否真的挂上（v1 是静默的，这正是「不知道是不是作用域问题」的根源）。
- **按类名查找**：不做 dex 扫描，类名/方法签名一变即失配，因此升级 App 后要重新核对。

## 4. Hook 清单

### 4.1 `PrivilegeManager`（16 条）

| # | 方法 | 参数 | 强制返回 | 作用 | 新增 |
|---|---|---|---|---|---|
| 1 | `isVip` | — | `true` | 基础会员判断 | |
| 2 | `isAnyVip` | — | `true` | 任一会员类型判断 | |
| 3 | `isForeverNoAd` | — | `true` | 永久免广告标记 | |
| 4 | `hasNoAdPrivilege` | — | `true` | 免广告权益 | |
| 5 | `hasNoAdFollAllScene` | — | `true` | 全场景免广告 | |
| 6 | `hasNoAdForShortSeries` | — | `true` | 短剧免广告 | |
| 7 | `hasNoAdReadConsumptionPrivilege` | — | `true` | 阅读消耗场景免广告 | |
| 8 | `hasPrivilege` | `String` | `true` | 通用权益查询一律放行 | |
| 9 | `isNoAd` | `String` | `true` | 按场景 key 的免广告查询 | |
| 10 | `isBookAdFree` | `String` | `Integer 1` | 书籍广告免费状态码（**int，不是 boolean**） | |
| 11 | `showPayVipEntranceInChapterEnd` | — | `false` | 章节末付费入口不展示 | |
| 12 | `adVipAvailable` | — | `true` | 广告型 VIP 可用性 | ✅ |
| 13 | `hasReadPaidBookPrivilege` | — | `true` | 付费书可读 | ✅ |
| 14 | `hasVipShortSeriesPrivilege` | — | `true` | 会员短剧权益 | ✅ |
| 15 | `canReadShortStory` | — | `true` | 短篇可读 | ✅ |
| 16 | `checkCommentForbidden` | — | `false` | 解除评论限制 | ✅ |

### 4.2 混淆类（1 条，版本相关）

| 类#方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `iv5.a#a` | `String` | `true` | 广告/权益判断。**在番茄 7.4.5.32 中 `iv5.a.a` 是字段而非方法**，该方法不存在 → 本条静默失效。保留是为了兼容红果或旧版仍存在该方法的版本 |

效果归纳：任何「是不是 VIP / 有没有某项权益」的询问都得到肯定答复；各场景免广告开关全开；章节末尾开通入口隐藏；付费书与短剧按有权益放行。

## 5. 相比 v1 改了什么

| 方面 | v1 | v2 |
|---|---|---|
| 包名闸门 | 仅 `com.dragon.read` | `com.dragon.read` + `com.phoenix.read` |
| 类路径 | 写死番茄路径 | dragon → phoenix 依次探测 |
| hook 数 | 12 | 17（新增 5 个内容/广告权益点） |
| 实现 | 手写 smali | Java（`src/main/java` + 编译期 stub） |
| 日志 | 无 | `XposedBridge.log` 输出包名与生效条数 |
| label | 番茄VIP Hook | 番茄/红果 VIP Hook |
| 签名 | 原 key | 新 key `viphook.keystore` |

## 6. 构建

```bash
cd /root/workspace/fanqie
./build.sh                # 产物 viphook.apk
./build.sh /tmp/test.apk  # 指定输出
```

脚本做五件事：`javac`（Java 8 字节码）→ 生成仅编译期用的 stub jar → `d8` 汇编 dex（只含 `com.vip.hook.*`）→ `aapt2 link` + `zip` 打包 → `zipalign` + `apksigner`。

环境注意：

- 本机 `apktool 2.9.3` 配 JDK 25 会在 aapt2 阶段抛 `NullPointerException`，且 apktool.jar 内的 smali 汇编器没有 CLI 入口，**`apktool b` 不可用**；故改用 Java + `d8` 出 dex，比手写 smali 更稳、更好维护。
- 依赖：`/opt/android-sdk/build-tools/36.0.0`、`/opt/android-sdk/platforms/android-34/android.jar`、JDK。
- `src/stubs/java` 下的 Xposed API 只是编译期占位，**不会打进 dex**（已用 `dexdump` 确认 dex 内只有 `Lcom/vip/hook/HookMain;` 一个类）。

产物校验：

```bash
md5sum viphook.apk                      # 0b90132130b94295c204b3baafe1aab7
unzip -p viphook.apk classes.dex | md5sum   # bb15e4c3ab49b5ba0fc1b6fcd54dda47
```

## 7. 安装与验证

1. 卸载旧版：`adb uninstall com.vip.hook`（签名变了，不卸载装不上）。
2. 安装 `viphook.apk`。
3. LSPosed 中启用模块，**作用域同时勾选番茄小说与红果短剧**（两个都勾，模块自己按包名分流）。
4. 强制停止目标 App 再打开（模块在进程启动时加载，热重启无效）。
5. 看 LSPosed 日志：应出现 `[viphook] com.dragon.read 已加载，生效 hook 数=16`（红果同理，数字取决于该类里存在多少方法）。若显示「未找到 PrivilegeManager」，见第 8 节。

## 8. 红果不适配时怎么查

模块对红果是「按同源假定」写的，未实测。若日志报未找到类或生效条数为 0：

```bash
BT=/opt/android-sdk/build-tools/36.0.0
$BT/aapt2 dump badging hongguo.apk | head -1          # 确认包名
unzip -o -q hongguo.apk 'classes*.dex' -d hg && cd hg
for f in classes*.dex; do
  strings -a "$f" | grep -q "privilege/PrivilegeManager" && echo "$f"
done
# 定位到 dex 后列出该类方法签名
$BT/dexdump classesN.dex | awk -v RS="Class #" \
  '/Class descriptor  : .L.*privilege\/PrivilegeManager;/' | grep -E "name|type" | head -40
```

拿到真实类路径后，改 `src/main/java/com/vip/hook/HookMain.java` 里的 `PRIVILEGE_CLASSES` 数组，`./build.sh` 重打包即可（方法名一般与番茄一致）。

## 9. 已知限制与风险

- **仅本地生效**：VIP 状态与免广告都只是客户端判断被短路。VIP 专享内容下发、播放鉴权、CDN 签名仍按真实账号权益返回，可能出现「显示 VIP 但内容打不开」「仍提示购买」。
- **跳过原逻辑的副作用**：原方法体内的初始化/缓存写入一并被跳过，可能 NPE 或界面状态异常；闪退时优先怀疑返回 true 的状态查询（#1/#2/#13/#14）。
- **`returnConstant` 类型必须严格匹配**：`isBookAdFree` 必须返回 `Integer 1`；若目标版本改成 boolean，该条会静默失效。
- **版本脆弱**：类名、方法名、参数个数任一变化即失配；混淆类 `iv5.a` 最先失效。
- **首次加载有延迟**：模块在 `handleLoadPackage` 里同步挂 17 个 hook，冷启动首帧可能略慢（毫秒级）。
- **合规风险**：修改会员/广告逻辑通常违反目标 App 用户协议，账号可能被风控或封禁；仅供个人学习研究，勿分发或商用。

## 10. 目录

```
fanqie/
├── viphook.apk                  # v2 产物（双目标）
├── viphook_v1.apk               # v1 备份（仅番茄，可回滚）
├── viphook.keystore             # 签名密钥（口令 viphook，丢失后无法覆盖升级）
├── build.sh                     # 一键构建
├── AndroidManifest.xml          # 模块清单（label/描述/xposed 元数据）
├── assets/xposed_init           # 入口类声明
└── src/
    ├── main/java/com/vip/hook/HookMain.java   # 全部 hook 逻辑
    └── stubs/java/de/robv/android/xposed/…    # 编译期 API 占位，不入包
```
