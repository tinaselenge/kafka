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

import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.ConsumerProtocol;
import org.apache.kafka.common.GroupType;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.RetriableException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.kafka.connect.mirror.TestUtils.makeProps;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;


public class MirrorGroupOffsetConnectorTest {

    @Test
    public void testNoConsumerGroup() {
        MirrorGroupOffsetConfig config = new MirrorGroupOffsetConfig(makeProps());
        MirrorGroupOffsetConnector connector = new MirrorGroupOffsetConnector(new HashSet<>(), config);
        List<Map<String, String>> output = connector.taskConfigs(1);
        // expect no task will be created
        assertEquals(0, output.size(), "ConsumerGroup shouldn't exist");
    }

    @Test
    public void testConsumerGroupInitializeTimeout() {
        MirrorGroupOffsetConfig config = new MirrorGroupOffsetConfig(makeProps());
        MirrorGroupOffsetConnector connector = new MirrorGroupOffsetConnector(null, config);

        assertThrows(
                RetriableException.class,
                () -> connector.taskConfigs(1),
                "taskConfigs should throw exception when initial loading ConsumerGroup timeout"
        );
    }

    @Test
    public void testFindConsumerGroups() throws Exception {
        MirrorGroupOffsetConfig config = new MirrorGroupOffsetConfig(makeProps());
        MirrorGroupOffsetConnector connector = new MirrorGroupOffsetConnector(Set.of(), config);
        connector = spy(connector);

        List<GroupListing> groups = List.of(
                new GroupListing("g1", Optional.of(GroupType.CLASSIC), "", Optional.empty()),
                new GroupListing("g2", Optional.of(GroupType.CLASSIC), ConsumerProtocol.PROTOCOL_TYPE, Optional.empty()));
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("t1", 0), new OffsetAndMetadata(0));
        doReturn(groups).when(connector).listConsumerGroups();
        doReturn(true).when(connector).shouldReplicateByTopicFilter(anyString());
        doReturn(true).when(connector).shouldReplicateByGroupFilter(anyString());

        Map<String, Map<TopicPartition, OffsetAndMetadata>> groupToOffsets = new HashMap<>();
        groupToOffsets.put("g1", offsets);
        groupToOffsets.put("g2", offsets);
        doReturn(groupToOffsets).when(connector).listConsumerGroupOffsets(anyList());
        Set<String> groupFound = connector.findConsumerGroups();

        Set<String> expectedGroups = groups.stream().map(GroupListing::groupId).collect(Collectors.toSet());
        assertEquals(expectedGroups, groupFound,
                "Expected groups are not the same as findConsumerGroups");

        doReturn(false).when(connector).shouldReplicateByTopicFilter(anyString());
        Set<String> topicFilterGroupFound = connector.findConsumerGroups();
        assertEquals(Set.of(), topicFilterGroupFound);
    }

    @Test
    public void testFindConsumerGroupsInCommonScenarios() throws Exception {
        MirrorGroupOffsetConfig config = new MirrorGroupOffsetConfig(makeProps());
        MirrorGroupOffsetConnector connector = new MirrorGroupOffsetConnector(Set.of(), config);
        connector = spy(connector);

        List<GroupListing> groups = List.of(
                new GroupListing("g1", Optional.of(GroupType.CLASSIC), "", Optional.empty()),
                new GroupListing("g2", Optional.of(GroupType.CLASSIC), ConsumerProtocol.PROTOCOL_TYPE, Optional.empty()),
                new GroupListing("g3", Optional.of(GroupType.CLASSIC), ConsumerProtocol.PROTOCOL_TYPE, Optional.empty()),
                new GroupListing("g4", Optional.of(GroupType.CLASSIC), ConsumerProtocol.PROTOCOL_TYPE, Optional.empty()));
        Map<TopicPartition, OffsetAndMetadata> offsetsForGroup1 = new HashMap<>();
        Map<TopicPartition, OffsetAndMetadata> offsetsForGroup2 = new HashMap<>();
        Map<TopicPartition, OffsetAndMetadata> offsetsForGroup3 = new HashMap<>();
        offsetsForGroup1.put(new TopicPartition("t1", 0), new OffsetAndMetadata(0));
        offsetsForGroup1.put(new TopicPartition("t2", 0), new OffsetAndMetadata(0));
        offsetsForGroup2.put(new TopicPartition("t2", 0), new OffsetAndMetadata(0));
        offsetsForGroup2.put(new TopicPartition("t3", 0), new OffsetAndMetadata(0));
        offsetsForGroup3.put(new TopicPartition("t3", 0), new OffsetAndMetadata(0));
        doReturn(groups).when(connector).listConsumerGroups();
        doReturn(false).when(connector).shouldReplicateByTopicFilter("t1");
        doReturn(true).when(connector).shouldReplicateByTopicFilter("t2");
        doReturn(false).when(connector).shouldReplicateByTopicFilter("t3");
        doReturn(true).when(connector).shouldReplicateByGroupFilter("g1");
        doReturn(true).when(connector).shouldReplicateByGroupFilter("g2");
        doReturn(true).when(connector).shouldReplicateByGroupFilter("g3");
        doReturn(false).when(connector).shouldReplicateByGroupFilter("g4");

        Map<String, Map<TopicPartition, OffsetAndMetadata>> groupToOffsets = new HashMap<>();
        groupToOffsets.put("g1", offsetsForGroup1);
        groupToOffsets.put("g2", offsetsForGroup2);
        groupToOffsets.put("g3", offsetsForGroup3);
        doReturn(groupToOffsets).when(connector).listConsumerGroupOffsets(List.of("g1", "g2", "g3"));

        Set<String> groupFound = connector.findConsumerGroups();
        Set<String> verifiedSet = new HashSet<>();
        verifiedSet.add("g1");
        verifiedSet.add("g2");
        assertEquals(verifiedSet, groupFound);
    }

}
