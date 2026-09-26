# Java → Kotlin 迁移陷阱表

本文件由迁移过程**实测**积累，每条都造成过编译失败或静默行为改变。
转换任何文件前请逐条对照。

## 一、硬性纪律（违反即改变行为，且编译期无提示）

| # | 纪律 | 原因 |
|---|---|---|
| 1 | **绝不把 `intercept { }` 改成 `hook { }`** | classic 风格会吞异常并回退调用原方法；chain 风格把异常上抛宿主 |
| 2 | **绝不删除或改写 `catch (Throwable)`** | 这些块是本项目刻意的防御，删掉会改变失败行为 |
| 3 | **`LiquidGlassHookBridge` 的吞异常语义必须保留** | 它是全工程唯一与其余挂载点**相反**的地方（见下） |
| 4 | **反射目标必须保持 `java.lang.Class`（`Class<*>`）而非 `KClass`** | `KFunction` 不暴露 `isBridge()` / `isSynthetic()`，会误挂宿主方法 |
| 5 | **并发模型不得改写** | `synchronized` 范围、`volatile`、`Handler.post` 位置、`runGeneration` 递增时机都是刻意设计 |

### `LiquidGlassHookBridge` 为何相反

```kotlin
fun hookExecutable(executable: Executable, function: ChainFunction) {
    val m = module ?: return
    m.hook(executable).intercept { chain ->
        try { function.apply(chain) }
        catch (t: Throwable) { chain.proceed() }   // ← 吞异常 + 回退原方法
    }
}
```

玻璃特效跑在宿主**布局/测量/绘制**路径上。宿主升级改了布局类名就会失败；
若上抛，整个页面崩掉，而「玻璃不生效」才是可接受降级。

调用它的 lambda **必须允许抛异常**。Kotlin 里 `ChainFunction` 带 `@Throws(Throwable::class)`，
lambda 内直接写抛异常的代码即可——**不要**自己加 `catch (Throwable)`，那会二次吞异常。

## 二、编译器会报错的写法差异

| # | Java 写法 | Kotlin 必须写 |
|---|---|---|
| 6 | `s.length()` / `list.size()` | `s.length` / `list.size`（**属性**）。但 `JSONArray.length()`、`View.getWidth()`、`Layout.getLineCount()` 仍是方法 |
| 7 | `arr.set(i, x)` | `arr[i] = x` |
| 8 | `String.valueOf(x)` | Kotlin **无此静态方法** → `private fun stringify(v: Any?): String = if (v == null) "null" else v.toString()` |
| 9 | `new URL(x)` | `URL(x)` |
| 10 | `Long.parseLong(s)` | `s.toLong()` |
| 11 | `String.join(",", list)` | `list.joinToString(",")` |
| 12 | `Float.isNaN(x)` | `x.isNaN()` |
| 13 | `setTextSize(unit, 14)` | `setTextSize(unit, 14f)`（Int **不会**自动拓宽为 Float） |
| 14 | `list.sort(Comparator{...})` | `list.sortWith(compareByDescending { it.x })`（`sort` 已废弃） |
| 15 | `Comparator.comparingInt(...)` | `compareBy { it.field }` |
| 16 | `a ? b : c` | `if (a) b else c`（**Kotlin 没有三元运算符**） |
| 17 | `Regex(x).matcher(s)` | `Pattern.compile(x).matcher(s)`（`matcher` 是 `Matcher` 的 API） |
| 18 | `s.split("\\|")` | `s.split(Regex("\\|"))`（Kotlin 的 `split(vararg String)` 是**字面量**语义） |
| 19 | `0d` | `0.0`（`0d` 不是合法 Kotlin 字面量；`0f`/`0L` 合法） |
| 20 | `for (i=0; i<n && i<3; i++)` | `for (i in 0 until minOf(n, 3))` |
| 21 | `map.keySet()` | `map.keys`（`WeakHashMap` 映射为 `MutableMap` 后无 `keySet`） |
| 22 | `int + " 天"` | `"$x 天"` 模板（`Int + String` 在 Kotlin 有歧义） |
| 23 | 跨行 `a` `\n+ b` | **`+` 必须在行尾**；行首 `+` 会被读成**一元正号**，报 `unresolved reference 'unaryPlus'` |
| 24 | 类内 `private static final` 常量 | `const val` **只允许**顶层 / object / companion，放 class 内直接报错 |
| 25 | `0xFF1677FF` | 高位为 1 的十六进制字面量会被推断为 **Long** → `0xFF1677FF.toInt()` |
| 26 | `(Number) x).intValue()` | 刻意用 `java.lang.Number`：Kotlin 的 `Number.toInt()` 是 `toDouble().toInt()`，对超范围 Long **饱和**而非截断 |
| 27 | `import java.util.List` | **必须删掉**。显式导入会让 `List` 绑定 Java 类，`MutableList` 不再是其子类型，产生看似不可能的类型错误 |
| 28 | `import com.better.heyboxXxx` | 少一个点。报错只说 `Unresolved reference`，**本项目最常见的错误** |
| 29 | 泛型 SAM 接口 | `fun interface` **不支持泛型参数** → 保持普通 `interface` |
| 30 | Java 接口的 `throws` | Kotlin 无受检异常，但**仍是 Java 的调用方需要 `@Throws`**，否则 javac 报 `unreported exception` |

