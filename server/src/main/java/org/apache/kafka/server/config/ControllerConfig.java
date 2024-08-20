package org.apache.kafka.server.config;

import org.apache.kafka.common.config.ConfigDef;

public class ControllerConfig {

    public static void main(String[] args) {
        ConfigDef configDef = new ConfigDef();

        AbstractKafkaConfig.CONFIG_DEF.configKeys().values().stream().forEach( ck -> {
            if (ck.nodeRoles != null && ck.nodeRoles.isController()) {
                configDef.define(ck);
            }
        });

        System.out.println(configDef.toHtml(4, config -> "controllerconfigs_" +  config));
        //    System.out.println(brokerConfigDef.toHtml(4, (config: String) => "brokerconfigs_" + config,
        //      DynamicBrokerConfig.dynamicConfigUpdateModes))
    }
}
