# 跳过广告 · Android 版 v1.0

一个开屏广告自动跳过工具。只在**你打开某个应用的最初几秒**读取当前界面，
寻找写着「跳过 / 跳过广告 / Skip」之类的按钮并自动点掉，之后立即停止。

**不联网、不上传、不读取输入框、不修改任何系统设置。**

---

## 一、安装

1. 把 `跳过广告-v1.0.apk` 传到手机（数据线 / 微信文件传输 / QQ / LocalSend 都可以）。
2. 在手机上点击该 APK 安装。首次会提示「不允许安装未知应用」，
   按提示在弹出页面里允许**文件管理 / 浏览器 / 传输工具**安装即可。
3. 桌面会出现蓝色图标「跳过广告」。

> 安装时若提示「该应用未经检测」或「存在风险」，这是所有非应用商店 APK 的通用提示，
> 选择「继续安装」即可。

## 二、开启（关键一步）

1. 打开「跳过广告」。
2. 点页面上的 **打开无障碍设置**。
3. 在系统列表里找到 **已下载的服务 → 跳过广告**，点进去，打开开关。
4. 系统会弹出「要允许跳过广告执行以下操作吗」，点 **允许**。
5. 回到应用，顶部应显示绿色的 **● 已启用**。

### 列表里找不到「跳过广告」怎么办

这是最常见的问题，按下面顺序排查（应用里已经放了对应按钮）：

**① 「已下载的服务」要展开。**
绝大多数手机的无障碍页面里，本应用不在最外层列表，而是折叠在
**「已下载的服务」** 或 **「已安装的服务」** 这一项里面，需要再点进去一层。
一直往下翻，通常在列表最底部。

**② 直接点应用里的快捷入口。**
首页状态卡片上有两个描边按钮：

- **找不到「跳过广告」？点这里直达开关** —— 直接跳到本服务自己的开关页，不用在大列表里翻。
- **开关是灰的？点这里解除受限设置** —— 见第 ③ 条。

**③ Android 13 及以上：解除「受限设置」。**
用数据线/浏览器等方式**侧载安装**的应用，Android 13 起默认被标记为「受限设置」，
无障碍开关会是**灰色不可点**，并提示「受限制的设置，出于安全考虑，此设置当前不可用」。

解除方法（任一即可）：

- 点应用里的 **开关是灰的？点这里解除受限设置**，进入应用信息页，
  点**右上角三点菜单** → **允许受限设置**；
- 或者：设置 → 应用 → 跳过广告 → 右上角菜单 → 允许受限设置；
- 或者：用 adb 执行
  ```
  adb shell pm set-app-links --package com.lbx.skipad 0 all
  adb shell appops set --uid com.lbx.skipad ACCESS_RESTRICTED_SETTINGS allow
  ```

解除后再回无障碍页面，开关就能点开了。

**④ 部分国产系统会「隐藏」非白名单无障碍服务。**
个别 ROM（尤其开了「纯净模式」「安全模式」的机器）只显示系统认证过的无障碍应用。
此时需要在 **设置 → 安全 → 更多安全设置** 里关闭纯净模式，
或暂时关闭手机管家类的拦截功能，重新打开一次本应用。

**⑤ 确认确实装上了。**
桌面应该有蓝色图标「跳过广告」，点开能正常看到界面。
如果桌面没有图标，说明 APK 没装成功，重新安装一次。

### 各品牌补充设置（防止服务被系统杀掉）

| 品牌 | 需要做的事 |
|---|---|
| 小米 / Redmi | 设置 → 应用设置 → 跳过广告 → 省电策略 → **无限制**；并在最近任务里给本应用**加锁** |
| 华为 / 荣耀 | 手机管家 → 应用启动管理 → 跳过广告 → 关闭「自动管理」，三项全部手动允许 |
| OPPO / 一加 / realme | 设置 → 电池 → 应用耗电管理 → 跳过广告 → 允许**后台运行** |
| vivo / iQOO | 设置 → 电池 → 后台高耗电 → 允许跳过广告 |
| 三星 | 设置 → 电池 → 后台使用限制 → 把跳过广告移出「休眠应用」 |

## 三、使用

开启后不需要任何操作。正常打开其他 App，开屏广告出现时会被自动点掉，
并伴随一次轻微震动和一行「已跳过广告」提示。

