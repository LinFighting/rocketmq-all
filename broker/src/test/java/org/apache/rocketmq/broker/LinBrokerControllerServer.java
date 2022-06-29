package org.apache.rocketmq.broker;

import org.apache.commons.lang3.time.DateUtils;
import org.apache.rocketmq.common.BrokerConfig;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.config.MessageStoreConfig;

public class LinBrokerControllerServer {

    public static void main(String[] args) {
        BrokerController brokerController = null;
        try {
            //设定当前的执行版本
            System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

            final NettyServerConfig nettyServerConfig = new NettyServerConfig();
            nettyServerConfig.setListenPort(10911);

            final BrokerConfig brokerConfig = new BrokerConfig();
            //命名
            brokerConfig.setBrokerName("broker-lin");
            //指定nameserver的地址，执行注册及心跳
            brokerConfig.setNamesrvAddr("127.0.0.1:9876");

            final MessageStoreConfig messageStoreConfig = new MessageStoreConfig();
            messageStoreConfig.setDeleteWhen("04");
            messageStoreConfig.setFileReservedTime(48);
            messageStoreConfig.setFlushDiskType(FlushDiskType.ASYNC_FLUSH);
            messageStoreConfig.setDuplicationEnable(false);

            brokerController = new BrokerController(
                    brokerConfig,
                    nettyServerConfig,
                    new NettyClientConfig(),
                    messageStoreConfig
            );
            brokerController.initialize();
            brokerController.start();

            Thread.sleep(DateUtils.MILLIS_PER_DAY);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (null != brokerController) {
                brokerController.shutdown();
            }
        }
    }
}