## 三、编译能过但**行为静默改变**（最危险）

| # | 陷阱 | 处理 |
|---|---|---|
| 31 | 字符串里的裸 `$`：`Class.forName("...Dialog$a")` | Kotlin 会当成**未定义变量的模板引用**。必须转义 `Dialog\$a` |
| 32 | Java 的 `==` 在 Kotlin 对对象变成 `equals` | 身份比较写 `===`（如 `v === startHandle`）。**但 enum 比较用 `==` 是对的** |
| 33 | Java 的 `(Foo) null` 返回 null，Kotlin `null as Foo` 抛异常 | 原代码靠后续调用抛出再被 catch 兜住时，继续用 `as` 可保持可观察行为一致；不确定则用 `as?` 加判空 |
| 34 | Java 允许 `instance.module.TAG`（经实例访问静态） | Kotlin **非法** → `MainModule.TAG` |
| 35 | 外层类可以读嵌套类的 `private` 成员，**Kotlin 不行** | 需要时把嵌套类成员改为 `internal` 或去掉 `private` |
| 36 | Kotlin 构造器对非空参数有**内建 null 检查**（Java 调用也拦） | 需要传 null 做测试时，把参数显式声明为可空 |
| 37 | `Int` 字面量传给 `Float` 形参 | 不会自动拓宽 → 加 `f` 后缀 |

## 四、可见性与 Java 互操作

| # | 场景 | 处理 |
|---|---|---|
| 38 | `public static final String KEY_X` | companion 里 `const val`，编译后仍是静态常量，`App.KEY_X` 两侧写法不变 |
| 39 | 非常量静态字段 | `@JvmField`（否则变成 getter，Java 侧 `obj.field` 编译失败） |
| 40 | 静态方法 | `@JvmStatic` 放 companion |
| 41 | Java 的**包级可见** | Kotlin 无对应。仅本文件使用时收为 `private`；否则用 `@JvmStatic internal` —— **但必须配 `@JvmName`，见 #44** |
| 42 | 私有静态方法传给 `Thread(...)` | 方法引用会生成 `Function0` 而非 `Runnable` → 用 lambda 包裹 |
| 43 | `SimpleDateFormat` / `ThreadLocal` 匿名子类 | `object : ThreadLocal<T>() { override fun initialValue(): T = ... }` |
| 44 | **`internal` 会改写 JVM 方法名** | 见下节，**这是本项目实际踩过的坑** |

### #44 `internal` 的名称改写（本项目实测）

`internal` 的字节码可见性是 public，但**方法名会被加上 `$模块名` 后缀**：

```
Kotlin:  internal fun isBbsLinkBinder(m: Method): Boolean
javap:   public static final boolean isBbsLinkBinder$app_debug(java.lang.reflect.Method)
```

后果：同包（甚至同 module）的 **Java** 代码写 `HeyboxTargets.isBbsLinkBinder(m)` 会**链接失败**，
报 `NoSuchMethodError`——而 Kotlin 源码侧一切正常，编译期无任何提示。

**正确写法**（`HeyboxTargets` 的 14 个 matcher 方法、`GameLibraryCleanHook.touchState` 均如此）：

```kotlin
@JvmStatic
@JvmName("isBbsLinkBinder")   // 抵消名称改写，JVM 名与 Java 版逐字一致
internal fun isBbsLinkBinder(method: Method): Boolean
```

三个注解缺一不可：
- `internal` → 保持「模块内可见、对外不暴露」的意图
- `@JvmStatic` → 静态方法形态
- `@JvmName` → 抵消名称改写，让 Java 调用点能链接

**注意**：`@JvmName` 只影响 JVM 名，不影响 Kotlin 侧调用——Kotlin 代码仍写 `isBbsLinkBinder(m)`。

## 五、验证手段

转换完成后按顺序：

```powershell
# 1. 单文件编译（.java 仍在时也能验证，绕开 Redeclaration）
pwsh tools/ktcheck.ps1 app/src/main/java/com/better/heybox/hooks/XxxHook.kt

# 2. 纪律审计（classic hook / 丢 catch / KClass / import 少点）
pwsh tools/audit-kotlin-migration.ps1
```

再交给父代理做整仓构建 + 装机验证。

**注意**：`ktcheck.ps1` 只证明该文件自身，不验证跨文件调用点。
它还有两个已知局限，遇到时**不要据此改代码**：

1. 引用本 module 的 `internal` 声明会误报 `cannot access ... it is internal in file`
   （单文件编译不构成同一编译单元）。改用整仓 `:app:compileDebugKotlin` 判断。
2. 它**不会**发现 #44 的名称改写问题——单文件编译时没有 Java 调用点参与链接。
   凡是用 `internal` 暴露给 Java 的方法，必须靠 `javap` 或整仓构建确认 JVM 名。