### 界面上的开关

- **启用跳过功能** —— 总开关，关掉后服务完全不工作。
- **跳过时震动** —— 每次成功跳过震一下。
- **跳过时提示** —— 屏幕下方显示一行提示。
- **监听所有应用** —— 默认开启。关掉后只监听 `编辑规则` 里单独配置过的应用。
- **查看日志** —— 最近 60 次跳过记录（时间 / 应用 / 命中原因）。
- **恢复默认规则** / **清空日志**。

## 四、自定义规则

点 **编辑规则** 会打开一个 JSON 编辑器，改完点保存。格式如下：

```json
{
  "global": {
    "watchAll": true,
    "scanIntervalMs": 120,
    "maxTextLen": 8,
    "maxWaitMs": 15000,
    "retryLimit": 5,
    "continueAfterSkip": false
  },
  "skipTexts":   ["跳过", "跳过广告", "Skip"],
  "strongTexts": ["跳过", "关闭广告"],
  "denyTexts":   ["跳过此步骤", "跳过引导", "跳过教程", "取消"],
  "skipIds":     ["*:*skip*", "*:*close_ad*"],
  "excludeApps": ["com.android.settings", "com.miui.home"],
  "apps": {
    "com.example.app": {
      "name": "某应用",
      "enabled": true,
      "extraTexts": ["关闭广告"],
      "extraIds": ["btn_close_ad"]
    }
  }
}
```

### 字段说明

| 字段 | 作用 |
|---|---|
| `watchAll` | 是否监听所有应用。`false` 时只监听 `apps` 里列出的、且 `enabled` 为 true 的应用 |
| `scanIntervalMs` | 扫描间隔（毫秒），范围 40–2000。越小响应越快、越耗电，默认 120 |
| `maxTextLen` | 关键词包含匹配的长度上限，范围 2–20。**调大更容易命中，但也更容易误伤**，默认 8 |
| `maxWaitMs` | 观察窗口时长（毫秒），范围 2000–120000。超过就停止扫描，默认 15000 |
| `retryLimit` | 一次启动内最多尝试点击几次，范围 1–50，默认 5 |
| `continueAfterSkip` | 跳过一次后是否继续找下一个跳过后，默认 `false` |
| `skipTexts` | **精确匹配**：控件文字去掉空白和标点后与此完全相同才命中 |
| `strongTexts` | **包含匹配**：文字压缩后长度 ≤ `maxTextLen` 且包含此关键词即命中 |
| `denyTexts` | 黑名单。完全相等，或黑名单词 ≥3 字且被包含时，**一律不点** |
| `skipIds` | 控件 ID 规则，见下 |
| `excludeApps` | 永不监听的应用（桌面、设置、安装器、系统 UI 等） |
| `apps` | 针对单个应用的覆写：`enabled` 是否监听、`extraTexts`/`extraIds` 追加规则 |

### 控件 ID 的三种写法

Android 的控件 ID 长这样：`com.tencent.mm:id/skip_btn`，冒号后面是 id 名。

| 写法 | 含义 | 例子 |
|---|---|---|
| `*:xxx` | 只比较 `/` 后面的 id 名，支持 `*` 通配（**推荐**） | `*:*skip*` 命中 `skip`、`skip_btn`、`btn_skip`、`splash_skip_view` |
| 含 `/` | 与完整 ID 通配匹配 | `com.tencent.mm:id/skip*` |
| 其它 | 与 id 名通配匹配 | `skip_btn` 只命中名字正好是 `skip_btn` 的 |

通配符 `*` 表示任意长度字符（含空）。

### 某个 App 跳不掉怎么办

1. 先开 **查看日志**：如果压根没有记录，说明没找到按钮 —— 进第 2 步；
   如果有记录但广告还在，说明点错了位置 —— 见第 4 步。
2. 把 `maxTextLen` 临时调到 **10~12**，多半就能覆盖。
3. 仍然不行，就把该 App 的跳过按钮文字加到 `skipTexts`（精确匹配最安全）。
   例如按钮是「跳过 3」，就加 `"跳过 3"`。
4. 如果是误点（点进了广告），把误点的文字加进 `denyTexts`。

### 误伤了正常按钮怎么办

按优先级依次尝试：

