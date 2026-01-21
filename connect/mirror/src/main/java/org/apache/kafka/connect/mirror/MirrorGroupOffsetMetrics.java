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

import org.apache.kafka.common.MetricNameTemplate;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.MetricsReporter;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Min;
import org.apache.kafka.common.metrics.stats.Value;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Metrics for replicating group offsets */
class MirrorGroupOffsetMetrics implements AutoCloseable {

    private static final String GROUP_OFFSET_CONNECTOR_GROUP = MirrorGroupOffsetConnector.class.getSimpleName();

    private static final Set<String> GROUP_TAGS = Set.of("source", "target", "group", "topic", "partition");

    private static final MetricNameTemplate GROUP_OFFSET_SYNC_LATENCY = new MetricNameTemplate(
            "group-offset-sync-latency-ms", GROUP_OFFSET_CONNECTOR_GROUP,
            "Time it takes group offsets to replicate from source to target cluster.", GROUP_TAGS);
    private static final MetricNameTemplate GROUP_OFFSET_SYNC_MAX = new MetricNameTemplate(
            "group-offset-sync-latency-ms-max", GROUP_OFFSET_CONNECTOR_GROUP,
            "Max time it takes  group offsets to replicate from source to target cluster.", GROUP_TAGS);
    private static final MetricNameTemplate GROUP_OFFSET_SYNC_MIN = new MetricNameTemplate(
            "group-offset-sync-latency-ms-min", GROUP_OFFSET_CONNECTOR_GROUP,
            "Min time it takes group offsets to replicate from source to target cluster.", GROUP_TAGS);
    private static final MetricNameTemplate GROUP_OFFSET_SYNC_AVG = new MetricNameTemplate(
            "group-offset-sync-latency-avg", GROUP_OFFSET_CONNECTOR_GROUP,
            "Average time it takes group offsets to replicate from source to target cluster.", GROUP_TAGS);


    private final Metrics metrics;
    private final Map<String, GroupMetrics> groupMetrics = new HashMap<>();
    private final String source;
    private final String target;

    MirrorGroupOffsetMetrics(MirrorGroupOffsetConfig taskConfig) {
        this.target = taskConfig.targetClusterAlias();
        this.source = taskConfig.sourceClusterAlias();
        this.metrics = new Metrics();

        // for side-effect
        metrics.sensor("record-count");
        metrics.sensor("byte-rate");
        metrics.sensor("record-age");
        metrics.sensor("replication-latency");
    }

    @Override
    public void close() {
        metrics.close();
    }

    void groupOffsetSyncLatency(TopicPartition topicPartition, String group, long millis) {
        group(topicPartition, group).groupOffsetSyncLatencySensor.record((double) millis);
    }

    GroupMetrics group(TopicPartition topicPartition, String group) {
        return groupMetrics.computeIfAbsent(String.join("-", topicPartition.toString(), group),
            x -> new GroupMetrics(topicPartition, group));
    }

    void addReporter(MetricsReporter reporter) {
        metrics.addReporter(reporter);
    }

    private class GroupMetrics {
        private final Sensor groupOffsetSyncLatencySensor;

        GroupMetrics(TopicPartition topicPartition, String group) {
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("source", source); 
            tags.put("target", target); 
            tags.put("group", group);
            tags.put("topic", topicPartition.topic());
            tags.put("partition", Integer.toString(topicPartition.partition()));
 
            groupOffsetSyncLatencySensor = metrics.sensor("consumer-groups-sync-latency");
            groupOffsetSyncLatencySensor.add(metrics.metricInstance(GROUP_OFFSET_SYNC_LATENCY, tags), new Value());
            groupOffsetSyncLatencySensor.add(metrics.metricInstance(GROUP_OFFSET_SYNC_MAX, tags), new Max());
            groupOffsetSyncLatencySensor.add(metrics.metricInstance(GROUP_OFFSET_SYNC_MIN, tags), new Min());
            groupOffsetSyncLatencySensor.add(metrics.metricInstance(GROUP_OFFSET_SYNC_AVG, tags), new Avg());
        }
    }
}
