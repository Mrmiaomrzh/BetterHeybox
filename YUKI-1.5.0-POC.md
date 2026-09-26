# YukiHookAPI 1.5.0-beta.4 迁移 POC 勘察

> 分支：`Poc探索`
> 勘察对象：本地 `1.5.0-beta.4.zip`（Maven 仓库布局，Apache-2.0）
> 依据：包内 **AAR 字节码 + sources jar 源码**，`javap` 反编译校验。
> **明确不以官网文档为准** —— 官网仍描述 1.2.x 的 `findClass().hook { injectMember{} }`，
> 与 1.5.0 实际 API（`PackageParam` / `Member.intercept` / `HookChain`）已经不一致。

---

## 0. 依赖包构成

| 模块 | packaging | 类数 | 体积 | 作用 |
|------|-----------|-----:|-----:|------|
| `yukihook-bom` | pom | — | — | 版本对齐 |
| `yukihook-core` | aar | 142 | 336 KB | Hook DSL、生命周期、存储、寄生、DataChannel |
| `yukihook-compiler` | jar | — | 44 KB | KSP 处理器（`SymbolProcessorProvider` → `YukiHookProcessor`） |
| `yukihook-runtime-libxposed` | aar | 29 | 53 KB | **Modern API / libxposed 后端** |
| `yukihook-runtime-xposed82` | aar | 24 | 49 KB | 传统 Xposed 82 后端 |

Yuki 自身仅 171 个类 / ~390 KB。**体积风险全部来自传递依赖**（见 §7）。

`yukihook-runtime-libxposed` 的 AAR manifest 声明 `minSdkVersion=26`，与本项目一致，无冲突。

---

## 1. 核心结论：1.5.0 保留 libxposed 后端，不必放弃 Modern API 102

`yukihook-runtime-libxposed-1.5.0-beta.4.pom` 依赖 `io.github.libxposed:service:102.0.0`。
入口 `LibXposedEntry : XposedModule()` 保留同名生命周期回调：

| 框架原生 | Yuki libxposed 后端 |
|---------|-------------------|
| `onModuleLoaded(ModuleLoadedParam)` | `onModuleLoaded` |
| `onPackageReady(PackageReadyParam)` | `onPackageReady` |
| `onPackageLoaded(PackageLoadedParam)` | `onPackageLoaded` |
| `onSystemServerStarting(...)` | `onSystemServerStarting` |

`XposedService` / `RemotePreferences` 亦已封装（`LibXposedService` / `LibXposedPreferences` /
`LibXposedSharedFiles` / `YukiHookServiceBridge`），**无需自建 IPC 通道**。

### 1.1 必须锁定 libxposed runtime，排除 xposed82

`yukihook-runtime-xposed82/.../YukiHookBridge.kt` 明确抛异常：

| 行号 | 能力 | xposed82 行为 |
|-----:|------|--------------|
| `:64` | `moduleScope` | `UnsupportedOperationException` |
| `:74-75` | `observeService` | `UnsupportedOperationException` |
| `:77-78` | `preferences` | 走**另一条** `Xposed82Preferences` 路径，非 libxposed remote prefs |
| `:80-81` | `sharedFiles` | `UnsupportedOperationException` |
| `:83-84` | `deoptimize` | `UnsupportedOperationException` |
| `:96-98` | 静态初始化器 Hook | `UnsupportedOperationException` |
| `:102-103` | `replace`（原子替换） | `UnsupportedOperationException` |

→ 构建期**只能引入 `yukihook-runtime-libxposed`**，并使用 `@YukiHookLibXposedEntry`。
本项目现状即 API 102，天然吻合。

---

## 2. Hook 调用点：chain 风格与现状同构

现状（约 150 处）：

```java
module.hook(method).intercept(chain -> {
    Object r = chain.proceed();
    return r;
});
```

Yuki 侧对应（`javap` 实测，`PackageParam` 上的 **public final 实例方法**）：

```java
public final MemberHooker$Result intercept(
    java.lang.reflect.Member, YukiHookPriority,
    kotlin.jvm.functions.Function1<? super HookChain, ? extends Object>);

public final MemberHooker$Result hookAllByMember(
    Collection<? extends Member>, YukiHookPriority,
    Function1<? super ClassicMemberHooker, Unit>);
```

`Member` 即 `java.lang.reflect.Member`，**DexKit / 反射产出的 `Method` 可直接挂载**，
候选列表场景对应 `hookAllByMember`。

`HookChain` 与现状语义对齐：

| 现状（libxposed Chain） | Yuki `HookChain` |
|------------------------|------------------|
| `proceed()` | `proceed()` |
| `proceed(args)` | `proceed(args)` |
| `proceedWith(instance)` | `proceedWith(instance)` |
| `getArgs()` | `args: List<Any?>` |
| `getThisObject()` | `instance`（继承自 `HookInvocation`，`HookInvocation.kt:92`） |

底层 `LibXposedHookApi.createNativeHooker` 对 `ChainHookerBridge` 直接桥接原生
`XposedInterface.Hooker`，chain 即 libxposed 原生链，非本地模拟。

---

## 3. 迁移规约（必须写死的约束）

### 3.1 一律使用 chain 风格，禁止 classic

```kotlin
// YukiHookCreator.kt:400-408  ChainMemberHooker
runCatching { callback(invocation) }.getOrElse {
    notifyCallbackFailure(invocation, it)
    // The remaining chain may already have executed, never retry it as failure recovery.
    throw it
}
```

- **chain**：回调异常 → 必然重抛给宿主，与 `ExceptionMode.PASSTHROUGH`（`LibXposedHookApi.kt:74`）一致
- **classic**：`intercept` 异常 → **回退调用未 hook 的原方法**（`YukiHookCreator.kt:567`），故障被静默吞掉

→ 本项目必须用 `Member.intercept {}`，**不要用 `Member.hook {}`**。选错不报错，只静默改变行为。

### 3.2 `Member.intercept` 默认值会废掉原方法

```kotlin
fun Member.intercept(priority: YukiHookPriority = DEFAULT,
                      invocation: HookChain.() -> Any? = { null })   // PackageParam.kt:948-951
```