1. 把误点文字加入 `denyTexts`；
2. 把 `maxTextLen` 调小（比如调到 4~5），只保留「跳过」这类核心词；
3. 把该应用加进 `excludeApps`；
4. 关掉 `watchAll`，只在 `apps` 里逐个添加你确实想跳广告的应用。

## 五、工作原理

```
应用切到前台
      ↓
判断是否在监听范围（不在 excludeApps、且 watchAll 或已单独配置）
      ↓  是
开启观察窗口（默认 15 秒），窗口内监听窗口内容变化
      ↓
遍历窗口控件树，逐个比对：
      · 控件 ID 命中 skipIds？
      · 文字去掉空白标点后与 skipTexts 完全相同？
      · 文字很短（≤ maxTextLen）且包含 strongTexts 关键词？
      · 命中 denyTexts 黑名单？→ 直接放弃
      · 是输入框 / WebView？→ 直接放弃
      ↓  命中
点击：先试控件自身的点击事件，再试它所在坐标的模拟点击，
      最后才向上找可点击的父控件（顺序刻意如此，避免点进广告）
      ↓
震动 + 提示 + 记一条日志，结束本次观察
      ↓  超时 15 秒仍没找到
静默退出，本次不再扫描
```

### 为什么这样设计

**只在启动窗口内工作。** 开屏广告只可能出现在应用启动的最初几秒。
过了这个窗口就完全停止扫描，所以不会在你正常刷微信、看视频时误点。

**长度限制（`maxTextLen`）。** 「点击跳过，查看更多精彩内容」这种长句里也含「跳过」，
但它显然不是跳过按钮。压缩后超过 8 个字就一律不做包含匹配，从源头掐掉大部分误伤。

**黑名单机制。** 「跳过此步骤」「跳过引导」「跳过教程」这类按钮点下去会真的跳过流程，
所以宁可漏点也不点。

**点击顺序。** 很多开屏页整个广告区域都是可点击的（点了就跳广告落地页），
而「跳过」文字只是它的子节点。如果一上来就找可点击的父控件，很可能点到广告本身，
所以优先用文字节点自己的坐标精准点击。

## 六、隐私

- 没有任何网络权限，**Manifest 里连 INTERNET 都没申请**，物理上不可能联网。
- 只读取无障碍服务当前所在窗口的控件树，不读取其他应用的数据。
- 跳过记录只存在手机本地，随时可清空。
- 唯一申请的权限是 `VIBRATE`（震动）。

可以在应用设置里查看：本 APK 申请的权限只有 1 项 —— `android.permission.VIBRATE`。

## 七、常见问题

**Q：装了没反应？**
A：九成是无障碍服务没开，或被杀后台了。回到应用看顶部是不是绿色「● 已启用」，
并对照第二节的品牌补充设置。

**Q：无障碍列表里根本找不到「跳过广告」？**
A：见第二节「列表里找不到「跳过广告」怎么办」。
最常见两种：一是它被折叠在 **「已下载的服务」** 里面；二是 Android 13+
对侧载应用默认「受限设置」，开关是灰的，需要去应用信息页右上角菜单里放行。
应用首页已经为这两种情况各放了一个快捷按钮。

**Q：显示已启用，但某些 App 还是跳不掉？**
A：说明这些 App 的跳过按钮文字或结构特殊。按第四节「某个 App 跳不掉怎么办」逐步排查，
把 `maxTextLen` 调大通常能解决大部分情况。

**Q：会不会把我微信聊天里的内容传出去？**
A：不会。没有网络权限，代码里也没有任何网络调用。

**Q：费电吗？**
A：只在应用启动后的 15 秒内工作，其余时间完全休眠，日常使用几乎无感。

**Q：能跳过应用内的弹窗广告、信息流广告吗？**
A：不能。本应用只处理**开屏广告**，这也是它安全的原因 ——
精准限定在启动窗口，代价是不处理其他类型的广告。

**Q：卸载后服务还开着吗？**
A：卸载会自动解除。若先手动关闭，去 设置 → 无障碍 → 已下载的服务，关掉「跳过广告」。

## 八、签名信息

本 APK 使用自签名证书，指纹如下（用于校验 APK 是否为原版，未被二次修改）：

