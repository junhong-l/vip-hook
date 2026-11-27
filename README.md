# imespoof — Teams / Outlook 输入法放行（LSPosed）

> **这个分支只放这一个模块。** 番茄小说 / 红果短剧的 VIP 解锁模块在 [`main`](https://github.com/junhong-l/vip-hook/tree/main) 分支，
> 两者目标 App、原理、代码都完全无关，只是恰好放在同一个仓库里。
>
> - `main` → viphook：番茄小说 `com.dragon.read` / 红果短剧 `com.phoenix.read`
> - `microsoft` → imespoof：Teams `com.microsoft.teams` / Outlook `com.microsoft.office.outlook`

一个约 17 KB 的 LSPosed 模块，绕过 **Microsoft Teams** 与 **Outlook**（Android）里由 Intune App SDK 执行的
**Approved keyboards（批准的键盘）** 限制，让你可以继续用自己装的任何输入法（例如豆包输入法），
不再被要求「只能使用 Google / Microsoft / Samsung 键盘」。

| 项 | 值 |
|---|---|
| 包名 | `com.teams.imespoof` |
| 作用目标 | `com.microsoft.teams`、`com.microsoft.office.outlook` |
| 产物 | `imespoof-release.apk`（v1.0，无日志） |
| 运行环境 | 已 Root + LSPosed（Xposed API ≥ 82） |

## 背景：这个限制是怎么来的

公司在 Intune 里给 Teams / Outlook 下发了 App Protection Policy（APP/MAM），其中
**Data protection → Approved keyboards** 一条设置为「要求」，并给了一份允许的键盘包名列表。
这两个 App 内嵌了 Intune App SDK，启动后在**自己的进程内**读取当前输入法并和白名单比对：

- 不一致 → 阻止输入，弹出「请安装批准的键盘」；
- 一致 → 正常使用。

因为判断发生在客户端进程里，所以它是一个可 hook 的对象——模块不需要改 APK，也不需要动 Company Portal。

## 原理

SDK 拿当前输入法只有几条系统 API 可走，全部在目标 App 进程内：

| SDK 的取法 | 模块的处理 |
|---|---|
| `Settings.Secure.getString(cr, "default_input_method")` | 返回 `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` |
| `Settings.Secure.getStringForUser(cr, "default_input_method", userId)` | 同上 |
| `Settings.Secure.getString(cr, "enabled_input_methods")` | 同上（单条组件名） |
| `InputMethodManager.getEnabledInputMethodList()` / `getInputMethodList()` | 只做观察记录，不改返回值 |

实测结论：这套 SDK 只做**字符串比对**，不校验 Gboard 是否真的安装，
所以机器上没装 Google 输入法也能通过（这一点是试出来的，不是文档写的）。

除主力的「伪装」外，模块还留了两层兜底，应对不同 SDK 版本换了实现：

| 层次 | 做法 |
|---|---|
| 策略兜底 | 把本机所有已启用的输入法追加进 `ApprovedKeyboards`，并把 `KeyboardsRestricted` 强制改 `false` |
| SDK 兜底 | hook `com.microsoft.intune.*` 里所有名字带 `keyboard` / `inputmethod` 的布尔方法，按语义强制通过（`isKeyboardApproved→true`、`isKeyboardRestricted→false`） |

## 目录结构

```
.
├── imespoof-release.apk                                  # 预构建产物（可直接安装）
├── build.sh                                              # javac → d8 → aapt2 → zipalign → apksigner
├── AndroidManifest.xml                                   # Xposed 模块声明 + xposedscope 作用域
├── assets/xposed_init                                    # 入口类 com.teams.imespoof.HookMain
├── res/values/arrays.xml                                 # xposedscope: teams + outlook
├── res/values/strings.xml                                # 模块名
└── src/
    ├── main/java/com/teams/imespoof/HookMain.java        # 全部 hook 逻辑
    └── stubs/java/de/robv/android/xposed/                # 编译期 API 占位（不进 dex）
```

`src/stubs` 里的 5 个类只是让 `javac` 能过，`build.sh` 喂给 `d8` 的只有 `com/teams/imespoof/*.class`，
运行期由 LSPosed 提供真实实现。

## 构建

依赖 JDK 11+、Android build-tools、platform（脚本会自动探测 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，
找不到时回退 `/opt/android-sdk`；也可用 `BT` / `AJ` / `JDK` 环境变量指定）。

```bash
./build.sh imespoof-release.apk                    # release：无日志 + 预置作用域
LOG_ENABLED=true ./build.sh imespoof-debug.apk     # debug：把诊断日志写到 Download/IMESpoof
SPOOF_AS="" ./build.sh imespoof-nospoof.apk        # 关掉伪装，只留策略兜底
SPOOF_AS="pkg/pkg.ImeService" ./build.sh out.apk   # 假装成别的输入法
```

`SPOOF_AS` 与 `LOG_ENABLED` 在构建时写进 `Config.java` 常量，release 版里 `LOG_ENABLED=false`
是编译期短路：不建文件、不打 logcat、不抓调用栈。

签名密钥（`*.keystore`）与 `build/` 在 `.gitignore` 里，不会入库。

## 安装

```bash
adb install -r imespoof-release.apk
```

1. LSPosed → 模块 → 启用 **IMESpoof**
2. 作用域：manifest 里通过 `xposedscope` 预置了 `com.microsoft.teams` 与 `com.microsoft.office.outlook`，
   首次启用会自动勾选；若是覆盖升级，进作用域页确认这两项即可
3. 强停两个 App 再打开：

```bash
adb shell am force-stop com.microsoft.teams
adb shell am force-stop com.microsoft.office.outlook
```

## 排查（debug 版）

`LOG_ENABLED=true` 构建的版本会把日志写到三处（能写就写），文件名带 App 与进程名：

```
/sdcard/Download/IMESpoof/imespoof-teams-com.microsoft.teams.log
/sdcard/Download/IMESpoof/imespoof-outlook-com.microsoft.office.outlook.log
/sdcard/Android/data/<包名>/files/IMESpoof/
/data/data/<包名>/files/IMESpoof/            # 需 root
```

```bash
adb pull /sdcard/Download/IMESpoof/
adb logcat -s LSPosed-Bridge | grep IMESpoof
```

关键行：

```
real default IME = com.larus.ime/.ImeService
READ default_input_method = com.larus.ime/...  ->  com.google.android.inputmethod.latin/...
POLICY KeyboardsRestricted: true -> false
SDK hit: com.microsoft.intune.mam.xxx#isKeyboardApproved : boolean
```

- 只有 `SDK hit` 没有 `READ`：检查走的是 `InputMethodManager`，看 `getEnabledInputMethodList ->` 那几行；
- 什么都没有：模块没注入（作用域没勾）或这项检查不在该进程内。

## 限制

- **只在客户端生效**。模块改变的是 App 自己的判断，不改变服务端与合规上报；MDM 侧依然能看到真实输入法。
- **与其它策略无关**。PIN、加密、剪贴板、截屏、根检测、Knox attestation 这些都不受影响，
  公司若启用了 root / 设备完整性检查，App 仍可能在启动阶段直接拒绝访问。
- **版本脆弱**。SDK 升级后如果改了取输入法的路径，需要按 debug 日志重新定位 hook 点。
- **合规风险**。绕过公司 App Protection Policy 通常违反企业设备与数据使用规定，仅供个人学习研究，
  建议只在自己的测试设备与测试租户上验证。
