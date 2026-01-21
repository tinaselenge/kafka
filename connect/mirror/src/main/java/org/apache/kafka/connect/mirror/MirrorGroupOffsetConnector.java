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

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.util.ConnectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.kafka.connect.mirror.MirrorUtils.adminCall;

/** Replicate consumer group state between clusters.
 *
 *  @see MirrorGroupOffsetConfig for supported config properties.
 */
public class MirrorGroupOffsetConnector extends SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(MirrorGroupOffsetConnector.class);

    private Scheduler scheduler;
    private MirrorGroupOffsetConfig config;
    private TopicFilter topicFilter;
    private GroupFilter groupFilter;
    private Admin sourceAdminClient;
    private SourceAndTarget sourceAndTarget;
    private Set<String> knownConsumerGroups = null;

    public MirrorGroupOffsetConnector() {
        // nop
    }

    // visible for testing
    MirrorGroupOffsetConnector(Set<String> knownConsumerGroups, MirrorGroupOffsetConfig config) {
        this.knownConsumerGroups = knownConsumerGroups;
        this.config = config;
    }

    @Override
    public void start(Map<String, String> props) {
        config = new MirrorGroupOffsetConfig(props);
        if (!config.enabled()) {
            return;
        }
        sourceAndTarget = new SourceAndTarget(config.sourceClusterAlias(), config.targetClusterAlias());
        topicFilter = config.topicFilter();
        groupFilter = config.groupFilter();
        sourceAdminClient = config.forwardingAdmin(config.sourceAdminConfig("consumer-groups-source-admin"));

        scheduler = new Scheduler(getClass(), config.entityLabel(), config.adminTimeout());
        scheduler.execute(this::loadInitialConsumerGroups, "loading initial consumer groups");
        scheduler.scheduleRepeatingDelayed(this::refreshConsumerGroups, config.refreshGroupsInterval(),
                "refreshing consumer groups");
    }

    @Override
    public void stop() {
        if (!config.enabled()) {
            return;
        }
        Utils.closeQuietly(scheduler, "scheduler");
        Utils.closeQuietly(topicFilter, "topic filter");
        Utils.closeQuietly(groupFilter, "group filter");
        Utils.closeQuietly(sourceAdminClient, "source admin client");
    }

    @Override
    public Class<? extends Task> taskClass() {
        return MirrorGroupOffsetTask.class;
    }

    // divide consumer groups among tasks
    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        // If the replication is disabled, no 'MirrorConsumerGroupsTask' will be created.
        if (!config.enabled()) {
            return List.of();
        }

        if (knownConsumerGroups == null) {
            // If knownConsumerGroup is null, it means the initial loading has not finished.
            // An exception should be thrown to trigger the retry behavior in the framework.
            log.debug("Initial consumer loading has not yet completed");
            throw new RetriableException("Timeout while loading consumer groups.");
        }

        // If the consumer group is empty, no 'MirrorConsumerGroupsTask' will be created.
        if (knownConsumerGroups.isEmpty()) {
            return List.of();
        }

        int numTasks = Math.min(maxTasks, knownConsumerGroups.size());
        List<List<String>> groupsPartitioned = ConnectorUtils.groupPartitions(new ArrayList<>(knownConsumerGroups), numTasks);
        return IntStream.range(0, numTasks)
                .mapToObj(i -> config.taskConfigForConsumerGroups(groupsPartitioned.get(i), i))
                .collect(Collectors.toList());
    }

    @Override
    public ConfigDef config() {
        return MirrorGroupOffsetConfig.CONNECTOR_CONFIG_DEF;
    }

    @Override
    public String version() {
        return AppInfoParser.getVersion();
    }

    private void refreshConsumerGroups()
            throws InterruptedException, ExecutionException {
        // If loadInitialConsumerGroups fails for any reason(e.g., timeout), knownConsumerGroups may be null.
        // We still want this method to recover gracefully in such cases.
        Set<String> knownConsumerGroups = this.knownConsumerGroups == null ? Set.of() : this.knownConsumerGroups;
        Set<String> consumerGroups = findConsumerGroups();
        Set<String> newConsumerGroups = new HashSet<>(consumerGroups);
        newConsumerGroups.removeAll(knownConsumerGroups);
        Set<String> deadConsumerGroups = new HashSet<>(knownConsumerGroups);
        deadConsumerGroups.removeAll(consumerGroups);
        if (!newConsumerGroups.isEmpty() || !deadConsumerGroups.isEmpty()) {
            log.info("Found {} consumer groups for {}. {} are new. {} were removed. Previously had {}.",
                    consumerGroups.size(), sourceAndTarget, newConsumerGroups.size(), deadConsumerGroups.size(),
                    knownConsumerGroups.size());
            log.debug("Found new consumer groups: {}", newConsumerGroups);
            this.knownConsumerGroups = consumerGroups;
            context.requestTaskReconfiguration();
        }
    }

    private void loadInitialConsumerGroups()
            throws InterruptedException, ExecutionException {
        String connectorName = config.connectorName();
        knownConsumerGroups = findConsumerGroups();
        log.info("Started {} with {} consumer groups.", connectorName, knownConsumerGroups.size());
        log.debug("Started {} with consumer groups: {}", connectorName, knownConsumerGroups);
    }

    Set<String> findConsumerGroups()
            throws InterruptedException, ExecutionException {
        List<String> filteredGroups = listConsumerGroups().stream()
                .map(GroupListing::groupId)
                .filter(this::shouldReplicateByGroupFilter)
                .collect(Collectors.toList());

        Set<String> consumerGroupsToSync = new HashSet<>();
        Set<String> irrelevantGroups = new HashSet<>();

        Map<String, Map<TopicPartition, OffsetAndMetadata>> groupToOffsets = listConsumerGroupOffsets(filteredGroups);
        for (String group : filteredGroups) {
            Set<String> consumedTopics = groupToOffsets.get(group).keySet().stream()
                    .map(TopicPartition::topic)
                    .filter(this::shouldReplicateByTopicFilter)
                    .collect(Collectors.toSet());
            // Only sync consumer groups that have offsets for at least one topic that's accepted
            // by the topic filter.
            if (consumedTopics.isEmpty()) {
                irrelevantGroups.add(group);
            } else {
                consumerGroupsToSync.add(group);
            }
        }

        log.debug("Ignoring the following groups which do not have any offsets for topics that are accepted by " +
                        "the topic filter: {}", irrelevantGroups);
        return consumerGroupsToSync;
    }

    Collection<GroupListing> listConsumerGroups()
            throws InterruptedException, ExecutionException {
        return adminCall(
                () -> sourceAdminClient.listGroups(ListGroupsOptions.forConsumerGroups()).valid().get(),
                () -> "list consumer groups on " + config.sourceClusterAlias() + " cluster"
        );
    }

    Map<String, Map<TopicPartition, OffsetAndMetadata>> listConsumerGroupOffsets(List<String> groups)
            throws InterruptedException, ExecutionException {
        ListConsumerGroupOffsetsSpec groupOffsetsSpec = new ListConsumerGroupOffsetsSpec();
        Map<String, ListConsumerGroupOffsetsSpec> groupSpecs = groups.stream()
                .collect(Collectors.toMap(group -> group, group -> groupOffsetsSpec));
        return adminCall(
                () -> sourceAdminClient.listConsumerGroupOffsets(groupSpecs).all().get(),
                () -> String.format("list offsets for consumer groups %s on %s cluster", groups, config.sourceClusterAlias())
        );
    }

    boolean shouldReplicateByGroupFilter(String group) {
        return groupFilter.shouldReplicateGroup(group);
    }

    boolean shouldReplicateByTopicFilter(String topic) {
        return topicFilter.shouldReplicateTopic(topic);
    }
}
