/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.kubernetes;

import java.util.concurrent.ExecutorService;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.camel.spi.EndpointServiceLocation;
import org.apache.camel.spi.UriParam;
import org.apache.camel.support.DefaultEndpoint;

/**
 * The base kubernetes endpoint allows to work with Kubernetes PaaS.
 */
public abstract class AbstractKubernetesEndpoint extends DefaultEndpoint implements EndpointServiceLocation {

    @UriParam
    private KubernetesConfiguration configuration;

    private transient KubernetesClient client;

    protected AbstractKubernetesEndpoint(String uri, AbstractKubernetesComponent component, KubernetesConfiguration config) {
        super(uri, component);
        this.configuration = config;
    }

    @Override
    public String getServiceUrl() {
        String answer = configuration.getMasterUrl();
        if (answer == null || answer.isEmpty() && client != null) {
            answer = client.getMasterUrl().toString();
        }
        return answer;
    }

    @Override
    public String getServiceProtocol() {
        return "rest";
    }

    public KubernetesConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        client = KubernetesHelper.getKubernetesClient(configuration);
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        if (client != null && configuration.getKubernetesClient() == null) {
            client.close();
        }
    }

    public ExecutorService createExecutor(Object source) {
        return getCamelContext().getExecutorServiceManager().newFixedThreadPool(source, "KubernetesConsumer",
                configuration.getPoolSize());
    }

    public KubernetesClient getKubernetesClient() {
        return client;
    }

    /**
     * The kubernetes Configuration
     */
    public KubernetesConfiguration getKubernetesConfiguration() {
        return configuration;
    }

}
