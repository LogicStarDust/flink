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

package org.apache.flink.runtime.operators.sort;

import org.apache.flink.api.common.functions.GroupCombineFunction;
import org.apache.flink.api.common.functions.RichGroupReduceFunction;
import org.apache.flink.api.common.typeutils.TypeComparator;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerFactory;
import org.apache.flink.api.common.typeutils.base.IntComparator;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.disk.iomanager.IOManagerAsync;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.memory.MemoryManagerBuilder;
import org.apache.flink.runtime.operators.testutils.DummyInvokable;
import org.apache.flink.runtime.operators.testutils.TestData;
import org.apache.flink.runtime.operators.testutils.TestData.TupleGenerator.KeyMode;
import org.apache.flink.runtime.operators.testutils.TestData.TupleGenerator.ValueMode;
import org.apache.flink.runtime.util.ReusingKeyGroupedIterator;
import org.apache.flink.util.Collector;
import org.apache.flink.util.MutableObjectIterator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class CombiningExternalSorterITCase {

    private static final Logger LOG = LoggerFactory.getLogger(CombiningExternalSorterITCase.class);

    private static final long SEED = 649180756312423613L;

    private static final int KEY_MAX = 1000;

    private static final int VALUE_LENGTH = 118;

    private static final int NUM_PAIRS = 50000;

    public static final int MEMORY_SIZE = 1024 * 1024 * 256;

    private final AbstractInvokable parentTask = new DummyInvokable();

    private IOManager ioManager;

    private MemoryManager memoryManager;

    private TypeSerializerFactory<Tuple2<Integer, String>> serializerFactory1;
    private TypeSerializerFactory<Tuple2<Integer, Integer>> serializerFactory2;

    private TypeComparator<Tuple2<Integer, String>> comparator1;
    private TypeComparator<Tuple2<Integer, Integer>> comparator2;

    @BeforeEach
    void beforeTest() {
        this.memoryManager = MemoryManagerBuilder.newBuilder().setMemorySize(MEMORY_SIZE).build();
        this.ioManager = new IOManagerAsync();

        this.serializerFactory1 = TestData.getIntStringTupleSerializerFactory();
        this.comparator1 = TestData.getIntStringTupleComparator();

        this.serializerFactory2 = TestData.getIntIntTupleSerializerFactory();
        this.comparator2 = TestData.getIntIntTupleComparator();
    }

    @AfterEach
    void afterTest() throws Exception {
        this.ioManager.close();

        if (this.memoryManager != null) {
            assertThat(this.memoryManager.verifyEmpty())
                    .withFailMessage(
                            "Memory Leak: not all segments have been returned to the memory manager.")
                    .isTrue();
            this.memoryManager.shutdown();
            this.memoryManager = null;
        }
    }

    @Test
    void testCombine() throws Exception {
        int noKeys = 100;
        int noKeyCnt = 10000;

        TestData.MockTuple2Reader<Tuple2<Integer, Integer>> reader =
                TestData.getIntIntTupleReader();

        LOG.debug("initializing sortmerger");

        TestCountCombiner comb = new TestCountCombiner();

        Sorter<Tuple2<Integer, Integer>> merger =
                ExternalSorter.newBuilder(
                                this.memoryManager,
                                this.parentTask,
                                this.serializerFactory2.getSerializer(),
                                this.comparator2)
                        .maxNumFileHandles(64)
                        .withCombiner(comb)
                        .enableSpilling(this.ioManager, 0.7f)
                        .memoryFraction(0.25)
                        .objectReuse(false)
                        .largeRecords(true)
                        .build(reader);

        final Tuple2<Integer, Integer> rec = new Tuple2<>();
        rec.setField(1, 1);

        for (int i = 0; i < noKeyCnt; i++) {
            for (int j = 0; j < noKeys; j++) {
                rec.setField(j, 0);
                reader.emit(rec);
            }
        }
        reader.close();

        MutableObjectIterator<Tuple2<Integer, Integer>> iterator = merger.getIterator();

        Iterator<Integer> result =
                getReducingIterator(
                        iterator, serializerFactory2.getSerializer(), comparator2.duplicate());
        while (result.hasNext()) {
            assertThat(result.next()).isEqualTo(noKeyCnt);
        }

        merger.close();

        // if the combiner was opened, it must have been closed
        assertThat(comb.opened).isEqualTo(comb.closed);
    }

    @Test
    void testCombineSpilling() throws Exception {
        int noKeys = 100;
        int noKeyCnt = 10000;

        TestData.MockTuple2Reader<Tuple2<Integer, Integer>> reader =
                TestData.getIntIntTupleReader();

        LOG.debug("initializing sortmerger");

        TestCountCombiner comb = new TestCountCombiner();

        Sorter<Tuple2<Integer, Integer>> merger =
                ExternalSorter.newBuilder(
                                this.memoryManager,
                                this.parentTask,
                                this.serializerFactory2.getSerializer(),
                                this.comparator2)
                        .maxNumFileHandles(64)
                        .withCombiner(comb)
                        .enableSpilling(this.ioManager, 0.005f)
                        .memoryFraction(0.01)
                        .objectReuse(true)
                        .largeRecords(true)
                        .build(reader);

        final Tuple2<Integer, Integer> rec = new Tuple2<>();
        rec.setField(1, 1);

        for (int i = 0; i < noKeyCnt; i++) {
            for (int j = 0; j < noKeys; j++) {
                rec.setField(j, 0);
                reader.emit(rec);
            }
        }
        reader.close();

        MutableObjectIterator<Tuple2<Integer, Integer>> iterator = merger.getIterator();

        Iterator<Integer> result =
                getReducingIterator(
                        iterator, serializerFactory2.getSerializer(), comparator2.duplicate());
        while (result.hasNext()) {
            assertThat(result.next()).isEqualTo(noKeyCnt);
        }

        merger.close();

        // if the combiner was opened, it must have been closed
        assertThat(comb.opened).isEqualTo(comb.closed);
    }

    @Test
    void testCombineSpillingDisableObjectReuse() throws Exception {
        int noKeys = 100;
        int noKeyCnt = 10000;

        TestData.MockTuple2Reader<Tuple2<Integer, Integer>> reader =
                TestData.getIntIntTupleReader();

        LOG.debug("initializing sortmerger");

        MaterializedCountCombiner comb = new MaterializedCountCombiner();

        // set maxNumFileHandles = 2 to trigger multiple channel merging
        Sorter<Tuple2<Integer, Integer>> merger =
                ExternalSorter.newBuilder(
                                this.memoryManager,
                                this.parentTask,
                                this.serializerFactory2.getSerializer(),
                                this.comparator2)
                        .maxNumFileHandles(2)
                        .withCombiner(comb)
                        .enableSpilling(this.ioManager, 0.005f)
                        .memoryFraction(0.01)
                        .objectReuse(false)
                        .largeRecords(true)
                        .build(reader);

        final Tuple2<Integer, Integer> rec = new Tuple2<>();

        for (int i = 0; i < noKeyCnt; i++) {
            rec.setField(i, 0);
            for (int j = 0; j < noKeys; j++) {
                rec.setField(j, 1);
                reader.emit(rec);
            }
        }
        reader.close();

        MutableObjectIterator<Tuple2<Integer, Integer>> iterator = merger.getIterator();
        Iterator<Integer> result =
                getReducingIterator(
                        iterator, serializerFactory2.getSerializer(), comparator2.duplicate());
        while (result.hasNext()) {
            assertThat(result.next()).isEqualTo(4950);
        }

        merger.close();
    }

    @Test
    void testSortAndValidate() throws Exception {
        final Hashtable<Integer, Integer> countTable = new Hashtable<>(KEY_MAX);
        for (int i = 1; i <= KEY_MAX; i++) {
            countTable.put(i, 0);
        }

        // comparator
        final TypeComparator<Integer> keyComparator = new IntComparator(true);

        // reader
        TestData.MockTuple2Reader<Tuple2<Integer, String>> reader =
                TestData.getIntStringTupleReader();

        // merge iterator
        LOG.debug("initializing sortmerger");

        TestCountCombiner2 comb = new TestCountCombiner2();

        Sorter<Tuple2<Integer, String>> merger =
                ExternalSorter.newBuilder(
                                this.memoryManager,
                                this.parentTask,
                                this.serializerFactory1.getSerializer(),
                                this.comparator1)
                        .maxNumFileHandles(2)
                        .withCombiner(comb)
                        .enableSpilling(this.ioManager, 0.7f)
                        .memoryFraction(0.25)
                        .objectReuse(false)
                        .largeRecords(true)
                        .build(reader);

        // emit data
        LOG.debug("emitting data");
        TestData.TupleGenerator generator =
                new TestData.TupleGenerator(
                        SEED, KEY_MAX, VALUE_LENGTH, KeyMode.RANDOM, ValueMode.FIX_LENGTH);
        Tuple2<Integer, String> rec = new Tuple2<>();

        for (int i = 0; i < NUM_PAIRS; i++) {
            assertThat(rec = generator.next(rec)).isNotNull();
            final Integer key = rec.f0;
            rec.setField("1", 1);
            reader.emit(rec);

            countTable.put(key, countTable.get(key) + 1);
        }
        reader.close();

        // check order
        MutableObjectIterator<Tuple2<Integer, String>> iterator = merger.getIterator();

        LOG.debug("checking results");

        Tuple2<Integer, String> rec1 = new Tuple2<>();
        Tuple2<Integer, String> rec2 = new Tuple2<>();

        assertThat(rec1 = iterator.next(rec1)).isNotNull();
        countTable.put(rec1.f0, countTable.get(rec1.f0) - (Integer.parseInt(rec1.f1)));

        while ((rec2 = iterator.next(rec2)) != null) {
            int k1 = rec1.f0;
            int k2 = rec2.f0;

            assertThat(keyComparator.compare(k1, k2)).isLessThanOrEqualTo(0);
            countTable.put(k2, countTable.get(k2) - (Integer.parseInt(rec2.f1)));

            rec1 = rec2;
        }

        for (Integer cnt : countTable.values()) {
            assertThat(cnt).isZero();
        }

        merger.close();

        // if the combiner was opened, it must have been closed
        assertThat(comb.opened).isEqualTo(comb.closed);
    }

    // --------------------------------------------------------------------------------------------

    private static class TestCountCombiner
            extends RichGroupReduceFunction<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>>
            implements GroupCombineFunction<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>> {
        private static final long serialVersionUID = 1L;

        private Integer count = 0;

        public volatile boolean opened = false;

        public volatile boolean closed = false;

        @Override
        public void combine(
                Iterable<Tuple2<Integer, Integer>> values,
                Collector<Tuple2<Integer, Integer>> out) {
            Tuple2<Integer, Integer> rec = new Tuple2<>();
            int cnt = 0;
            for (Tuple2<Integer, Integer> next : values) {
                rec = next;
                cnt += rec.f1;
            }

            this.count = cnt;
            rec.setField(this.count, 1);
            out.collect(rec);
        }

        @Override
        public void reduce(
                Iterable<Tuple2<Integer, Integer>> values,
                Collector<Tuple2<Integer, Integer>> out) {}

        @Override
        public void open(Configuration parameters) throws Exception {
            opened = true;
        }

        @Override
        public void close() throws Exception {
            closed = true;
        }
    }

    private static class TestCountCombiner2
            extends RichGroupReduceFunction<Tuple2<Integer, String>, Tuple2<Integer, String>>
            implements GroupCombineFunction<Tuple2<Integer, String>, Tuple2<Integer, String>> {
        private static final long serialVersionUID = 1L;

        public volatile boolean opened = false;

        public volatile boolean closed = false;

        @Override
        public void combine(
                Iterable<Tuple2<Integer, String>> values, Collector<Tuple2<Integer, String>> out) {
            Tuple2<Integer, String> rec = new Tuple2<>();
            int cnt = 0;
            for (Tuple2<Integer, String> next : values) {
                rec = next;
                cnt += Integer.parseInt(rec.f1);
            }

            out.collect(new Tuple2(rec.f0, cnt + ""));
        }

        @Override
        public void reduce(
                Iterable<Tuple2<Integer, String>> values, Collector<Tuple2<Integer, String>> out) {
            // yo, nothing, mon
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            opened = true;
        }

        @Override
        public void close() throws Exception {
            closed = true;
        }
    }

    // --------------------------------------------------------------------------------------------

    private static class MaterializedCountCombiner
            extends RichGroupReduceFunction<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>>
            implements GroupCombineFunction<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>> {
        private static final long serialVersionUID = 1L;

        @Override
        public void combine(
                Iterable<Tuple2<Integer, Integer>> values,
                Collector<Tuple2<Integer, Integer>> out) {
            ArrayList<Tuple2<Integer, Integer>> valueList = new ArrayList<>();
            for (Tuple2<Integer, Integer> next : values) {
                valueList.add(next);
            }

            int count = 0;
            Tuple2<Integer, Integer> rec = new Tuple2<>();
            for (Tuple2<Integer, Integer> tuple : valueList) {
                rec.setField(tuple.f0, 0);
                count += tuple.f1;
            }
            rec.setField(count, 1);
            out.collect(rec);
        }

        @Override
        public void reduce(
                Iterable<Tuple2<Integer, Integer>> values, Collector<Tuple2<Integer, Integer>> out)
                throws Exception {}
    }

    private static Iterator<Integer> getReducingIterator(
            MutableObjectIterator<Tuple2<Integer, Integer>> data,
            TypeSerializer<Tuple2<Integer, Integer>> serializer,
            TypeComparator<Tuple2<Integer, Integer>> comparator) {

        final ReusingKeyGroupedIterator<Tuple2<Integer, Integer>> groupIter =
                new ReusingKeyGroupedIterator<>(data, serializer, comparator);

        return new Iterator<Integer>() {

            private boolean hasNext = false;

            @Override
            public boolean hasNext() {
                if (hasNext) {
                    return true;
                }

                try {
                    hasNext = groupIter.nextKey();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return hasNext;
            }

            @Override
            public Integer next() {
                if (hasNext()) {
                    hasNext = false;

                    Iterator<Tuple2<Integer, Integer>> values = groupIter.getValues();

                    Tuple2<Integer, Integer> rec;
                    int cnt = 0;
                    while (values.hasNext()) {
                        rec = values.next();
                        cnt += rec.f1;
                    }

                    return cnt;
                } else {
                    throw new NoSuchElementException();
                }
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
