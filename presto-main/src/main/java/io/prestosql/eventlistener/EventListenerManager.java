/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.eventlistener;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.prestosql.spi.classloader.ThreadContextClassLoader;
import io.prestosql.spi.eventlistener.AuditLogEvent;
import io.prestosql.spi.eventlistener.EventListener;
import io.prestosql.spi.eventlistener.EventListenerFactory;
import io.prestosql.spi.eventlistener.QueryCompletedEvent;
import io.prestosql.spi.eventlistener.QueryCreatedEvent;
import io.prestosql.spi.eventlistener.SplitCompletedEvent;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static io.airlift.configuration.ConfigurationLoader.loadPropertiesFrom;
import static java.util.Objects.requireNonNull;

public class EventListenerManager
{
    private static final Logger log = Logger.get(EventListenerManager.class);
    private static final File EVENT_LISTENER_CONFIGURATION = new File("etc/event-listener.properties");
    private static final String EVENT_LISTENER_PROPERTY_NAME = "event-listener.name";
    private static String logOutput = "etc/log/";
    private static String logconversionpattern = "yyyy-MM-dd.HH";

    private final Map<String, EventListenerFactory> eventListenerFactories = new ConcurrentHashMap<>();
    private final AtomicReference<Optional<EventListener>> configuredEventListener = new AtomicReference<>(Optional.empty());

    public void addEventListenerFactory(EventListenerFactory eventListenerFactory)
    {
        requireNonNull(eventListenerFactory, "eventListenerFactory is null");

        if (eventListenerFactories.putIfAbsent(eventListenerFactory.getName(), eventListenerFactory) != null) {
            throw new IllegalArgumentException(String.format("Event listener '%s' is already registered", eventListenerFactory.getName()));
        }
    }

    public void loadConfiguredEventListener()
            throws Exception
    {
        if (EVENT_LISTENER_CONFIGURATION.exists()) {
            Map<String, String> properties = new HashMap<>(loadPropertiesFrom(EVENT_LISTENER_CONFIGURATION.getPath()));

            String eventListenerName = properties.remove(EVENT_LISTENER_PROPERTY_NAME);
            checkArgument(!isNullOrEmpty(eventListenerName),
                    "Access control configuration %s does not contain %s", EVENT_LISTENER_CONFIGURATION.getAbsoluteFile(), EVENT_LISTENER_PROPERTY_NAME);
            logOutput = properties.get("hetu.auditlog.logoutput");
            logconversionpattern = properties.get("hetu.auditlog.logconversionpattern");
            setConfiguredEventListener(eventListenerName, properties);
        }
    }

    @VisibleForTesting
    protected void setConfiguredEventListener(String name, Map<String, String> properties)
    {
        requireNonNull(name, "name is null");
        requireNonNull(properties, "properties is null");

        log.info("-- Loading event listener --");

        EventListenerFactory eventListenerFactory = eventListenerFactories.get(name);
        checkState(eventListenerFactory != null, "Event listener %s is not registered", name);

        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(eventListenerFactory.getClass().getClassLoader())) {
            EventListener eventListener = eventListenerFactory.create(ImmutableMap.copyOf(properties));
            this.configuredEventListener.set(Optional.of(eventListener));
        }

        log.info("-- Loaded event listener %s --", name);
    }

    public String getLogOutput()
    {
        return logOutput;
    }

    public String getLogconversionpattern()
    {
        return logconversionpattern;
    }

    public void queryCompleted(QueryCompletedEvent queryCompletedEvent)
    {
        if (configuredEventListener.get().isPresent()) {
            configuredEventListener.get().get().queryCompleted(queryCompletedEvent);
        }
    }

    public void queryCreated(QueryCreatedEvent queryCreatedEvent)
    {
        if (configuredEventListener.get().isPresent()) {
            configuredEventListener.get().get().queryCreated(queryCreatedEvent);
        }
    }

    public void splitCompleted(SplitCompletedEvent splitCompletedEvent)
    {
        if (configuredEventListener.get().isPresent()) {
            configuredEventListener.get().get().splitCompleted(splitCompletedEvent);
        }
    }

    //Expand event, it includes
    //1.user login and logout
    //2.cluster node added and deleted
    //3.openLooKeng start and close
    public void eventEnhanced(AuditLogEvent auditLogEvent)
    {
        if (configuredEventListener.get().isPresent()) {
            configuredEventListener.get().get().auditLogged(auditLogEvent);
        }
    }
}