默认 `{ null }` 在 chain 语义下是「**直接短路返回 null，不调用原方法**」。
`member.intercept { }` 空实现体 = 把宿主方法打成 stub。禁止省略 invocation。

### 3.3 运行时未就绪时注册是静默 no-op

```kotlin
internal fun hook() {
    if (!YukiHookBridge.isAvailable) return   // YukiHookCreator.kt:113
```

DexKit 异步补挂若抢在 runtime attach 前完成，hook **无声消失**，不进 `YukiHookResult`。
必须显式校验 `YukiHookBridge.isAvailable`，不能以"未抛异常"判定成功。

### 3.4 跨线程补挂必须用带 block 的形式

- `Member.hook(priority)`（无 block）→ `HookMode.IMMEDIATE`，**调用即注册**（`PackageParam.kt:1240`）
- `Member.hook(priority) { }`（有 block）→ `HookMode.LAZY`，`.apply(hooker).build()`（`PackageParam.kt:1249`）

延迟/跨线程场景一律用 LAZY 形式。

### 3.5 `PackageParam` 是成员扩展，作用域受限

`Member.intercept` / `Member.hook` / `Collection<Member>.hookAll` 全部声明在
`PackageParam` 类体内（`PackageParam.kt:67-1547`），全仓无其它重载点。

| 场景 | 可用性 | 处置 |
|------|--------|------|
| 独立工具类直接写 | ❌ 编译不过 | 持 `PackageParam` 字段 + `with(param){}`，或继承 `PackageParam`（`YukiBaseHooker` 即如此，`YukiBaseHooker.kt:40`） |
| `loadApp{}` 内定义的嵌套 lambda（含 `onCreate{}`、`Thread{}`、DexKit 回调） | ✅ | 隐式 receiver 是**词法栈**，实例被闭包捕获 |
| `loadApp{}` 之外定义再传入的 lambda | ❌ | 无 receiver |

**DexKit 延迟补挂成立**：`loadApp(name){ block(this) }` 传的是同一个 `this`；
`PackageParam` 每入口新建且框架不再改写（`XposedModuleRuntime.kt:152`）。

注意 `PackageParam.currentClassLoader` 与 `wrapper` 的读写**无同步**（`PackageParam.kt:70,118-122`），
跨线程改这些属性需自行加锁。

### 3.6 `priority` 只有 3 档，同级顺序不可控

`YukiHookPriority` = `DEFAULT(50)` / `LOWEST(-10000)` / `HIGHEST(10000)`
（`YukiHookHelper.kt:40-42`）。core 层只保证**相对顺序**，
同优先级仲裁逻辑**不在本包源码内**（`FrameworkHookBridge.kt:123` 仅透传 Int）。

本项目存在同一方法被多处挂载（`HeyboxTargets.PENDING` + `installGroup`），
**多 Hook 相对顺序是不可控变量**，必须进 POC 验证。

### 3.7 其他

- `loadApp(excludeSelf = false, ...)` **默认不排除模块自身**（`PackageParam.kt:308,320,332`）；
  空包名匹配所有宿主。写 `loadApp("com.max.xiaoheihe")` 是精确相等匹配，安全。
- `AppLifecycle` **只有 Application 回调，无任何 Activity 生命周期 API**。
  `onCreate` 经 `Instrumentation.callApplicationOnCreate` 的 after 分发（`AppParasitism.kt:345-365`）；
  **无 `onResume`** → 本项目 6 处 `onResume` hook 必须维持显式方法挂载。
- `registerAppLifecycle` **仅在 `isFirstApplication == true` 时注册**（`PackageParam.kt:246-252`），
  即只在主进程首入口生效，子进程走这条路无效。
- **无 `unhook`**，但有公开的 `MemberHooker.removeSelf()`(`:103`)、`Result.remove()`(`:270`)、
  回调内 `HookInvocation.removeSelf()`(`HookInvocation.kt:126`)。
- `IYukiHookXposedInit.encase` 是**空实现**（`YukiHookFactory.kt:196,204`），不要用。
- 包内 **零 DexKit 引用**（全树 grep `dexkit|luckypray|DexKitBridge` 无匹配）。
  唯一成员查找能力是 KavaRef 反射封装，**DexKit 继续由模块侧自持**。

---

## 4. 配置与跨进程

### 4.1 `PackageParam.preferences(name)` = remote preferences（只读）

宿主进程链路：
`PackageParam.kt:194-203`（`from()` 不传 Context）→ `YukiHookPreferences.kt:97-102`
→ `YukiHookBridge.kt:85`（`context.takeUnless { hostEnvironment }` → null）
→ `LibXposedPreferences.kt:34` → `Host.open` → `api.getRemotePreferences(name)`（`:58-64`，校验 `PROP_CAP_REMOTE`）

**时序有利**：`XposedInterface` 在 `onModuleLoaded` 即 attach
（`LibXposedEntry.kt:47-48` → `LibXposedHookApi.kt:55`），早于 `onPackageReady`。
→ **宿主侧 remote preferences 在 `loadApp{}` 内即可用，不依赖 service 绑定。冷启动读配置无问题。**

### 4.2 ⚠️ 模块侧写入存在静默降级

```kotlin
// LibXposedPreferences.kt:42-43
LibXposedService.preferencesOrNull(name)
    ?: context.getSharedPreferences(name, Context.MODE_PRIVATE)
```

`preferencesOrNull` 在 service 未 bind 时返回 `null`（`LibXposedService.kt:104-106`），
降级**不抛错不告警**。因两侧存储名一致（`${modulePackageName}_preferences`），
通常"碰巧"落到同一文件——但这是**隐式约定的降级路径，不是显式保证**。

本项目当前的 `PreferenceReceiver` 已有「等待 service 绑定 6 秒 + 待提交缓存 + commit 兜底」
（`PreferenceReceiver.java:51-83`），**这套机制必须保留**，不能换成 Yuki 的默认路径。

就绪观测：`YukiHook.Status.observeFrameworkService { available -> }`（`YukiHook.kt:158-160`），
回调注册时立即触发一次当前可用性，之后 bind/died 再触发；**回调可能运行在 binder 线程**。

### 4.3 宿主进程内只读

