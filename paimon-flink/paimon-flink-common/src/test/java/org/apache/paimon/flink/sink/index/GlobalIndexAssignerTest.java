/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.sink.index;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.CoreOptions.MergeEngine;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.TableTestBase;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowKind;

import org.apache.flink.api.java.tuple.Tuple2;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.paimon.io.DataFileTestUtils.row;
import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link GlobalIndexAssigner}. */
public class GlobalIndexAssignerTest extends TableTestBase {

    private GlobalIndexAssigner<InternalRow> createAssigner(MergeEngine mergeEngine)
            throws Exception {
        return createAssigner(mergeEngine, false);
    }

    private GlobalIndexAssigner<InternalRow> createAssigner(
            MergeEngine mergeEngine, boolean enableTtl) throws Exception {
        Identifier identifier = identifier("T");
        Options options = new Options();
        options.set(CoreOptions.MERGE_ENGINE, mergeEngine);
        if (mergeEngine == MergeEngine.FIRST_ROW) {
            options.set(CoreOptions.CHANGELOG_PRODUCER, CoreOptions.ChangelogProducer.LOOKUP);
        }
        options.set(CoreOptions.DYNAMIC_BUCKET_TARGET_ROW_NUM, 3L);
        options.set(CoreOptions.BUCKET, -1);
        if (enableTtl) {
            options.set(CoreOptions.CROSS_PARTITION_UPSERT_INDEX_TTL, Duration.ofSeconds(1000));
        }
        Schema schema =
                Schema.newBuilder()
                        .column("pt", DataTypes.INT())
                        .column("pk", DataTypes.INT())
                        .column("col", DataTypes.INT())
                        .partitionKeys("pt")
                        .primaryKey("pk")
                        .options(options.toMap())
                        .build();
        catalog.createTable(identifier, schema, true);
        return GlobalIndexAssignerOperator.createRowDataAssigner(catalog.getTable(identifier));
    }

    @Test
    public void testBucketAssign() throws Exception {
        innerTestBucketAssign(false);
    }

    @Test
    public void testEnableTtl() throws Exception {
        innerTestBucketAssign(true);
    }

    private IOManager ioManager() {
        return IOManager.create(new File(tempPath.toFile(), "io").getPath());
    }

    private void innerTestBucketAssign(boolean enableTtl) throws Exception {
        GlobalIndexAssigner<InternalRow> assigner =
                createAssigner(MergeEngine.DEDUPLICATE, enableTtl);
        List<Integer> output = new ArrayList<>();
        assigner.open(
                ioManager(),
                new File(warehouse.getPath()),
                2,
                0,
                (row, bucket) -> output.add(bucket));
        assigner.endBoostrap();

        // assign
        assigner.processInput(GenericRow.of(1, 1, 1));
        assigner.processInput(GenericRow.of(1, 2, 2));
        assigner.processInput(GenericRow.of(1, 3, 3));
        assertThat(output).containsExactly(0, 0, 0);
        output.clear();

        // full
        assigner.processInput(GenericRow.of(1, 4, 4));
        assertThat(output).containsExactly(2);
        output.clear();

        // another partition
        assigner.processInput(GenericRow.of(2, 5, 5));
        assertThat(output).containsExactly(0);
        output.clear();

        // read assigned
        assigner.processInput(GenericRow.of(1, 4, 4));
        assigner.processInput(GenericRow.of(1, 2, 2));
        assigner.processInput(GenericRow.of(1, 3, 3));
        assertThat(output).containsExactly(2, 0, 0);
        output.clear();

        assigner.close();
    }

