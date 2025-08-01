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
package org.apache.camel.main;

import org.apache.camel.spi.BootstrapCloseable;
import org.apache.camel.spi.Configurer;
import org.apache.camel.spi.Metadata;

/**
 * Global configuration for Micrometer Metrics.
 */
@Configurer(extended = true)
public class MetricsConfigurationProperties implements BootstrapCloseable {

    private MainConfigurationProperties parent;

    private boolean enabled;
    @Metadata(defaultValue = "default", enums = "default,legacy")
    private String namingStrategy;
    @Metadata(defaultValue = "true")
    private boolean enableRoutePolicy = true;
    @Metadata(defaultValue = "all", enums = "all,route,context")
    private String routePolicyLevel = "all";
    private boolean enableMessageHistory;
    @Metadata(defaultValue = "true")
    private boolean enableExchangeEventNotifier = true;
    @Metadata(defaultValue = "true")
    private boolean baseEndpointURIExchangeEventNotifier = true;
    @Metadata(defaultValue = "true")
    private boolean enableRouteEventNotifier = true;
    @Metadata(defaultValue = "false")
    private boolean enableInstrumentedThreadPoolFactory;
    @Metadata(defaultValue = "true")
    private boolean clearOnReload = true;
    @Metadata(defaultValue = "false")
    private boolean skipCamelInfo = false;
    @Metadata(defaultValue = "0.0.4", enums = "0.0.4,1.0.0")
    private String textFormatVersion = "0.0.4";
    @Metadata
    private String binders;
    @Metadata(defaultValue = "/observe/metrics")
    private String path = "/observe/metrics";

    public MetricsConfigurationProperties(MainConfigurationProperties parent) {
        this.parent = parent;
    }

