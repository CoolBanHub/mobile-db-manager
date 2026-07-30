/*
 * Copyright 2008-present MongoDB, Inc.
 * Licensed under the Apache License, Version 2.0.
 *
 * Android adaptation: Android has no JMX runtime, so registration is a no-op.
 */

package com.mongodb.internal.management.jmx;

import com.mongodb.management.MBeanServer;

/**
 * Android replacement for the MongoDB driver's Java SE JMX implementation.
 */
@SuppressWarnings("deprecation")
public final class JMXMBeanServer implements MBeanServer {
    @Override
    public void registerMBean(Object mBean, String mBeanName) {
        // Android does not expose JMX. Driver statistics remain in-process only.
    }

    @Override
    public void unregisterMBean(String mBeanName) {
        // Android does not expose JMX.
    }
}