`isWritable = !isHostEnvironment || isUsingNativeStorage`（`YukiHookPreferences.kt:128`）；
宿主侧 `commit()` 返回 false、`apply()` 空实现（`:316-317`）。宿主要写自己的私有 SP 需先 `native()`。

### 4.4 DataChannel 能力有限

`YukiHookDataChannel` 是**系统广播**通道（`YukiHookDataChannel.kt:58-66`），非 Binder、非订阅模型：

- 回调常驻，能感知变更；但**变更必须显式 `put`**，无值监听
- ⚠️ **宿主 receiver 在 `Application.onCreate` 之后才注册**（`AppParasitism.kt:393-396`），
  冷启动早期 `put` **静默丢失**，源码中**无排队/重发/重试**（`:706-715` fire-and-forget）
- 要求模块与宿主进程都活着；单包上限默认 500KB（`:99`）

→ 正确用法是 **preferences 存值 + dataChannel 推脏标记**；**不能**用 dataChannel 承担配置存储。

---

## 5. UI 寄生能力

| 需求 | 支持度 | 依据 |
|------|--------|------|
| 宿主内拉起模块 Activity | ✅ 现成 | `YukiHookFactory.kt:161` + `AppParasitism.kt:463-632` + `ModuleActivity.kt:73-123` |
| 资源注入 | ✅ 显式调用 | `ModuleResources.kt:77-87`；**逐 Context 调用**，API 30+ **必须主线程**（`YukiHookFactory.kt:121-125`） |
| 主题包装 | ✅ | `ModuleContextThemeWrapper.kt:44-96`，宿主环境自动注入资源（`:73`） |
| **内嵌到宿主既有页面**（菜单/Fragment/Toolbar 注入） | ❌ **需自研** | 全库 `onCreateOptionsMenu` / `onCreateView` / `onActivityResult` **零匹配** |

本项目 `SettingsEntryHook` 是**内嵌**注入小黑盒页面，
→ Yuki 只提供 lifecycle / Context / 资源 / ClassLoader 四项基础设施，**注入逻辑必须自研**（即维持现状）。

---

## 6. 已识别回归项

### 6.1 热重载：净损失，且 `javaEntries` 无法补救

KSP 生成 `module.prop` 时 `autoHotReload=false` 是**硬编码**：

```kotlin
// YukiHookXposedGenerator.kt:371-374
minApiVersion=$minApiVersion
targetApiVersion=$targetApiVersion
staticScope=$staticScope
autoHotReload=false        // 写死
```

`@YukiHookLibXposedEntry` 参数仅 `entryClassName / minApiVersion / targetApiVersion /
scope / staticScope / javaEntries / nativeEntries`——**无 `autoHotReload` 参数**。
该字段是**模块级**的，故保留 `MainModule` 作 `javaEntries` 也不会让框架回调 `onHotReloading`
（`javaEntries` 校验见 `YukiHookXposedGenerator.kt:203-234`：只取类名写入 `java_init.list`，
要求直接继承 `io.github.libxposed.api.XposedModule` 且不继承 Yuki 入口基类——本项目
`MainModule.java:46` 正好满足）。**`javaEntries` 保住的是类本身，不是热重载开关。**

本项目现状 `autoHotReload=true` + `onHotReloading()` 返回 true。
走 KSP 即失去；详见 §8.1 的路线选择。

### 6.2 R8 规则需自备

`yukihook-core.aar/proguard.txt` **为空**，AAR 不携带 consumer 规则。
本项目 release 已开 `isMinifyEnabled` + `isShrinkResources` → 必须自行补 keep 规则。

### 6.3 日志可替代，检查点不可

- `YLog` 双通道（logd + Xposed hooker log）、内存快照、落文件、跨进程汇聚（`obtainLoggerInMemoryData`）
- ❌ `inMemoryData` **无容量上限**（`YLog.kt:389` 仅 `add`），长时间运行无限增长
- ❌ 宿主/模块进程**日志隔离**（`YLog.kt:180-181`），需显式拉取
- ❌ `Config.recording` **默认 false**（`YLog.kt:128`）
- ❌ **无任何结构化检查点 API** → 本项目 `Checkpoint.java` 必须自建

---

## 7. 依赖膨胀（最高优先风险）

`yukihook-core` 以 **`runtime` scope** 带入：
`androidx.appcompat:1.7.1`、`androidx.preference:preference-ktx:1.2.1`、
`androidx.core:core-ktx:1.17.0`、`androidx.lifecycle:lifecycle-common:2.9.0`、
`org.lsposed.hiddenapibypass:hiddenapibypass:6.1`、
`kavaref-core` / `kavaref-android` / `kavaref-extension`、
`betterandroid:ui-extension` / `betterandroid:system-extension`、
`io.github.libxposed:service:102.0.0`、`androidx.annotation:1.10.0`

这些会**打进模块 APK 并在宿主进程加载**。Yuki 自身仅 171 类，膨胀全部来自此列表，
与小黑盒自带 androidx 存在类冲突面。**这是迁移前必须最先验证的一条。**

---

## 8. 语言策略：Kotlin 只需入口，业务可留 Java

