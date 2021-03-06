/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.clustering.infinispan.subsystem;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import javax.management.MBeanServer;

import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.global.GlobalJmxStatisticsConfigurationBuilder;
import org.infinispan.configuration.global.ShutdownHookBehavior;
import org.infinispan.configuration.global.TransportConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.CacheStarted;
import org.infinispan.notifications.cachemanagerlistener.annotation.CacheStopped;
import org.infinispan.notifications.cachemanagerlistener.event.CacheStartedEvent;
import org.infinispan.notifications.cachemanagerlistener.event.CacheStoppedEvent;
import org.jboss.as.clustering.infinispan.ChannelProvider;
import org.jboss.as.clustering.infinispan.DefaultEmbeddedCacheManager;
import org.jboss.as.clustering.infinispan.ExecutorProvider;
import org.jboss.as.clustering.infinispan.InfinispanLogger;
import org.jboss.as.clustering.infinispan.MBeanServerProvider;
import org.jboss.as.clustering.msc.AsynchronousService;
import org.jboss.logging.Logger;
import org.jboss.msc.service.ServiceName;
import org.jgroups.Channel;
import org.jgroups.util.TopologyUUID;

/**
 * @author Paul Ferraro
 */
@Listener
public class EmbeddedCacheManagerService extends AsynchronousService<EmbeddedCacheManager> {

    private static final Logger log = Logger.getLogger(EmbeddedCacheManagerService.class.getPackage().getName());
    private static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append(InfinispanExtension.SUBSYSTEM_NAME);

    public static ServiceName getServiceName(String name) {
        return (name != null) ? SERVICE_NAME.append(name) : SERVICE_NAME;
    }

    interface TransportConfiguration {
        Long getLockTimeout();
        Channel getChannel();
        Executor getExecutor();
    }

    interface Dependencies {
        TransportConfiguration getTransportConfiguration();
        MBeanServer getMBeanServer();
        Executor getListenerExecutor();
        ScheduledExecutorService getEvictionExecutor();
        ScheduledExecutorService getReplicationQueueExecutor();
    }

    private final String name;
    private final String defaultCache;
    private final Dependencies dependencies;
    private volatile EmbeddedCacheManager container;

    public EmbeddedCacheManagerService(String name, String defaultCache, Dependencies dependencies) {
        this.name = name;
        this.defaultCache = defaultCache;
        this.dependencies = dependencies;
    }

    /**
     * {@inheritDoc}
     * @see org.jboss.msc.value.Value#getValue()
     */
    @Override
    public EmbeddedCacheManager getValue() throws IllegalStateException, IllegalArgumentException {
        return this.container;
    }

    @Override
    protected void start() {

        GlobalConfigurationBuilder globalBuilder = new GlobalConfigurationBuilder();
        globalBuilder
            .shutdown().hookBehavior(ShutdownHookBehavior.DONT_REGISTER)
        ;

        // set up transport only if transport is required by some cache in the cache manager
        TransportConfiguration transport = this.dependencies.getTransportConfiguration();
        TransportConfigurationBuilder transportBuilder = globalBuilder.transport();

        if (transport != null) {
            // See ISPN-1675
            // transportBuilder.transport(new ChannelTransport(transport.getChannel()));
            ChannelProvider.init(transportBuilder, transport.getChannel());
            Long timeout = transport.getLockTimeout();
            if (timeout != null) {
                transportBuilder.distributedSyncTimeout(timeout.longValue());
            }
            // Topology is retrieved from the channel
            Channel channel = transport.getChannel();
            if(channel.getAddress() instanceof TopologyUUID) {
                TopologyUUID topologyAddress = (TopologyUUID) channel.getAddress();
                String site = topologyAddress.getSiteId();
                if (site != null) {
                    transportBuilder.siteId(site);
                }
                String rack = topologyAddress.getRackId();
                if (rack != null) {
                    transportBuilder.rackId(rack);
                }
                String machine = topologyAddress.getMachineId();
                if (machine != null) {
                    transportBuilder.machineId(machine);
                }
            }
            transportBuilder.clusterName(this.name);

            Executor executor = transport.getExecutor();
            if (executor != null) {
                // See ISPN-1675
                // globalBuilder.asyncTransportExecutor().factory(new ManagedExecutorFactory(executor));
                ExecutorProvider.initTransportExecutor(globalBuilder, executor);
            }
        }

        Executor listenerExecutor = this.dependencies.getListenerExecutor();
        if (listenerExecutor != null) {
            // See ISPN-1675
            // globalBuilder.asyncListenerExecutor().factory(new ManagedExecutorFactory(listenerExecutor));
            ExecutorProvider.initListenerExecutor(globalBuilder, listenerExecutor);
        }
        ScheduledExecutorService evictionExecutor = this.dependencies.getEvictionExecutor();
        if (evictionExecutor != null) {
            // See ISPN-1675
            // globalBuilder.evictionScheduledExecutor().factory(new ManagedScheduledExecutorFactory(evictionExecutor));
            ExecutorProvider.initEvictionExecutor(globalBuilder, evictionExecutor);
        }
        ScheduledExecutorService replicationQueueExecutor = this.dependencies.getReplicationQueueExecutor();
        if (replicationQueueExecutor != null) {
            // See ISPN-1675
            // globalBuilder.replicationQueueScheduledExecutor().factory(new ManagedScheduledExecutorFactory(replicationQueueExecutor));
            ExecutorProvider.initReplicationQueueExecutor(globalBuilder, replicationQueueExecutor);
        }

        GlobalJmxStatisticsConfigurationBuilder jmxBuilder = globalBuilder.globalJmxStatistics().cacheManagerName(this.name);

        MBeanServer server = this.dependencies.getMBeanServer();
        if (server != null) {
            jmxBuilder.enable()
                .mBeanServerLookup(new MBeanServerProvider(server))
                .jmxDomain(SERVICE_NAME.getCanonicalName())
                .allowDuplicateDomains(true)
            ;
        } else {
            jmxBuilder.disable();
        }

        // create the cache manager
        this.container = new DefaultEmbeddedCacheManager(globalBuilder.build(), this.defaultCache);
        this.container.addListener(this);
        this.container.start();
        log.debugf("%s cache container started", this.name);
    }

    @Override
    protected void stop() {
        if ((this.container != null) && this.container.getStatus().allowInvocations()) {
            this.container.stop();
            this.container.removeListener(this);
            log.debugf("%s cache container stopped", this.name);
        }
    }

    @CacheStarted
    public void cacheStarted(CacheStartedEvent event) {
        InfinispanLogger.ROOT_LOGGER.cacheStarted(event.getCacheName(), new DefaultEmbeddedCacheManager(event.getCacheManager(), null).getCacheManagerConfiguration().globalJmxStatistics().cacheManagerName());
    }

    @CacheStopped
    public void cacheStopped(CacheStoppedEvent event) {
        InfinispanLogger.ROOT_LOGGER.cacheStopped(event.getCacheName(), new DefaultEmbeddedCacheManager(event.getCacheManager(), null).getCacheManagerConfiguration().globalJmxStatistics().cacheManagerName());
    }
}
