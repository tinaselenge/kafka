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
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownMemberIdException;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.apache.kafka.connect.mirror.MirrorUtils.adminCall;

/** Syncs consumer group offsets from source to target */
public class MirrorGroupOffsetTask extends SourceTask {

    private static final Logger log = LoggerFactory.getLogger(MirrorGroupOffsetTask.class);

    private Admin sourceAdminClient;
    private Admin targetAdminClient;
    private KafkaConsumer<byte[], byte[]> sourceConsumer;
    private String sourceClusterAlias;
    private String targetClusterAlias;
    private Duration pollTimeout;
    private TopicFilter topicFilter;
    private Set<String> groups;
    private boolean stopping;
    private MirrorGroupOffsetMetrics metrics;
    private Scheduler scheduler;
    private Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset;

    public MirrorGroupOffsetTask() {}

    // for testing
    MirrorGroupOffsetTask(String sourceClusterAlias, String targetClusterAlias,
                          Set<String> groups,
                          Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset) {
        this.sourceClusterAlias = sourceClusterAlias;
        this.targetClusterAlias = targetClusterAlias;
        this.groups = groups;
        this.idleConsumerGroupsOffset = idleConsumerGroupsOffset;
        this.topicFilter = topic -> true;
        this.pollTimeout = Duration.ofNanos(1);
    }

    @Override
    public void start(Map<String, String> props) {
        MirrorGroupOffsetTaskConfig config = new MirrorGroupOffsetTaskConfig(props);
        stopping = false;
        sourceClusterAlias = config.sourceClusterAlias();
        targetClusterAlias = config.targetClusterAlias();
        groups = config.taskAssignedGroups();
        topicFilter = config.topicFilter();
        pollTimeout = config.consumerPollTimeout();
        sourceAdminClient = config.forwardingAdmin(config.sourceAdminConfig("mirror-group-offset-source-admin"));
        targetAdminClient = config.forwardingAdmin(config.targetAdminConfig("mirror-group-offset-target-admin"));
        sourceConsumer = MirrorUtils.newConsumer(config.sourceConsumerConfig("mirror-group-offset-consumer", Map.of(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 1024)));
        metrics = config.metrics();
        idleConsumerGroupsOffset = new HashMap<>();
        scheduler = new Scheduler(getClass(), config.entityLabel(), config.adminTimeout());
        scheduler.executeAsync(() -> {
            scheduler.scheduleRepeating(this::refreshIdleConsumerGroupOffset, config.syncGroupOffsetsInterval(),
                    "refreshing idle group offsets at target cluster");
            scheduler.scheduleRepeatingDelayed(this::syncGroupOffsets, config.syncGroupOffsetsInterval(),
                    "sync idle group offset from source to target");
        }, "starting group offset sync");
        log.info("{} syncing {} group offsets {}->{}: {}.", Thread.currentThread().getName(),
                groups.size(), sourceClusterAlias, config.targetClusterAlias(), groups);
    }

    @Override
    public void commit() {
        // nop
    }

    @Override
    public void stop() {
        long start = System.currentTimeMillis();
        stopping = true;
        Utils.closeQuietly(topicFilter, "topic filter");
        Utils.closeQuietly(sourceAdminClient, "source admin client");
        Utils.closeQuietly(targetAdminClient, "target admin client");
        Utils.closeQuietly(sourceConsumer, "source consumer");
        Utils.closeQuietly(metrics, "metrics");
        Utils.closeQuietly(scheduler, "scheduler");
        log.info("Stopping {} took {} ms.", Thread.currentThread().getName(), System.currentTimeMillis() - start);
    }

    @Override
    public String version() {
        return new MirrorGroupOffsetConnector().version();
    }

    Map<TopicPartition, OffsetAndMetadata> listConsumerGroupOffsets(String group)
            throws InterruptedException, ExecutionException {
        if (stopping) {
            // short circuit if stopping
            return Map.of();
        }
        return adminCall(
                () -> sourceAdminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(),
                () -> String.format("list offsets for consumer group %s on %s cluster", group, sourceClusterAlias)
        );
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        // we don't do anything here.
        return null;
    }


    private Map<TopicPartition, OffsetAndMetadata> translateOffset(String group, Map<TopicPartition, OffsetAndMetadata> upstreamGroupOffsets) {
        Map<TopicPartition, OffsetAndMetadata> translatedOffsets = new HashMap<>();
        log.debug("Getting timestamps of {} group on source cluster {}", group, sourceClusterAlias);
        Map<TopicPartition, Long> offsetsToFetch = new HashMap<>();
        upstreamGroupOffsets.forEach((tp, offsetAndMetadata) -> {
            // Consumer group offsets point to the next record to consume (not the last consumed).
            // We fetch offset-1 because if at log end offset, no record could exist yet
            // unless it's at log start offset
            if (offsetAndMetadata.offset() != 0) {
                offsetsToFetch.put(tp, offsetAndMetadata.offset() - 1);
            } else {
                offsetsToFetch.remove(tp);
                translatedOffsets.put(tp, offsetAndMetadata);
            }
        });

        Map<TopicPartition, Long> timestamps = getTimestampForOffsets(offsetsToFetch);
        if (timestamps.isEmpty()) {
            log.warn("No timestamps were retrieved from source cluster {} to translate offsets for group {}.", sourceClusterAlias, group);
            return translatedOffsets;
        }

        try {
            getOffsetForTimestampFromTarget(timestamps).forEach((tp, downstreamOffset) -> {
                if (downstreamOffset.isPresent()) {
                   // downstreamOffset is the offset of the record we found, so add 1 to get next-to-consume.
                    translatedOffsets.put(tp, new OffsetAndMetadata(downstreamOffset.getAsLong() + 1));
                } else {
                    log.warn("No downstream offset was found for topic partition {} assigned to group {} from cluster {}", tp, group, targetClusterAlias);
                }

            });
        } catch (ExecutionException | InterruptedException e) {
            log.error("Error querying downstream offsets for group {} on cluster {}.", group, targetClusterAlias, e);
        }

        return translatedOffsets;
    }

