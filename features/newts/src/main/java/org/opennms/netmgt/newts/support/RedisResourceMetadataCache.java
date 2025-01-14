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
package org.opennms.netmgt.newts.support;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.nustaq.serialization.FSTConfiguration;
import org.opennms.newts.api.Context;
import org.opennms.newts.api.Resource;
import org.opennms.newts.cassandra.search.ResourceIdSplitter;
import org.opennms.newts.cassandra.search.ResourceMetadata;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

/**
 * A caching strategy that stores the {@link org.opennms.newts.cassandra.search.ResourceMetadata} in
 * a Redis database.
 *
 * Can be used when the cache needs to be shared amongst several JVMs, or when persistence is required.
 *
 * Note that this cache will operate slower than the {@link GuavaSearchableResourceMetadataCache} since
 * it requires network I/O and serialization/deserialization of the associated structures. However, this
 * cache will also operate with significantly less memory.
 *
 * FST is used for serialization instead Java's default implementation.
 *
 * @author jwhite
 */
public class RedisResourceMetadataCache implements SearchableResourceMetadataCache {

    private static final String METADATA_PREFIX = "_M";

    private static final String SEARCH_PREFIX = "_S";

    private static final FSTConfiguration conf = FSTConfiguration.createDefaultConfiguration();

    private static final Joiner m_keyJoiner = Joiner.on(ResourceIdSplitter.SEPARATOR);

    private final ResourceIdSplitter m_resourceIdSplitter;

    private final JedisPool m_pool;

    @Inject
    public RedisResourceMetadataCache(@Named("redis.hostname") String hostname, @Named("redis.port") Integer port, @Named("newts.writer_threads") Integer numWriterThreads, @Named("newtsMetricRegistry") MetricRegistry registry, ResourceIdSplitter resourceIdSplitter) {
        Preconditions.checkNotNull(hostname, " hostname argument");
        Preconditions.checkNotNull(port, "port argument");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMinIdle(numWriterThreads);
        poolConfig.setMaxTotal(numWriterThreads * 4);
        m_pool = new JedisPool(poolConfig, hostname, port);

        Preconditions.checkNotNull(registry, "registry argument");
        registry.register(MetricRegistry.name("cache", "size"),
                new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        try (Jedis jedis = m_pool.getResource()) {
                            return jedis.dbSize();
                        }
                    }
                });
        registry.register(MetricRegistry.name("cache", "max-size"),
                new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return 0L;
                    }
                });

        m_resourceIdSplitter = Preconditions.checkNotNull(resourceIdSplitter, "resourceIdSplitter argument");
    }

    @Override
    public void merge(Context context, Resource resource, ResourceMetadata metadata) {
        final Optional<ResourceMetadata> o = get(context, resource);

        try (Jedis jedis = m_pool.getResource()) {
            if (!o.isPresent()) {
                final ResourceMetadata newMetadata = new ResourceMetadata();
                newMetadata.merge(metadata);
                final Transaction t = jedis.multi();
                final byte[] key = key(METADATA_PREFIX, context.getId(), resource.getId());
                t.set(key, conf.asByteArray(newMetadata));

                // Index the key, element by element, in order to support calls to getResourceIdsWithPrefix()
                final List<String> elements = Lists.newArrayList(SEARCH_PREFIX, context.getId());
                for (String el : m_resourceIdSplitter.splitIdIntoElements(resource.getId())) {
                    elements.add(el);
                    t.lpush(m_resourceIdSplitter.joinElementsToId(elements).getBytes(), key);
                }

                // Update the keys in transaction
                t.exec();
            } else if (o.get().merge(metadata)) {
                // Update the value stored in the cache if it was changed as a result of the merge
                jedis.set(key(METADATA_PREFIX, context.getId(), resource.getId()), conf.asByteArray(o.get()));
            }
        }
    }

    @Override
    public Optional<ResourceMetadata> get(Context context, Resource resource) {
        try (Jedis jedis = m_pool.getResource()) {
            final byte[] bytes = jedis.get(key(METADATA_PREFIX, context.getId(), resource.getId()));
            return (bytes != null) ? Optional.of((ResourceMetadata)conf.asObject(bytes)): Optional.absent();
        }
    }

    @Override
    public void delete(final Context context, final Resource resource) {
        try (Jedis jedis = m_pool.getResource()) {
            jedis.del(key(METADATA_PREFIX, context.getId(), resource.getId()));
        }
    }

    @Override
    public List<String> getResourceIdsWithPrefix(Context context, String resourceIdPrefix) {
        try (Jedis jedis = m_pool.getResource()) {
            final List<String> elements = Lists.newArrayList(SEARCH_PREFIX, context.getId());
            elements.addAll(m_resourceIdSplitter.splitIdIntoElements(resourceIdPrefix));
            return jedis.lrange(m_resourceIdSplitter.joinElementsToId(elements).getBytes(), 0, -1).stream()
                    .map(bytes -> resourceId(METADATA_PREFIX, context.getId(), bytes))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Creates a unique key for the (prefix, contextId, resourceId) tuple
     */
    private static byte[] key(String prefix, String contextId, String resourceId) {
        return m_keyJoiner.join(prefix, contextId, resourceId).getBytes();
    }

    /**
     * Extracts the resource id from a key produced by {@link #key(String, String, String)}
     */
    private String resourceId(String prefix, String contextId, byte[] key) {
        return new String(key).substring(prefix.length() + contextId.length() + 2);
    }
}
