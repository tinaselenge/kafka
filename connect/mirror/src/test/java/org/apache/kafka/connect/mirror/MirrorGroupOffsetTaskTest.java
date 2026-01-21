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

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class MirrorGroupOffsetTaskTest {

    private static final long TEST_TIMESTAMP = 1773402599906L;

    @Test
    public void testSyncOffset() throws ExecutionException, InterruptedException {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();
        String consumer1 = "consumer1";
        String consumer2 = "consumer2";

        String topic1 = "topic1";
        String topic2 = "topic2";

        // 'c1t1' denotes consumer offsets of all partitions of topic1 for consumer1
        Map<TopicPartition, OffsetAndMetadata> c1t1 = new HashMap<>();
        // 't1p0' denotes topic1, partition 0
        TopicPartition t1p0 = new TopicPartition(topic1, 0);
        c1t1.put(t1p0, new OffsetAndMetadata(100));

        Map<TopicPartition, OffsetAndMetadata> c2t2 = new HashMap<>();
        TopicPartition t2p0 = new TopicPartition(topic2, 0);
        c2t2.put(t2p0, new OffsetAndMetadata(50));

        idleConsumerGroupsOffset.put(consumer1, c1t1);
        idleConsumerGroupsOffset.put(consumer2, c2t2);

        MirrorGroupOffsetTask task = new MirrorGroupOffsetTask("source1", "target2",
                Set.of(consumer1, consumer2), idleConsumerGroupsOffset);
        task = spy(task);

        //we fetch timestamp for the previous offset
        var t1SrcOffset = Map.of(t1p0, 99L);
        var t2SrcOffset = Map.of(t2p0, 49L);
        var t1TimestampToReturn = Map.of(t1p0, TEST_TIMESTAMP);
        var t2TimestampToReturn = Map.of(t2p0, TEST_TIMESTAMP);
        var c1TargetOffset = Map.of(t1p0, OptionalLong.of(120));
        var c2TargetOffset = Map.of(t2p0, OptionalLong.of(70));

        doReturn(c1t1).when(task).listConsumerGroupOffsets(consumer1);
        doReturn(c2t2).when(task).listConsumerGroupOffsets(consumer2);
        doReturn(t1TimestampToReturn).when(task).getTimestampForOffsets(t1SrcOffset);
        doReturn(t2TimestampToReturn).when(task).getTimestampForOffsets(t2SrcOffset);
        doReturn(c1TargetOffset).when(task).getOffsetForTimestampFromTarget(t1TimestampToReturn);
        doReturn(c2TargetOffset).when(task).getOffsetForTimestampFromTarget(t2TimestampToReturn);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = task.syncGroupOffsets();

        assertEquals(121, output.get(consumer1).get(t1p0).offset(),
                "Consumer 1 " + topic1 + " failed");
        assertEquals(71, output.get(consumer2).get(t2p0).offset(),
                "Consumer 2 " + topic2 + " failed");
    }

    @Test
    public void testSyncOffsetForNewGroup() throws ExecutionException, InterruptedException {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();
        String consumer1 = "consumer1";
        String consumer2 = "consumer2";

        String topic1 = "topic1";
        String topic2 = "topic2";

        // 'c1t1' denotes consumer offsets of all partitions of topic1 for consumer1
        Map<TopicPartition, OffsetAndMetadata> c1t1 = new HashMap<>();
        // 't1p0' denotes topic1, partition 0
        TopicPartition t1p0 = new TopicPartition(topic1, 0);
        c1t1.put(t1p0, new OffsetAndMetadata(100));

        Map<TopicPartition, OffsetAndMetadata> c2t2 = new HashMap<>();
        TopicPartition t2p0 = new TopicPartition(topic2, 0);
        c2t2.put(t2p0, new OffsetAndMetadata(50));

        idleConsumerGroupsOffset.put(consumer1, c1t1);

        MirrorGroupOffsetTask task = new MirrorGroupOffsetTask("source1", "target2",
                Set.of(consumer1, consumer2), idleConsumerGroupsOffset);
        task = spy(task);
        doReturn(c1t1).when(task).listConsumerGroupOffsets(consumer1);
        doReturn(c2t2).when(task).listConsumerGroupOffsets(consumer2);

        //we fetch timestamp for the previous offset
        var t1SrcOffset = Map.of(t1p0, 99L);
        var t2SrcOffset = Map.of(t2p0, 49L);
        var t1TimestampToReturn = Map.of(t1p0, TEST_TIMESTAMP);
        var t2TimestampToReturn = Map.of(t2p0, TEST_TIMESTAMP);
        var c1TargetOffset = Map.of(t1p0, OptionalLong.of(120));
        var c2TargetOffset = Map.of(t1p0, OptionalLong.of(70));

        doReturn(t1TimestampToReturn).when(task).getTimestampForOffsets(t1SrcOffset);
        doReturn(t2TimestampToReturn).when(task).getTimestampForOffsets(t2SrcOffset);
        doReturn(c1TargetOffset).when(task).getOffsetForTimestampFromTarget(t1TimestampToReturn);
        doReturn(c2TargetOffset).when(task).getOffsetForTimestampFromTarget(t2TimestampToReturn);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = task.syncGroupOffsets();

        assertEquals(121, output.get(consumer1).get(t1p0).offset(),
                "Consumer 1 " + topic1 + " failed");
        assertEquals(71, output.get(consumer2).get(t1p0).offset(),
                "Consumer 2 " + topic2 + " failed");
    }

    @Test
    public void testSyncOffsetForNewTopicPartition() throws ExecutionException, InterruptedException {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();
        String consumer1 = "consumer1";

        String topic1 = "topic1";
        String topic2 = "topic2";

        // 'c1t1' denotes consumer offsets of all partitions of topic1 for consumer1
        Map<TopicPartition, OffsetAndMetadata> c1t1 = new HashMap<>();
        // 't1p0' denotes topic1, partition 0
        TopicPartition t1p0 = new TopicPartition(topic1, 0);
        c1t1.put(t1p0, new OffsetAndMetadata(100));

        idleConsumerGroupsOffset.put(consumer1, c1t1);

        TopicPartition t2p0 = new TopicPartition(topic2, 0);
        c1t1.put(t2p0, new OffsetAndMetadata(50));

        MirrorGroupOffsetTask task = new MirrorGroupOffsetTask("source1", "target2",
                Set.of(consumer1), idleConsumerGroupsOffset);
        task = spy(task);
        doReturn(c1t1).when(task).listConsumerGroupOffsets(consumer1);

        var timestampToReturn = Map.of(t1p0, TEST_TIMESTAMP, t2p0, TEST_TIMESTAMP);
        var targetOffsets = Map.of(t1p0, OptionalLong.of(120),  t2p0, OptionalLong.of(70));

        doReturn(timestampToReturn).when(task).getTimestampForOffsets(any());
        doReturn(targetOffsets).when(task).getOffsetForTimestampFromTarget(timestampToReturn);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = task.syncGroupOffsets();

        assertEquals(121, output.get(consumer1).get(t1p0).offset(),
                "Consumer 1 " + topic1 + " failed");
        assertEquals(71, output.get(consumer1).get(t2p0).offset(),
                "Consumer 2 " + topic2 + " failed");
    }

    @Test
    public void testSyncOffsetTargetGreaterThanSource() throws ExecutionException, InterruptedException {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();

        String consumer = "consumer";
        String topic = "topic";
        TopicPartition tp = new TopicPartition(topic, 0);
        Map<TopicPartition, OffsetAndMetadata> existingTargetOffset = Map.of(tp, new OffsetAndMetadata(150));
        idleConsumerGroupsOffset.put(consumer, existingTargetOffset);

        MirrorGroupOffsetTask task = new MirrorGroupOffsetTask("source1", "target2",
                Set.of(consumer), idleConsumerGroupsOffset);
        task = spy(task);

        var timestampToReturn = Map.of(tp, TEST_TIMESTAMP);
        var targetOffset = Map.of(tp, OptionalLong.of(101));

        doReturn(existingTargetOffset).when(task).listConsumerGroupOffsets(consumer);
        doReturn(timestampToReturn).when(task).getTimestampForOffsets(any());
        doReturn(targetOffset).when(task).getOffsetForTimestampFromTarget(timestampToReturn);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = task.syncGroupOffsets();

        assertEquals(0, output.size(), "Consumer " + topic + " failed");
    }

    @Test
    public void testSyncOffsetForTargetGroupWithNullOffsetAndMetadata() throws ExecutionException, InterruptedException {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset = new HashMap<>();

        String consumer = "consumer";
        String topic = "topic";
        Map<TopicPartition, OffsetAndMetadata> existingTargetOffset = new HashMap<>();
        TopicPartition tp = new TopicPartition(topic, 0);
        // Simulate other clients such as Sarama, which may reset group offsets to -1. This can cause
        // the obtained `OffsetAndMetadata` of the target cluster to be null.
        existingTargetOffset.put(tp, null);
        idleConsumerGroupsOffset.put(consumer, existingTargetOffset);

        MirrorGroupOffsetTask task = new MirrorGroupOffsetTask("source1", "target2",
                Set.of(consumer), idleConsumerGroupsOffset);
        task = spy(task);

        var timestampToReturn = Map.of(tp, TEST_TIMESTAMP);
        var targetOffset = Map.of(tp, OptionalLong.of(101));

        doReturn(Map.of(tp, new OffsetAndMetadata(200))).when(task).listConsumerGroupOffsets(consumer);
        doReturn(timestampToReturn).when(task).getTimestampForOffsets(any());
        doReturn(targetOffset).when(task).getOffsetForTimestampFromTarget(timestampToReturn);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> output = task.syncGroupOffsets();

        assertEquals(102, output.get(consumer).get(tp).offset(), "Consumer " + topic + " failed");
    }
}
