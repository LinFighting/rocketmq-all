package org.apache.rocketmq.namesrv;

import org.apache.commons.lang3.time.DateUtils;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;

public class LinNameServerInstance {

    public static void main(String[] args) {
        NamesrvController namesrvController = null;
        try {
            final NamesrvConfig namesrvConfig = new NamesrvConfig();

            final NettyServerConfig nettyServerConfig = new NettyServerConfig();
            //设定自定义的端口
            nettyServerConfig.setListenPort(9876);

            namesrvController = new NamesrvController(namesrvConfig, nettyServerConfig);
            namesrvController.initialize();
            namesrvController.start();

            Thread.sleep(DateUtils.MILLIS_PER_DAY);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (null != namesrvController) {
                namesrvController.shutdown();
            }
        }
    }
}