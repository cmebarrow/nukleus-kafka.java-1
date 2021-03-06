/**
 * Copyright 2016-2018 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.kafka.internal;

import static java.lang.String.format;
import static org.reaktivity.nukleus.route.RouteKind.CLIENT;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.Configuration;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.NukleusBuilder;
import org.reaktivity.nukleus.NukleusFactorySpi;
import org.reaktivity.nukleus.kafka.internal.memory.CountingMemoryManager;
import org.reaktivity.nukleus.kafka.internal.memory.DefaultMemoryManager;
import org.reaktivity.nukleus.kafka.internal.memory.MemoryLayout;
import org.reaktivity.nukleus.kafka.internal.memory.MemoryManager;
import org.reaktivity.nukleus.kafka.internal.stream.ClientStreamFactoryBuilder;
import org.reaktivity.nukleus.kafka.internal.stream.KafkaError;
import org.reaktivity.nukleus.kafka.internal.stream.NetworkConnectionPool;
import org.reaktivity.nukleus.kafka.internal.types.KafkaHeaderFW;
import org.reaktivity.nukleus.kafka.internal.types.ListFW;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;
import org.reaktivity.nukleus.kafka.internal.types.control.KafkaRouteExFW;
import org.reaktivity.nukleus.kafka.internal.types.control.RouteFW;
import org.reaktivity.nukleus.kafka.internal.util.DelayedTaskScheduler;

public final class KafkaNukleusFactorySpi implements NukleusFactorySpi, Nukleus
{
    public static final String MESSAGE_CACHE_BUFFER_ACQUIRES = "message.cache.buffer.acquires";

    private static final MemoryManager OUT_OF_SPACE_MEMORY_MANAGER = new MemoryManager()
    {

        @Override
        public long acquire(
            int capacity)
        {
            return -1;
        }

        @Override
        public long resolve(
            long address)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(
            long address,
            int capacity)
        {
            throw new UnsupportedOperationException();
        }

    };

    private MemoryManager memoryManager = null;
    private MemoryLayout memoryLayout;

    private final Map<String, Long2ObjectHashMap<NetworkConnectionPool>> connectionPools = new LinkedHashMap<>();

    private final RouteFW routeRO = new RouteFW();
    private final KafkaRouteExFW routeExRO = new KafkaRouteExFW();

    private List<RouteFW> routesToProcess = new ArrayList<>();
    private BiFunction<String, Long, NetworkConnectionPool> connectionPoolFactory;

    private KafkaConfiguration kafkaConfig;

    @Override
    public String name()
    {
        return "kafka";
    }

    @Override
    public Nukleus create(
        Configuration config,
        NukleusBuilder builder)
    {
        kafkaConfig = new KafkaConfiguration(config);
        DelayedTaskScheduler scheduler = new DelayedTaskScheduler();

        ClientStreamFactoryBuilder streamFactoryBuilder = new ClientStreamFactoryBuilder(kafkaConfig,
                this::supplyMemoryManager, connectionPools, this::setConnectionPoolFactory, scheduler);

        return builder.streamFactory(CLIENT, streamFactoryBuilder)
                      .routeHandler(CLIENT, this::handleRoute)
                      .inject(scheduler::process)
                      .inject(this)
                      .build();
    }

    private MemoryManager supplyMemoryManager(
        KafkaCounters counters)
    {
        if (memoryManager == null)
        {
            memoryManager = createMemoryManager(counters);
        }
        return memoryManager;
    }

    private MemoryManager createMemoryManager(
        KafkaCounters counters)
    {
        MemoryManager result;
        long capacity = kafkaConfig.messageCacheCapacity();
        if (capacity == 0)
        {
            result = OUT_OF_SPACE_MEMORY_MANAGER;
        }
        else
        {
            @SuppressWarnings("deprecation")
            final MemoryLayout memoryLayout = new MemoryLayout.Builder()
                    // TODO: non-deprecated way of getting nukleus's home directory; change name of memory0?
                    .path(kafkaConfig.directory().resolve("kafka").resolve("memory0"))
                    .minimumBlockSize(kafkaConfig.messageCacheBlockCapacity())
                    .capacity(capacity)
                    .create(true)
                    .build();
            this.memoryLayout = memoryLayout;
            MemoryManager memoryManager = new DefaultMemoryManager(memoryLayout, counters);
            result = new CountingMemoryManager(
                memoryManager,
                counters.cacheBufferAcquires,
                counters.cacheBufferReleases);
        }
        return result;
    }

    public boolean handleRoute(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        boolean result = true;
        switch(msgTypeId)
        {
        case RouteFW.TYPE_ID:
            {
                RouteFW route = routeRO.wrap(buffer, index, index + length);
                final OctetsFW extension = route.extension();
                if (extension.sizeof() > 0)
                {
                    MutableDirectBuffer routeBuffer = new UnsafeBuffer(new byte[length]);
                    buffer.getBytes(index,  routeBuffer, 0, length);
                    routesToProcess.add(new RouteFW().wrap(routeBuffer, 0, length));
                }
            }
            break;
        default:
            break;
        }
        return result;
    }

    @Override
    public void close() throws Exception
    {
        if (memoryLayout != null)
        {
            memoryLayout.close();
        }
    }

    @Override
    public int process()
    {
        if (!routesToProcess.isEmpty() && connectionPoolFactory != null)
        {
            processRoutes(routesToProcess);
            routesToProcess.clear();
        }
        return 0;
    }

    public void processRoutes(
        List<RouteFW> routes)
    {
        for (RouteFW route : routes)
        {
            final OctetsFW extension = route.extension();
            if (extension.sizeof() > 0)
            {
                final KafkaRouteExFW routeEx = extension.get(routeExRO::wrap);
                final String topicName = routeEx.topicName().asString();

                final String networkName = route.target().asString();
                final long networkRef = route.targetRef();

                Long2ObjectHashMap<NetworkConnectionPool> connectionPoolsByRef =
                    connectionPools.computeIfAbsent(networkName, name -> new Long2ObjectHashMap<>());

                NetworkConnectionPool connectionPool = connectionPoolsByRef.computeIfAbsent(networkRef, ref ->
                    connectionPoolFactory.apply(networkName, networkRef));

                final ListFW<KafkaHeaderFW> headers = routeEx.headers();
                ListFW<KafkaHeaderFW> headersCopy = new ListFW<KafkaHeaderFW>(new KafkaHeaderFW());
                headersCopy.wrap(headers.buffer(), headers.offset(), headers.limit());
                connectionPool.addRoute(topicName, headersCopy, kafkaConfig.topicBootstrapEnabled(),
                        this::onKafkaError);
            }
        }
    }

    private void onKafkaError(
        KafkaError errorCode,
        String topicName)
    {
        switch(errorCode)
        {
        case UNKNOWN_TOPIC_OR_PARTITION:
            System.out.println(format(
                "WARNING: bootstrap failed for topic \"%s\" with error \"unknown topic\"",
                topicName));
            break;
        default:
            System.out.println(format(
                "WARNING: bootstrap failed while getting metadata for topic \"%s\" with error code %d",
                topicName, errorCode));
        }
    }

    private void setConnectionPoolFactory(
        BiFunction<String, Long, NetworkConnectionPool> connectionPoolFactory)
    {
        this.connectionPoolFactory = connectionPoolFactory;
    }
}
