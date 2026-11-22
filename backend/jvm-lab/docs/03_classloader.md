# 03 · 类加载实验讲义：双亲委派与自定义类加载器（ClassLoaderLab）

> 模块路径：`backend/jvm-lab`
> 包名：`com.campusflow.lab.jvm.classloader`

---

## 一、实验目标

1. 验证 `String.class.getClassLoader() == null`（Bootstrap 加载）；
2. 打印 JDK17 的三层加载器层级：App → Platform → Bootstrap；
3. `LoggingClassLoader` 在 loadClass 每步打印，直观呈现双亲委派全过程；
4. `DiskLoader`（parent=Platform）从磁盘读字节加载 `HelloTarget`，证明"类同一性 = ClassLoader + 全限定名"；
5. `ChildFirstLoader` 先自己加载再委派——**绕过双亲委派**，同名类变成两个独立 Class。

---

## 二、双亲委派模型（原理）

```
Bootstrap ClassLoader（C++，Java 中表现为 null，加载 rt.jar/jdk 核心模块）
        ↑ 委派
Platform ClassLoader（JDK9+，原 Extension，加载平台模块/扩展）
        ↑ 委派
Application ClassLoader（App，加载 classpath 应用类）
        ↑ 委派
自定义 ClassLoader
```

**委派流程**（`ClassLoader.loadClass` 默认实现）：
1. `findLoadedClass(name)` 查已加载缓存；
2. 有 parent 则 `parent.loadClass(name, false)` 委派；
3. parent 抛 ClassNotFoundException 才 `findClass(name)` 自己加载。

**为什么这样设计**：
- 安全：自定义 `java.lang.String` 永远先被 Bootstrap 加载，核心 API 不可篡改；
- 唯一性：同一个类全 JVM 只有一份 Class 对象，避免类型混乱。

**JDK9+ 模块化变化**：ExtensionClassLoader 更名 PlatformClassLoader；
`getParent()` 到 Bootstrap 层返回 null（Bootstrap 是 C++ 实现）。

---

## 三、源码要点

```java
// 1. 日志化加载器：完整复刻默认委派逻辑
protected Class<?> loadClass(String name, boolean resolve) {
    synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c == null) {
            try {
                c = parent.loadClass(name);          // 委派（loadClass(String,boolean) 是 protected，跨对象只能调 public 重载）
            } catch (ClassNotFoundException e) {
                c = findClass(name);                 // parent 找不到才自己来
            }
        }
        ...
    }
}

// 2. 子优先（破坏双亲委派）—— Tomcat WebappClassLoader 的核心思路
protected Class<?> loadClass(String name, boolean resolve) {
    Class<?> c = findLoadedClass(name);
    if (c == null) {
        try {
            c = findClass(name);                 // 先自己加载！
        } catch (ClassNotFoundException e) {
            c = super.loadClass(name, false);    // 失败才回头委派
        }
    }
    ...
}

// 3. 字节读取：只从磁盘 CodeSource 目录读本项目类
// 千万不能无限制用 getSystemResourceAsStream 兜底——它能经 jrt: 读到 JDK 的 Object.class，
// defineClass("java.lang.Object") 会抛 SecurityException: Prohibited package name: java.lang
```

**真实踩坑记录**：初版 `readClassBytes` 用系统资源流兜底读不到时，
`findClass("java.lang.Object")` 竟然从 `jrt:/java.base/java/lang/Object.class`
读到了 JDK 自带字节码，`defineClass` 时抛
`SecurityException: Prohibited package name: java.lang`——这本身就是"为什么有双亲委派"的活教材。

---

## 四、运行命令与真实输出

```bash
java -Dfile.encoding=UTF-8 -cp target/classes com.campusflow.lab.jvm.classloader.ClassLoaderLab
```

真实输出（JDK17，Temurin 17.0.21.1+2）：

```text
== ClassLoaderLab：双亲委派与自定义类加载器 ==

[1] String.class.getClassLoader() = null  （null 表示由 Bootstrap ClassLoader 加载）
    int[].class.getClassLoader()   = null  （数组类的类加载器与元素类型一致；基本类型数组也由 Bootstrap '加载'）

[2] 当前类加载器层级：
    jdk.internal.loader.ClassLoaders$AppClassLoader (app)
    jdk.internal.loader.ClassLoaders$PlatformClassLoader (platform)
    Bootstrap ClassLoader (C++ 实现，Java 中表示为 null)
    层级: Application(App) -> Platform(原 Extension, JDK9+) -> Bootstrap

[3] LoggingClassLoader 加载 java.lang.String（应看到委派给 parent 后命中）：
    [loadClass] java.lang.String -> 先查 JVM 已加载缓存 findLoadedClass
    [loadClass] java.lang.String -> 委派给 parent: AppClassLoader
    [loadClass] java.lang.String -> parent 加载成功, loader=Bootstrap(null)
    结果: java.lang.String 的 loader = null（与 String.class 相同实例: true）

[4] LoggingClassLoader 加载本模块自己的类（一路委派到 App 后命中）：
    [loadClass] com.campusflow.lab.jvm.classloader.HelloTarget -> 委派给 parent: AppClassLoader
    [loadClass] com.campusflow.lab.jvm.classloader.HelloTarget -> parent 加载成功, loader=AppClassLoader
    结果: loader = jdk.internal.loader.ClassLoaders$AppClassLoader@6d06d69c  与 HelloTarget.class 同一 Class 对象: true

[5] DiskLoader(parent=Platform) 从磁盘读字节加载 com.campusflow.lab.jvm.classloader.HelloTarget：
    HelloTarget.class 字节数: 1576
    加载成功: com.campusflow.lab.jvm.classloader.HelloTarget loader=DiskLoader
    与 App 加载的 HelloTarget.class 是同一 Class 对象吗? false（false：类同一性 = ClassLoader + 全限定名）
    强转 (HelloTarget) 抛出 ClassCastException: class com.campusflow.lab.jvm.classloader.HelloTarget cannot be cast to
    class com.campusflow.lab.jvm.classloader.HelloTarget (com.campusflow.lab.jvm.classloader.HelloTarget is in unnamed
    module of loader com.campusflow.lab.jvm.classloader.ClassLoaderLab$DiskLoader @3941a79c; ... is in unnamed module of loader 'app')
    -> 两个 ClassLoader 加载的同名类互不相通；跨加载器只能用反射或共同 parent 加载的接口交互
    反射调用 hello(): Hello, I am com.campusflow.lab.jvm.classloader.HelloTarget, loaded by ...$DiskLoader@3941a79c

[6] ChildFirstLoader 先自己加载、再委派（模拟 Tomcat WebappClassLoader 隔离策略）：
    [ChildFirst] com.campusflow.lab.jvm.classloader.HelloTarget -> 先自己 findClass（不走 parent！）
    [ChildFirst] java.lang.Object -> 先自己 findClass（不走 parent！）
    [ChildFirst] java.lang.Object -> 自己没有，回头委派 parent
    [ChildFirst] com.campusflow.lab.jvm.classloader.HelloTarget -> 自己加载成功，loader=ChildFirstLoader
    loader = ChildFirstLoader  与 App 的 HelloTarget.class 同一 Class 对象: false
    instanceof 检查: bypassed instanceof HelloTarget? false -> 即使字段结构完全相同，跨 ClassLoader 也不能互相赋值
```

