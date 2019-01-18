package com.dokyme.netty_pool;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

public interface ConnectionPool {

    /**
     * 准备n个已经连接好的TCP连接
     * n < coreSize时，准备coreSize个连接，否则准备n个连接
     *
     * @param number
     */
    void prepare(int number);

    /**
     * 取一个空闲的TCP连接
     * 如果当前维护的总连接数超过max，则返回null。
     *
     * @return
     */
    ChannelFuture lease();

    /**
     * 返还一个TCP连接，使其状态变为空闲
     *
     * @param connection
     */
    void revert(Channel connection);

    /**
     * 关闭该连接池，停止lease
     */
    void close();

    /**
     * 释放所有连接
     */
    void shutdown();
}
