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
package org.apache.camel.api.management.mbean;

import java.util.Collection;

import javax.management.openmbean.TabularData;

import org.apache.camel.api.management.ManagedAttribute;
import org.apache.camel.api.management.ManagedOperation;

public interface ManagedRouteMBean extends ManagedPerformanceCounterMBean {

    @ManagedAttribute(description = "Route ID")
    String getRouteId();

    @ManagedAttribute(description = "Node Prefix ID")
    String getNodePrefixId();

    @ManagedAttribute(description = "Route Group")
    String getRouteGroup();

    @ManagedAttribute(description = "Is this route created from a route template (or Kamelet)")
    boolean isCreatedByRouteTemplate();

    @ManagedAttribute(description = "Is this route created from a Kamelet")
    boolean isCreatedByKamelet();

    @ManagedAttribute(description = "Route Properties")
    TabularData getRouteProperties();

    @ManagedAttribute(description = "Route Description")
    String getDescription();

    @ManagedAttribute(description = "Route Auto Startup")
    Boolean getAutoStartup();

    @ManagedAttribute(description = "Route Source Location")
    String getSourceLocation();

    @ManagedAttribute(description = "Route Source Location (Short)")
    String getSourceLocationShort();

    @ManagedAttribute(description = "Route Configuration ID")
    String getRouteConfigurationId();

    @ManagedAttribute(description = "Route Endpoint URI", mask = true)
    String getEndpointUri();

    @ManagedAttribute(description = "Route State")
    String getState();

    @ManagedAttribute(description = "Route Uptime [human readable text]")
    String getUptime();

    @ManagedAttribute(description = "Route Uptime [milliseconds]")
    long getUptimeMillis();

    @ManagedAttribute(description = "Camel ID")
    String getCamelId();

    @ManagedAttribute(description = "Camel ManagementName")
    String getCamelManagementName();

    @ManagedAttribute(description = "Tracing")
    Boolean getTracing();

    @ManagedAttribute(description = "Tracing")
    void setTracing(Boolean tracing);

    @ManagedAttribute(description = "Message History")
    Boolean getMessageHistory();

    @ManagedAttribute(description = "Whether security mask for Logging is enabled")
    Boolean getLogMask();

    @ManagedAttribute(description = "Route Policy List")
    String getRoutePolicyList();

    @ManagedAttribute(description = "Average load (inflight messages, not cpu) over the last minute")
    String getLoad01();

    @ManagedAttribute(description = "Average load (inflight messages, not cpu) over the last five minutes")
    String getLoad05();

    @ManagedAttribute(description = "Average load (inflight messages, not cpu) over the last fifteen minutes")
    String getLoad15();

    @ManagedAttribute(description = "Throughput message/second")
    String getThroughput();

    @ManagedOperation(description = "Start route")
    void start() throws Exception;

    @ManagedOperation(description = "Stop route")
    void stop() throws Exception;

    @ManagedOperation(description = "Stop and marks the route as failed (health-check reporting as DOWN)")
    void stopAndFail() throws Exception;

    @ManagedOperation(description = "Stop route (using timeout in seconds)")
    void stop(long timeout) throws Exception;

    @ManagedOperation(description = "Stop route, abort stop after timeout (in seconds)")
    boolean stop(Long timeout, Boolean abortAfterTimeout) throws Exception;

    @ManagedOperation(description = "Remove route (must be stopped)")
    boolean remove() throws Exception;

    @ManagedOperation(description = "Restarts route (1 second delay before starting)")
    void restart() throws Exception;

    @ManagedOperation(description = "Restarts route (using delay in seconds before starting)")
    void restart(long delay) throws Exception;

    @ManagedOperation(description = "Dumps the route as XML")
    String dumpRouteAsXml() throws Exception;

    @ManagedOperation(description = "Dumps the route as XML")
    String dumpRouteAsXml(boolean resolvePlaceholders) throws Exception;

    @ManagedOperation(description = "Dumps the route as XML")
    String dumpRouteAsXml(boolean resolvePlaceholders, boolean generatedIds) throws Exception;

    @ManagedOperation(description = "Dumps the route as YAML")
    String dumpRouteAsYaml() throws Exception;

    @ManagedOperation(description = "Dumps the route as YAML")
    String dumpRouteAsYaml(boolean resolvePlaceholders) throws Exception;

    @ManagedOperation(description = "Dumps the route as YAML")
    String dumpRouteAsYaml(boolean resolvePlaceholders, boolean uriAsParameters) throws Exception;

    @ManagedOperation(description = "Dumps the route as YAML")
    String dumpRouteAsYaml(boolean resolvePlaceholders, boolean uriAsParameters, boolean generatedIds) throws Exception;

    @ManagedOperation(description = "Dumps the route stats as XML")
    String dumpRouteStatsAsXml(boolean fullStats, boolean includeProcessors) throws Exception;

    @ManagedOperation(description = "Dumps the route stats as JSon")
    String dumpRouteStatsAsJSon(boolean fullStats, boolean includeProcessors) throws Exception;

    @ManagedOperation(description = "Dumps the route and steps stats as XML")
    String dumpStepStatsAsXml(boolean fullStats) throws Exception;

    @ManagedOperation(description = "Dumps the route with mappings between node ids and their source location/line-number (currently only XML and YAML routes supported) as XML")
    String dumpRouteSourceLocationsAsXml() throws Exception;

    @ManagedOperation(description = "Reset counters")
    void reset(boolean includeProcessors) throws Exception;

    @ManagedAttribute(description = "Oldest inflight exchange duration")
    Long getOldestInflightDuration();

    @ManagedAttribute(description = "Oldest inflight exchange id")
    String getOldestInflightExchangeId();

    @ManagedAttribute(description = "Is using route controller")
    Boolean getHasRouteController();

    @ManagedAttribute(description = "Last error")
    RouteError getLastError();

    @ManagedOperation(description = "IDs for the processors that are part of this route")
    Collection<String> processorIds() throws Exception;

    @ManagedOperation(description = "Updates the route from XML")
    void updateRouteFromXml(String xml) throws Exception;

    @ManagedAttribute(description = "Whether update route from XML is enabled")
    boolean isUpdateRouteEnabled();

    @ManagedAttribute(description = "Whether the consumer connects to remote or local systems")
    boolean isRemoteEndpoint();

}
