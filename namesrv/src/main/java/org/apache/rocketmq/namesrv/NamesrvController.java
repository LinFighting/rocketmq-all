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
package org.apache.rocketmq.namesrv;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.common.Configuration;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.namesrv.kvconfig.KVConfigManager;
import org.apache.rocketmq.namesrv.processor.ClusterTestRequestProcessor;
import org.apache.rocketmq.namesrv.processor.DefaultRequestProcessor;
import org.apache.rocketmq.namesrv.routeinfo.BrokerHousekeepingService;
import org.apache.rocketmq.namesrv.routeinfo.RouteInfoManager;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.common.TlsMode;
import org.apache.rocketmq.remoting.netty.NettyRemotingServer;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.netty.TlsSystemConfig;
import org.apache.rocketmq.srvutil.FileWatchService;


public class NamesrvController {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);
    //nameSrv的配置
    private final NamesrvConfig namesrvConfig;
    //netty的配置
    private final NettyServerConfig nettyServerConfig;
    //执行单线程的任务调度，自定义编程名称
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl(
            "NSScheduledThread"));
    //kv的配置管理
    private final KVConfigManager kvConfigManager;
    //路由管理器，有broker的ip和队列信息，producer发送的queue信息，consumer的pull的queue信息
    private final RouteInfoManager routeInfoManager;
    //namesrv的netty的服务端实现
    private RemotingServer remotingServer;
    // 接收到请求事件的回调监听服务，主要处理netty的事件
    private BrokerHousekeepingService brokerHousekeepingService;

    private ExecutorService remotingExecutor;

    private Configuration configuration;
    private FileWatchService fileWatchService;

    public NamesrvController(NamesrvConfig namesrvConfig, NettyServerConfig nettyServerConfig) {
        // 同时会对里面的一些管理器组件进行创建，供后续使用。
        this.namesrvConfig = namesrvConfig;
        this.nettyServerConfig = nettyServerConfig;
        //构造kv配置的管理
        this.kvConfigManager = new KVConfigManager(this);
        //构造路由信息管理
        this.routeInfoManager = new RouteInfoManager();
        //构造网络连接事件管理
        this.brokerHousekeepingService = new BrokerHousekeepingService(this);
        //配置
        this.configuration = new Configuration(
                log,
                this.namesrvConfig, this.nettyServerConfig
        );
        this.configuration.setStorePathFromConfig(this.namesrvConfig, "configStorePath");
    }

    public boolean initialize() {
        //从持久化文件中加载配置到内存
        this.kvConfigManager.load();
        //本地的namesrv的netty服务
        //毕竟namesrv要作为服务端来接受broker，producer以及consumer的请求的，所以使用之前创建好的配置来创建netty server
        this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.brokerHousekeepingService);
        // 默认请求处理器的线程池
        this.remotingExecutor =
                Executors.newFixedThreadPool(nettyServerConfig.getServerWorkerThreads(), new ThreadFactoryImpl("RemotingExecutorThread_"));
        // 注册默认请求处理器（TODO 重要）
        this.registerProcessor();
        //重要： 每隔10s定时扫描broker状态的，看是否还存活着  定时扫描不可用的broker，同时删除不可用的broker，同时打印相关日志
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                NamesrvController.this.routeInfoManager.scanNotActiveBroker();
            }
        }, 5, 10, TimeUnit.SECONDS);


        // 定期打印kvConfigManager的配置  定时将kv的配置信息输出到info日志中
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                NamesrvController.this.kvConfigManager.printAllPeriodically();
            }
        }, 1, 10, TimeUnit.MINUTES);

        if (TlsSystemConfig.tlsMode != TlsMode.DISABLED) {
            // Register a listener to reload SslContext
            try {
                fileWatchService = new FileWatchService(
                        new String[]{
                                TlsSystemConfig.tlsServerCertPath,
                                TlsSystemConfig.tlsServerKeyPath,
                                TlsSystemConfig.tlsServerTrustCertPath
                        },
                        new FileWatchService.Listener() {
                            boolean certChanged, keyChanged = false;

                            @Override
                            public void onChanged(String path) {
                                if (path.equals(TlsSystemConfig.tlsServerTrustCertPath)) {
                                    log.info("The trust certificate changed, reload the ssl context");
                                    reloadServerSslContext();
                                }
                                if (path.equals(TlsSystemConfig.tlsServerCertPath)) {
                                    certChanged = true;
                                }
                                if (path.equals(TlsSystemConfig.tlsServerKeyPath)) {
                                    keyChanged = true;
                                }
                                if (certChanged && keyChanged) {
                                    log.info("The certificate and private key changed, reload the ssl context");
                                    certChanged = keyChanged = false;
                                    reloadServerSslContext();
                                }
                            }

                            private void reloadServerSslContext() {
                                ((NettyRemotingServer) remotingServer).loadSslContext();
                            }
                        });
            } catch (Exception e) {
                log.warn("FileWatchService created error, can't load the certificate dynamically");
            }
        }

        return true;
    }

    private void registerProcessor() {

        if (namesrvConfig.isClusterTest()) {
            //集群测试配置的处理器
            this.remotingServer.registerDefaultProcessor(new ClusterTestRequestProcessor(this, namesrvConfig.getProductEnvName()),
                    this.remotingExecutor);
        } else {
            //正常模式的处理器
            this.remotingServer.registerDefaultProcessor(new DefaultRequestProcessor(this), this.remotingExecutor);
        }
    }

    public void start() throws Exception {
        // netty服务的启动，核心网络通信
        this.remotingServer.start();

        if (this.fileWatchService != null) {
            this.fileWatchService.start();
        }
    }

    public void shutdown() {
        this.remotingServer.shutdown();
        this.remotingExecutor.shutdown();
        this.scheduledExecutorService.shutdown();

        if (this.fileWatchService != null) {
            this.fileWatchService.shutdown();
        }
    }

    public NamesrvConfig getNamesrvConfig() {
        return namesrvConfig;
    }

    public NettyServerConfig getNettyServerConfig() {
        return nettyServerConfig;
    }

    public KVConfigManager getKvConfigManager() {
        return kvConfigManager;
    }

    public RouteInfoManager getRouteInfoManager() {
        return routeInfoManager;
    }

    public RemotingServer getRemotingServer() {
        return remotingServer;
    }

    public void setRemotingServer(RemotingServer remotingServer) {
        this.remotingServer = remotingServer;
    }

    public Configuration getConfiguration() {
        return configuration;
    }
}
