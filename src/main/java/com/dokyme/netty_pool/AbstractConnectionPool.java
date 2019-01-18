package com.dokyme.netty_pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelId;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AbstractConnectionPool implements ConnectionPool {
    private static final Logger logger = LoggerFactory.getLogger(AbstractConnectionPool.class);

    private int coreConnectionSize;
    private int maxConnectionSize;
    private long keepAliveTime;
    private Bootstrap sample;
    private AtomicInteger currentConnectionSize;
    private AtomicBoolean hasClosed;
    private Map<ChannelId, FutureChannel> channelIdFutureChannelMap;
    private Set<FutureChannel> initialFutureChannelSet;

    /**
     * 连接队列，以空闲时间为主键的优先队列
     * 取出的第一个连接是最早开始进入空闲状态的连接
     */
    private Queue<FutureChannel> idleChannels;

    private NioEventLoopGroup eventLoopGroup;

    /**
     * 构造函数
     *
     * @param coreConnectionSize:核心连接个数，在pool生命周期中会保证至少coreSize个连接。
     * @param maxConnectionSize:最大连接个数，如果coreSize无法满足，pool会新开额外的连接。
     * @param keepAliveTime:额外连接的最大空闲时间。
     * @param sample
     */
    public AbstractConnectionPool(int coreConnectionSize, int maxConnectionSize, long keepAliveTime, Bootstrap sample) {
        this.coreConnectionSize = coreConnectionSize;
        this.maxConnectionSize = maxConnectionSize;
        this.keepAliveTime = keepAliveTime;
        this.sample = sample;
        initialFutureChannelSet = new HashSet<>();
        currentConnectionSize = new AtomicInteger(0);

        hasClosed = new AtomicBoolean(false);

        //留1个线程做idleCheck
        eventLoopGroup = new NioEventLoopGroup(coreConnectionSize + 1);

        //TODO:idleQueue用什么实现？
        idleChannels = new LinkedList<>();

        channelIdFutureChannelMap = new ConcurrentHashMap<>();

        if (keepAliveTime != 0) {
            eventLoopGroup.scheduleAtFixedRate(new IdleCheckingTask(), 0, keepAliveTime / 3, TimeUnit.MILLISECONDS);
        }

        prepare(coreConnectionSize);
    }

    /**
     * 多线程情况下：
     * 1.prepare(a)+prepare(b):合作prepare直到个数达到max(core,a,b)。
     *
     * @param number
     */
    @Override
    public void prepare(int number) {
        if (currentConnectionSize.get() >= number) {
            return;
        }
        if (number < coreConnectionSize) {
            number = coreConnectionSize;
        }
        int c;
        while ((c = currentConnectionSize.get()) <= number) {
            if (currentConnectionSize.compareAndSet(c, c + 1)) {
                //create and initialize a new connection
                FutureChannel channel = newFutureChannel();
                idleChannels.add(channel);
            }
        }
        printState();
    }

    /**
     * 预连接并创建一个新的futureChannel
     *
     * @return
     */
    private FutureChannel newFutureChannel() {
        printState();
        logger.debug("new futureChannel");
        ChannelFuture future = sample.clone()
                .group(eventLoopGroup)
                .connect();
        return new FutureChannel(future);
    }

    /**
     * 返回一个channelFuture，为了异步
     * 1. lease+shrink:poll出的channel刚好是要释放的那个~释放前检查state
     *
     * @return:完成连接或还未完成连接的channelFuture
     */
    @Override
    public ChannelFuture lease() {
        logger.debug("lease channel");
        printState();
        FutureChannel channel = idleChannels.poll();
        if (channel != null) {
            boolean closed = hasClosed.get();
            if (closed) {
                throw new RuntimeException("Pool has closed.");
            } else {
                channel.onLeased();
                return channel.channelFuture;
            }
        }
        //idleQueue is empty or may not empty(lease by another thread)
        int currentSize = currentConnectionSize.get();
        if (currentSize < maxConnectionSize) {
            int newSize = currentConnectionSize.incrementAndGet();
            if (newSize > maxConnectionSize) {
                currentConnectionSize.decrementAndGet();
                return null;
            }
            FutureChannel newChannel = newFutureChannel();
            newChannel.onLeased();
            return newChannel.channelFuture;
        } else {
            //size exceeds the maxSize
            return null;
        }
    }

    @Override
    public void revert(Channel channel) {
        logger.debug("revert channel");
        printState();
        FutureChannel futureChannel = channelIdFutureChannelMap.get(channel.id());
        idleChannels.add(futureChannel);
        futureChannel.onReverted();
    }

    /**
     * 关闭这个pool，但已经借出去的channnel可以不关闭
     */
    @Override
    public void close() {
        logger.debug("close pool");
        printState();
        boolean closed;
        if (!(closed = hasClosed.get())) {
            if (hasClosed.compareAndSet(closed, !closed)) {
                //此后，不能lease，但可以revert，connection还能正常运转
                idleChannels.clear();
            } else {
                throw new RuntimeException("Close the pool multiple times from multiple threads.");
            }
        } else {
            throw new RuntimeException("Close the pool multiple times.");
        }
    }

    /**
     * shutdown，如果没有close，则关闭close
     * 多个线程同时shutdown():依托concurrentHashMap的values()的视图特性，合作shutdown
     */
    @Override
    public void shutdown() {
        logger.debug("shutdown pool");
        printState();
        if (!hasClosed.get()) {
            close();
        }
        Iterator<FutureChannel> iterator = channelIdFutureChannelMap.values().iterator();
        while (iterator.hasNext()) {
            FutureChannel channel = iterator.next();
            channel.channel.closeFuture();
            channel.onClosed();
            iterator.remove();
        }
        channelIdFutureChannelMap.clear();
        eventLoopGroup.shutdownGracefully();
    }

    private void sizeShrink() {
        logger.debug("close channel idled too much time");
        printState();
        Iterator<FutureChannel> iterator = channelIdFutureChannelMap.values().iterator();
        while (iterator.hasNext()) {
            FutureChannel channel = iterator.next();
            if (currentConnectionSize.get() <= coreConnectionSize) {
                break;
            }
            if (channel.state.get().equals(ConnectionState.IDLE) &&
                    channel.idleTooMuch() &&
                    channel.state.compareAndSet(ConnectionState.IDLE, ConnectionState.CLOSING)) {
                channel.onClosed();
                iterator.remove();
                currentConnectionSize.decrementAndGet();
            }
        }
    }

    private void printState() {
        logger.debug(this.toString());
    }

    @Override
    public String toString() {
        return String.format("Pool[total:%d,initial:%d,idle:%d]", channelIdFutureChannelMap.size(), initialFutureChannelSet.size(), idleChannels.size());
    }

    enum ConnectionState {
        INITIAL, IDLE, BUSY, CLOSING
    }

    class IdleCheckingTask implements Runnable {
        @Override
        public void run() {
            sizeShrink();
        }
    }

    public class FutureChannel {

        private Channel channel;
        private ChannelFuture channelFuture;
        private AtomicReference<ConnectionState> state;

        //兜底的
        private long lastRevertTime = System.currentTimeMillis();

        public FutureChannel(ChannelFuture channelFuture) {
            this.channelFuture = channelFuture;
            state = new AtomicReference<>(ConnectionState.INITIAL);
            channelFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    channel = future.channel();
                    //针对先lease再future.complete的情况
                    if (state.get().equals(ConnectionState.INITIAL)) {
                        onReverted();
                    } else {
                        //state==busy,then just let it to be BUSY
                    }
                    channelIdFutureChannelMap.put(channel.id(), FutureChannel.this);
                    initialFutureChannelSet.remove(FutureChannel.this);
                }
            });
            initialFutureChannelSet.add(this);
        }

        boolean idleTooMuch() {
            return System.currentTimeMillis() - lastRevertTime >= keepAliveTime;
        }

        void onLeased() {
            state.set(ConnectionState.BUSY);
        }

        void onReverted() {
            lastRevertTime = System.currentTimeMillis();
            state.set(ConnectionState.IDLE);
        }

        void onClosed() {
            logger.debug("close the " + this);
            state.set(ConnectionState.CLOSING);
        }

        @Override
        public int hashCode() {
            return channelFuture.hashCode();
        }

        @Override
        public String toString() {
            return String.format("Channel[state:%s,idle:%d]", state.get().name(), System.currentTimeMillis() - lastRevertTime);
        }
    }
}
