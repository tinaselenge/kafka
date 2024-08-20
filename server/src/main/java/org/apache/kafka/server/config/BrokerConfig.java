package org.apache.kafka.server.config;

import org.apache.kafka.common.config.ConfigDef;

public class BrokerConfig {

    public static void main(String[] args) {
        ConfigDef configDef = new ConfigDef();

        AbstractKafkaConfig.CONFIG_DEF.configKeys().values().stream().forEach( ck -> {
            if (ck.nodeRoles == null || ck.nodeRoles.isBroker()) {
                configDef.define(ck);
            }
        });

        System.out.println(configDef.toHtml(4, config -> "brokerconfigs_" +  config));
        //    System.out.println(brokerConfigDef.toHtml(4, (config: String) => "brokerconfigs_" + config,
        //      DynamicBrokerConfig.dynamicConfigUpdateModes))
    }

}
