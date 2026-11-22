package com.campusflow.lab.jvm.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验：元空间溢出（java.lang.OutOfMemoryError: Metaspace）复现与分析。
 *
 * <p><b>【重要】必须限制元空间上限运行，否则要生成几十万类才可能触发：</b>
 * <pre>
 *   java -XX:MaxMetaspaceSize=32m -cp target/classes com.campusflow.lab.jvm.memory.MetaspaceLab
 * </pre>
 *
 * <p>实验思路（纯 JDK 实现，不依赖 ASM/Javassist）：
 * <ol>
 *   <li>{@link #minimalClassBytes(String)} 手工拼出一个最小合法的 .class 字节数组
 *       （只含常量池 4 项 + 类头，无字段无方法，继承 java.lang.Object）；</li>
 *   <li>每个类使用<b>独立</b>的自定义 ClassLoader {@link LeakingClassLoader} 加载，
 *       并把所有 loader 持强引用保存在静态 List 中 —— 这是真实世界元空间泄漏的典型形态
 *       （Groovy 脚本、动态代理、OSGi/Tomcat 反复 redeploy）；</li>
 *   <li>类元数据（klass、常量池、方法字节码等）存放在 Metaspace（本地内存），
 *       不设 MaxMetaspaceSize 时理论上只受物理内存限制，
 *       设 -XX:MaxMetaspaceSize=32m 后几秒内即可抛 {@code OutOfMemoryError: Metaspace}。</li>
 * </ol>
 *
 * <p>为什么不"用同一份字节码反复 defineClass 不同类名"？
 * defineClass 会校验字节码里常量池记录的类名必须与传入的 name 一致，
 * 否则抛 {@code NoClassDefFoundError: wrong name}。
 * 因此本实验按目标类名<b>逐个重新生成</b>同名写入常量池的字节码，既合规又能快速膨胀元空间。
 */
public class MetaspaceLab {

    /** 迭代守护上限，防止未设置 MaxMetaspaceSize 时无限循环。 */
    public static final int MAX_CLASSES = 200_000;

    /** 生成的类的基础包名。 */
    public static final String BASE_PACKAGE = "com.campusflow.lab.jvm.memory.gen";

    /** 持强引用的 loader 列表（防止类元数据被 GC 回收）。 */
    public static final List<ClassLoader> RETAINED_LOADERS = new ArrayList<>();
    /** 持强引用的 Class 对象列表。 */
    public static final List<Class<?>> RETAINED_CLASSES = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("== MetaspaceLab：元空间溢出复现 ==");
        System.out.println("提示：请用 -XX:MaxMetaspaceSize=32m -cp target/classes "
                + MetaspaceLab.class.getName() + " 运行");
        System.out.println("（JDK8 是 PermGen 永久代 -XX:PermSize/-XX:MaxPermSize；JDK8+ 已改为本地内存的 Metaspace）");
        System.out.println();

        LoadResult result = generateUntilOom(MAX_CLASSES, Long.MAX_VALUE);
        printAnalysis(result);
    }

    /** 生成/加载统计结果。 */
    public static final class LoadResult {
        public final boolean oomCaught;
        public final String oomDetail;
        public final int classesLoaded;
        public final Throwable otherFailure;

        public LoadResult(boolean oomCaught, String oomDetail, int classesLoaded, Throwable otherFailure) {
            this.oomCaught = oomCaught;
            this.oomDetail = oomDetail;
            this.classesLoaded = classesLoaded;
            this.otherFailure = otherFailure;
        }
    }

    /**
     * 持续生成并加载最小类直到 OOM 或达到数量上限（供 main 与 JUnit 共用）。
     * OOM 在内部 catch；其他异常在内部 catch 并记录。
     *
     * @param maxClasses 类数量上限
     * @param deadlineMillis 截止时间戳（System.currentTimeMillis 值）；传 Long.MAX_VALUE 表示不限
     */
    public static LoadResult generateUntilOom(int maxClasses, long deadlineMillis) {
        boolean oom = false;
        String oomDetail = null;
        Throwable other = null;
        int loaded = 0;
        try {
            for (int i = 0; i < maxClasses; i++) {
                if (System.currentTimeMillis() > deadlineMillis) {
                    break;
                }
                String name = BASE_PACKAGE + ".Generated_" + i;
                // 每个类一个独立 loader，并持有强引用 —— 元空间泄漏的经典形态
                LeakingClassLoader loader = new LeakingClassLoader();
                Class<?> c = loader.loadGeneratedClass(name);
                RETAINED_LOADERS.add(loader);
                RETAINED_CLASSES.add(c);
                loaded++;
            }
        } catch (OutOfMemoryError e) {
            oom = true;
            // 注意（实测发现）：元空间满时，catch 里任何字符串拼接都可能触发
            // StringConcatFactory 生成 indy 调用点类（也要占元空间）=> 再次 OOM 冲出 catch！
            // 所以 catch 里只置标志，描述字符串等释放引用后再构造。
        } catch (Throwable t) {
            other = t;
        } finally {
            // 释放 loader/class 强引用：元空间压力缓解，同时类元数据具备整体卸载条件
            RETAINED_LOADERS.clear();
            RETAINED_CLASSES.clear();
        }
        if (oom) {
            try {
                oomDetail = "java.lang.OutOfMemoryError: Metaspace";
            } catch (OutOfMemoryError e2) {
                oomDetail = "OutOfMemoryError（元空间仍紧张，跳过详细描述）";
            }
        }
        return new LoadResult(oom, oomDetail, loaded, other);
    }

    /** 打印元空间 OOM 分析要点。 */
    public static void printAnalysis(LoadResult result) {
        System.out.println("---- Metaspace 分析要点 ----");
        if (result.oomCaught) {
            System.out.println("捕获异常     : " + result.oomDetail);
            System.out.println("已加载类数量 : " + result.classesLoaded);
        } else if (result.otherFailure != null) {
            System.out.println("非 OOM 失败  : " + result.otherFailure);
        } else {
            System.out.println("加载了 " + result.classesLoaded + " 个类仍未触发 OOM，");
            System.out.println("请加 -XX:MaxMetaspaceSize=32m 重跑（默认无上限，仅受物理内存限制）。");
        }
        System.out.println("1) JDK8+ 没有永久代：类元数据在 Metaspace（本地内存/堆外），字符串常量池与静态变量在堆；");
        System.out.println("2) 触发条件：defineClass 的类元数据总量超过 MaxMetaspaceSize（或压缩类指针的 CompressedClassSpaceSize）；");
        System.out.println("3) 典型泄漏源：动态代理/ASM/CGLIB、Groovy/JS 脚本引擎热更新、大量 ClassLoader 反复加载、"); 
        System.out.println("   redeploy 后旧 ClassLoader 被 ThreadLocal/静态字段持有而无法整体卸载；");
        System.out.println("4) 类卸载条件（三要素同时满足）：该类所有实例已回收 + 加载它的 ClassLoader 已回收 + ");
        System.out.println("   对应 java.lang.Class 对象无引用；本实验刻意保留 loader 强引用，因此永远不会卸载；");
        System.out.println("5) 诊断：jcmd <pid> GC.class_histogram 看类数量；jcmd <pid> VM.metaspace 看元空间细分；");
        System.out.println("   -Xlog:gc+metaspace=trace 可观察元空间分配与卸载。");
    }

    /**
     * 自定义 ClassLoader：把手工生成的最小类字节码 defineClass 进 JVM。
     * parent 取平台类加载器（PlatformClassLoader），模拟真实动态类生成场景。
     */
    public static final class LeakingClassLoader extends ClassLoader {
        public LeakingClassLoader() {
            super(getPlatformClassLoader());
        }

        /** 生成字节码、defineClass 并触发初始化（初始化会完成链接，确保元数据真实写入）。 */
        public Class<?> loadGeneratedClass(String binaryName) {
            String internalName = binaryName.replace('.', '/');
            byte[] bytes = minimalClassBytes(internalName);
            Class<?> c = defineClass(binaryName, bytes, 0, bytes.length);
            try {
                // resolveClass 也可，这里用 forName 触发初始化（链接+验证均完成）
                Class.forName(binaryName, true, this);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("刚定义的类居然找不到: " + binaryName, e);
            }
            return c;
        }
    }

    /**
     * 手工拼装最小合法 .class 文件（Java 8 版本号 52，无字段、无方法、无属性表）：
     * <pre>
     * magic=0xCAFEBABE, minor=0, major=52
     * 常量池: #1 Class->#2, #2 Utf8(本类名), #3 Class->#4, #4 Utf8("java/lang/Object")
     * ACC_PUBLIC|ACC_SUPER(0x0021), this_class=#1, super_class=#3
     * interfaces=0, fields=0, methods=0, attributes=0
     * </pre>
     */
    public static byte[] minimalClassBytes(String internalName) {
        byte[] name = internalName.getBytes(StandardCharsets.UTF_8);
        byte[] sup = "java/lang/Object".getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64 + name.length + sup.length);
        buf.putInt(0xCAFEBABE);            // magic (u4)
        buf.putShort((short) 0);           // minor_version (u2)
        buf.putShort((short) 52);          // major_version (u2)
        buf.putShort((short) 5);           // constant_pool_count = 4 项 + 1 (u2)
        buf.put((byte) 7);                 // #1 tag=CONSTANT_Class (注意: tag 是 u1，1 字节!)
        buf.putShort((short) 2);           //    name_index -> #2 (u2)
        buf.put((byte) 1);                 // #2 tag=CONSTANT_Utf8 (u1)
        buf.putShort((short) name.length); //    length (u2)
        buf.put(name);
        buf.put((byte) 7);                 // #3 tag=CONSTANT_Class (u1)
        buf.putShort((short) 4);           //    name_index -> #4 (u2)
        buf.put((byte) 1);                 // #4 tag=CONSTANT_Utf8 (u1)
        buf.putShort((short) sup.length);  //    length (u2)
        buf.put(sup);
        buf.putShort((short) 0x0021);      // access_flags: ACC_PUBLIC | ACC_SUPER
        buf.putShort((short) 1);           // this_class = #1
        buf.putShort((short) 3);           // super_class = #3
        buf.putShort((short) 0);           // interfaces_count
        buf.putShort((short) 0);           // fields_count
        buf.putShort((short) 0);           // methods_count
        buf.putShort((short) 0);           // attributes_count
        int pos = buf.position();
        byte[] out = new byte[pos];
        buf.flip();
        buf.get(out);
        return out;
    }
}