KSP 仅处理 Kotlin 源集（`META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
→ `YukiHookProcessor`），故**入口类必须是 Kotlin**。
但 `javap` 实测 `PackageParam.intercept(...)` / `hookAllByMember(...)` 是 **public final 实例方法**，
Java 侧持有 `PackageParam` 引用即可直接调用。

| 方案 | 说明 | 代价 |
|------|------|------|
| **A. 单 Kotlin 入口 + Java 业务（推荐）** | 入口 1 个 .kt；Java 侧持 `PackageParam` 句柄，必要时套一层还原 `Chain` 形态的适配器 | 侵入最小，150 处调用点近乎零改动 |
| B. Hook 点逐步 Kotlin 化 | 新模块 Kotlin，旧模块 Java | 渐进，长期双语言 |
| C. 全量 Kotlin 化 | 整体重写 | 不建议 |

> 相比初版评估「Kotlin 强制引入成为主成本」，经 `javap` 校验后修正为：
> **Kotlin 成本仅限入口与构建对齐，不扩散到业务代码。**

---

## 8.1 入口与构建：二选一，无中间态

KSP 的 `checkSourceEntryFiles`（`YukiHookXposedGenerator.kt:499-530`）会扫描源码目录，
发现已存在 `resources/META-INF/xposed/{java_init.list,module.prop,scope.list,native_init.list}`
或 `assets/xposed_init` 即 `problem()` **终止构建**（`:525-529`）。

本项目这三个文件**现在就在** `app/src/main/resources/META-INF/xposed/`。
→ **KSP 生成与手写元数据互斥**，必须二选一：

| 路线 | 入口方式 | `module.prop` | 热重载 | Kotlin 需求 |
|------|---------|---------------|--------|-----------|
| **A. 走 KSP** | Kotlin 入口类 + `@YukiHookLibXposedEntry`，KSP 自动生成元数据 | `autoHotReload=false` 写死 | ❌ 失去 | 入口必须 Kotlin |
| **B. 不走 KSP** | Java 继承 `LibXposedEntry`，或保留现有 `MainModule` 手写元数据 | 继续手写，可留 `true` | ✅ 保留 | 仅入口胶水需 Kotlin |

**路线 B 可行性已核实**：

- `LibXposedEntry` 是 `public abstract class : XposedModule()`（`LibXposedEntry.kt:35-42`），
  生命周期方法全为 `final override`（`:47/60/64/77`），唯一抽象成员
  `protected abstract fun createHookEntry(): YukiHookXposedModule` —— **Java 可直接继承并 override**。
- core 对 KSP 生成类是**软引用**，缺类不崩：
  `YukiHook.kt:166` `runCatching { YukiHook_Impl.compiledTimestamp }`、
  `ModuleApplication.kt:76` `runCatching { ModuleApplication_Impl.callHookEntryInit() }`。
- 打包侧已就绪：`app/build.gradle.kts:59-62` 已有
  `packaging.resources.merges += "META-INF/xposed/*"`；
  `app/proguard-rules.pro:3` 已有 `-adaptresourcefilecontents META-INF/xposed/java_init.list`。
- 注意 `checkSourceEntryFiles` 显式排除 `build` / `.gradle`（`:500-504`），
  故自定义任务把元数据写进 `build/` 再并入 resources 不会被判违规。

**Gradle 接入要素（走 KSP 时）**：

```kotlin
plugins { alias(libs.plugins.android.application); kotlin("android"); alias(libs.plugins.ksp) }
dependencies {
    ksp("com.highcapable.yukihookapi:yukihook-compiler:1.5.0-beta.4")
    implementation("com.highcapable.yukihookapi:yukihook-core:1.5.0-beta.4")
    implementation("com.highcapable.yukihookapi:yukihook-runtime-libxposed:1.5.0-beta.4") // 与 xposed82 互斥
}
repositories { maven { url = uri("<abs>/.poc/m2-yuki") } } // 标准 Maven 布局，可本地消费
```

- JVM 17（项目已是）；Kotlin 对齐版本 2.4.10；**KSP 插件版本需自行选定并验证**（包内未固定）
- `yukihook-compiler` 无 Gradle plugin marker，仅 `META-INF/services` 注册 → 必须走 `ksp(...)` 配置
- 可选 `yukihook-bom` 统一版本

**KSP 生成的类**（勿与 `YukiHookProperties` 混淆——后者是 Yuki 自身 Gropify 生成的构建常量，与 KSP 无关）：

| 类 | 职责 |
|---|---|
| `YukiHook_Impl` | 构建期桥接：`compiledTimestamp` / `legacyModuleStatusClassName`（`:377-397`） |
| `YukiHookCompiledTimestamp` | `System.currentTimeMillis()`（`:399-408`） |
| `ModuleApplication_Impl` | `callHookEntryInit()`（`:410-421`） |
| `<包名>.<initClassName>` | 继承 `LibXposedEntry`，override `createHookEntry()`，标 `@Keep`（`:457-481`） |

入口名规则：`<入口类包名>.<entryClassName 或 "<类名>_YukiHookXposedInit">`，
永远是 `java_init.list` **第 1 行**，其后按声明顺序追加 `javaEntries` 类名（`:350,354`）。
`targetApiVersion` 注解**无默认值、必填**；校验
`minApiVersion >= 101 && targetApiVersion >= minApiVersion`（`:247-252`）。

`yukihook-runtime-libxposed.aar` manifest 仅声明 `minSdkVersion=26`（与项目一致），
**不含** `xposedmodule` / `xposedminversion` meta-data —— 与项目现有的
「模块声明全部由 `META-INF/xposed/` 提供」做法一致。

---

## 9. 修订后的可行性判断

| 维度 | 初版判断 | 修订后（源码核实） |
|------|---------|------------------|
| 放弃 Modern API 102 | 是 | **否**，1.5.0 原生 libxposed 后端 |
| `XposedService` / RemotePreferences | 需自建 | **已封装**，宿主侧冷启动即可用 |
| DexKit 目标解析 | 需重做 | **完全正交，零引用** |
| 150 处 hook 语义 | 高风险重写 | **同构改写**，chain 语义一致 |
| 跨线程 DexKit 补挂 | 存疑 | **成立**（词法作用域 + 实例捕获） |
| 语言成本 | 未识别 | **仅入口需 Kotlin**（javap 实证） |
| xposed82 兼容 | 未识别 | **必须排除**，多项能力抛异常 |
| 热重载 | 用 javaEntries 缓解 | **无法补救**，KSP 写死 false；须在 §8.1 两条路线间选择 |
| KSP 与手写元数据 | 未识别 | **互斥**，共存直接终止构建 |
| Activity 生命周期 | 未识别 | **框架不提供**，维持显式 hook |
| 内嵌 UI 注入 | 未识别 | **框架不提供**，维持自研 |
| 综合难度 | 中高 | **中**（难点在规约与验证，不在能力重建） |

工作量重估：

- 可行性 POC（入口 + 普通/UI/异步/DexKit 各一）：**1～2 人周**
- 全部 Hook 点可编译可运行：**3～5 人周**
- 叠加多宿主版本与多进程回归：**5～8 人周**

---

## 10. POC 验证清单（按优先级）

1. **依赖膨胀实测**：最小 APK 装入宿主，量类加载、启动耗时、方法数、类冲突。
   这条若不通过，后面全部无意义，故排第一。
2. **先定入口路线**（§8.1）：走 KSP（弃热重载）还是手写元数据（保热重载）。
   该决策影响后续所有构建配置。
3. 引入 Kotlin + KSP，仅接 `yukihook-core` + `yukihook-runtime-libxposed`（**排除 xposed82**）
4. 校验 KSP 生成的 `java_init.list` / `module.prop` / `scope.list` 与现有手写文件等价
5. Java 侧持 `PackageParam` 直接调 `intercept` 的最小样例（验证 §8 结论）
6. 六类样例：普通方法、构造方法、Activity 生命周期、异步回调、跨进程配置、DexKit 延迟补挂
7. **同优先级多 Hook 顺序**实测（§3.6 未决项）
8. 验证 `PreferenceReceiver` 的 service 等待机制在 Yuki 下是否仍必要（§4.2）
9. 自备 R8 规则（`proguard.txt` 为空，§6.2）
10. 全程不升级 DexKit / AGP / targetSdk

---

## 11. 骨架验证结果（:yuki-probe，已实测通过）

独立模块 `:yuki-probe`，与 `:app` 隔离，复用同一 Gradle 9.7.1 / AGP 9.2.1 工具链。

### 11.1 工具链：AGP 内置 Kotlin 版本不够，必须换外部插件

AGP 9.2.1 内置 Kotlin 为 **2.2.0**，无法读取 Yuki AAR 的 metadata：

```text
Class 'com.highcapable.yukihookapi.hook.param.PackageParam' was compiled with an
incompatible version of Kotlin. The actual metadata version is 2.4.0,
but the compiler version 2.2.0 can read versions up to 2.3.0.
```

查证 AGP 9.2.1 的 `BooleanOption` / `StringOption`，**没有内置 Kotlin 版本覆写点**
（`android.builtInKotlin` 是布尔开关，非版本）。可行解只有一条：

```properties
# gradle.properties
android.builtInKotlin=false
android.newDsl=false        # 外部 Kotlin 插件与 AGP 9 新 DSL 不兼容，必须同时退回
```

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)   // 2.4.10
    alias(libs.plugins.ksp)               // 2.3.10
}
```

