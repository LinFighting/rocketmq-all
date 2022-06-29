/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.remoting.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.ChannelEventListener;
import org.apache.rocketmq.remoting.InvokeCallback;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.common.Pair;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.remoting.common.TlsMode;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public class NettyRemotingServer extends NettyRemotingAbstract implements RemotingServer {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);
    private final ServerBootstrap serverBootstrap;
    private final EventLoopGroup eventLoopGroupSelector;
    private final EventLoopGroup eventLoopGroupBoss;
    private final NettyServerConfig nettyServerConfig;

    private final ExecutorService publicExecutor;
    private final ChannelEventListener channelEventListener;

    private final Timer timer = new Timer("ServerHouseKeepingService", true);
    private DefaultEventExecutorGroup defaultEventExecutorGroup;


    private int port = 0;

    private static final String HANDSHAKE_HANDLER_NAME = "handshakeHandler";
    private static final String TLS_HANDLER_NAME = "sslHandler";
    private static final String FILE_REGION_ENCODER_NAME = "fileRegionEncoder";

    // sharable handlers
    private HandshakeHandler handshakeHandler;
    private NettyEncoder encoder;
    private NettyConnectManageHandler connectionManageHandler;
    private NettyServerHandler serverHandler;

    public NettyRemotingServer(final NettyServerConfig nettyServerConfig) {
        this(nettyServerConfig, null);
    }


    /**
     * Server端在启动阶段会将之前实例化好的1个acceptor线程（eventLoopGroupBoss），
     * N个IO线程（eventLoopGroupSelector），
     * M1个worker 线程（defaultEventExecutorGroup）绑定
     */
    public NettyRemotingServer(final NettyServerConfig nettyServerConfig,
                               final ChannelEventListener channelEventListener) {
        //首先调用父类构造函数，传入参数为oneway（客户端单向访问无返回）以及客户端异步访问两种请求类型的并发数，这里不做过多介绍
        super(nettyServerConfig.getServerOnewaySemaphoreValue(), nettyServerConfig.getServerAsyncSemaphoreValue());

        //熟悉的Netty服务端启动类
        this.serverBootstrap = new ServerBootstrap();
        this.nettyServerConfig = nettyServerConfig;
        this.channelEventListener = channelEventListener;

        //publicThreadNums则定义线程池publicExecutor线程池线程数量
        int publicThreadNums = nettyServerConfig.getServerCallbackExecutorThreads();
        if (publicThreadNums <= 0) {
            publicThreadNums = 4;
        }

        //从配置中获取服务端事件处理线程池线程数量，注意这个线程池和Netty中的线程池没有关系，Netty线程读到网络事件之后，会调用
        //RocketMQ自定义的Processor进行逻辑处理，Processor的逻辑处理会被封装为Runnable放入下面的publicExecutor线程池。
        this.publicExecutor = Executors.newFixedThreadPool(publicThreadNums, new ThreadFactory() {
            private AtomicInteger threadIndex = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "NettyServerPublicExecutor_" + this.threadIndex.incrementAndGet());
            }
        });

        /**
         * 根据配置设置NIO还是Epoll来作为Selector线程池
         * 如果是Linux平台，并且开启了native epoll，就用EpollEventLoopGroup；否则，就用Java NIO的NioEventLoopGroup。
         */
        if (useEpoll()) {
            //初始化时候nThreads设置为1,说明RemotingServer端的Disptacher链接管理和分发请求的线程为1,用于接收客户端的TCP连接
            this.eventLoopGroupBoss = new EpollEventLoopGroup(1, new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyEPOLLBoss_%d", this.threadIndex.incrementAndGet()));
                }
            });

            this.eventLoopGroupSelector = new EpollEventLoopGroup(nettyServerConfig.getServerSelectorThreads(), new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);
                private int threadTotal = nettyServerConfig.getServerSelectorThreads();

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyServerEPOLLSelector_%d_%d", threadTotal, this.threadIndex.incrementAndGet()));
                }
            });
        } else {

            //初始化时候nThreads设置为1,说明RemotingServer端的Disptacher链接管理和分发请求的线程为1,用于接收客户端的TCP连接
            this.eventLoopGroupBoss = new NioEventLoopGroup(1, new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyNIOBoss_%d", this.threadIndex.incrementAndGet()));
                }
            });

            this.eventLoopGroupSelector = new NioEventLoopGroup(nettyServerConfig.getServerSelectorThreads(), new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);
                private int threadTotal = nettyServerConfig.getServerSelectorThreads();

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyServerNIOSelector_%d_%d", threadTotal, this.threadIndex.incrementAndGet()));
                }
            });
        }

        //有关安全套接字的上下文加载
        loadSslContext();
    }

    public void loadSslContext() {
        TlsMode tlsMode = TlsSystemConfig.tlsMode;
        log.info("Server is running in TLS {} mode", tlsMode.getName());

        if (tlsMode != TlsMode.DISABLED) {
            try {
                sslContext = TlsHelper.buildSslContext(false);
                log.info("SSLContext created for server");
            } catch (CertificateException e) {
                log.error("Failed to create SSLContext for server", e);
            } catch (IOException e) {
                log.error("Failed to create SSLContext for server", e);
            }
        }
    }

    private boolean useEpoll() {
        return RemotingUtil.isLinuxPlatform()
                && nettyServerConfig.isUseEpollNativeSelector()
                && Epoll.isAvailable();
    }

    @Override
    public void start() {
        //默认的处理线程池组,使用默认的处理线程池组用于处理后面的多个Netty Handler的逻辑操作
        this.defaultEventExecutorGroup = new DefaultEventExecutorGroup(
                nettyServerConfig.getServerWorkerThreads(),
                new ThreadFactory() {

                    private AtomicInteger threadIndex = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "NettyServerCodecThread_" + this.threadIndex.incrementAndGet());
                    }
                });

        prepareSharableHandlers();
        /**
         * RocketMQ NettyServer 的 Reactor 线程模型，
         * 1、一个 Reactor 主线程负责监听 TCP 连接请求，建立好连接后丢给 Reactor 线程池，它负责将建立好连接的 socket 注册到 selector
         * 2、selector然后监听真正的网络数据（这里有两种方式，NIO和Epoll，可配置），拿到网络数据后，再丢给 Worker 线程池;
         *
         */
        //RocketMQ-> Java NIO的1+N+M模型：1个acceptor线程，N个IO线程，M1个worker 线程。
        ServerBootstrap childHandler =
                this.serverBootstrap.group(this.eventLoopGroupBoss, this.eventLoopGroupSelector)
                        // 设置channel通道的类型
                        .channel(useEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                        //socket参数，服务端接受客户端链接的队列长度，如果队列已满则拒绝，默认值较小
                        .option(ChannelOption.SO_BACKLOG, 1024)
                        //socket参数，地址复用，默认false，快速启动的操作更优
                        .option(ChannelOption.SO_REUSEADDR, true)
                        //socket参数，连接保持，默认false，tcp会主动探测连接的有效性，可理解为心跳
                        .option(ChannelOption.SO_KEEPALIVE, false)
                        //tcp参数，立即发送数据，netty默认true，系统默认false，如果选择带宽的性能可以设置为false，一次发送多个数据款
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        //socket参数，以下这两个参数用于操作发送、接收缓冲区大小
                        .childOption(ChannelOption.SO_SNDBUF, nettyServerConfig.getServerSocketSndBufSize())
                        .childOption(ChannelOption.SO_RCVBUF, nettyServerConfig.getServerSocketRcvBufSize())
                        //绑定当前服务器及端口
                        .localAddress(new InetSocketAddress(this.nettyServerConfig.getListenPort()))
//                       // 设置子通道也就是SocketChannel的处理器， 其内部是实际业务开发的"主战场"
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel ch) throws Exception {
                                ch.pipeline()
                                        .addLast(defaultEventExecutorGroup, HANDSHAKE_HANDLER_NAME, handshakeHandler)
                                        .addLast(defaultEventExecutorGroup,
                                                encoder,//rocketmq编码器 通过设置的自定义的 NettyEncoder对发送的消息进行编码（序列化）。
                                                new NettyDecoder(),//通过NettyDecoder 对接收的消息进行解码操作（反序列化）
                                                // 此处指定的编码和解码器，他们分别覆盖了父类的encode和decode方法
                                                new IdleStateHandler(0, 0, nettyServerConfig.getServerChannelMaxIdleTimeSeconds()),//Netty自带的心跳管理器
                                                connectionManageHandler,//连接管理器，他负责捕获新连接、连接断开、异常等事件，然后统一调度到NettyEventExecuter处理器处理。
                                                serverHandler //当一个消息经过前面的解码等步骤后，然后调度到channelRead0方法，然后根据消息类型进行分发
                                        );
                            }
                        });

        //buf的分配器设置
        if (nettyServerConfig.isServerPooledByteBufAllocatorEnable()) {
            childHandler.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        }

        //启动当前的服务 （ 创建channel，绑定端口，等待返回）
        try {
            ChannelFuture sync = this.serverBootstrap.bind().sync();
            InetSocketAddress addr = (InetSocketAddress) sync.channel().localAddress();
            this.port = addr.getPort();
        } catch (InterruptedException e1) {
            throw new RuntimeException("this.serverBootstrap.bind().sync() InterruptedException", e1);
        }

        //namesrv端的事件回调处理机制，会调用channelEventListener的事件处理
        if (this.channelEventListener != null) {
            this.nettyEventExecutor.start();
        }

        //定时扫描responseTable,获取返回结果,并且处理超时
        this.timer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                try {
                    NettyRemotingServer.this.scanResponseTable();
                } catch (Throwable e) {
                    log.error("scanResponseTable exception", e);
                }
            }
        }, 1000 * 3, 1000);
    }

    @Override
    public void shutdown() {
        try {
            if (this.timer != null) {
                this.timer.cancel();
            }

            this.eventLoopGroupBoss.shutdownGracefully();

            this.eventLoopGroupSelector.shutdownGracefully();

            if (this.nettyEventExecutor != null) {
                this.nettyEventExecutor.shutdown();
            }

            if (this.defaultEventExecutorGroup != null) {
                this.defaultEventExecutorGroup.shutdownGracefully();
            }
        } catch (Exception e) {
            log.error("NettyRemotingServer shutdown exception, ", e);
        }

        if (this.publicExecutor != null) {
            try {
                this.publicExecutor.shutdown();
            } catch (Exception e) {
                log.error("NettyRemotingServer shutdown exception, ", e);
            }
        }
    }

    @Override
    public void registerRPCHook(RPCHook rpcHook) {
        if (rpcHook != null && !rpcHooks.contains(rpcHook)) {
            rpcHooks.add(rpcHook);
        }
    }

    @Override
    public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
        ExecutorService executorThis = executor;
        if (null == executor) {
            executorThis = this.publicExecutor;
        }

        Pair<NettyRequestProcessor, ExecutorService> pair = new Pair<NettyRequestProcessor, ExecutorService>(processor, executorThis);
        this.processorTable.put(requestCode, pair);
    }

    @Override
    public void registerDefaultProcessor(NettyRequestProcessor processor, ExecutorService executor) {
        this.defaultRequestProcessor = new Pair<NettyRequestProcessor, ExecutorService>(processor, executor);
    }

    @Override
    public int localListenPort() {
        return this.port;
    }

    @Override
    public Pair<NettyRequestProcessor, ExecutorService> getProcessorPair(int requestCode) {
        return processorTable.get(requestCode);
    }

    @Override
    public RemotingCommand invokeSync(final Channel channel, final RemotingCommand request, final long timeoutMillis)
            throws InterruptedException, RemotingSendRequestException, RemotingTimeoutException {
        return this.invokeSyncImpl(channel, request, timeoutMillis);
    }

    @Override
    public void invokeAsync(Channel channel, RemotingCommand request, long timeoutMillis, InvokeCallback invokeCallback)
            throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        this.invokeAsyncImpl(channel, request, timeoutMillis, invokeCallback);
    }

    @Override
    public void invokeOneway(Channel channel, RemotingCommand request, long timeoutMillis) throws InterruptedException,
            RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        this.invokeOnewayImpl(channel, request, timeoutMillis);
    }

    @Override
    public ChannelEventListener getChannelEventListener() {
        return channelEventListener;
    }


    @Override
    public ExecutorService getCallbackExecutor() {
        return this.publicExecutor;
    }

    private void prepareSharableHandlers() {
        handshakeHandler = new HandshakeHandler(TlsSystemConfig.tlsMode);
        encoder = new NettyEncoder();
        // 连接管理器，他负责捕获新连接、连接断开、异常等事件，然后统一调度到NettyEventExecuter处理器处理。
        connectionManageHandler = new NettyConnectManageHandler();
        serverHandler = new NettyServerHandler();
    }

    @ChannelHandler.Sharable
    class HandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private final TlsMode tlsMode;

        private static final byte HANDSHAKE_MAGIC_CODE = 0x16;

        HandshakeHandler(TlsMode tlsMode) {
            this.tlsMode = tlsMode;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {

            // mark the current position so that we can peek the first byte to determine if the content is starting with
            // TLS handshake
            msg.markReaderIndex();

            byte b = msg.getByte(0);

            if (b == HANDSHAKE_MAGIC_CODE) {
                switch (tlsMode) {
                    case DISABLED:
                        ctx.close();
                        log.warn("Clients intend to establish an SSL connection while this server is running in SSL disabled mode");
                        break;
                    case PERMISSIVE:
                    case ENFORCING:
                        if (null != sslContext) {
                            ctx.pipeline()
                                    .addAfter(defaultEventExecutorGroup, HANDSHAKE_HANDLER_NAME, TLS_HANDLER_NAME, sslContext.newHandler(ctx.channel().alloc()))
                                    .addAfter(defaultEventExecutorGroup, TLS_HANDLER_NAME, FILE_REGION_ENCODER_NAME, new FileRegionEncoder());
                            log.info("Handlers prepended to channel pipeline to establish SSL connection");
                        } else {
                            ctx.close();
                            log.error("Trying to establish an SSL connection but sslContext is null");
                        }
                        break;

                    default:
                        log.warn("Unknown TLS mode");
                        break;
                }
            } else if (tlsMode == TlsMode.ENFORCING) {
                ctx.close();
                log.warn("Clients intend to establish an insecure connection while this server is running in SSL enforcing mode");
            }

            // reset the reader index so that handshake negotiation may proceed as normal.
            msg.resetReaderIndex();

            try {
                // Remove this handler
                ctx.pipeline().remove(this);
            } catch (NoSuchElementException e) {
                log.error("Error while removing HandshakeHandler", e);
            }

            // Hand over this message to the next .
            ctx.fireChannelRead(msg.retain());
        }
    }

    @ChannelHandler.Sharable
    class NettyServerHandler extends SimpleChannelInboundHandler<RemotingCommand> {
        //读取网络请求
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RemotingCommand msg) throws Exception {
            processMessageReceived(ctx, msg);
        }
    }

    /**
     * 将重点对服务的注册，链接激活，链接关闭，链接的检测，链接的操作异常进行管理
     * 链接激活：如果本机的监听存在则设置到永久循环队列中，之间内部业务管理，事件为链接
     * 激活关闭：如果本机的监听存在则设置到永久循环队列中，之间内部业务管理，事件为关闭
     * 链接检测：如果本机的监听存在则设置到永久循环队列中，之间内部业务管理，事件为检测
     * 异常操作：如果本机的监听存在则设置到永久循环队列中，之间内部业务管理，事件为异常
     * 底层是基于无限循环的操作队列内容，对事件进行操作，基于接口设计，不同的服务端有不同的业务需求
     */
    @ChannelHandler.Sharable
    class NettyConnectManageHandler extends ChannelDuplexHandler {
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelRegistered {}", remoteAddress);
            super.channelRegistered(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelUnregistered, the channel[{}]", remoteAddress);
            super.channelUnregistered(ctx);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelActive, the channel[{}]", remoteAddress);
            super.channelActive(ctx);
            //处理连接的事件
            if (NettyRemotingServer.this.channelEventListener != null) {
                NettyRemotingServer.this.putNettyEvent(new NettyEvent(NettyEventType.CONNECT, remoteAddress, ctx.channel()));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelInactive, the channel[{}]", remoteAddress);
            super.channelInactive(ctx);
            //处理连接关闭的事件
            if (NettyRemotingServer.this.channelEventListener != null) {
                NettyRemotingServer.this.putNettyEvent(new NettyEvent(NettyEventType.CLOSE, remoteAddress, ctx.channel()));
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent event = (IdleStateEvent) evt;
                if (event.state().equals(IdleState.ALL_IDLE)) {
                    final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
                    log.warn("NETTY SERVER PIPELINE: IDLE exception [{}]", remoteAddress);
                    RemotingUtil.closeChannel(ctx.channel());
                    //处理检测的事件
                    if (NettyRemotingServer.this.channelEventListener != null) {
                        NettyRemotingServer.this
                                .putNettyEvent(new NettyEvent(NettyEventType.IDLE, remoteAddress, ctx.channel()));
                    }
                }
            }

            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.warn("NETTY SERVER PIPELINE: exceptionCaught {}", remoteAddress);
            log.warn("NETTY SERVER PIPELINE: exceptionCaught exception.", cause);
            //处理异常的事件
            if (NettyRemotingServer.this.channelEventListener != null) {
                NettyRemotingServer.this.putNettyEvent(new NettyEvent(NettyEventType.EXCEPTION, remoteAddress, ctx.channel()));
            }

            RemotingUtil.closeChannel(ctx.channel());
        }
    }
}
