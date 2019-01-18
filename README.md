# 一个简单的TCP连接池

基于Netty实现的TCP连接池。

## Example

```java
Bootstrap b = new Bootstrap()
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {

                    }
                })
                .remoteAddress(HOST, PORT);
ConnectionPool pool = ConnectionPools.newCachedConnectionPool(b);
ChannelFuture channelFuture = pool.lease();
Channel channel = channelFuture.sync().channel();
//......
//Some operations on the channel
//Like:channel().write()
//......
pool.revert(channel);
pool.shutdown();
```