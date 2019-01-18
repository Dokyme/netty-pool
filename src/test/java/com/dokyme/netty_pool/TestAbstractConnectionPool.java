package com.dokyme.netty_pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TestAbstractConnectionPool {

    static final String HOST = System.getProperty("host", "127.0.0.1");
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {

        }
    }

    public Bootstrap setupBootstrap() {
        Bootstrap b = new Bootstrap();
        b
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                    }
                })
                .remoteAddress(HOST, PORT);
        return b;
    }

    @Test
    public void testLeaseExceedCoreChannels() throws Exception {
        Bootstrap b = setupBootstrap();
        ConnectionPool pool = new AbstractConnectionPool(10, 20, 5000, b);
        List<ChannelFuture> channelFutures = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            channelFutures.add(pool.lease());
        }
        sleep(10 * 1000);
        for (int i = 0; i < 10; i++) {
            channelFutures.add(pool.lease());
            sleep(1000);
        }
        sleep(20 * 1000);
        pool.shutdown();
    }

    @Test
    public void testLeaseOneChannel() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = setupBootstrap();
            ConnectionPool pool = new AbstractConnectionPool(10, 20, 5000, b);
            ChannelFuture channelFuture1 = pool.lease();
            channelFuture1.sync();
        } finally {
            // Shut down the event loop to terminate all threads.
            group.shutdownGracefully();
        }
    }

}
