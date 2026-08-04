/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright (c) 2012-2014 Monty Program Ab
 * Copyright (c) 2015-2025 MariaDB Corporation Ab
 *
 * Android adaptation: JMX registration is omitted because Android does not
 * provide java.lang.management or javax.management.
 */

package org.mariadb.jdbc.pool;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.Driver;

/**
 * Android-compatible MariaDB pool facade.
 *
 * <p>Mobile DB Manager uses non-pooled direct connections. If a pooled MariaDB URL is supplied
 * by external code, this facade keeps the driver's binary contract and returns
 * an independent physical connection without Java SE JMX registration.
 */
public class Pool implements AutoCloseable, PoolMBean {
    private final Configuration configuration;
    private final String poolTag;
    private final AtomicLong connectionRequests = new AtomicLong();

    public Pool(
            Configuration configuration,
            int poolIndex,
            ScheduledThreadPoolExecutor poolExecutor) {
        this.configuration = configuration;
        this.poolTag = configuration.poolName() == null
                ? "MariaDB-pool"
                : configuration.poolName() + "-" + poolIndex;
    }

    public MariaDbInnerPoolConnection getPoolConnection() throws SQLException {
        connectionRequests.incrementAndGet();
        return new MariaDbInnerPoolConnection(Driver.connect(configuration));
    }

    public MariaDbInnerPoolConnection getPoolConnection(String username, String password)
            throws SQLException {
        Configuration connectionConfiguration = configuration.clone(username, password);
        connectionRequests.incrementAndGet();
        return new MariaDbInnerPoolConnection(Driver.connect(connectionConfiguration));
    }

    public Configuration getConf() {
        return configuration;
    }

    @Override
    public void close() {
        Pools.remove(this);
    }

    public String getPoolTag() {
        return poolTag;
    }

    @Override
    public long getActiveConnections() {
        return 0;
    }

    @Override
    public long getTotalConnections() {
        return 0;
    }

    @Override
    public long getIdleConnections() {
        return 0;
    }

    @Override
    public long getConnectionRequests() {
        return connectionRequests.get();
    }

    public List<Long> testGetConnectionIdleThreadIds() {
        return Collections.emptyList();
    }
}
