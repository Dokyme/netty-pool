package com.dokyme.netty_pool;


import io.netty.bootstrap.Bootstrap;

public class ConnectionPools {

    private static final int DEFAULT_CORE_SIZE = 10;
    private static final int DEFAULT_MAX_SIZE = 10;
    private static final long DEFAULT_KEEPALIVE = 20 * 1000l;

    public ConnectionPool newSingleConnectionPool(Bootstrap sample) {
        return new AbstractConnectionPool(1, 1, 0, sample);
    }

    public ConnectionPool newCachedConnectionPool(Bootstrap sample) {
        return new AbstractConnectionPool(DEFAULT_CORE_SIZE, DEFAULT_MAX_SIZE, DEFAULT_KEEPALIVE, sample);
    }

    public ConnectionPool newFixedConnectionPool(int num, Bootstrap sample) {
        return new AbstractConnectionPool(num, num, DEFAULT_KEEPALIVE, sample);
    }
}
