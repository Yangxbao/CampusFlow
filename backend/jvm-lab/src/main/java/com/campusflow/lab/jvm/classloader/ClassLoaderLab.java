package com.campusflow.lab.jvm.classloader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 实验：类加载器体系、双亲委派模型与"绕过双亲委派"。
 *
 * <p>运行方式：
 * <pre>
 *   java -cp target/classes com.campusflow.lab.jvm.classloader.ClassLoaderLab
 * </pre>
 *
 * <p>实验内容：
 * <ol>
 *   <li>验证 {@code String.class.getClassLoader() == null}（Bootstrap 加载，Java 里表现为 null）；</li>
 *   <li>打印当前类加载器层级（App -> Platform -> Bootstrap）；</li>
 *   <li>{@link LoggingClassLoader}：在 loadClass 每一步打印委派路径，直观展示双亲委派；</li>
 *   <li>{@link DiskLoader}：parent 取 PlatformClassLoader（看不到 App classpath），
 *       从磁盘读 HelloTarget.class 字节并 defineClass —— 由于父加载器加载不到，
 *       只能由子加载器自己加载，这是"委派到底层失败后自己加载"的正常路径；</li>
 *   <li>{@link ChildFirstLoader}：覆写 loadClass，<b>先</b> findClass 再委派 parent，
 *       成功绕过双亲委派 —— 同一个 HelloTarget 变成了两个不同的 Class 对象；</li>
 *   <li>验证"类的同一性由 ClassLoader + 全限定名共同决定"。</li>
 * </ol>
 */
public class ClassLoaderLab {

    public static final String TARGET_CLASS = "com.campusflow.lab.jvm.classloader.HelloTarget";

    public static void main(String[] args) throws Exception {
        System.out.println("== ClassLoaderLab：双亲委派与自定义类加载器 ==");

        // ---- 1. Bootstrap 加载核心类：String 的 ClassLoader 是 null ----
        ClassLoader stringLoader = String.class.getClassLoader();
        System.out.println();
        System.out.println("[1] String.class.getClassLoader() = " + stringLoader
                + "  （null 表示由 Bootstrap ClassLoader 加载）");
        System.out.println("    int[].class.getClassLoader()   = " + int[].class.getClassLoader()
                + "  （数组类的类加载器与元素类型一致；基本类型数组也由 Bootstrap '加载'）");

        // ---- 2. 当前类的加载器层级 ----
        System.out.println();
        System.out.println("[2] 当前类加载器层级：");
        ClassLoader cl = ClassLoaderLab.class.getClassLoader();
        while (cl != null) {
            System.out.println("    " + cl.getClass().getName() + " (" + cl.getName() + ")");
            cl = cl.getParent();
        }
        System.out.println("    Bootstrap ClassLoader (C++ 实现，Java 中表示为 null)");
        System.out.println("    层级: Application(App) -> Platform(原 Extension, JDK9+) -> Bootstrap");

        // ---- 3. LoggingClassLoader 直观展示双亲委派 ----
        System.out.println();
        System.out.println("[3] LoggingClassLoader 加载 java.lang.String（应看到委派给 parent 后命中）：");
        LoggingClassLoader logging = new LoggingClassLoader();
        Class<?> stringAgain = logging.loadClass("java.lang.String");
        System.out.println("    结果: " + stringAgain.getName() + " 的 loader = " + stringAgain.getClassLoader()
                + "（与 String.class 相同实例: " + (stringAgain == String.class) + "）");

        System.out.println();
        System.out.println("[4] LoggingClassLoader 加载本模块自己的类（一路委派到 App 后命中）：");
        Class<?> labAgain = logging.loadClass(TARGET_CLASS);
        System.out.println("    结果: loader = " + labAgain.getClassLoader()
                + "  与 HelloTarget.class 同一 Class 对象: " + (labAgain == HelloTarget.class));

        // ---- 5. DiskLoader：parent=Platform，只能自己从磁盘读字节加载 ----
        System.out.println();
        System.out.println("[5] DiskLoader(parent=Platform) 从磁盘读字节加载 " + TARGET_CLASS + "：");
        byte[] bytes = readClassBytes(TARGET_CLASS);
        System.out.println("    HelloTarget.class 字节数: " + (bytes == null ? -1 : bytes.length));
        DiskLoader diskLoader = new DiskLoader();
        Class<?> diskLoaded = diskLoader.defineFromBytes(TARGET_CLASS, bytes);
        Object instance = diskLoaded.getDeclaredConstructor().newInstance();
        System.out.println("    加载成功: " + diskLoaded.getName() + " loader=" + diskLoaded.getClassLoader().getClass().getSimpleName());
        System.out.println("    与 App 加载的 HelloTarget.class 是同一 Class 对象吗? "
                + (diskLoaded == HelloTarget.class) + "（false：类同一性 = ClassLoader + 全限定名）");
        try {
            // 用 App 加载器的 HelloTarget 去接 DiskLoader 加载的实例 -> 必然 ClassCastException
            HelloTarget casted = (HelloTarget) instance;
            System.out.println("    调用 hello(): " + casted.hello());
        } catch (ClassCastException cce) {
            System.out.println("    强转 (HelloTarget) 抛出 ClassCastException: " + cce.getMessage());
            System.out.println("    -> 两个 ClassLoader 加载的同名类互不相通；跨加载器只能用反射或共同 parent 加载的接口交互");
            Object ret = diskLoaded.getMethod("hello").invoke(instance);
            System.out.println("    反射调用 hello(): " + ret);
        }

        // ---- 6. ChildFirstLoader：先自己加载再委派 => 绕过双亲委派 ----
        System.out.println();
        System.out.println("[6] ChildFirstLoader 先自己加载、再委派（模拟 Tomcat WebappClassLoader 隔离策略）：");
        ChildFirstLoader childFirst = new ChildFirstLoader();
        Class<?> bypassed = childFirst.loadClass(TARGET_CLASS);
        System.out.println("    loader = " + bypassed.getClassLoader().getClass().getSimpleName()
                + "  与 App 的 HelloTarget.class 同一 Class 对象: " + (bypassed == HelloTarget.class));
        System.out.println("    instanceof 检查: bypassed instanceof HelloTarget? "
                + (HelloTarget.class.isAssignableFrom(bypassed))
                + " -> 即使字段结构完全相同，跨 ClassLoader 也不能互相赋值，会抛 ClassCastException");

        System.out.println();
        System.out.println("---- 分析要点 ----");
        System.out.println("1) 双亲委派：loadClass 先委派 parent，parent 加载不到才自己 findClass；");
        System.out.println("    好处：核心类唯一且安全（自定义 java.lang.String 会被 Bootstrap 先加载，防止核心 API 被篡改）；");
        System.out.println("2) JDK9+ 模块化后 ExtensionClassLoader 更名为 PlatformClassLoader，三层结构保留；");
        System.out.println("3) 绕过双亲委派的合法场景：Tomcat 应用隔离、SPI(java.sql.Driver 由线程上下文加载器回加载)、");
        System.out.println("    OSGi 网状委派、热部署；线程上下文类加载器 Thread.currentThread().getContextClassLoader() 是桥梁；");
        System.out.println("4) 类同一性由 (ClassLoader, FQCN) 决定：同名字节码被两个 loader 加载 => 两个独立 Class =>");
        System.out.println("    静态字段、instanceof、反射互不相通 —— 这是隔离也是 ClassCastException 的常见来源；");
        System.out.println("5) 自定义加载器最小实现：覆写 findClass 读取字节并 defineClass；覆写 loadClass 可改变委派顺序。");
    }

