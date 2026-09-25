# YukiHookAPI 1.5.0-beta.4 迁移 POC 勘察

> 分支：`Poc探索`
> 勘察对象：本地 `1.5.0-beta.4.zip`（Maven 仓库布局，Apache-2.0）
> 目的：核实「迁移到 YukiHookAPI + DexKit」的可行性与真实成本，取代基于 1.2.x/1.3.x 文档的推测。

## 0. 依赖包构成

zip 内为本地 Maven 仓库，5 个模块，全部 `1.5.0-beta.4`：

| 模块 | packaging | 作用 |
|------|-----------|------|
| `yukihook-bom` | pom | 版本对齐 |
| `yukihook-core` | aar | Hook DSL、生命周期、存储、寄生、Channel |
| `yukihook-compiler` | jar | **KSP** 处理器（`SymbolProcessorProvider` → `YukiHookProcessor`） |
| `yukihook-runtime-libxposed` | aar | **Modern API / libxposed 后端** |
| `yukihook-runtime-xposed82` | aar | 传统 Xposed 82 后端 |

> 1.5.0 已把后端拆成独立 runtime artifact，并同时提供 libxposed 与 xposed82 两套实现。
> 这是本次勘察中影响结论最大的事实。

## 1. 结论修正：不需要放弃 LibXposed Modern API

`yukihook-runtime-libxposed-1.5.0-beta.4.pom` 明确依赖：

```xml
<groupId>io.github.libxposed</groupId>
<artifactId>service</artifactId>
<version>102.0.0</version>
```

入口 `LibXposedEntry` 直接继承 `XposedModule`，并保留同名生命周期回调：

| 框架原生 | Yuki 1.5.0 libxposed 后端 |
|---------|--------------------------|
| `onModuleLoaded(ModuleLoadedParam)` | `onModuleLoaded` |
| `onPackageReady(PackageReadyParam)` | `onPackageReady` |
| `onPackageLoaded(PackageLoadedParam)` | `onPackageLoaded` |
| `onSystemServerStarting(...)` | `onSystemServerStarting` |

`getClassLoader()` / `applicationInfo` 经 `YukiHookModuleCaller.callOnPackageLoaded(...)` 透传为 `PackageParam`。

**含义**：先前「必须放弃 LibXposed 服务层」的判断作废。本项目可继续跑在 API 102 上，
`XposedService` / `RemotePreferences` 由 Yuki 接管：

- `LibXposedPreferences` — 宿主侧走 `api.getRemotePreferences(name)`（并校验 `PROP_CAP_REMOTE`），
  模块侧走 `LibXposedService.preferencesOrNull(name)`，失败回退模块私有 `SharedPreferences`
- `LibXposedService` / `YukiHookServiceBridge` — 封装 `XposedServiceHelper`
- `LibXposedSharedFiles` — 封装 `getRemoteFile`

## 2. Hook 调用点：与现状高度同构

项目当前写法（`MainModule` / 20 个 Hook 类 / 约 150 处）：

```java
module.hook(method).intercept(chain -> {
    Object r = chain.proceed();
    return r;
});
```

Yuki 1.5.0 在 `PackageParam` 上提供对应扩展：

```kotlin
fun Member.intercept(priority: YukiHookPriority = DEFAULT, invocation: HookChain.() -> Any? = { null })
fun Member.hook(priority: ..., hooker: YukiHookCreator.ClassicMemberHooker.() -> Unit)
fun Collection<Member>.hookAll(priority: ..., hooker: ...)
fun MemberResolver<*, *>.intercept(...)
```

**关键点**：`Member` 就是 `java.lang.reflect.Member`，`Method` / `Constructor` 均实现它。
本项目 `HeyboxTargets` 由 DexKit / 反射产出的正是 `Method`，
候选列表场景对应 `Collection<Member>.hookAll {}`。
→ **动态目标解析层可以原样保留，只换挂载调用点。**

`HookChain` 语义与现状对齐：

| 现状（libxposed Chain） | Yuki `HookChain` |
|------------------------|------------------|
| `proceed()` | `proceed()` |
| `proceed(args)` | `proceed(args)` |
| `proceedWith(instance)` | `proceedWith(instance)` |
| `getArgs()` | `args: List<Any?>` |
| `getThisObject()` | `instance` |

底层 `LibXposedHookApi.createNativeHooker` 对 `ChainHookerBridge` 直接桥接原生
`XposedInterface.Hooker`，即 chain 仍是 libxposed 原生链，不是本地模拟链。

另有 classic 风格可用：`HookParam` 提供 `result` / `hasThrowable` / `throwable` /
`callOriginal()` / `invokeOriginal(vararg)` / `Member.deoptimize()`（API 102 起）。

## 3. 真实成本：Kotlin 强制引入（此前未识别的最大项）

- `@YukiHookLibXposedEntry` 的 `@Target(AnnotationTarget.CLASS)`，入口类必须实现
  `YukiHookXposedModule`（Kotlin 接口）→ 入口只能是 Kotlin