> **KSP 版本可与 Kotlin 解耦**：KSP gradle 插件只依赖 `symbol-processing-api` 与
> `symbol-processing-common-deps`，**不含 kotlin-compiler-embeddable**（已核实 2.3.10 / 2.3.12 的 POM），
> 它是挂进现有 Kotlin 编译器的编译器插件。故 **KSP 2.3.10 + Kotlin 2.4.10 可行，无需自建 KSP**。

注意：`android.newDsl=false` 是全局属性，会同时作用于 `:app`；两项均将于 AGP 10 移除。

### 11.2 KSP 生成产物与手写文件对照

`kspDebugKotlin` 正常执行，生成 4 个 Kotlin 源 + 3 个资源文件。

| 字段 | `:app` 手写 | KSP 生成 | 差异 |
|---|---|---|---|
| minApiVersion | 101 | 101 | 一致 |
| targetApiVersion | 102 | 102 | 一致 |
| staticScope | true | true | 一致 |
| **autoHotReload** | **true** | **false** | **唯一差异** |

生成物：

- `java_init.list` → `com.better.heybox.yukiprobe.ProbeEntry_YukiHookXposedInit`
- `scope.list` → `com.max.xiaoheihe`
- 源文件：`ProbeEntry_YukiHookXposedInit.kt`、`YukiHook_Impl.kt`、
  `YukiHookCompiledTimestamp.kt`、`ModuleApplication_Impl.kt`

### 11.3 生成入口类：热重载的补丁点已具体化

```kotlin
@Keep
public class ProbeEntry_YukiHookXposedInit : LibXposedEntry() {
    override fun createHookEntry(): YukiHookXposedModule = ProbeEntry
}
```

**没有 `onHotReloading` 覆写**，且 `LibXposedEntry` 未声明该方法（非 final），
故补丁是两行：

```kotlin
override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam) = true
```

加上 `module.prop` 的 `autoHotReload=true`，共 **2 处**。两处产物都在
`build/generated/ksp/` 下，可由 Gradle 任务后处理，**无需 fork**。

### 11.4 全局 DSL 降级对 :app 的影响（clean A/B 实测）

`android.newDsl=false` / `android.builtInKotlin=false` 是全局属性，会同时作用于 `:app`。
两侧均 **clean 构建**后对比，排除增量产物污染：

| 配置 | classes | methods | APK |
|---|---:|---:|---:|
| `newDsl=true`（AGP 内置 Kotlin 2.2.0） | 5392 | 42537 | 4499.7 KB |
| `newDsl=false` + 外部 Kotlin 2.4.10 | 5320 | 42019 | 4403.7 KB |
| 差值 | **−72** | **−518** | **−96 KB** |

结论：

- `:app:assembleDebug` **不受影响**，`:app:assembleDebug` 与根 `assembleDebug`（含 `:yuki-probe`）均通过
- 产物元数据**逐字节一致**：
  `java_init.list=com.better.heybox.MainModule`、`autoHotReload=true`、`scope.list=com.max.xiaoheihe`
- 唯一差异 −72 class / −96 KB，来源是 Kotlin 编译器/stdlib 解析不同，
  `:app` 无 Kotlin 源码，量级可接受

> 教训：混用 DSL 模式做增量构建会残留旧产物（曾观测到 6782 KB 的假差异），
> 任何 A/B 对照都必须先 `clean`。

### 11.5 依赖膨胀实测（以真实 APK 为准）

| APK | classes | methods | dex 未压缩 | native | 条目数 |
|---|---:|---:|---:|---:|---:|
| yuki-probe | 7378 | 59174 | 11390 KB | 0 | 432 |
| app | 5366 | 42283 | 7400 KB | 1445 KB | 95 |
| **增量** | **+2012** | **+16891** | **+4.0 MB** | — | — |

> 早前按依赖闭包估算的 +3956 class 偏高：两端共享 libxposed 类，
> 且探针不含 dexkit / liquidglass。真实净增 **+2012 class / +16891 method**。

APK 文件体积只差 +171 KB，是 ZIP 压缩造成的错觉；**dex 实际增长 4.0 MB（+54%）**。