**输出中最有价值的三个点**：
1. `[5]` 的 ClassCastException 消息里两个"同名类"分别属于不同 loader——
   *"HelloTarget cannot be cast to HelloTarget"* 是双亲委派话题的标志性现场；
2. `[6]` 中 ChildFirstLoader 加载 HelloTarget 时，JVM 顺带解析其父类 `java.lang.Object`：
   自己 findClass 失败后回头委派 parent——**子优先模式也要为基础类型回退委派**；
3. 数组类 `int[].class` 的 loader 是 null：数组类由 JVM 动态生成，随元素类型的加载器。

---

## 五、常见面试追问与参考答案

**Q1：能不能自己写一个 java.lang.String 并被加载？**
A：不能。双亲委派保证 Bootstrap 先加载 java.lang.String；即使绕开委派强行 defineClass，
`ClassLoader.preDefineClass` 会检查包名并以 `SecurityException: Prohibited package name: java.lang`
拒绝（本实验在踩坑中真实复现了该异常）。这是"沙箱安全"的一部分。

**Q2：Tomcat 如何用类加载器实现多应用隔离？**
A：每个 Webapp 一个 WebappClassLoader，**子优先**加载应用自己的类（先 WEB-INF/classes、
WEB-INF/lib，再委派），实现应用间同名类互不可见与独立卸载（redeploy 卸载整个 loader）；
容器公共类（JSP 引擎等）由 common loader 加载。Q2 是 Q"绕过双亲委派"最经典的工程实践。

**Q3：什么是线程上下文类加载器？解决什么问题？**
A：`Thread.currentThread().getContextClassLoader()`。JDK 核心类（如 JDBC 的
`java.sql.DriverManager`）按双亲委派"看不到"classpath 里的第三方驱动（它们在 App classpath），
于是核心类通过线程上下文类加载器"反向"委托 App 加载器加载 SPI 实现——
这是双亲委派模型的官方补充（桥梁），也是 SPI/JNDI/Tomcat 的常用手法。

**Q4：类同一性由什么决定？会有什么坑？**
A：`(ClassLoader, 全限定名)` 二元组。两个 loader 各自加载同名类 → 两个独立 Class：
instanceof/反射互不相通、静态字段各一份、互相赋值抛 ClassCastException
（本实验 [5] 真实演示）。坑：热部署后 instanceof 失效、单例模式跨 loader 失效、
SPI 接口与实现被不同 loader 加载引发 NoClassDefFoundError/ClassCastException。
最佳实践：**接口放共同的 parent loader**，实现类放子 loader。

**Q5：自定义 ClassLoader 最小实现是什么？defineClass/findClass/loadClass 三者关系？**
A：最小实现 = 覆写 `findClass(String)`（读字节 + `defineClass(name, bytes, off, len)`）。
loadClass 是委派流程入口（一般不改）；findClass 是"自己找类"的钩子（必须覆写）；
defineClass 是把字节数组变成 Class 的 JVM 入口（不能随便调，注意校验与保护域）。
要改委派顺序才覆写 loadClass，并注意用 `getClassLoadingLock(name)` 保证并发安全。

**Q6：类什么时候会被卸载？Metaspace 泄漏怎么和它扯上关系？**
A：三条件：① 类所有实例已回收；② 加载它的 ClassLoader 已被回收；③ java.lang.Class
对象无引用。泄漏场景：静态集合持有 loader/Class、ThreadLocal 残留、
JDBC 驱动注册表持有 —— loader 卸不掉 → 该 loader 加载的所有类元数据无法释放 → Metaspace OOM
（详见 01_memory.md 的 MetaspaceLab）。

**Q7：JDK9 模块化对类加载有什么影响？**
A：三层结构保留但 Extension→Platform；类的可见性先由模块图决定（`Loads`/`Exports`），
再由加载器分工；Bootstrap 由 jrt:/ 模块文件系统支撑；
`getSystemResourceAsStream` 能读到模块内资源（本实验踩坑），但 defineClass 保护依旧。
