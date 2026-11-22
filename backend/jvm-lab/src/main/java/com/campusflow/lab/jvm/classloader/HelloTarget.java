package com.campusflow.lab.jvm.classloader;

/**
 * 类加载实验的"目标类"：
 * 该类的 .class 文件会被 ClassLoaderLab 手工读出字节，
 * 交给自定义 ClassLoader defineClass，从而演示
 * "同一个全限定类名，被两个不同 ClassLoader 加载 => 两个互不相等的 Class 对象"。
 */
public class HelloTarget {

    public String hello() {
        return "Hello, I am " + getName() + ", loaded by " + loaderDescription();
    }

    public String getName() {
        return "com.campusflow.lab.jvm.classloader.HelloTarget";
    }

    public String loaderDescription() {
        ClassLoader cl = getClass().getClassLoader();
        return cl == null ? "Bootstrap ClassLoader(null)" : cl.getClass().getName() + "@" + Integer.toHexString(cl.hashCode());
    }
}