---

## 12. 真机验证（MuMu 15 / Android 15 / LSPosed IT 2.1.1）

环境：MuMu Player 15（Android 15 / SDK 35 / x86_64）、KernelSU + ZygiskSU、
LSPosed IT v2.1.1（W5MMT 分支，API 102）、宿主 **小黑盒 1.3.396 (versionCode 1134)**。

### 12.1 Yuki 骨架端到端跑通

```text
D/YukiHook     Welcome to YukiHook 1.5.0-beta.4! Running on LSPosed API 102
I/YukiProbe    probe: hooked protected void android.app.Activity.onResume()
D/YukiHook     Executing hooker [intercept] (1) for ...Activity.onResume()
I/YukiProbe    probe: onResume on com.max.xiaoheihe.MainActivity
```

- `Member.intercept` chain 风格挂载成功
- 拦截器实际触发，`instanceOrNull` 正确解析到宿主 `MainActivity`
- **子进程同样加载**（`com.max.xiaoheihe:pushservice`，PID 3870）

### 12.2 类冲突：未发现（关键结论）

§11.5 的 +2012 class 全部经模块类加载器进入宿主进程。全量扫描
`NoSuchMethodError` / `NoClassDefFoundError` / `ClassNotFoundException` /
`VerifyError` / `LinkageError` / `Duplicate class`，**唯一命中项与 Yuki 无关**：

```text
W System.err: java.lang.NoClassDefFoundError: com.bun.miitmdid.core.MdidSdkHelper
```

MIIT OAID 设备标识 SDK，模拟器上普遍缺失，经 `System.err` 告警、非致命，宿主继续运行。
**未出现任何指向 androidx / hiddenapibypass / coroutines / betterandroid / kavaref
的加载或链接错误。**

### 12.3 与现有模块共存

同进程（PID 4502）内两个模块同时加载成功：

| 模块 | 框架 | 结果 |
|---|---|---|
| `com.better.heybox` v0.8.4 | LibXposed API 102 | 全部 Hook 正常安装（版本检测 / 更新屏蔽 / 广告过滤 / onActivityResult…） |
| `com.better.heybox.yukiprobe` | YukiHook 1.5.0-beta.4 | `Welcome to YukiHook 1.5.0-beta.4!` |

宿主进程存活、无崩溃。**Yuki 依赖膨胀未与宿主或既有模块产生冲突。**

### 12.4 遗留观察

- 探针仅验证 chain 拦截与类加载；`AppLifecycle`、DexKit 延迟补挂、
  跨进程 preferences、同优先级多 Hook 顺序等仍需完整迁移后验证。
- 模拟器为 x86_64，宿主 `primaryCpuAbi=arm64-v8a`，**真机 ARM 环境需复测**。

---

## 13. 热重载补丁（已验证可行，现已放弃）

### 13.1 方案

两处补丁都落在 `build/generated/ksp/` 产物上，
而 `checkSourceEntryFiles`（`YukiHookXposedGenerator.kt:499-530`）只扫描源码目录
且显式排除 `build/`，因此**无需 fork YukiHook**：

| 补丁 | 目标产物 | 内容 |
|---|---|---|
| 1 | `META-INF/xposed/module.prop` | `autoHotReload=false` → `true` |
| 2 | `<pkg>.<Entry>_YukiHookXposedInit.kt` | 追加 `onHotReloading` override 返回 `true` |

### 13.2 任务接线（顺序是硬要求）

```text
kspDebugKotlin -> patchHotReload -> compileDebugKotlin
```

踩过三个坑，均已修正：

1. **仅用 `finalizedBy` 不可靠**——它只保证补丁在 KSP 之后执行，
   下游 `compileDebugKotlin` 可能在补丁前完成编译；构建仍成功，只是热重载静默失效。
2. **在 `configureEach` 内再 `provider.configure {}` 非法**——
   `DefaultTaskContainer#NamedDomainObjectProvider.configure ... cannot be executed
   in the current context`。接线改到 `gradle.projectsEvaluated`。
3. **谓词误匹配测试任务形成循环依赖**——
   `kspDebugUnitTestKotlin` 依赖 `bundleDebugClassesToCompileJar`，
   而该 jar 依赖主编译任务。需排除名字含 `Test` 的任务。

### 13.3 已验证

- clean 构建后任务顺序正确：`kspDebugKotlin` → `patchHotReload` → `compileDebugKotlin`
- debug / release 两个变体均被打补丁
- 编译产物校验（`javap`）：

```text
public final class ProbeEntry_YukiHookXposedInit extends LibXposedEntry {
  protected YukiHookXposedModule createHookEntry();
  public boolean onHotReloading(XposedModuleInterface$HotReloadingParam);   // ← 补丁生效
}
```

- APK 内 `module.prop` 为 `autoHotReload=true`
- 补丁版装机后模块正常加载，与既有 `com.better.heybox` 共存，无崩溃/链接错误
### 13.4 已放弃热重载，改用「模块更新时提示并重启」

补丁本身验证可行（编译产物含 `onHotReloading`、APK 携带 `autoHotReload=true`、装机正常），
但**决定放弃**，原因三条：

1. 补丁依赖 `build/generated/ksp/` 目录结构与任务顺序，
   `kspDebugKotlin → patchHotReload → compileDebugKotlin` 一旦被打乱，
   补丁会被静默丢弃而**构建仍报成功**（§13.2 坑 1）
2. 深层风险未闭环：旧 hook 由框架按 `HotReloadedParam.getOldHookHandles()` 摘除，
   而 Yuki 把 handle 包在私有 `RegistrationHandle` 里从不外抛，重载后是否正确清理存疑
3. 实际触发走 `ILSPManagerService` binder，只能由 LSPosed Manager UI 发起，无法脚本化验证

已回退到 KSP 默认 `autoHotReload=false`，`yuki-probe` 构建脚本中仅保留决策注释。

---

## 14. 替代方案：模块更新时提示并重启宿主

复用项目**已有**的基础设施，无需新增机制。

### 14.1 现有可复用件

