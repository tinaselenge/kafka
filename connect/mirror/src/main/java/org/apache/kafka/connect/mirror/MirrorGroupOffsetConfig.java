/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.mirror;

import org.apache.kafka.common.config.ConfigDef;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class MirrorGroupOffsetConfig extends MirrorConnectorConfig {
    protected static final String REFRESH_GROUPS = "refresh.groups";
    protected static final String SYNC_GROUP_OFFSETS = "sync.group.offsets";

    public static final String GROUPS = DefaultGroupFilter.GROUPS_INCLUDE_CONFIG;
    public static final String GROUPS_DEFAULT = DefaultGroupFilter.GROUPS_INCLUDE_DEFAULT;
    private static final String GROUPS_DOC = "Consumer groups to replicate. Supports comma-separated group IDs and regexes.";
    public static final String GROUPS_EXCLUDE = DefaultGroupFilter.GROUPS_EXCLUDE_CONFIG;

    public static final String GROUPS_EXCLUDE_DEFAULT = DefaultGroupFilter.GROUPS_EXCLUDE_DEFAULT;
    private static final String GROUPS_EXCLUDE_DOC = "Exclude groups. Supports comma-separated group IDs and regexes."
            + " Excludes take precedence over includes.";

    protected static final String TASK_ASSIGNED_GROUPS = "task.assigned.groups";

    public static final String CONSUMER_POLL_TIMEOUT_MILLIS = "consumer.poll.timeout.ms";
    private static final String CONSUMER_POLL_TIMEOUT_MILLIS_DOC = "Timeout when polling source cluster.";
    public static final long CONSUMER_POLL_TIMEOUT_MILLIS_DEFAULT = 1000L;

    public static final String REFRESH_GROUPS_ENABLED = REFRESH_GROUPS + ENABLED_SUFFIX;
    private static final String REFRESH_GROUPS_ENABLED_DOC = "Whether to periodically check for new consumer groups.";
    public static final boolean REFRESH_GROUPS_ENABLED_DEFAULT = true;
    public static final String REFRESH_GROUPS_INTERVAL_SECONDS = REFRESH_GROUPS + INTERVAL_SECONDS_SUFFIX;
    private static final String REFRESH_GROUPS_INTERVAL_SECONDS_DOC = "Frequency of group refresh.";
    public static final long REFRESH_GROUPS_INTERVAL_SECONDS_DEFAULT = 10 * 60;

    public static final String SYNC_GROUP_OFFSETS_INTERVAL_SECONDS = SYNC_GROUP_OFFSETS + INTERVAL_SECONDS_SUFFIX;
    private static final String SYNC_GROUP_OFFSETS_INTERVAL_SECONDS_DOC = "Frequency of consumer group offset sync.";
    public static final long SYNC_GROUP_OFFSETS_INTERVAL_SECONDS_DEFAULT = 60;

    public static final String GROUP_FILTER_CLASS = "group.filter.class";
    private static final String GROUP_FILTER_CLASS_DOC = "GroupFilter to use. Selects consumer groups to replicate.";
    public static final Class<?> GROUP_FILTER_CLASS_DEFAULT = DefaultGroupFilter.class;

    public MirrorGroupOffsetConfig(Map<String, String> props) {
        super(CONNECTOR_CONFIG_DEF, props);
    }

    public MirrorGroupOffsetConfig(ConfigDef configDef, Map<String, String> props) {
        super(configDef, props);
    }

    Duration refreshGroupsInterval() {
        if (getBoolean(REFRESH_GROUPS_ENABLED)) {
            return Duration.ofSeconds(getLong(REFRESH_GROUPS_INTERVAL_SECONDS));
        } else {
            // negative interval to disable
            return Duration.ofMillis(-1);
        }
    }

    GroupFilter groupFilter() {
        return getConfiguredInstance(GROUP_FILTER_CLASS, GroupFilter.class);
    }

    TopicFilter topicFilter() {
        return getConfiguredInstance(TOPIC_FILTER_CLASS, TopicFilter.class);
    }

    Duration syncGroupOffsetsInterval() {
        return Duration.ofSeconds(getLong(SYNC_GROUP_OFFSETS_INTERVAL_SECONDS));
    }

    Map<String, String> taskConfigForConsumerGroups(List<String> groups, int taskIndex) {
        Map<String, String> props = originalsStrings();
        props.put(TASK_ASSIGNED_GROUPS, String.join(",", groups));
        props.put(TASK_INDEX, String.valueOf(taskIndex));
        return props;
    }


    Duration consumerPollTimeout() {
        return Duration.ofMillis(getLong(CONSUMER_POLL_TIMEOUT_MILLIS));
    }

    private static ConfigDef defineMirrorConsumerGroupsConfig(ConfigDef baseConfig) {
        return baseConfig
                .define(
                        CONSUMER_POLL_TIMEOUT_MILLIS,
                        ConfigDef.Type.LONG,
                        CONSUMER_POLL_TIMEOUT_MILLIS_DEFAULT,
                        ConfigDef.Importance.LOW,
                        CONSUMER_POLL_TIMEOUT_MILLIS_DOC)
                .define(
                        GROUPS,
                        ConfigDef.Type.LIST,
                        GROUPS_DEFAULT,
                        ConfigDef.ValidList.anyNonDuplicateValues(true, false),
                        ConfigDef.Importance.HIGH,
                        GROUPS_DOC)
                .define(
                        GROUPS_EXCLUDE,
                        ConfigDef.Type.LIST,
                        GROUPS_EXCLUDE_DEFAULT,
                        ConfigDef.ValidList.anyNonDuplicateValues(true, false),
                        ConfigDef.Importance.HIGH,
                        GROUPS_EXCLUDE_DOC)
                .define(
                        GROUP_FILTER_CLASS,
                        ConfigDef.Type.CLASS,
                        GROUP_FILTER_CLASS_DEFAULT,
                        ConfigDef.Importance.LOW,
                        GROUP_FILTER_CLASS_DOC)
                .define(
                        REFRESH_GROUPS_ENABLED,
                        ConfigDef.Type.BOOLEAN,
                        REFRESH_GROUPS_ENABLED_DEFAULT,
                        ConfigDef.Importance.LOW,
                        REFRESH_GROUPS_ENABLED_DOC)
                .define(
                        REFRESH_GROUPS_INTERVAL_SECONDS,
                        ConfigDef.Type.LONG,
                        REFRESH_GROUPS_INTERVAL_SECONDS_DEFAULT,
                        ConfigDef.Importance.LOW,
                        REFRESH_GROUPS_INTERVAL_SECONDS_DOC)
                .define(
                        SYNC_GROUP_OFFSETS_INTERVAL_SECONDS,
                        ConfigDef.Type.LONG,
                        SYNC_GROUP_OFFSETS_INTERVAL_SECONDS_DEFAULT,
                        ConfigDef.Importance.LOW,
                        SYNC_GROUP_OFFSETS_INTERVAL_SECONDS_DOC)
                .define(
                        TOPIC_FILTER_CLASS,
                        ConfigDef.Type.CLASS,
                        TOPIC_FILTER_CLASS_DEFAULT,
                        ConfigDef.Importance.LOW,
                        TOPIC_FILTER_CLASS_DOC);
    }

    protected static final ConfigDef CONNECTOR_CONFIG_DEF = defineMirrorConsumerGroupsConfig(new ConfigDef(BASE_CONNECTOR_CONFIG_DEF));

    public static void main(String[] args) {
        System.out.println(defineMirrorConsumerGroupsConfig(new ConfigDef()).toHtml(4, config -> "mirror_consumer_groups_" + config));
    }
}
