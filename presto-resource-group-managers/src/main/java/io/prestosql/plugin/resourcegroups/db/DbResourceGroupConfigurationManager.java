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
package io.prestosql.plugin.resourcegroups.db;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.airlift.log.Logger;
import io.airlift.stats.CounterStat;
import io.airlift.units.Duration;
import io.prestosql.plugin.resourcegroups.AbstractResourceConfigurationManager;
import io.prestosql.plugin.resourcegroups.ManagerSpec;
import io.prestosql.plugin.resourcegroups.ResourceGroupIdTemplate;
import io.prestosql.plugin.resourcegroups.ResourceGroupSelector;
import io.prestosql.plugin.resourcegroups.ResourceGroupSpec;
import io.prestosql.plugin.resourcegroups.SelectorSpec;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.memory.ClusterMemoryPoolManager;
import io.prestosql.spi.resourcegroups.ResourceGroup;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import io.prestosql.spi.resourcegroups.SelectionContext;
import io.prestosql.spi.resourcegroups.SelectionCriteria;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.units.Duration.succinctNanos;
import static io.prestosql.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.prestosql.spi.StandardErrorCode.CONFIGURATION_UNAVAILABLE;
import static java.util.Collections.synchronizedList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public class DbResourceGroupConfigurationManager
        extends AbstractResourceConfigurationManager
{
    private static final Logger log = Logger.get(DbResourceGroupConfigurationManager.class);
    private final ResourceGroupsDao dao;
    private final ConcurrentMap<ResourceGroupId, ResourceGroup> groups = new ConcurrentHashMap<>();
    @GuardedBy("this")
    private Map<ResourceGroupIdTemplate, ResourceGroupSpec> resourceGroupSpecs = new HashMap<>();
    private final ConcurrentMap<ResourceGroupIdTemplate, List<ResourceGroupId>> configuredGroups = new ConcurrentHashMap<>();
    private final AtomicReference<List<ResourceGroupSpec>> rootGroups = new AtomicReference<>(ImmutableList.of());
    private final AtomicReference<List<ResourceGroupSelector>> selectors = new AtomicReference<>();
    private final AtomicReference<Optional<Duration>> cpuQuotaPeriod = new AtomicReference<>(Optional.empty());
    private final ScheduledExecutorService configExecutor = newSingleThreadScheduledExecutor(daemonThreadsNamed("DbResourceGroupConfigurationManager"));
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong lastRefresh = new AtomicLong();
    private final String environment;
    private final Duration maxRefreshInterval;
    private final boolean exactMatchSelectorEnabled;

    private final CounterStat refreshFailures = new CounterStat();

    @Inject
    public DbResourceGroupConfigurationManager(ClusterMemoryPoolManager memoryPoolManager, DbResourceGroupConfig config, ResourceGroupsDao dao, @ForEnvironment String environment)
    {
        super(memoryPoolManager);
        requireNonNull(memoryPoolManager, "memoryPoolManager is null");
        requireNonNull(config, "config is null");
        requireNonNull(dao, "daoProvider is null");
        this.environment = requireNonNull(environment, "environment is null");
        this.maxRefreshInterval = config.getMaxRefreshInterval();
        this.exactMatchSelectorEnabled = config.getExactMatchSelectorEnabled();
        this.dao = dao;
        this.dao.createResourceGroupsGlobalPropertiesTable();
        this.dao.createResourceGroupsTable();
        this.dao.createSelectorsTable();
        if (exactMatchSelectorEnabled) {
            this.dao.createExactMatchSelectorsTable();
        }
        load();
    }

    @Override
    protected Optional<Duration> getCpuQuotaPeriod()
    {
        return cpuQuotaPeriod.get();
    }

    @Override
    protected List<ResourceGroupSpec> getRootGroups()
    {
        if (lastRefresh.get() == 0) {
            throw new PrestoException(CONFIGURATION_UNAVAILABLE, "Root groups cannot be fetched from database");
        }
        if (this.selectors.get().isEmpty()) {
            throw new PrestoException(CONFIGURATION_INVALID, "No root groups are configured");
        }

        return rootGroups.get();
    }

    @PreDestroy
    public void destroy()
    {
        configExecutor.shutdownNow();
    }

    @PostConstruct
    public void start()
    {
        if (started.compareAndSet(false, true)) {
            configExecutor.scheduleWithFixedDelay(this::load, 1, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void configure(ResourceGroup group, SelectionContext<ResourceGroupIdTemplate> criteria)
    {
        ResourceGroupSpec groupSpec = getMatchingSpec(group, criteria);
        if (groups.putIfAbsent(group.getId(), group) == null) {
            // If a new spec replaces the spec returned from getMatchingSpec the group will be reconfigured on the next run of load().
            configuredGroups.computeIfAbsent(criteria.getContext(), v -> synchronizedList(new ArrayList<>())).add(group.getId());
        }
        synchronized (getRootGroup(group.getId())) {
            configureGroup(group, groupSpec);
        }
    }

    @Override
    public Optional<SelectionContext<ResourceGroupIdTemplate>> match(SelectionCriteria criteria)
    {
        if (lastRefresh.get() == 0) {
            throw new PrestoException(CONFIGURATION_UNAVAILABLE, "Selectors cannot be fetched from database");
        }
        if (selectors.get().isEmpty()) {
            throw new PrestoException(CONFIGURATION_INVALID, "No selectors are configured");
        }

        return selectors.get().stream()
                .map(s -> s.match(criteria))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    @VisibleForTesting
    public List<ResourceGroupSelector> getSelectors()
    {
        if (lastRefresh.get() == 0) {
            throw new PrestoException(CONFIGURATION_UNAVAILABLE, "Selectors cannot be fetched from database");
        }
        if (selectors.get().isEmpty()) {
            throw new PrestoException(CONFIGURATION_INVALID, "No selectors are configured");
        }
        return selectors.get();
    }

    private synchronized Optional<Duration> getCpuQuotaPeriodFromDb()
    {
        List<ResourceGroupGlobalProperties> globalProperties = dao.getResourceGroupGlobalProperties();
        checkState(globalProperties.size() <= 1, "There is more than one cpu_quota_period");
        return (!globalProperties.isEmpty()) ? globalProperties.get(0).getCpuQuotaPeriod() : Optional.empty();
    }

    @VisibleForTesting
    public synchronized void load()
    {
        try {
            Map.Entry<ManagerSpec, Map<ResourceGroupIdTemplate, ResourceGroupSpec>> specsFromDb = buildSpecsFromDb();
            ManagerSpec managerSpec = specsFromDb.getKey();
            Map<ResourceGroupIdTemplate, ResourceGroupSpec> resourceGroupSpecMap = specsFromDb.getValue();
            Set<ResourceGroupIdTemplate> changedSpecs = new HashSet<>();
            Set<ResourceGroupIdTemplate> deletedSpecs = Sets.difference(this.resourceGroupSpecs.keySet(), resourceGroupSpecMap.keySet());

            for (Map.Entry<ResourceGroupIdTemplate, ResourceGroupSpec> entry : resourceGroupSpecMap.entrySet()) {
                if (!entry.getValue().sameConfig(this.resourceGroupSpecs.get(entry.getKey()))) {
                    changedSpecs.add(entry.getKey());
                }
            }

            this.resourceGroupSpecs = resourceGroupSpecMap;
            this.cpuQuotaPeriod.set(managerSpec.getCpuQuotaPeriod());
            this.rootGroups.set(managerSpec.getRootGroups());
            List<ResourceGroupSelector> selectorList = buildSelectors(managerSpec);
            if (exactMatchSelectorEnabled) {
                ImmutableList.Builder<ResourceGroupSelector> builder = ImmutableList.builder();
                builder.add(new DbSourceExactMatchSelector(environment, dao));
                builder.addAll(selectorList);
                this.selectors.set(builder.build());
            }
            else {
                this.selectors.set(selectorList);
            }

            configureChangedGroups(changedSpecs);
            disableDeletedGroups(deletedSpecs);

            if (lastRefresh.get() > 0) {
                for (ResourceGroupIdTemplate deleted : deletedSpecs) {
                    log.info("Resource group spec deleted %s", deleted);
                }
                for (ResourceGroupIdTemplate changed : changedSpecs) {
                    log.info("Resource group spec %s changed to %s", changed, resourceGroupSpecMap.get(changed));
                }
            }
            else {
                log.info("Loaded %s selectors and %s resource groups from database", this.selectors.get().size(), this.resourceGroupSpecs.size());
            }

            lastRefresh.set(System.nanoTime());
        }
        catch (Throwable e) {
            if (succinctNanos(System.nanoTime() - lastRefresh.get()).compareTo(maxRefreshInterval) > 0) {
                lastRefresh.set(0);
            }
            refreshFailures.update(1);
            log.error(e, "Error loading configuration from db");
        }
    }

    // Populate temporary data structures to build resource group specs and selectors from db
    private synchronized void populateFromDbHelper(Map<Long, ResourceGroupSpecBuilder> recordMap,
            Set<Long> rootGroupIds,
            Map<Long, ResourceGroupIdTemplate> resourceGroupIdTemplateMap,
            Map<Long, Set<Long>> subGroupIdsToBuild)
    {
        List<ResourceGroupSpecBuilder> records = dao.getResourceGroups(environment);
        for (ResourceGroupSpecBuilder record : records) {
            recordMap.put(record.getId(), record);
            if (!record.getParentId().isPresent()) {
                rootGroupIds.add(record.getId());
                resourceGroupIdTemplateMap.put(record.getId(), new ResourceGroupIdTemplate(record.getNameTemplate().toString()));
            }
            else {
                subGroupIdsToBuild.computeIfAbsent(record.getParentId().get(), k -> new HashSet<>()).add(record.getId());
            }
        }
    }

    private synchronized Map.Entry<ManagerSpec, Map<ResourceGroupIdTemplate, ResourceGroupSpec>> buildSpecsFromDb()
    {
        // New resource group spec map
        Map<ResourceGroupIdTemplate, ResourceGroupSpec> resourceGroupSpecHashMap = new HashMap<>();
        // Set of root group db ids
        Set<Long> rootGroupIds = new HashSet<>();
        // Map of id from db to resource group spec
        Map<Long, ResourceGroupSpec> resourceGroupSpecMap = new HashMap<>();
        // Map of id from db to resource group template id
        Map<Long, ResourceGroupIdTemplate> resourceGroupIdTemplateMap = new HashMap<>();
        // Map of id from db to resource group spec builder
        Map<Long, ResourceGroupSpecBuilder> recordMap = new HashMap<>();
        // Map of subgroup id's not yet built
        Map<Long, Set<Long>> subGroupIdsToBuild = new HashMap<>();
        populateFromDbHelper(recordMap, rootGroupIds, resourceGroupIdTemplateMap, subGroupIdsToBuild);
        // Build up resource group specs from leaf to root
        for (LinkedList<Long> queue = new LinkedList<>(rootGroupIds); !queue.isEmpty(); ) {
            Long id = queue.pollFirst();
            resourceGroupIdTemplateMap.computeIfAbsent(id, k -> {
                ResourceGroupSpecBuilder builder = recordMap.get(id);
                return ResourceGroupIdTemplate.forSubGroupNamed(
                        resourceGroupIdTemplateMap.get(builder.getParentId().get()),
                        builder.getNameTemplate().toString());
            });
            Set<Long> childrenToBuild = subGroupIdsToBuild.getOrDefault(id, ImmutableSet.of());
            // Add to resource group specs if no more child resource groups are left to build
            if (childrenToBuild.isEmpty()) {
                ResourceGroupSpecBuilder builder = recordMap.get(id);
                ResourceGroupSpec resourceGroupSpec = builder.build();
                resourceGroupSpecMap.put(id, resourceGroupSpec);
                // Add newly built spec to spec map
                resourceGroupSpecHashMap.put(resourceGroupIdTemplateMap.get(id), resourceGroupSpec);
                // Add this resource group spec to parent subgroups and remove id from subgroup ids to build
                builder.getParentId().ifPresent(parentId -> {
                    recordMap.get(parentId).addSubGroup(resourceGroupSpec);
                    subGroupIdsToBuild.get(parentId).remove(id);
                });
            }
            else {
                // Add this group back to queue since it still has subgroups to build
                queue.addFirst(id);
                // Add this group's subgroups to the queue so that when this id is dequeued again childrenToBuild will be empty
                queue.addAll(0, childrenToBuild);
            }
        }

        // Specs are built from db records, validate and return manager spec
        List<ResourceGroupSpec> resourceGroupSpecList = rootGroupIds.stream().map(resourceGroupSpecMap::get).collect(Collectors.toList());

        List<SelectorSpec> selectorSpecList = dao.getSelectors(environment)
                .stream()
                .map(selectorRecord ->
                        new SelectorSpec(
                                selectorRecord.getUserRegex(),
                                selectorRecord.getSourceRegex(),
                                selectorRecord.getQueryType(),
                                selectorRecord.getClientTags(),
                                selectorRecord.getSelectorResourceEstimate(),
                                resourceGroupIdTemplateMap.get(selectorRecord.getResourceGroupId()))
                ).collect(Collectors.toList());
        ManagerSpec managerSpec = new ManagerSpec(resourceGroupSpecList, selectorSpecList, getCpuQuotaPeriodFromDb());
        validateRootGroups(managerSpec);
        return new AbstractMap.SimpleImmutableEntry<>(managerSpec, resourceGroupSpecHashMap);
    }

    private synchronized void configureChangedGroups(Set<ResourceGroupIdTemplate> changedSpecs)
    {
        for (ResourceGroupIdTemplate resourceGroupIdTemplate : changedSpecs) {
            for (ResourceGroupId resourceGroupId : configuredGroups(resourceGroupIdTemplate)) {
                synchronized (getRootGroup(resourceGroupId)) {
                    configureGroup(groups.get(resourceGroupId), resourceGroupSpecs.get(resourceGroupIdTemplate));
                }
            }
        }
    }

    private synchronized void disableDeletedGroups(Set<ResourceGroupIdTemplate> deletedSpecs)
    {
        for (ResourceGroupIdTemplate resourceGroupIdTemplate : deletedSpecs) {
            for (ResourceGroupId resourceGroupId : configuredGroups(resourceGroupIdTemplate)) {
                disableGroup(groups.get(resourceGroupId));
            }
        }
    }

    private List<ResourceGroupId> configuredGroups(ResourceGroupIdTemplate idTemplate)
    {
        List<ResourceGroupId> groupIdList = configuredGroups.get(idTemplate);
        if (groupIdList == null) {
            return ImmutableList.of();
        }
        synchronized (groupIdList) { // configuredGroups values are synchronized lists
            return ImmutableList.copyOf(groupIdList);
        }
    }

    private synchronized void disableGroup(ResourceGroup group)
    {
        // Disable groups that are removed from the db
        group.setHardConcurrencyLimit(0);
        group.setMaxQueuedQueries(0);
    }

    private ResourceGroup getRootGroup(ResourceGroupId groupId)
    {
        ResourceGroupId resourceGroupId = groupId;
        Optional<ResourceGroupId> parent = resourceGroupId.getParent();
        while (parent.isPresent()) {
            resourceGroupId = parent.get();
            parent = resourceGroupId.getParent();
        }
        // GroupId is guaranteed to be in groups: it is added before the first call to this method in configure()
        return groups.get(resourceGroupId);
    }

    @Managed
    @Nested
    public CounterStat getRefreshFailures()
    {
        return refreshFailures;
    }
}