    Map<TopicPartition, Long> getTimestampForOffsets(Map<TopicPartition, Long> offsetsToFetch) {
        sourceConsumer.assign(offsetsToFetch.keySet());
        // Always retrieve the previous offset's timestamp because when we reach log end offset we won't be able to consume a record at that offset
        offsetsToFetch.forEach(sourceConsumer::seek);
        Map<TopicPartition, Long> partitionTimestamps = new HashMap<>();
        int numOfPolls = 0;

        try {
            while (partitionTimestamps.size() < offsetsToFetch.size() && numOfPolls < offsetsToFetch.size()) {
                ConsumerRecords<byte[], byte[]> records = sourceConsumer.poll(pollTimeout);

                if (records.isEmpty()) {
                    log.debug("No records in this poll");
                    return Collections.emptyMap();
                }

                for (ConsumerRecord<byte[], byte[]> rec : records) {
                    TopicPartition topicPartition = new TopicPartition(rec.topic(), rec.partition());
                    if (offsetsToFetch.containsKey(topicPartition)) {
                        var targetOffset = offsetsToFetch.get(topicPartition);

                        if (rec.offset() == targetOffset) {
                            log.debug("Got timestamp of offset {} from {} on cluster {}: {}", targetOffset, topicPartition, sourceClusterAlias, rec.timestamp());
                            partitionTimestamps.put(topicPartition, rec.timestamp());
                        }
                        if (rec.offset() > targetOffset) {
                            // Offset was compacted or doesn't exist
                            log.warn("Offset {} not found in {}. Next available offset is {}. " +
                                    "Record may have been compacted.", targetOffset, topicPartition, rec.offset());
                        }
                        sourceConsumer.pause(Collections.singleton(topicPartition));
                    }
                }
                numOfPolls++;
            }
        } catch (KafkaException e) {
            log.warn("Failure during poll.", e);
            return Collections.emptyMap();
        } catch (Throwable e) {
            log.error("Failure during poll.", e);
            // allow Connect to deal with the exception
            throw e;
        }

        return partitionTimestamps;
    }