- 入口文件由 **KSP** 生成，而 KSP 只处理 Kotlin 源集 → 纯 Java 工程无法使用入口生成
- `yukihook-core` 依赖 `kotlin-stdlib:2.4.10`（compile scope）

本项目是 **100% Java**，20+ Hook 类、约 150 处 intercept。
因此主导成本不是 Hook 语义改写，而是 **语言与构建体系引入**。

可选路径：

| 方案 | 说明 | 代价 |
|------|------|------|
| A. Kotlin 薄适配层 | 入口 + 少量 Kotlin shim，业务 Hook 保持 Java，由 shim 暴露 `intercept` 包装 | 最低侵入；Java 侧需把 `chain` 当 `Function1` 调用，可读性差 |
| B. Hook 点逐步 Kotlin 化 | 入口 Kotlin，新迁移模块用 Kotlin，旧模块保留 Java 分支 | 渐进；长期双语言 |
| C. 全量 Kotlin 化 | 整体重写 | 不建议，收益不足 |

> 无论哪种，都需要锁定 Kotlin 2.4.10 与 KSP 版本，并确认与 AGP 9.2.1 / Gradle 9.7.1 兼容。

## 4. 已识别回归项

**热重载丢失**。`YukiHookLibXposedEntry` 文档明示：

> Hot reload is not supported in 1.x. Support is deferred to 2.x.

而本项目当前：

- `module.prop` 设 `autoHotReload=true`
- `MainModule.onHotReloading()` 返回 `true`

**缓解**：注解提供 `javaEntries: Array<KClass<*>>`（额外原生 libxposed Java 入口，继承 `XposedModule`）。
可保留现有 `MainModule` 作为并行 javaEntry + 新增 Yuki 入口，
既保住热重载，也形成新旧双轨验证路径。这是当前最值得利用的能力。

## 5. 待验证风险

1. **依赖膨胀**：`yukihook-core` 以 `runtime` scope 带入
   `androidx.appcompat:1.7.1`、`preference-ktx:1.2.1`、`core-ktx:1.17.0`、
   `lifecycle-common:2.9.0`、`hiddenapibypass:6.1`、`kavaref-*`、`betterandroid ui/system-extension`。
   这些会打进模块 APK 并在宿主进程加载，与小黑盒自带 androidx 存在类冲突面。
2. **多 Hook 同方法的链序**：`priority` 仅排序本地 chain，不保证框架级顺序；
   本项目存在同一方法被多处挂载（`HeyboxTargets.PENDING`），需验证 Yuki + 非 Yuki 混挂表现。
3. **DexKit 协同**：`DexKitBridge` 与 Yuki 无耦合，可原样保留；
   但补挂时机（扫描异步完成后）需验证在 Yuki 生命周期内是否仍可安全挂载。
4. **缓存键**：应扩为 `包名 + versionCode + versionName + dex 校验 + 规则版本 + schema 版本`；
   扫描失败拒绝使用不匹配的陈旧结果。
5. **R8**：Yuki 的 R8 规则需并入 `proguard-rules.pro`；当前 release 已开 `minify + shrinkResources`。

## 6. 修订后的可行性判断

| 维度 | 先前判断 | 修订后 |
|------|---------|--------|
| 是否必须放弃 Modern API 102 | 是 | **否**，1.5.0 原生支持 libxposed 后端 |
| `XposedService` / RemotePreferences | 需自建通道 | **已封装**，可保留 |
| DexKit 目标解析 | 需重做 | **可原样保留** |
| 150 处 hook 语义 | 高风险重写 | **同构改写**，chain 语义一致 |
| 语言与构建 | 未识别 | **Kotlin 强制引入，成为主成本** |
| 热重载 | 未识别 | **1.x 丢失**，需用 `javaEntries` 缓解 |
| 综合难度 | 中高 | **中**（难点从「能力重建」转为「语言引入 + 构建对齐」） |

工作量重估（含 Kotlin 引入与构建对齐）：

- 仅做可行性 POC（入口 + 普通/UI/异步/DexKit 各一）：**约 1～2 人周**
- 迁移到全部 Hook 点可编译可运行：**约 3～5 人周**
- 叠加多宿主版本与多进程回归：**约 5～8 人周**

## 7. 建议的下一步（POC 验证清单）

1. 引入 Kotlin + KSP，仅接入 `yukihook-core` + `yukihook-runtime-libxposed`，产出可安装 APK
2. 校验 KSP 生成的 `java_init.list` / `module.prop` / `scope.list` 与现有手写文件等价
3. 验证 6 类样例：普通方法、构造方法、Activity 生命周期、异步回调、跨进程配置、DexKit 延迟补挂
4. 记录依赖膨胀对宿主进程的影响（类加载、启动耗时、方法数）
5. 验证 `javaEntries` 双轨方案能否保住 `autoHotReload`
6. 全程不升级 DexKit / AGP / targetSdk，避免混淆问题来源

---

本地依赖与解包产物位于 `.poc/`（已 gitignore）。
勘察依据为包内 AAR / sources jar 源码，非文档推测。
