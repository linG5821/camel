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
package org.apache.camel.main.download;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.main.util.VersionHelper;
import org.apache.camel.main.util.XmlHelper;
import org.apache.camel.support.service.ServiceHelper;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.tooling.maven.MavenArtifact;
import org.apache.camel.tooling.maven.MavenDownloader;
import org.apache.camel.tooling.maven.MavenDownloaderImpl;
import org.apache.camel.tooling.maven.MavenGav;
import org.apache.camel.tooling.maven.MavenResolutionException;
import org.apache.camel.tooling.maven.RemoteArtifactDownloadListener;
import org.apache.camel.tooling.maven.RepositoryResolver;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenDependencyDownloader extends ServiceSupport implements DependencyDownloader {

    private static final String MINIMUM_QUARKUS_VERSION = "2.0.0";

    private static final Logger LOG = LoggerFactory.getLogger(MavenDependencyDownloader.class);
    private static final String CP = System.getProperty("java.class.path");

    private String[] bootClasspath;
    private DownloadThreadPool threadPool;
    private boolean verbose;
    private ClassLoader classLoader;
    private CamelContext camelContext;
    private final Set<DownloadListener> downloadListeners = new LinkedHashSet<>();
    private final Set<ArtifactDownloadListener> artifactDownloadListeners = new LinkedHashSet<>();
    private final Map<String, DownloadRecord> downloadRecords = new HashMap<>();
    private KnownReposResolver knownReposResolver;
    private boolean download = true;

    // all maven-resolver work is delegated to camel-tooling-maven
    private MavenDownloader mavenDownloader;

    // repository URLs set from "camel.jbang.repos" property or --repos option.
    private String repositories;
    private boolean fresh;

    // settings.xml and settings-security.xml locations to be passed to MavenDownloader from camel-tooling-maven
    private String mavenSettings;
    private String mavenSettingsSecurity;
    // to make it easy to turn off maven central/snapshot
    boolean mavenCentralEnabled = true;
    boolean mavenApacheSnapshotEnabled = true;

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public KnownReposResolver getKnownReposResolver() {
        return knownReposResolver;
    }

    public void setKnownReposResolver(KnownReposResolver knownReposResolver) {
        this.knownReposResolver = knownReposResolver;
    }

    @Override
    public RepositoryResolver getRepositoryResolver() {
        if (mavenDownloader != null) {
            return mavenDownloader.getRepositoryResolver();
        } else {
            return null;
        }
    }

    @Override
    public void addDownloadListener(DownloadListener downloadListener) {
        CamelContextAware.trySetCamelContext(downloadListener, getCamelContext());
        downloadListeners.add(downloadListener);
    }

    @Override
    public void addArtifactDownloadListener(ArtifactDownloadListener downloadListener) {
        CamelContextAware.trySetCamelContext(downloadListener, getCamelContext());
        artifactDownloadListeners.add(downloadListener);
    }

    @Override
    public String getRepositories() {
        return repositories;
    }

    @Override
    public void setRepositories(String repositories) {
        this.repositories = repositories;
    }

    @Override
    public boolean isFresh() {
        return fresh;
    }

    @Override
    public void setFresh(boolean fresh) {
        this.fresh = fresh;
    }

    public boolean isDownload() {
        return download;
    }

    public void setDownload(boolean download) {
        this.download = download;
    }

    @Override
    public String getMavenSettings() {
        return mavenSettings;
    }

    @Override
    public void setMavenSettings(String mavenSettings) {
        this.mavenSettings = mavenSettings;
    }

    @Override
    public String getMavenSettingsSecurity() {
        return mavenSettingsSecurity;
    }

    @Override
    public void setMavenSettingsSecurity(String mavenSettingsSecurity) {
        this.mavenSettingsSecurity = mavenSettingsSecurity;
    }

    @Override
    public boolean isMavenCentralEnabled() {
        return mavenCentralEnabled;
    }

    @Override
    public void setMavenCentralEnabled(boolean mavenCentralEnabled) {
        this.mavenCentralEnabled = mavenCentralEnabled;
    }

    @Override
    public boolean isMavenApacheSnapshotEnabled() {
        return mavenApacheSnapshotEnabled;
    }

    @Override
    public void setMavenApacheSnapshotEnabled(boolean mavenApacheSnapshotEnabled) {
        this.mavenApacheSnapshotEnabled = mavenApacheSnapshotEnabled;
    }

    @Override
    public void downloadDependencyWithParent(String parentGav, String groupId, String artifactId, String version) {
        doDownloadDependencyWithParent(parentGav, groupId, artifactId, version, true, false, null);
    }

    @Override
    public void downloadDependency(String groupId, String artifactId, String version) {
        downloadDependency(groupId, artifactId, version, true);
    }

    @Override
    public void downloadDependency(String groupId, String artifactId, String version, String extraRepos) {
        doDownloadDependency(groupId, artifactId, version, true, false, extraRepos);
    }

    @Override
    public void downloadHiddenDependency(String groupId, String artifactId, String version) {
        doDownloadDependency(groupId, artifactId, version, true, true, null);
    }

    @Override
    public void downloadDependency(String groupId, String artifactId, String version, boolean transitively) {
        doDownloadDependency(groupId, artifactId, version, transitively, false, null);
    }

    protected void doDownloadDependency(
            String groupId, String artifactId, String version, boolean transitively,
            boolean hidden, String extraRepos) {
        doDownloadDependencyWithParent(null, groupId, artifactId, version, transitively, hidden, extraRepos);
    }

    protected void doDownloadDependencyWithParent(
            String parentGav,
            String groupId, String artifactId, String version, boolean transitively,
            boolean hidden, String extraRepos) {

        if (!hidden) {
            // trigger listener
            for (DownloadListener listener : downloadListeners) {
                listener.onDownloadDependency(groupId, artifactId, version);
            }
        }

        // when running jbang directly then the CP has some existing camel components
        // that essentially is not needed to be downloaded, but we need the listener to trigger
        // to capture that the GAV is required for running the application
        if (CP != null) {
            // is it already on classpath
            String target = artifactId;
            if (version != null) {
                target = target + "-" + version;
            }
            if (CP.contains(target)) {
                // already on classpath
                return;
            }
        }

        // we need version to be able to download from maven
        if (ObjectHelper.isEmpty(version)) {
            return;
        }

        String gav = groupId + ":" + artifactId + ":" + version;
        threadPool.download(LOG, () -> {
            List<String> deps = List.of(gav);

            // include Apache snapshot to make it easy to use upcoming releases
            boolean useApacheSnapshots = "org.apache.camel".equals(groupId) && version.contains("SNAPSHOT");

            // include extra repositories (if any) - these will be used in addition
            // to the ones detected from ~/.m2/settings.xml and configured in
            // org.apache.camel.main.download.MavenDependencyDownloader#repos
            Set<String> extraRepositories = new LinkedHashSet<>(resolveExtraRepositories(extraRepos));
            if (knownReposResolver != null) {
                // and from known extra repositories (if any)
                String known = knownReposResolver.getRepo(artifactId);
                extraRepositories.addAll(resolveExtraRepositories(known));
            }

            List<MavenArtifact> artifacts = resolveDependenciesViaAether(parentGav, deps, extraRepositories,
                    transitively, useApacheSnapshots);
            List<File> files = new ArrayList<>();
            if (verbose) {
                LOG.info("Dependencies: {} -> [{}]", gav, artifacts);
            } else {
                LOG.debug("Dependencies: {} -> [{}]", gav, artifacts);
            }

            for (MavenArtifact a : artifacts) {
                File file = a.getFile();
                // only add to classpath if not already present (do not trigger listener)
                if (!alreadyOnClasspath(a.getGav().getGroupId(), a.getGav().getArtifactId(),
                        a.getGav().getVersion(), false)) {
                    if (classLoader instanceof DependencyDownloaderClassLoader) {
                        DependencyDownloaderClassLoader ddc = (DependencyDownloaderClassLoader) classLoader;
                        ddc.addFile(file);
                    }
                    files.add(file);
                    if (verbose) {
                        LOG.info("Added classpath: {}", a.getGav());
                    } else {
                        LOG.debug("Added classpath: {}", a.getGav());
                    }
                }
            }

            // trigger listeners after downloaded and added to classloader
            for (File file : files) {
                for (ArtifactDownloadListener listener : artifactDownloadListeners) {
                    listener.onDownloadedFile(file);
                }
            }
            if (!artifacts.isEmpty()) {
                for (DownloadListener listener : downloadListeners) {
                    listener.onDownloadedDependency(groupId, artifactId, version);
                }
            }
            if (!extraRepositories.isEmpty()) {
                for (String repo : extraRepositories) {
                    for (DownloadListener listener : downloadListeners) {
                        listener.onExtraRepository(repo);
                    }
                }
            }

        }, gav);
    }

    @Override
    public MavenArtifact downloadArtifact(String groupId, String artifactId, String version) {
        List<MavenArtifact> artifacts = downloadArtifacts(groupId, artifactId, version, false);
        if (artifacts != null && artifacts.size() == 1) {
            return artifacts.get(0);
        }
        return null;
    }

    @Override
    public List<MavenArtifact> downloadArtifacts(String groupId, String artifactId, String version, boolean transitively) {
        String gav = groupId + ":" + artifactId + ":" + version;
        List<String> deps = List.of(gav);

        // include Apache snapshot to make it easy to use upcoming releases
        boolean useApacheSnapshots = "org.apache.camel".equals(groupId) && version.contains("SNAPSHOT");

        List<MavenArtifact> artifacts = resolveDependenciesViaAether(deps, null, transitively, useApacheSnapshots);
        if (verbose) {
            LOG.info("Dependencies: {} -> [{}]", gav, artifacts);
        } else {
            LOG.debug("Dependencies: {} -> [{}]", gav, artifacts);
        }

        return artifacts;
    }

    @Override
    public List<String[]> resolveAvailableVersions(
            String groupId, String artifactId,
            String minimumVersion, String repo) {
        String gav = groupId + ":" + artifactId;
        if (verbose) {
            LOG.info("Downloading available versions: {}", gav);
        } else {
            LOG.debug("Downloading available versions: {}", gav);
        }

        List<String[]> answer = new ArrayList<>();
        try {
            List<MavenGav> gavs = mavenDownloader.resolveAvailableVersions(groupId, artifactId, repo);

            Set<String> extraRepos = repo == null ? null : Collections.singleton(repo);

            for (MavenGav mavenGav : gavs) {
                String v = mavenGav.getVersion();
                if ("camel-spring-boot".equals(artifactId)) {
                    resolveSpringBoot(minimumVersion, v, extraRepos, answer);
                } else if ("camel-quarkus-catalog".equals(artifactId)) {
                    resolveQuarkus(minimumVersion, v, extraRepos, answer);
                } else {
                    answer.add(new String[] { v, null });
                }
            }
        } catch (Exception e) {
            throw new DownloadException(e.getMessage(), e);
        }

        return answer;
    }

    private void resolveQuarkus(String minimumVersion, String v, Set<String> extraRepos, List<String[]> answer)
            throws Exception {
        if (VersionHelper.isGE(v, MINIMUM_QUARKUS_VERSION)) {
            String cv = resolveCamelVersionByQuarkusVersion(v, extraRepos);
            if (cv != null && VersionHelper.isGE(cv, minimumVersion)) {
                answer.add(new String[] { cv, v });
            }
        }
    }

    private void resolveSpringBoot(String minimumVersion, String v, Set<String> extraRepos, List<String[]> answer)
            throws Exception {
        String sbv = null;
        if (VersionHelper.isGE(v, minimumVersion)) {
            sbv = resolveSpringBootVersionByCamelVersion(v, extraRepos);
        }
        answer.add(new String[] { v, sbv });
    }

    public boolean alreadyOnClasspath(String groupId, String artifactId, String version) {
        return alreadyOnClasspath(groupId, artifactId, version, true);
    }

    private boolean alreadyOnClasspath(String groupId, String artifactId, String version, boolean listener) {
        // if no artifact then regard this as okay
        if (artifactId == null) {
            return true;
        }

        String target = artifactId;
        if (version != null) {
            target = target + "-" + version;
        }

        if (bootClasspath != null) {
            for (String s : bootClasspath) {
                if (s.contains(target)) {
                    if (listener) {
                        for (DownloadListener dl : downloadListeners) {
                            dl.onDownloadDependency(groupId, artifactId, version);
                        }
                    }
                    // already on classpath
                    return true;
                }
            }
        }

        if (classLoader instanceof URLClassLoader) {
            // create path like target to match against the file url
            String urlTarget = groupId + "/" + artifactId;
            urlTarget = urlTarget.replace('.', '/');
            urlTarget += "/" + version + "/" + target + ".jar";
            urlTarget = FileUtil.normalizePath(urlTarget); // windows vs linux
            URLClassLoader ucl = (URLClassLoader) classLoader;
            for (URL u : ucl.getURLs()) {
                String s = u.toString();
                s = FileUtil.normalizePath(s);
                if (s.contains(urlTarget)) {
                    // trigger listener
                    if (listener) {
                        for (DownloadListener dl : downloadListeners) {
                            dl.onDownloadDependency(groupId, artifactId, version);
                        }
                    }
                    // already on classpath
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onLoadingKamelet(String name) {
        // trigger listener
        for (DownloadListener listener : downloadListeners) {
            listener.onLoadingKamelet(name);
        }
    }

    @Override
    public DownloadRecord getDownloadState(String groupId, String artifactId, String version) {
        return downloadRecords.get(groupId + ":" + artifactId + ":" + version);
    }

    @Override
    public Collection<DownloadRecord> downloadRecords() {
        return downloadRecords.values();
    }

    private Set<String> resolveExtraRepositories(String repositoryList) {
        Set<String> repositories = new LinkedHashSet<>();
        if (repositoryList != null) {
            for (String repo : repositoryList.split("\\s*,\\s*")) {
                try {
                    URL url = URI.create(repo).toURL();
                    if (url.getHost().equals("repo1.maven.org")) {
                        continue;
                    }
                    repositories.add(url.toExternalForm());
                } catch (MalformedURLException e) {
                    LOG.warn("Cannot use {} URL: {}. Skipping.", repo, e.getMessage(), e);
                }
            }
        }
        return repositories;
    }

    @Override
    protected void doBuild() {
        if (classLoader == null && camelContext != null) {
            classLoader = camelContext.getApplicationContextClassLoader();
        }
        threadPool = new DownloadThreadPool(this);
        threadPool.setVerbose(verbose);
        threadPool.setCamelContext(camelContext);
        ServiceHelper.buildService(threadPool);

        MavenDownloaderImpl mavenDownloaderImpl = new MavenDownloaderImpl();
        mavenDownloaderImpl.setMavenSettingsLocation(mavenSettings);
        mavenDownloaderImpl.setMavenSettingsSecurityLocation(mavenSettingsSecurity);
        mavenDownloaderImpl.setMavenCentralEnabled(mavenCentralEnabled);
        mavenDownloaderImpl.setMavenApacheSnapshotEnabled(mavenApacheSnapshotEnabled);
        mavenDownloaderImpl.setRepos(repositories);
        mavenDownloaderImpl.setFresh(fresh);
        mavenDownloaderImpl.setOffline(!download);
        // use listener to keep track of which JARs was downloaded from a remote Maven repo (and how long time it took)
        mavenDownloaderImpl.setRemoteArtifactDownloadListener(new RemoteArtifactDownloadListener() {
            @Override
            public void artifactDownloading(String groupId, String artifactId, String version, String repoId, String repoUrl) {
                String gav = groupId + ":" + artifactId + ":" + version;
                if (verbose) {
                    LOG.info("Downloading: {} from: {}@{}", gav, repoId, repoUrl);
                } else {
                    LOG.debug("Downloading: {} from: {}@{}", gav, repoId, repoUrl);
                }
            }

            @Override
            public void artifactDownloaded(
                    String groupId, String artifactId, String version, String repoId, String repoUrl, long elapsed) {
                String gav = groupId + ":" + artifactId + ":" + version;
                downloadRecords.put(gav, new DownloadRecord(groupId, artifactId, version, repoId, repoUrl, elapsed));
                if (verbose) {
                    LOG.info("Downloaded: {} (took: {}ms) from: {}@{}", gav, elapsed, repoId, repoUrl);
                } else {
                    LOG.debug("Downloaded: {} (took: {}ms) from: {}@{}", gav, elapsed, repoId, repoUrl);
                }
            }
        });
        ServiceHelper.buildService(mavenDownloaderImpl);

        mavenDownloader = mavenDownloaderImpl;
    }

    @Override
    protected void doInit() {
        RuntimeMXBean mb = ManagementFactory.getRuntimeMXBean();
        if (mb != null) {
            bootClasspath = mb.getClassPath().split("[:|;]");
        }
        ServiceHelper.initService(threadPool);
        ServiceHelper.initService(mavenDownloader);
    }

    @Override
    protected void doStop() {
        ServiceHelper.stopAndShutdownService(mavenDownloader);
        ServiceHelper.stopAndShutdownService(threadPool);
    }

    public List<MavenArtifact> resolveDependenciesViaAether(
            List<String> depIds,
            Set<String> extraRepositories, boolean transitively, boolean useApacheSnapshots) {
        return resolveDependenciesViaAether(null, depIds, extraRepositories, transitively,
                useApacheSnapshots);
    }

    public List<MavenArtifact> resolveDependenciesViaAether(
            String parentGav,
            List<String> depIds, Set<String> extraRepositories,
            boolean transitively, boolean useApacheSnapshots) {
        try {
            return mavenDownloader.resolveArtifacts(parentGav, depIds, extraRepositories, transitively, useApacheSnapshots);
        } catch (MavenResolutionException e) {
            String repos = (e.getRepositories() == null || e.getRepositories().isEmpty())
                    ? "(empty URL list)"
                    : String.join(", ", e.getRepositories());
            String msg = "Cannot resolve dependencies in " + repos;
            throw new DownloadException(msg, e);
        } catch (RuntimeException e) {
            throw new DownloadException("Unknown error occurred while trying to resolve dependencies", e);
        }
    }

    private String resolveCamelVersionByQuarkusVersion(String quarkusVersion, Set<String> extraRepos)
            throws Exception {
        String gav = "org.apache.camel.quarkus" + ":" + "camel-quarkus" + ":pom:" + quarkusVersion;

        try {
            List<MavenArtifact> artifacts = resolveDependenciesViaAether(List.of(gav), extraRepos, false, false);
            if (!artifacts.isEmpty()) {
                MavenArtifact ma = artifacts.get(0);
                if (ma != null && ma.getFile() != null) {
                    String name = ma.getFile().getAbsolutePath();
                    File file = new File(name);
                    if (file.exists()) {
                        DocumentBuilderFactory dbf = XmlHelper.createDocumentBuilderFactory();
                        DocumentBuilder db = dbf.newDocumentBuilder();
                        Document dom = db.parse(file);
                        // the camel version is in <parent>
                        NodeList nl = dom.getElementsByTagName("parent");
                        if (nl.getLength() == 1) {
                            Element node = (Element) nl.item(0);
                            return node.getElementsByTagName("version").item(0).getTextContent();
                        }
                    }
                }
            }
        } catch (DownloadException ex) {
            // Artifact may not exist on repository, just skip it
            LOG.debug(ex.getMessage(), ex);
        }

        return null;
    }

    private String resolveSpringBootVersionByCamelVersion(String camelVersion, Set<String> extraRepos)
            throws Exception {
        String gav = "org.apache.camel.springboot" + ":" + "spring-boot" + ":pom:" + camelVersion;

        List<MavenArtifact> artifacts = resolveDependenciesViaAether(List.of(gav), extraRepos, false, false);
        if (!artifacts.isEmpty()) {
            MavenArtifact ma = artifacts.get(0);
            if (ma != null && ma.getFile() != null) {
                String name = ma.getFile().getAbsolutePath();
                File file = new File(name);
                if (file.exists()) {
                    DocumentBuilderFactory dbf = XmlHelper.createDocumentBuilderFactory();
                    DocumentBuilder db = dbf.newDocumentBuilder();
                    Document dom = db.parse(file);
                    // the camel version is in <properties>
                    NodeList nl = dom.getElementsByTagName("properties");
                    if (nl.getLength() > 0) {
                        Element node = (Element) nl.item(0);
                        return node.getElementsByTagName("spring-boot-version").item(0).getTextContent();
                    }
                }
            }
        }

        return null;
    }

}