    /* ==================== 自定义类加载器们 ==================== */

    /** 在每一步打印日志的自定义加载器：完整模拟双亲委派流程。 */
    public static final class LoggingClassLoader extends ClassLoader {
        public LoggingClassLoader() {
            // parent = AppClassLoader（即加载本类的加载器）
            super(ClassLoaderLab.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                System.out.println("    [loadClass] " + name + " -> 先查 JVM 已加载缓存 findLoadedClass");
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        ClassLoader parent = getParent();
                        System.out.println("    [loadClass] " + name + " -> 委派给 parent: "
                                + (parent == null ? "Bootstrap(null)" : parent.getClass().getSimpleName()));
                        // 注意：loadClass(String,boolean) 是 protected，跨对象只能调 public 的 loadClass(String)
                        c = parent.loadClass(name);
                        System.out.println("    [loadClass] " + name + " -> parent 加载成功, loader="
                                + (c.getClassLoader() == null ? "Bootstrap(null)" : c.getClassLoader().getClass().getSimpleName()));
                    } catch (ClassNotFoundException e) {
                        System.out.println("    [loadClass] " + name + " -> parent 找不到，自己 findClass");
                        c = findClass(name);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }

    /** parent=Platform 的磁盘加载器：App classpath 里的类对它不可见，必须自己读字节。 */
    public static final class DiskLoader extends ClassLoader {
        public DiskLoader() {
            super(getPlatformClassLoader());
        }

        public Class<?> defineFromBytes(String name, byte[] bytes) {
            if (bytes == null) {
                throw new IllegalStateException("读不到 " + name + " 的 class 字节，请先 mvn compile");
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = readClassBytes(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** 子优先（破坏双亲委派）加载器：先自己 findClass，失败才委派 parent。 */
    public static final class ChildFirstLoader extends ClassLoader {
        public ChildFirstLoader() {
            super(ClassLoaderLab.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    System.out.println("    [ChildFirst] " + name + " -> 先自己 findClass（不走 parent！）");
                    try {
                        c = findClass(name);
                        System.out.println("    [ChildFirst] " + name + " -> 自己加载成功，loader="
                                + c.getClassLoader().getClass().getSimpleName());
                    } catch (ClassNotFoundException e) {
                        System.out.println("    [ChildFirst] " + name + " -> 自己没有，回头委派 parent");
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = readClassBytes(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /* ==================== 字节读取 ==================== */

    /**
     * 读取某个编译在 classpath 里的 .class 文件字节：
     * 优先按 CodeSource 目录读磁盘文件；失败则回退用 AppClassLoader 的资源流。
     * 注意：这里只是"读文件"，不发生类加载；类加载由自定义 loader 的 defineClass 完成。
     */
    public static byte[] readClassBytes(String binaryName) {
        String rel = binaryName.replace('.', '/') + ".class";
        try {
            URL loc = ClassLoaderLab.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null && "file".equals(loc.getProtocol())) {
                Path p = Paths.get(loc.toURI()).resolve(rel);
                if (Files.exists(p)) {
                    return Files.readAllBytes(p);
                }
            }
        } catch (Exception ignore) {
            // fallthrough
        }
        // 兜底：只允许读本项目的类。
        // 千万不能用 getSystemResourceAsStream 无限制兜底 —— 它能通过 jrt: 文件系统
        // 读到 JDK 模块里的 Object.class 等字节，defineClass("java.lang.Object") 会抛
        // SecurityException: Prohibited package name: java.lang
        if (!binaryName.startsWith("com.campusflow")) {
            return null;
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(rel)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 供测试与脚本使用的磁盘读取包装（演示 FileInputStream 用法）。 */
    public static byte[] readClassBytesFromFile(File classFile) throws Exception {
        try (FileInputStream fin = new FileInputStream(classFile)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fin.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
