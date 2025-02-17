/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.newts;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.opennms.core.logging.Logging;
import org.opennms.netmgt.newts.support.NewtsUtils;
import org.opennms.newts.api.Sample;
import org.opennms.newts.api.SampleRepository;
import org.opennms.newts.api.search.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.math.DoubleMath;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.FatalExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.WorkerPool;
import com.swrve.ratelimitedlogger.RateLimitedLog;

/**
 * Used to write samples to the {@link org.opennms.newts.api.SampleRepository}.
 *
 * Calls to {@link #insert()} publish the samples to a ring buffer so
 * that they don't block while the data is being persisted.
 *
 * @author jwhite
 */
public class NewtsWriter implements WorkHandler<SampleBatchEvent>, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(NewtsWriter.class);

    private static final RateLimitedLog RATE_LIMITED_LOGGER = RateLimitedLog
            .withRateLimit(LOG)
            .maxRate(5).every(Duration.ofSeconds(30))
            .build();

    @Autowired
    private SampleRepository m_sampleRepository;

    @Autowired
    private Indexer m_indexer;

    private WorkerPool<SampleBatchEvent> m_workerPool;

    private RingBuffer<SampleBatchEvent> m_ringBuffer;

    private final int m_maxBatchSize;

    private final int m_ringBufferSize;

    private final int m_numWriterThreads;

    private final Meter m_droppedSamples;

    /**
     * The {@link RingBuffer} doesn't appear to expose any methods that indicate the number
     * of elements that are currently "queued", so we keep track of them with this atomic counter.
     */
    private final AtomicLong m_numEntriesOnRingBuffer = new AtomicLong();

    @Inject
    public NewtsWriter(@Named("newts.max_batch_size") Integer maxBatchSize, @Named("newts.ring_buffer_size") Integer ringBufferSize,
            @Named("newts.writer_threads") Integer numWriterThreads, @Named("newtsMetricRegistry") MetricRegistry registry) {
        Preconditions.checkArgument(maxBatchSize > 0, "maxBatchSize must be strictly positive");
        Preconditions.checkArgument(ringBufferSize > 0, "ringBufferSize must be positive");
        Preconditions.checkArgument(DoubleMath.isMathematicalInteger(Math.log(ringBufferSize) / Math.log(2)), "ringBufferSize must be a power of two");
        Preconditions.checkArgument(numWriterThreads > 0, "numWriterThreads must be positive");
        Preconditions.checkNotNull(registry, "metric registry");

        m_maxBatchSize = maxBatchSize;
        m_ringBufferSize = ringBufferSize;
        m_numWriterThreads = numWriterThreads;
        m_numEntriesOnRingBuffer.set(0L);

        registry.register(MetricRegistry.name("ring-buffer", "size"),
                new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return m_numEntriesOnRingBuffer.get();
                    }
                });
        registry.register(MetricRegistry.name("ring-buffer", "max-size"),
                new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return Long.valueOf(m_ringBufferSize);
                    }
                });

        m_droppedSamples = registry.meter(MetricRegistry.name("ring-buffer", "dropped-samples"));

        LOG.debug("Using max_batch_size: {} and ring_buffer_size: {}", maxBatchSize, m_ringBufferSize);
        setUpWorkerPool();
    }

    private void setUpWorkerPool() {
        // Executor that will be used to construct new threads for consumers
        final ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("NewtsWriter-Consumer-%d").build();
        final Executor executor = Executors.newCachedThreadPool(namedThreadFactory);

        @SuppressWarnings("unchecked")
        final WorkHandler<SampleBatchEvent> handlers[] = new WorkHandler[m_numWriterThreads];
        for (int i = 0; i < m_numWriterThreads; i++) {
            handlers[i] = this;
        }

        m_ringBuffer = RingBuffer.createMultiProducer(SampleBatchEvent::new, m_ringBufferSize);
        m_workerPool = new WorkerPool<SampleBatchEvent>(
                m_ringBuffer,
                m_ringBuffer.newBarrier(),
                new FatalExceptionHandler(),
                handlers);
        m_ringBuffer.addGatingSequences(m_workerPool.getWorkerSequences());

        m_workerPool.start(executor);
    }

    @Override
    public void destroy() throws Exception {
        if (m_workerPool != null) {
            m_workerPool.drainAndHalt();
        }
    }

    public void insert(List<Sample> samples) {
        pushToRingBuffer(samples, TRANSLATOR);
    }

    public void index(List<Sample> samples) {
        pushToRingBuffer(samples, INDEX_ONLY_TRANSLATOR);
    }

    private void pushToRingBuffer(List<Sample> samples, EventTranslatorOneArg<SampleBatchEvent, List<Sample>> translator) {
        // Add the samples to the ring buffer
        if (!m_ringBuffer.tryPublishEvent(translator, samples)) {
            RATE_LIMITED_LOGGER.error("The ring buffer is full. {} samples associated with resource ids {} will be dropped.",
                    samples.size(), new Object() {
                        @Override
                        public String toString() {
                            // We wrap this in a toString() method to avoid build the string
                            // unless the log message is actually printed
                            return samples.stream()
                                    .map(s -> s.getResource().getId())
                                    .distinct()
                                    .collect(Collectors.joining(", "));
                        }
                    });
            m_droppedSamples.mark(samples.size());
            return;
        }
        // Increase our entry counter
        m_numEntriesOnRingBuffer.incrementAndGet();
    }

    @Override
    public void onEvent(SampleBatchEvent event) throws Exception {
        // We'd expect the logs from this thread to be in collectd.log
        Logging.putPrefix("collectd");

        List<Sample> samples = event.getSamples();

        // Partition the samples into collections smaller then max_batch_size
        for (List<Sample> batch : Lists.partition(samples, m_maxBatchSize)) {
            try {
                if (event.isIndexOnly() && !NewtsUtils.DISABLE_INDEXING) {
                    LOG.debug("Indexing {} samples", batch.size());
                    m_indexer.update(batch);
                } else {
                    LOG.debug("Inserting {} samples", batch.size());
                    m_sampleRepository.insert(batch);
                }

                if (LOG.isDebugEnabled()) {
                    String uniqueResourceIds = batch.stream()
                        .map(s -> s.getResource().getId())
                        .distinct()
                        .collect(Collectors.joining(", "));
                    LOG.debug("Successfully inserted samples for resources with ids {}", uniqueResourceIds);
                }
            } catch (Throwable t) {
                RATE_LIMITED_LOGGER.error("An error occurred while inserting samples. Some sample may be lost.", t);
            }
        }

        // Decrement our entry counter
        m_numEntriesOnRingBuffer.decrementAndGet();
    }

    private static final EventTranslatorOneArg<SampleBatchEvent, List<Sample>> TRANSLATOR =
            new EventTranslatorOneArg<SampleBatchEvent, List<Sample>>() {
                public void translateTo(SampleBatchEvent event, long sequence, List<Sample> samples) {
                    event.setIndexOnly(false);
                    event.setSamples(samples);
                }
            };

    private static final EventTranslatorOneArg<SampleBatchEvent, List<Sample>> INDEX_ONLY_TRANSLATOR =
            new EventTranslatorOneArg<SampleBatchEvent, List<Sample>>() {
                public void translateTo(SampleBatchEvent event, long sequence, List<Sample> samples) {
                    event.setIndexOnly(true);
                    event.setSamples(samples);
                }
            };

    public void setSampleRepository(SampleRepository sampleRepository) {
        m_sampleRepository = sampleRepository;
    }

    public void setIndexer(Indexer indexer) {
        m_indexer = indexer;
    }
}
