/*
 * Copyright 2008-present MongoDB, Inc.
 * Licensed under the Apache License, Version 2.0.
 *
 * Android 适配：系统没有 JMX 运行时，因此注册操作为空实现。
 */

package com.mongodb.management;

/**
 * MongoDB 驱动 Java SE JMX 实现的 Android 替代类。
 */
@SuppressWarnings("deprecation")
public final class JMXMBeanServer implements MBeanServer {
    public JMXMBeanServer() {}

    @Override
    public void registerMBean(Object mBean, String mBeanName) {
        // Android 不提供 JMX，驱动统计信息只保留在进程内。
    }

    @Override
    public void unregisterMBean(String mBeanName) {
        // Android 不提供 JMX，无需注销。
    }
}