| 位置 | 能力 |
|---|---|
| `MainModule.activateIfNotDowngraded`（`MainModule.java:118-173`） | 读 `KEY_MODULE_VERSION_FLOOR`，`own > floor` 时抬升 floor —— **这正是「模块刚更新」的信号** |
| `GeneralHook.notifyDowngraded(Object app)`（`GeneralHook.java:113-...`） | 注册 `ActivityLifecycleCallbacks`，首次 `onResume` 弹提示；已用宿主 toast 工具，失败回退系统 Toast |
| `SettingsEntryHook.showRestartAppDialog(Activity, ClassLoader)`（`:3354-3372`） | 复用宿主 `AccelWorldWebkitKt.x` 重启弹窗，失败回退系统 AlertDialog |
| `AndroidManifest` `KILL_BACKGROUND_PROCESSES` | 免 root 杀宿主后台进程 |

`HeyboxPrefs` 存在**宿主目录**，跨模块更新与宿主重启持久，因此 floor 天然可作版本哨兵。

### 14.2 接入点

```text
activateIfNotDowngraded
  └─ own > floor  →  moduleUpdated = true
       └─ installHooks(...) 传入该标志
            └─ GeneralHook.notifyModuleUpdated(app)   // 复用 notifyDowngraded 的回调模式
                 └─ 首次 onResume → showRestartAppDialog(activity, cl, "模块已更新，请重启小黑盒")
```

`showRestartAppDialog` 当前消息硬编码为「底栏改动需重启小黑盒后生效」，
需改为接收 message 参数（底栏调用方传入原字符串即可，行为不变）。

### 14.3 语义澄清（重要）

「模块更新后新代码才运行」意味着：**新代码能提示时，它本身已经生效了**。
因此该提示的价值不是唤醒旧代码，而是：

- 显式确认更新已加载
- 提供一键重启，保证所有 hook 在干净的进程里重新安装，
  消除「旧 hook 残留 + 新 hook 叠加」的混合状态

这一点在 LSPosed 某些分支会热替换模块作用域时会变成刚需——
混合状态会导致 hook 重复安装，而重复安装不会报错。

### 14.4 状态

**已实现**（§15）。`activateIfNotDowngraded` 在 `own > floor` 时置 `moduleUpdated`，
安装完成后调用 `GeneralHook.notifyModuleUpdated(app)`，复用与 `notifyDowngraded`
相同的 `registerActivityLifecycleCallbacks` 首次 `onResume` 提示模式
（两者已抽为共用的 `notifyOnce`）。

---

## 15. :app 正式迁移（已完成并装机验证）

### 15.1 改造策略：桥接层保住 150 处调用点

核心决策是**不把业务 Hook 改成 Kotlin**，而是用一层 Kotlin 桥接让 Java 调用点几乎零改动。

| 新增（Kotlin） | 作用 |
|---|---|
| `yuki/HookEntry.kt` | `@YukiHookLibXposedEntry` 入口，`loadApp` 回调里 `MainModule.attach(this)` |
| `yuki/YukiChain.kt` | `YukiChain`（5 个方法）、`YukiChainView`、`YukiChainFunction` |
| `yuki/YukiHookBridge.kt` | `hook(Member)` / `frameworkLog` / `remotePreferences` |

`MainModule` 保留**全部 public 方法**（20 个 Hook 类与约 150 处
`module.hook(m).intercept(chain -> ...)` 逐字未动），只做四类改动：

1. 不再 `extends XposedModule`，改由静态 `attach(PackageParam)` 接入
2. 补回继承来的 API：`getRemotePreferences` / `getModuleApplicationInfo` / `log`
3. 生命周期回调（`onModuleLoaded` / `onPackageReady` / `onHotReloading`）移出入口
4. 10 处声明类型 `XposedInterface.Chain` → `YukiChain`

### 15.2 三个非显然的坑

**1. Java lambda 无法抛受检异常。**
Yuki 的 chain 回调类型是 `kotlin.jvm.functions.Function1`，其 `invoke` **未声明**
`throws Throwable`（已 javap 确认），而项目大量 handler 声明 `throws Throwable`。
解法是 Kotlin `fun interface YukiChainFunction` + `@Throws(Throwable::class)`——
JVM 签名因此带上 `throws Throwable`，Java lambda 才能编译。已验证产物：

```text
public interface com.better.heybox.yuki.YukiChainFunction {
  public abstract java.lang.Object apply(com.better.heybox.yuki.YukiChain) throws java.lang.Throwable;
}
```

**2. `remotePreferences` 必须反射取。**
Yuki 的 `YukiHookPreferences` 既未实现 `SharedPreferences`，也不暴露
`registerOnSharedPreferenceChangeListener`，而 `watchSettingsChanges()` 依赖该能力。
其 `internal val current` 编译为 public 的 `getCurrent$yukihook_core()`，
桥接层反射取回以保留既有行为。**该方法名绑定 Yuki 模块名，上游改名会静默退化**
（届时退化为需重启宿主才生效，属可接受降级）。

**3. 成员扩展需要词法接收者。**
`intercept` 声明在 `PackageParam` 类体内，跨文件用 `param.intercept(...)` 解析失败，
必须 `with(param) { member.intercept(...) }`；且回调是**接收者** lambda，
写 `{ chain -> }` 会报类型不匹配，须用 `this`。

### 15.3 装机验证（MuMu 15 / Android 15 / LSPosed IT 2.1.1 / 小黑盒 1.3.396）

```text
D/YukiHook       Welcome to YukiHook 1.5.0-beta.4! Running on LSPosed API 102
I/BetterHeybox   >>> 命中小黑盒，安装 Hook
D/YukiHook       Executing hooker [intercept] (1) for public void android.app.Application.onCreate()
I/BetterHeybox   目标解析完成: game.rec.list.bind=候选 ... feeds.list.bind.6=候选
I/BetterHeybox   ✔ Heybox 版本检测/更新屏蔽/伪装通知权限/开屏广告/信息流广告/气泡广告/角标广告
I/BetterHeybox   ✔ onActivityResult / 设置页入口 / 液态玻璃 实现启动提示 Hook 已安装
D/BetterHeybox   跳过安装（开关关闭）: 底部导航
```

