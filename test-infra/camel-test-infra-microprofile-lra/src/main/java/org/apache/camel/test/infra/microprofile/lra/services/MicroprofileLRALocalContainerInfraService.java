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
package org.apache.camel.test.infra.microprofile.lra.services;

import com.github.dockerjava.api.model.Network;
import org.apache.camel.spi.annotations.InfraService;
import org.apache.camel.test.infra.common.LocalPropertyResolver;
import org.apache.camel.test.infra.common.services.ContainerEnvironmentUtil;
import org.apache.camel.test.infra.common.services.ContainerService;
import org.apache.camel.test.infra.microprofile.lra.common.MicroprofileLRAProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@InfraService(service = MicroprofileLRAInfraService.class,
              description = "Transaction Manager for microservices that is based on the SAGA pattern for distributed transaction.",
              serviceAlias = { "microprofile" },
              serviceImplementationAlias = "lra")
public class MicroprofileLRALocalContainerInfraService
        implements MicroprofileLRAInfraService, ContainerService<GenericContainer> {
    public static final String CONTAINER_NAME = "microprofile-lra";

    private static final Logger LOG = LoggerFactory.getLogger(MicroprofileLRALocalContainerInfraService.class);

    private final GenericContainer container;

    public MicroprofileLRALocalContainerInfraService() {
        this(LocalPropertyResolver.getProperty(
                MicroprofileLRALocalContainerInfraService.class,
                MicroprofileLRAProperties.MICROPROFILE_LRA_CONTAINER));
    }

    public MicroprofileLRALocalContainerInfraService(String imageName) {
        container = initContainer(imageName, CONTAINER_NAME);
    }

    public MicroprofileLRALocalContainerInfraService(GenericContainer container) {
        this.container = container;
    }

    public GenericContainer initContainer(String imageName, String networkAlias) {
        class MicroprofileLRAContainer extends GenericContainer<MicroprofileLRAContainer> {
            public MicroprofileLRAContainer(boolean fixedPort) {
                super(DockerImageName.parse(imageName));

                withNetworkAliases(networkAlias)
                        .waitingFor(Wait.forListeningPort())
                        .waitingFor(Wait.forLogMessage(".*lra-coordinator-quarkus.*Listening on.*", 1));

                if (fixedPort) {
                    addFixedExposedPort(MicroprofileLRAProperties.DEFAULT_PORT, MicroprofileLRAProperties.DEFAULT_PORT);
                } else {
                    withExposedPorts(MicroprofileLRAProperties.DEFAULT_PORT);
                }
            }
        }

        return new MicroprofileLRAContainer(ContainerEnvironmentUtil.isFixedPort(this.getClass()));
    }

    @Override
    public void registerProperties() {
        System.setProperty(MicroprofileLRAProperties.SERVICE_ADDRESS, getServiceAddress());
        System.setProperty(MicroprofileLRAProperties.PORT, String.valueOf(port()));
        System.setProperty(MicroprofileLRAProperties.HOST, host());
    }

    @Override
    public void initialize() {
        LOG.info("Trying to start the Microprofile LRA container");
        container.start();

        registerProperties();
        LOG.info("Microprofile LRA instance running at {}", getServiceAddress());
    }

    @Override
    public void shutdown() {
        LOG.info("Stopping the Microprofile LRA container");
        container.stop();
    }

    @Override
    public GenericContainer getContainer() {
        return container;
    }

    @Override
    public String host() {
        return container.getHost();
    }

    @Override
    public int port() {
        return container.getMappedPort(MicroprofileLRAProperties.DEFAULT_PORT);
    }

    @Override
    public String callbackHost() {
        // Get host ip address from container
        Network bridgeNetwork = this.container.getDockerClient()
                .inspectNetworkCmd()
                .withNetworkId("bridge")
                .exec();

        String networkGateway = bridgeNetwork.getIpam().getConfig().stream()
                .filter(config -> config.getGateway() != null)
                .findAny()
                .map(Network.Ipam.Config::getGateway)
                .orElseThrow(() -> new IllegalStateException("Gateway cannot be found in the bridge network"));

        return networkGateway;
    }
}
