package com.campusflow.lab.jvm.classloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ClassLoaderLab 的 JUnit 测试：双亲委派与类同一性验证。 */
class ClassLoaderLabTest {

    @Test
    @Timeout(30)
    void stringLoadedByBootstrap() {
        // Bootstrap 加载的核心类在 Java 里 getClassLoader() == null
        assertNull(String.class.getClassLoader(), "String 应由 Bootstrap 加载（loader 为 null）");
        assertNull(Object.class.getClassLoader());
        // 数组类的加载器与元素类型一致：元素为 Bootstrap 类，数组类加载器也是 null
        assertNull(int[].class.getClassLoader());
        assertNull(String[].class.getClassLoader());
    }

    @Test
    @Timeout(30)
    void loaderHierarchyIsAppAbovePlatform() {
        ClassLoader loader = ClassLoaderLab.class.getClassLoader();
        assertTrue(loader != null, "应用类应由 Application ClassLoader 加载");
        assertTrue(loader.getClass().getName().contains("AppClassLoader"));
        // JDK9+: App 的 parent 是 Platform（原 Extension）
        ClassLoader parent = loader.getParent();
        assertTrue(parent != null && parent.getClass().getName().contains("PlatformClassLoader"));
        // Platform 的 parent 是 Bootstrap（Java 中不可见，getParent() 返回 null）
        assertNull(parent.getParent());
    }

    @Test
    @Timeout(60)
    void delegationReturnsSameClassInstance() throws Exception {
        ClassLoaderLab.LoggingClassLoader logging = new ClassLoaderLab.LoggingClassLoader();
        // 核心 API：委派链最终由 Bootstrap 返回 => 与 String.class 是同一 Class 对象
        Class<?> s = logging.loadClass("java.lang.String");
        assertSame(String.class, s);
        // 应用类：委派到 AppClassLoader 返回 => 与 HelloTarget.class 同一 Class 对象
        Class<?> t = logging.loadClass(ClassLoaderLab.TARGET_CLASS);
        assertSame(HelloTarget.class, t);
        assertSame(HelloTarget.class.getClassLoader(), t.getClassLoader());
    }

    @Test
    @Timeout(60)
    void childFirstLoaderBypassesDelegation() throws Exception {
        ClassLoaderLab.ChildFirstLoader childFirst = new ClassLoaderLab.ChildFirstLoader();
        Class<?> loaded = childFirst.loadClass(ClassLoaderLab.TARGET_CLASS);

        // 绕过双亲委派：同名类产生两个互不相等的 Class 对象
        assertNotSame(HelloTarget.class, loaded);
        assertNotSame(HelloTarget.class.getClassLoader(), loaded.getClassLoader());
        assertEquals(HelloTarget.class.getName(), loaded.getName());

        // 字节码来自同一 target/classes，但 Class 实例不同
        Object instance = loaded.getDeclaredConstructor().newInstance();
        Object ret = loaded.getMethod("hello").invoke(instance);
        assertTrue(ret.toString().contains("HelloTarget"));
    }

    @Test
    @Timeout(60)
    void diskLoaderWithPlatformParentLoadsFromBytes() throws Exception {
        byte[] bytes = ClassLoaderLab.readClassBytes(ClassLoaderLab.TARGET_CLASS);
        assertTrue(bytes != null && bytes.length > 0, "应能读到 HelloTarget.class 字节");

        ClassLoaderLab.DiskLoader diskLoader = new ClassLoaderLab.DiskLoader();
        Class<?> loaded = diskLoader.defineFromBytes(ClassLoaderLab.TARGET_CLASS, bytes);
        assertEquals(diskLoader, loaded.getClassLoader());
        assertFalse(loaded == HelloTarget.class);

        // 实例不能赋给 App loader 的 HelloTarget 类型（跨加载器不兼容），只能反射调用
        Object instance = loaded.getDeclaredConstructor().newInstance();
        Object ret = loaded.getMethod("hello").invoke(instance);
        assertTrue(ret.toString().contains("loaded by"));
    }
}