    public MainConfigurationProperties end() {
        return parent;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * To enable Micrometer metrics.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamingStrategy() {
        return namingStrategy;
    }

    /**
     * Controls the name style to use for metrics.
     *
     * Default = uses micrometer naming convention. Legacy = uses the classic naming style (camelCase)
     */
    public void setNamingStrategy(String namingStrategy) {
        this.namingStrategy = namingStrategy;
    }

    public boolean isEnableRoutePolicy() {
        return enableRoutePolicy;
    }

    /**
     * Set whether to enable the MicrometerRoutePolicyFactory for capturing metrics on route processing times.
     */
    public void setEnableRoutePolicy(boolean enableRoutePolicy) {
        this.enableRoutePolicy = enableRoutePolicy;
    }

    public String getRoutePolicyLevel() {
        return routePolicyLevel;
    }

    /**
     * Sets the level of information to capture. all = both context and routes.
     */
    public void setRoutePolicyLevel(String routePolicyLevel) {
        this.routePolicyLevel = routePolicyLevel;
    }

    public boolean isEnableMessageHistory() {
        return enableMessageHistory;
    }

    /**
     * Set whether to enable the MicrometerMessageHistoryFactory for capturing metrics on individual route node
     * processing times.
     *
     * Depending on the number of configured route nodes, there is the potential to create a large volume of metrics.
     * Therefore, this option is disabled by default.
     */
    public void setEnableMessageHistory(boolean enableMessageHistory) {
        this.enableMessageHistory = enableMessageHistory;
    }

    public boolean isEnableExchangeEventNotifier() {
        return enableExchangeEventNotifier;
    }

    /**
     * Set whether to enable the MicrometerExchangeEventNotifier for capturing metrics on exchange processing times.
     */
    public void setEnableExchangeEventNotifier(boolean enableExchangeEventNotifier) {
        this.enableExchangeEventNotifier = enableExchangeEventNotifier;
    }

    public boolean isBaseEndpointURIExchangeEventNotifier() {
        return baseEndpointURIExchangeEventNotifier;
    }

    /**
     * Whether to use static or dynamic values for Endpoint Name tags in captured metrics.
     *
     * By default, static values are used.
     *
     * When using dynamic tags, then a dynamic to (toD) can compute many different endpoint URIs that, can lead to many
     * tags as the URI is dynamic, so use this with care if setting this option to false.
     */
    public void setBaseEndpointURIExchangeEventNotifier(boolean baseEndpointURIExchangeEventNotifier) {
        this.baseEndpointURIExchangeEventNotifier = baseEndpointURIExchangeEventNotifier;
    }

    public boolean isEnableRouteEventNotifier() {
        return enableRouteEventNotifier;
    }

    /**
     * Set whether to enable the MicrometerRouteEventNotifier for capturing metrics on the total number of routes and
     * total number of routes running.
     */
    public void setEnableRouteEventNotifier(boolean enableRouteEventNotifier) {
        this.enableRouteEventNotifier = enableRouteEventNotifier;
    }

    public boolean isEnableInstrumentedThreadPoolFactory() {
        return enableInstrumentedThreadPoolFactory;
    }

    /**
     * Set whether to gather performance information about Camel Thread Pools by injecting an
     * InstrumentedThreadPoolFactory.
     */
    public void setEnableInstrumentedThreadPoolFactory(boolean enableInstrumentedThreadPoolFactory) {
        this.enableInstrumentedThreadPoolFactory = enableInstrumentedThreadPoolFactory;
    }

    public boolean isClearOnReload() {
        return clearOnReload;
    }

    /**
     * Clear the captured metrics data when Camel is reloading routes such as when using Camel JBang.
     */
    public void setClearOnReload(boolean clearOnReload) {
        this.clearOnReload = clearOnReload;
    }

    public boolean isSkipCamelInfo() {
        return skipCamelInfo;
    }

    /**
     * Skip the evaluation of "app.info" metric which contains runtime provider information (default, `false`).
     */
    public void setSkipCamelInfo(boolean skipCamelInfo) {
        this.skipCamelInfo = skipCamelInfo;
    }

    public String getTextFormatVersion() {
        return textFormatVersion;
    }

    /**
     * The text-format version to use with Prometheus scraping.
     *
     * 0.0.4 = text/plain; version=0.0.4; charset=utf-8 1.0.0 = application/openmetrics-text; version=1.0.0;
     * charset=utf-8
     */
    public void setTextFormatVersion(String textFormatVersion) {
        this.textFormatVersion = textFormatVersion;
    }

    public String getBinders() {
        return binders;
    }

    /**
     * Additional Micrometer binders to include such as jvm-memory, processor, jvm-thread, and so forth. Multiple
     * binders can be separated by comma.
     *
     * The following binders currently is available from Micrometer: class-loader, commons-object-pool2,
     * file-descriptor, hystrix-metrics-binder, jvm-compilation, jvm-gc, jvm-heap-pressure, jvm-info, jvm-memory,
     * jvm-thread, log4j2, logback, processor, uptime
     */
    public void setBinders(String binders) {
        this.binders = binders;
    }

    public String getPath() {
        return path;
    }

    /**
     * The path endpoint used to expose the metrics.
     */
    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public void close() {
        parent = null;
    }

    /**
     * Set whether to enable the MicrometerRoutePolicyFactory for capturing metrics on route processing times.
     */
    public MetricsConfigurationProperties withEnableRoutePolicy(boolean enableRoutePolicy) {
        this.enableRoutePolicy = enableRoutePolicy;
        return this;
    }

    /**
     * Sets the level of information to capture. all = both context and routes.
     */
    public MetricsConfigurationProperties withRoutePolicyLevel(String routePolicyLevel) {
        this.routePolicyLevel = routePolicyLevel;
        return this;
    }

    /**
     * To enable Micrometer metrics.
     */
    public MetricsConfigurationProperties withEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Controls the name style to use for metrics.
     *
     * Default = uses micrometer naming convention. Legacy = uses the classic naming style (camelCase)
     */
    public MetricsConfigurationProperties withNamingStrategy(String namingStrategy) {
        this.namingStrategy = namingStrategy;
        return this;
    }

    /**
     * Set whether to enable the MicrometerMessageHistoryFactory for capturing metrics on individual route node
     * processing times.
     *
     * Depending on the number of configured route nodes, there is the potential to create a large volume of metrics.
     * Therefore, this option is disabled by default.
     */
    public MetricsConfigurationProperties withEnableMessageHistory(boolean enableMessageHistory) {
        this.enableMessageHistory = enableMessageHistory;
        return this;
    }

    /**
     * Set whether to enable the MicrometerExchangeEventNotifier for capturing metrics on exchange processing times.
     */
    public MetricsConfigurationProperties withEnableExchangeEventNotifier(boolean enableExchangeEventNotifier) {
        this.enableExchangeEventNotifier = enableExchangeEventNotifier;
        return this;
    }

    /**
     * Set whether to enable the MicrometerRouteEventNotifier for capturing metrics on the total number of routes and
     * total number of routes running.
     */
    public MetricsConfigurationProperties witheEnableRouteEventNotifier(boolean enableRouteEventNotifier) {
        this.enableRouteEventNotifier = enableRouteEventNotifier;
        return this;
    }

    /**
     * Set whether to gather performance information about Camel Thread Pools by injecting an
     * InstrumentedThreadPoolFactory.
     */
    public MetricsConfigurationProperties withEnableInstrumentedThreadPoolFactory(boolean enableInstrumentedThreadPoolFactory) {
        this.enableInstrumentedThreadPoolFactory = enableInstrumentedThreadPoolFactory;
        return this;
    }

    /**
     * Clear the captured metrics data when Camel is reloading routes such as when using Camel JBang.
     */
    public MetricsConfigurationProperties withClearOnReload(boolean clearOnReload) {
        this.clearOnReload = clearOnReload;
        return this;
    }

    /**
     * Skip the evaluation of "app.info" metric which contains runtime provider information (default, `false`).
     */
    public MetricsConfigurationProperties withSkipCamelInfo(boolean skipCamelInfo) {
        this.skipCamelInfo = skipCamelInfo;
        return this;
    }

    /**
     * The text-format version to use with Prometheus scraping.
     *
     * 0.0.4 = text/plain; version=0.0.4; charset=utf-8 1.0.0 = application/openmetrics-text; version=1.0.0;
     * charset=utf-8
     */
    public MetricsConfigurationProperties withTextFormatVersion(String textFormatVersion) {
        this.textFormatVersion = textFormatVersion;
        return this;
    }

    /**
     * Additional Micrometer binders to include such as jvm-memory, processor, jvm-thread, and so forth. Multiple
     * binders can be separated by comma.
     *
     * The following binders currently is available from Micrometer: class-loader, commons-object-pool2,
     * file-descriptor, hystrix-metrics-binder, jvm-compilation, jvm-gc, jvm-heap-pressure, jvm-info, jvm-memory,
     * jvm-thread, log4j2, logback, processor, uptime
     */
    public MetricsConfigurationProperties withBinders(String binders) {
        this.binders = binders;
        return this;
    }

}