    @Test
    public void testUpsert() throws Exception {
        GlobalIndexAssigner<InternalRow> assigner = createAssigner(MergeEngine.DEDUPLICATE);
        List<Tuple2<InternalRow, Integer>> output = new ArrayList<>();
        assigner.open(
                ioManager(),
                new File(warehouse.getPath()),
                2,
                0,
                (row, bucket) -> output.add(new Tuple2<>(row, bucket)));
        assigner.endBoostrap();

        // change partition
        assigner.processInput(GenericRow.of(1, 1, 1));
        assigner.processInput(GenericRow.of(2, 1, 2));
        assertThat(output)
                .containsExactly(
                        new Tuple2<>(GenericRow.of(1, 1, 1), 0),
                        new Tuple2<>(GenericRow.ofKind(RowKind.DELETE, 1, 1, 2), 0),
                        new Tuple2<>(GenericRow.of(2, 1, 2), 0));
        output.clear();

        // test partition 1 deleted
        assigner.processInput(GenericRow.of(1, 2, 2));
        assigner.processInput(GenericRow.of(1, 3, 3));
        assigner.processInput(GenericRow.of(1, 4, 4));
        assertThat(output.stream().map(t -> t.f1)).containsExactly(0, 0, 0);
        output.clear();

        // move from full bucket
        assigner.processInput(GenericRow.of(2, 4, 4));
        assertThat(output)
                .containsExactly(
                        new Tuple2<>(GenericRow.ofKind(RowKind.DELETE, 1, 4, 4), 0),
                        new Tuple2<>(GenericRow.of(2, 4, 4), 0));
        output.clear();

        // test partition 1 deleted
        assigner.processInput(GenericRow.of(1, 5, 5));
        assertThat(output.stream().map(t -> t.f1)).containsExactly(0);
        output.clear();

        assigner.close();
    }

    @Test
    public void testUseOldPartition() throws Exception {
        MergeEngine mergeEngine =
                ThreadLocalRandom.current().nextBoolean()
                        ? MergeEngine.PARTIAL_UPDATE
                        : MergeEngine.AGGREGATE;
        GlobalIndexAssigner<InternalRow> assigner = createAssigner(mergeEngine);
        List<Tuple2<InternalRow, Integer>> output = new ArrayList<>();
        assigner.open(
                ioManager(),
                new File(warehouse.getPath()),
                2,
                0,
                (row, bucket) -> output.add(new Tuple2<>(row, bucket)));
        assigner.endBoostrap();

        // change partition
        assigner.processInput(GenericRow.of(1, 1, 1));
        assigner.processInput(GenericRow.of(2, 1, 2));
        assertThat(output)
                .containsExactly(
                        new Tuple2<>(GenericRow.of(1, 1, 1), 0),
                        new Tuple2<>(GenericRow.of(1, 1, 2), 0));
        output.clear();

        // test partition 2 no effect
        assigner.processInput(GenericRow.of(2, 2, 2));
        assigner.processInput(GenericRow.of(2, 3, 3));
        assigner.processInput(GenericRow.of(2, 4, 4));
        assertThat(output.stream().map(t -> t.f1)).containsExactly(0, 0, 0);
        output.clear();
        assigner.close();
    }

    @Test
    public void testFirstRow() throws Exception {
        GlobalIndexAssigner<InternalRow> assigner = createAssigner(MergeEngine.FIRST_ROW);
        List<Tuple2<InternalRow, Integer>> output = new ArrayList<>();
        assigner.open(
                ioManager(),
                new File(warehouse.getPath()),
                2,
                0,
                (row, bucket) -> output.add(new Tuple2<>(row, bucket)));
        assigner.endBoostrap();

        // change partition
        assigner.processInput(GenericRow.of(1, 1, 1));
        assigner.processInput(GenericRow.of(2, 1, 2));
        assertThat(output).containsExactly(new Tuple2<>(GenericRow.of(1, 1, 1), 0));
        output.clear();

        // test partition 2 no effect
        assigner.processInput(GenericRow.of(2, 2, 2));
        assigner.processInput(GenericRow.of(2, 3, 3));
        assigner.processInput(GenericRow.of(2, 4, 4));
        assertThat(output.stream().map(t -> t.f1)).containsExactly(0, 0, 0);
        output.clear();
        assigner.close();
    }
}
