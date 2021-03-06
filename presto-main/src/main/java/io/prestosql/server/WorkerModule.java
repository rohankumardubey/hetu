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
package io.prestosql.server;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.prestosql.execution.QueryManager;
import io.prestosql.execution.resourcegroups.NoOpResourceGroupManager;
import io.prestosql.execution.resourcegroups.ResourceGroupManager;
import io.prestosql.failuredetector.FailureDetector;
import io.prestosql.failuredetector.NoOpFailureDetector;
import io.prestosql.failuredetector.WorkerGossipFailureDetectorModule;
import io.prestosql.queryeditorui.QueryEditorConfig;
import io.prestosql.server.security.NoOpWebUIAuthenticator;
import io.prestosql.server.security.WebUIAuthenticator;
import io.prestosql.statestore.NoOpStateStoreLauncher;
import io.prestosql.statestore.StateStoreLauncher;
import io.prestosql.transaction.NoOpTransactionManager;
import io.prestosql.transaction.TransactionManager;

import javax.inject.Singleton;

import static com.google.common.reflect.Reflection.newProxy;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;

public class WorkerModule
        implements Module
{
    private boolean gossip;

    public WorkerModule(boolean gossip)
    {
        this.gossip = gossip;
    }

    public WorkerModule()
    {
        this(false);
    }

    @Override
    public void configure(Binder binder)
    {
        configBinder(binder).bindConfig(QueryEditorConfig.class);
        // Install no-op session supplier on workers, since only coordinators create sessions.
        binder.bind(SessionSupplier.class).to(NoOpSessionSupplier.class).in(Scopes.SINGLETON);

        // Install no-op resource group manager on workers, since only coordinators manage resource groups.
        binder.bind(ResourceGroupManager.class).to(NoOpResourceGroupManager.class).in(Scopes.SINGLETON);

        // Install no-op transaction manager on workers, since only coordinators manage transactions.
        binder.bind(TransactionManager.class).to(NoOpTransactionManager.class).in(Scopes.SINGLETON);

        // failure detector
        if (gossip) {
            binder.install(new WorkerGossipFailureDetectorModule());
            jaxrsBinder(binder).bind(GossipStatusResource.class);
            httpClientBinder(binder).bindHttpClient("workerInfo", ForWorkerInfo.class);
        }
        else {
            // Install no-op failure detector on workers, since only coordinators need global node selection.
            binder.bind(FailureDetector.class).to(NoOpFailureDetector.class).in(Scopes.SINGLETON);
        }

        // HACK: this binding is needed by SystemConnectorModule, but will only be used on the coordinator
        binder.bind(QueryManager.class).toInstance(newProxy(QueryManager.class, (proxy, method, args) -> {
            throw new UnsupportedOperationException();
        }));

        binder.bind(StateStoreLauncher.class).to(NoOpStateStoreLauncher.class).in(Scopes.SINGLETON);

        binder.bind(WebUIAuthenticator.class).to(NoOpWebUIAuthenticator.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    public static ResourceGroupManager<?> getResourceGroupManager(@SuppressWarnings("rawtypes") ResourceGroupManager manager)
    {
        return manager;
    }
}