- 目标解析（DexKit + 候选名）全部命中
- 开关门控生效（底部导航按开关跳过）
- 业务过滤实时工作：关键词 / 视频帖 / 等级阈值 / 点赞阈值 + 视图层探针
- **主进程与 pushservice 双进程均正常**
- 异常扫描 `NoSuchMethodError` / `ClassNotFoundException` / `VerifyError` /
  `LinkageError` / `FATAL EXCEPTION`：**无**

### 15.4 release（R8）验证

`yukihook-core.aar` 的 `proguard.txt` 为空，consumer 规则需自备。已补：

- `-keep class com.better.heybox.** { *; }`（含 KSP 生成的入口类）
- `-keep` 三个由 Yuki 反射定位的类：`generated.YukiHookProperties`、
  `YukiHook_Impl`、`ModuleApplication_Impl`
- `-adaptresourcefilecontents META-INF/xposed/java_init.list` 保持入口类名

release 构建通过，R8 后 `java_init.list` 仍为
`com.better.heybox.yuki.HookEntry_YukiHookXposedInit`，元数据完好。
release APK 1989 KB / debug APK 9168.8 KB。

> 迁移前 debug APK 为 4403.7 KB，增量即 Yuki 依赖膨胀（§11.5）。

### 15.5 遗留

- `android.newDsl=false` / `android.builtInKotlin=false` 为全局属性，AGP 10 将移除
- 真机 arm64 环境未复测（模拟器为 x86_64，宿主 `primaryCpuAbi=arm64-v8a`）
- 同优先级多 Hook 顺序、`AppLifecycle`、DexKit 延迟补挂仍未专项验证

### 15.6 Release 体积精确 A/B

在 worktree 中 checkout 迁移前提交 `9a0d3a5`，以**相同工具链**构建 release 后对比
（基线产出 unsigned APK，差异仅为签名块）：

| | 迁移前 | 迁移后 | 增量 |
|---|---:|---:|---:|
| APK 总体 | 1876.6 KB | 1989.0 KB | **+112.4 KB（+6.0%）** |
| dex | 798.2 KB | 925.8 KB | +127.6 KB |
| native | 1445.3 KB | 1445.3 KB | ±0 |
| res+其他 | 18.5 KB | 61.1 KB | +42.6 KB |
| dex 类数 | 500 | 732 | +232 |
| dex 方法数 | 5324 | 6282 | +958 |

**修正 §11.5 的判断。** 该节「+2012 class / +4.0 MB dex」基于依赖闭包与
**debug（无 R8）**构建。release 开 R8 后，Yuki 传递引入的
appcompat / preference / emoji2 / recyclerview 等绝大部分被裁掉——
Yuki 实际只用到其中很小一部分。**发布产物只涨 112 KB（+6%），native 部分完全没变。**

---

## 16. Java → Kotlin 迁移勘察

### 16.1 规模

| 项 | 数量 |
|---|---:|
| Java 文件 | 66 个 |
| 总行数 | 33,986 行 |
| 平均 | 515 行/文件 |
| `hooks/` 业务 Hook 类 | 27 个 / 18,575 行 |
| 其他（基础设施、工具） | 39 个 / 15,411 行 |

行数最大的文件：
`SettingsEntryHook` 4003、`LiquidGlassInstaller` 2675、`VideoDownloadManager` 1906、
`GameLibraryCleanHook` 1756、`DailyTaskHook` 1736、`CommentFilterHook` 1390、
`PostFilterHook` 1367、`CustomTextSelection` 1267。

### 16.2 与框架的耦合分布（决定性数据）

对 `MainModule` 全部 public 方法做调用点统计：

| API | 调用点 | 占比 | 转 Kotlin 能否改善 |
|---|---:|---:|---|
| `module.logd` / `logv` | **503** | 65% | 否——日志门面，调用点一字不改 |
| `module.isEnabled` / `getString` / `dp` | 169 | 22% | 否——配置读取，语言无关 |
| `module.hook(...)` → `.intercept` | **94** | 12% | **是——唯一受益点** |
| 其他（`onSettingChanged` 等） | 13 | 2% | 否 |

另有 `YukiChain` 类型声明 21 处。

**结论：只有约 12% 的框架耦合能真正受益。**
`logd` 是全项目最大的耦合点（503 次），而它恰恰是语言无关的。

> 更正 §15.1 中「约 150 处」的说法：实测 `.intercept(` 调用点为 **96 处**，
> `module.hook` 为 94 处。

### 16.3 其他关键密度

| 指标 | 数量 | 说明 |
|---|---:|---|
| `Class.forName` | 154 | 宿主私有 API 解析 |
| `getDeclaredMethod/Field` | 135 | 同上 |
| `Method.invoke` | 122 | 同上 |
| 宿主类名硬编码（`com.max.*`） | 156 | 混淆/改名即失效 |
| null 检查 | **1428** | Kotlin 空安全的最大受益点 |
| `synchronized` | 99 | 改协程风险高 |
| 线程 / Handler / Executor | 50 | 同上 |

反射调用合计约 **411 处**。这是 Kotlin **帮不上忙**甚至更啰嗦的部分——
Kotlin 的 `::class` / 属性引用在跨进程反射场景下需要 `KClass` ↔ `Class` 转换，
并引入 `kotlin.reflect` 依赖（`minSdk 26` 下需额外体积）。

反射最密集：`SettingsEntryHook`(49)、`DailyTaskHook`(44)、`ImageShareHook`(33)、
`GameLibraryCleanHook`(30)、`PostFilterHook`(29)。

### 16.4 有利条件：抽象边界已经干净

`HeyboxTargets`（803 行）是所有 Hook 的共同依赖，负责目标解析：
声明式 `Target` 定义（key / classes / methods / anchors / 参数区间 / validator）
+ DexKit 解析 + 反射兜底 + 持久化缓存 + 单线程异步解析 + 挂起队列。

**它完全不依赖 libxposed 或 Yuki**，只产出 `java.lang.reflect.Method` 交给消费者。
这意味着：

- 该层可以永久保持 Java，零成本
- Kotlin 迁移可以渐进进行，且限制在「挂载层」

这与 §15.1 的桥接层设计一致——抽象边界已经切好了。

---

本地依赖与解包产物位于 `.poc/`（已 gitignore）。
所有结论均来自包内源码与字节码，**未使用官网文档**。