    Map<TopicPartition, OptionalLong> getOffsetForTimestampFromTarget(Map<TopicPartition, Long> timestamps) throws ExecutionException, InterruptedException {
        if (stopping) {
            // short circuit if stopping
            return Map.of();
        }

        Map<TopicPartition, OffsetSpec> offsetSpecs = timestamps.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> OffsetSpec.forTimestamp(e.getValue())));

        log.debug("Getting offsets for topic partitions from cluster {} with {}", timestamps.keySet(), targetClusterAlias);
        return adminCall(
                () -> targetAdminClient.listOffsets(offsetSpecs).all().get().entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> OptionalLong.of(e.getValue().offset()))),
                () -> String.format("get offsets for topic partitions %s on %s cluster", offsetSpecs.keySet(), targetClusterAlias)
        );
    }

    boolean shouldSyncOffsetsForTopic(String topic) {
        return topicFilter.shouldReplicateTopic(topic);
    }

    private void refreshIdleConsumerGroupOffset() throws ExecutionException, InterruptedException {
        Map<String, KafkaFuture<ConsumerGroupDescription>> consumerGroupsDesc = adminCall(
                () -> targetAdminClient.describeConsumerGroups(groups).describedGroups(),
                () -> String.format("describe consumer groups %s on %s cluster", groups, targetClusterAlias)
        );

        for (String group : groups) {
            try {
                ConsumerGroupDescription consumerGroupDesc = consumerGroupsDesc.get(group).get();
                GroupState consumerGroupState = consumerGroupDesc.groupState();
                // sync offset to the target cluster only if the state of current consumer group is:
                // (1) idle: because the consumer at target is not actively consuming the mirrored topic
                // (2) dead: the new consumer that is recently created at source and never existed at target
                //           This case will be reported as a GroupIdNotFoundException
                if (consumerGroupState == GroupState.EMPTY) {
                    idleConsumerGroupsOffset.put(
                            group,
                            adminCall(
                                    () -> targetAdminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(),
                                    () -> String.format("list offsets for consumer group %s on %s cluster", group, targetClusterAlias)
                            )
                    );
                }
                // new consumer upstream has state "DEAD" and will be identified during the offset sync-up
            } catch (InterruptedException ie) {
                log.error("Error querying for consumer group {} on cluster {}.", group, targetClusterAlias, ie);
            } catch (ExecutionException ee) {
                // check for non-existent new consumer upstream which will be identified during the offset sync-up
                if (!(ee.getCause() instanceof GroupIdNotFoundException)) {
                    log.error("Error querying for consumer group {} on cluster {}.", group, targetClusterAlias, ee);
                }
            }
        }
    }

    Map<String, Map<TopicPartition, OffsetAndMetadata>> syncGroupOffsets() throws ExecutionException, InterruptedException {
        // This is returned to be used for testing
        Map<String, Map<TopicPartition, OffsetAndMetadata>> offsetsToSyncAll = new HashMap<>();

        long syncStartTime = System.currentTimeMillis();
        for (String groupId : groups) {
            Map<TopicPartition, OffsetAndMetadata> upstreamGroupOffsets = listConsumerGroupOffsets(groupId).entrySet().stream()
                    .filter(e -> shouldSyncOffsetsForTopic(e.getKey().topic()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Map<TopicPartition, OffsetAndMetadata> offsetsToSync = new HashMap<>();

            Map<TopicPartition, OffsetAndMetadata> translatedOffsets = translateOffset(groupId, upstreamGroupOffsets);
            if (translatedOffsets.isEmpty()) {
                log.warn("Offsets for group {} could not be translated.", groupId);
                continue;
            }

            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry: translatedOffsets.entrySet()) {
                TopicPartition topicPartition = entry.getKey();

                OffsetAndMetadata translatedOffsetAndMetadata = entry.getValue();
                Map<TopicPartition, OffsetAndMetadata> targetConsumerOffset = idleConsumerGroupsOffset.get(groupId);
                if (targetConsumerOffset == null) {
                    // this is a new consumer, just sync the offset to target
                    offsetsToSync.put(topicPartition, translatedOffsetAndMetadata);
                    continue;
                }


                if (!targetConsumerOffset.containsKey(topicPartition)) {
                    // if is a new topicPartition from upstream, just sync the offset to target
                    offsetsToSync.put(topicPartition, translatedOffsetAndMetadata);
                    continue;
                }

                // if translated offset from upstream is smaller than the current consumer offset
                // in the target, skip updating the offset for that partition
                OffsetAndMetadata targetOffsetAndMetadata = targetConsumerOffset.get(topicPartition);
                if (targetOffsetAndMetadata != null) {
                    if (targetOffsetAndMetadata.offset() >= translatedOffsetAndMetadata.offset()) {
                        log.trace("the current target offset {} is larger than or equal to the new translated offset {} for "
                                + "TopicPartition {}", targetOffsetAndMetadata.offset(), translatedOffsetAndMetadata.offset(), topicPartition);
                        continue;
                    }
                } else {
                    // It is possible that when resetting offsets are performed in the java kafka client, the reset to -1 will be intercepted.
                    // However, there are some other types of clients such as sarama, which can magically reset the group offset to -1, which will cause
                    // `targetOffsetAndMetadata` here is null. For this case, just sync the offset to target.
                    log.warn("Group {} offset for partition {} may has been reset to a negative offset, just sync the offset to target.",
                            groupId, topicPartition);
                }
                offsetsToSync.put(topicPartition, translatedOffsetAndMetadata);
            }

            if (offsetsToSync.isEmpty()) {
                log.trace("skip syncing offsets for consumer group: {}", groupId);
                continue;
            }
            alterConsumerGroupOffsets(groupId, offsetsToSync, syncStartTime);

            offsetsToSyncAll.put(groupId, offsetsToSync);
        }

        idleConsumerGroupsOffset.clear();
        return offsetsToSyncAll;
    }

    void alterConsumerGroupOffsets(String consumerGroupId, Map<TopicPartition, OffsetAndMetadata> offsetToSync, long syncStartTime) throws ExecutionException, InterruptedException {
        if (targetAdminClient != null) {
            adminCall(
                    () -> targetAdminClient.alterConsumerGroupOffsets(consumerGroupId, offsetToSync).all()
                            .whenComplete((v, throwable) -> {
                                if (throwable != null) {
                                    if (throwable.getCause() instanceof UnknownMemberIdException) {
                                        log.warn("Unable to sync offsets for consumer group {}. This is likely caused " +
                                                "by consumers currently using this group in the target cluster.", consumerGroupId);
                                    } else {
                                        log.error("Unable to sync offsets for consumer group {}.", consumerGroupId, throwable);
                                    }
                                } else {
                                    offsetToSync.forEach((key, value) -> {
                                        metrics.groupOffsetSyncLatency(key,
                                                consumerGroupId,
                                                System.currentTimeMillis() - syncStartTime);
                                    });

                                    log.trace("Sync-ed {} offsets for consumer group {}.", offsetToSync.size(), consumerGroupId);
                                }
                            }),
                    () -> String.format("alter offsets for consumer group %s on %s cluster", consumerGroupId, targetClusterAlias)
            );
        }
    }
}