```
证书主题  : CN=SkipAd, OU=Personal, O=LBX, L=CN, ST=CN, C=CN
SHA-256  : 75:34:24:6A:3B:BF:DF:90:87:25:75:67:3B:05:DF:F9:68:92:E5:22:3C:04:0E:C4:FD:65:19:D6:DD:B6:C2:93
SHA-1    : 55:C3:5C:95:B6:28:38:3B:CF:DC:3C:7B:2C:9E:E8:79:C0:95:AE:85
签名方案  : V2 + V3
```

`apksigner verify --verbose` 的结果：

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
```

> 说明：APK 里也带有 V1（JAR 签名）文件，但其 `META-INF/*.SF` 里写有
> `X-Android-APK-Signed: 2, 3`，表示「本包已用 V2/V3 签名，V1 可忽略」。
> 因为最低版本是 Android 7.0（API 24），V2 即可覆盖全部目标机型，V1 无需校验。
>
> 重新编译时密钥库会自动生成在 工程根目录的 `keystore/skipad.jks`（口令 `skipad123`）。
> **升级已安装的应用必须用同一个密钥库**，否则系统会拒绝覆盖安装。

## 九、源码与重新编译

本仓库就是一个标准的 Android 工程，可以直接用 Android Studio 打开；
也可以用附带的脚本编译，**不需要安装 Android Studio 或 Gradle**：

```powershell
pwsh -File build.ps1     # 编译出 APK
pwsh -File test.ps1      # 跑 114 项逻辑自测
```

构建流程：

```
aapt2 compile（编译资源）
   → aapt2 link（链接资源 + 生成 R.java）
   → javac --release 11（编译 Java）
   → d8（转 Dalvik 字节码）
   → aapt add（把 classes.dex 塞进 APK）
   → zipalign -p 4（对齐）
   → apksigner（签名）
```

> 为什么用 `aapt add` 而不是重写 zip：`resources.arsc` 必须以**未压缩 + 4 字节对齐**
> 的方式存放（targetSdk 30+ 的硬性要求），重写 zip 会破坏这个状态。
> `aapt add` 只追加新条目，不动原有条目。

### 路径含中文时的处理（已经自动搞定）

`aapt2` 在 Windows 上有个已知缺陷：**无法打开路径里含非 ASCII 字符的文件**。
把本工程放在 `D:\手机跳广告（安卓版）\源码` 这类含中文的路径下时，直接调用会报：

```
aapt2 compile --dir <带中文的路径>
error: failed to open directory: 系统找不到指定的文件。 (2).
```

`build.ps1` 已内置规避：**检测到工程路径含中文时，自动把构建过程搬到
`%TEMP%` 下的纯 ASCII 临时目录里执行，完成后再把 APK 和密钥库拷回工程目录。**
对你完全透明，产物位置不变。构建时你会看到一行提示：

```
检测到工程路径含中文：
  D:\手机跳广告（安卓版）\源码
aapt2 不支持非 ASCII 路径，本次构建将在临时目录中进行：
  C:\Users\...\Temp\skipad-build-xxxxxxxxxx
```

（其它工具 javac / d8 / zipalign / apksigner 都能正常处理中文路径，
只有 aapt2 有此问题。）

### 源码结构

```
.
  README.md                                本说明
  先看这里.txt                              给「只想装 APK」的人看的三分钟上手
  apk/跳过广告-v1.0.apk                     已签名的成品，可直接安装
  app/
    build.gradle                           模块配置（无第三方依赖）
    src/main/AndroidManifest.xml
    src/main/java/com/lbx/skipad/
        MainActivity.java                  主界面：开关、规则编辑、日志
        SkipAccessibilityService.java      无障碍服务：观察窗口、事件调度、点击与记录
        SkipEngine.java                    核心算法：文字归一化、匹配、遍历控件树、点击
        RuleStore.java                     规则读写与 JSON 解析
    src/main/res/
        raw/default_rules.json             默认规则
        values/strings.xml, styles.xml
        xml/accessibility_service_config.xml
        mipmap-*/ic_launcher*.png          应用图标（5 种密度）
        drawable/, mipmap-anydpi-v26/
    src/test/java/com/lbx/skipad/EngineTest.java   114 项纯逻辑单元测试
  build.gradle / settings.gradle / gradle.properties   Android Studio 工程配置
  build.ps1                                免 Gradle 构建脚本（路径自动推导，可整个搬走，含中文路径）
  test.ps1                                 免设备单元测试脚本
  gen_icon.py                              图标生成（Pillow）
  get_json.ps1                             下载单测用的 org.json 实现
  tools/                                   随仓库附带的官方工具链（约 100 MB）
      jdk/                                 OpenJDK 17 最小运行时（jlink 裁剪，含 javac / keytool / java）
      build-tools/                         aapt2 / aapt / d8 / zipalign / apksigner
      platform/                            android.jar（API 34）
      lib/                                 单测用的 json-20231013.jar
```

### 关于附带的工具链

`tools/` 里的 JDK、build-tools、android.jar 都是从**官方源的国内镜像**下载的
（OpenJDK 来自华为云镜像，Android SDK 来自腾讯云镜像，均为原始未修改文件），
这样在没有 Android Studio、没有 Gradle 的环境里也能一键重编。

为了让仓库能放进 GitHub（单文件上限 100 MB），做了这些无损裁剪：

- **JDK**：用 `jlink` 生成只含编译所需模块的最小运行时（`java.base` + `jdk.compiler` 等），
  保留 `javac` / `keytool` / `java`，从 294 MB 压到 52 MB；
- **build-tools**：删掉只服务 NDK 与 RenderScript 的
  `libLLVM_android.dll` / `libclang_android.dll` / `llvm-rs-cc.exe` / `renderscript/`，
  从 137 MB 压到 29 MB；
- **platform**：只保留 `android.jar`，删掉 `data/` `skins/` `templates/`。

裁剪后仓库约 100 MB，`aapt2` / `d8` / `zipalign` / `apksigner` 功能完全不受影响
（`build.ps1` 与 `test.ps1` 均已在裁剪后的工具链上验证通过）。

**如果你只需要装 APK，不需要重新编译，可以把 `tools/` 整个删掉**，能省下约 100 MB。

如果你想要完整未裁剪的官方工具链（或换 JDK/SDK 版本），下载地址：

```
OpenJDK 17 : https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip
Android SDK: https://mirrors.cloud.tencent.com/AndroidSDK/
             ↳ build-tools_r34-windows.zip
             ↳ platform-34-ext7_r03.zip
             ↳ platform-tools_r37.0.1-win.zip
```

解压到 `tools/` 下对应位置即可，`build.ps1` 会自动识别目录名里的版本号。

### 关于签名密钥

**本仓库不含签名密钥库**（`keystore/` 已在 `.gitignore` 中排除），因为私钥不应公开。

这不影响编译：`build.ps1` 发现密钥库缺失时会自动生成一份新的，
只是新密钥的签名指纹与本仓库 `apk/` 里那份成品不同。
这两种包**不能互相覆盖安装**（Android 要求签名一致才能升级），需要先卸载再装。

`apk/跳过广告-v1.0.apk` 的签名指纹：

```
证书主题  : CN=SkipAd, OU=Personal, O=LBX, L=CN, ST=CN, C=CN
SHA-256  : 75:34:24:6A:3B:BF:DF:90:87:25:75:67:3B:05:DF:F9:68:92:E5:22:3C:04:0E:C4:FD:65:19:D6:DD:B6:C2:93
SHA-1    : 55:C3:5C:95:B6:28:38:3B:CF:DC:3C:7B:2C:9E:E8:79:C0:95:AE:85
```

### 自测覆盖

`test.ps1` 会在桌面 JVM 上运行 114 项断言，不需要手机、不需要模拟器：

- 规则 JSON 解析与边界钳制（`scanIntervalMs` 下限 40、`maxTextLen` 上限 20 等）
- 文本归一化（去空白、去标点、大小写、全角空格）
- 正向命中：13 种真实跳过按钮写法（「跳过5」「5s 跳过」「跳过|5」「Skip Ad」…）
- 反向拒绝：27 个常见正常按钮（「同意并继续」「一键登录」「以后再说」…）零误伤
- 黑名单拦截：「跳过此步骤」「跳过引导」「跳过学习」等
- 控件 ID 通配匹配（`*:*skip*`、全限定写法、`glob` 边界）
- 监听范围判定（桌面、设置、安装器不监听）
- 逐应用覆写、非法 JSON 容错、null 入参不崩

---

版本 1.0 · 最低支持 Android 7.0（API 24）· 目标 Android 14（API 34）· 体积 98 KB
