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
package org.apache.camel.dsl.jbang.core.commands;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.camel.FailedToCreateRouteException;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.dsl.jbang.core.common.CommandLineHelper;
import org.apache.camel.dsl.jbang.core.common.LoggingLevelCompletionCandidates;
import org.apache.camel.dsl.jbang.core.common.Printer;
import org.apache.camel.dsl.jbang.core.common.RuntimeCompletionCandidates;
import org.apache.camel.dsl.jbang.core.common.RuntimeType;
import org.apache.camel.dsl.jbang.core.common.RuntimeTypeConverter;
import org.apache.camel.dsl.jbang.core.common.RuntimeUtil;
import org.apache.camel.dsl.jbang.core.common.Source;
import org.apache.camel.dsl.jbang.core.common.SourceHelper;
import org.apache.camel.dsl.jbang.core.common.SourceScheme;
import org.apache.camel.dsl.jbang.core.common.VersionHelper;
import org.apache.camel.main.KameletMain;
import org.apache.camel.main.download.DownloadListener;
import org.apache.camel.spi.BacklogDebugger;
import org.apache.camel.support.ResourceHelper;
import org.apache.camel.util.AntPathMatcher;
import org.apache.camel.util.CamelCaseOrderedProperties;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.StopWatch;
import org.apache.camel.util.StringHelper;
import org.apache.camel.util.json.JsonObject;
import org.apache.camel.util.json.Jsoner;
import org.apache.camel.xml.io.util.XmlStreamDetector;
import org.apache.camel.xml.io.util.XmlStreamInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.apache.camel.dsl.jbang.core.common.CamelCommandHelper.CAMEL_INSTANCE_TYPE;
import static org.apache.camel.dsl.jbang.core.common.CamelCommandHelper.extractState;
import static org.apache.camel.dsl.jbang.core.common.GistHelper.asGistSingleUrl;
import static org.apache.camel.dsl.jbang.core.common.GistHelper.fetchGistUrls;
import static org.apache.camel.dsl.jbang.core.common.GitHubHelper.asGithubSingleUrl;
import static org.apache.camel.dsl.jbang.core.common.GitHubHelper.fetchGithubUrls;

@Command(name = "run", description = "Run as local Camel integration", sortOptions = false, showDefaultValues = true)
public class Run extends CamelCommand {

    // special template for running camel-jbang in docker containers
    public static final String RUN_JAVA_SH = "classpath:templates/run-java.sh";

    public static final String RUN_SETTINGS_FILE = "camel-jbang-run.properties";
    private static final String RUN_PLATFORM_DIR = ".camel-jbang-run";

    private static final String[] ACCEPTED_XML_ROOT_ELEMENT_NAMES = new String[] {
            "route", "routes",
            "routeTemplate", "routeTemplates",
            "templatedRoute", "templatedRoutes",
            "rest", "rests",
            "routeConfiguration",
            "beans", "blueprint", "camel"
    };

    private static final Set<String> ACCEPTED_XML_ROOT_ELEMENTS
            = new HashSet<>(Arrays.asList(ACCEPTED_XML_ROOT_ELEMENT_NAMES));

    private static final String OPENAPI_GENERATED_FILE = CommandLineHelper.CAMEL_JBANG_WORK_DIR + "/generated-openapi.yaml";
    private static final String CLIPBOARD_GENERATED_FILE = CommandLineHelper.CAMEL_JBANG_WORK_DIR + "/generated-clipboard";

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^\\s*package\\s+([a-zA-Z][.\\w]*)\\s*;.*$", Pattern.MULTILINE);

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^\\s*public class\\s+([a-zA-Z0-9]*)[\\s+|;].*$", Pattern.MULTILINE);

    public boolean exportRun;
    boolean scriptRun;
    boolean transformRun;
    boolean transformMessageRun;
    boolean debugRun;

    private Path logFile;
    public long spawnPid;

    private Printer quietPrinter;

    @Parameters(description = "The Camel file(s) to run. If no files specified then application.properties is used as source for which files to run.",
                arity = "0..9", paramLabel = "<files>", parameterConsumer = FilesConsumer.class)
    Path[] filePaths; // Defined only for file path completion; the field never used

    public List<String> files = new ArrayList<>();

    @Option(names = { "--runtime" },
            completionCandidates = RuntimeCompletionCandidates.class,
            defaultValue = "camel-main",
            converter = RuntimeTypeConverter.class,
            description = "Runtime (${COMPLETION-CANDIDATES})")
    RuntimeType runtime = RuntimeType.main;

    @Option(names = { "--source-dir" },
            description = "Source directory for dynamically loading Camel file(s) to run. When using this, then files cannot be specified at the same time.")
    String sourceDir;

    @Option(names = { "--background" }, defaultValue = "false", description = "Run in the background")
    public boolean background;

    @Option(names = { "--background-wait" }, defaultValue = "true",
            description = "To wait for run in background to startup successfully, before returning")
    public boolean backgroundWait = true;

    @Option(names = { "--empty" }, defaultValue = "false", description = "Run an empty Camel without loading source files")
    public boolean empty;

    @Option(names = { "--camel-version" }, description = "To run using a different Camel version than the default version.")
    String camelVersion;

    @Option(names = { "--camel-spring-boot-version" },
            description = "To run using a different Camel Spring Boot version than the default version.")
    String camelSpringBootVersion;

    @Option(names = { "--kamelets-version" }, description = "Apache Camel Kamelets version")
    String kameletsVersion;

    @CommandLine.Option(names = { "--quarkus-group-id" }, description = "Quarkus Platform Maven groupId",
                        defaultValue = "io.quarkus.platform")
    String quarkusGroupId = "io.quarkus.platform";

    @CommandLine.Option(names = { "--quarkus-artifact-id" }, description = "Quarkus Platform Maven artifactId",
                        defaultValue = "quarkus-bom")
    String quarkusArtifactId = "quarkus-bom";

    @Option(names = { "--quarkus-version" }, description = "Quarkus Platform version",
            defaultValue = RuntimeType.QUARKUS_VERSION)
    String quarkusVersion = RuntimeType.QUARKUS_VERSION;

    @Option(names = { "--spring-boot-version" }, description = "Spring Boot version",
            defaultValue = RuntimeType.SPRING_BOOT_VERSION)
    String springBootVersion = RuntimeType.SPRING_BOOT_VERSION;

    @Option(names = { "--profile" }, scope = CommandLine.ScopeType.INHERIT, defaultValue = "dev",
            description = "Profile to run (dev, test, or prod).")
    String profile = "dev";

    @Option(names = { "--dep", "--dependency" }, description = "Add additional dependencies",
            split = ",")
    List<String> dependencies = new ArrayList<>();

    @CommandLine.Option(names = { "--repos" },
                        description = "Additional maven repositories (Use commas to separate multiple repositories)")
    String repositories;

    @Option(names = { "--gav" }, description = "The Maven group:artifact:version (used during exporting)")
    String gav;

    @Option(names = { "--maven-settings" },
            description = "Optional location of Maven settings.xml file to configure servers, repositories, mirrors and proxies."
                          + " If set to \"false\", not even the default ~/.m2/settings.xml will be used.")
    String mavenSettings;

    @Option(names = { "--maven-settings-security" },
            description = "Optional location of Maven settings-security.xml file to decrypt settings.xml")
    String mavenSettingsSecurity;

    @Option(names = { "--maven-central-enabled" }, defaultValue = "true",
            description = "Whether downloading JARs from Maven Central repository is enabled")
    boolean mavenCentralEnabled = true;

    @Option(names = { "--maven-apache-snapshot-enabled" }, defaultValue = "true",
            description = "Whether downloading JARs from ASF Maven Snapshot repository is enabled")
    boolean mavenApacheSnapshotEnabled = true;

    @Option(names = { "--fresh" }, defaultValue = "false", description = "Make sure we use fresh (i.e. non-cached) resources")
    boolean fresh;

    @Option(names = { "--download" }, defaultValue = "true",
            description = "Whether to allow automatic downloading JAR dependencies (over the internet)")
    boolean download = true;

    @CommandLine.Option(names = { "--package-scan-jars" }, defaultValue = "false",
                        description = "Whether to automatic package scan JARs for custom Spring or Quarkus beans making them available for Camel JBang")
    boolean packageScanJars;

    @Option(names = { "--jvm-debug" }, parameterConsumer = DebugConsumer.class, paramLabel = "<true|false|port>",
            description = "To enable JVM remote debugging on port 4004 by default. The supported values are true to " +
                          "enable the remote debugging, false to disable the remote debugging or a number to use a custom port")
    int jvmDebugPort;

    @Option(names = { "--name" }, defaultValue = "CamelJBang", description = "The name of the Camel application")
    String name;

    @CommandLine.Option(names = { "--exclude" }, description = "Exclude files by name or pattern")
    List<String> excludes = new ArrayList<>();

    @Option(names = { "--logging" }, defaultValue = "true", description = "Can be used to turn off logging")
    boolean logging = true;

    @Option(names = { "--logging-level" }, completionCandidates = LoggingLevelCompletionCandidates.class,
            defaultValue = "info", description = "Logging level (${COMPLETION-CANDIDATES})")
    String loggingLevel;

    @Option(names = { "--logging-color" }, defaultValue = "true", description = "Use colored logging")
    boolean loggingColor = true;

    @Option(names = { "--logging-json" }, defaultValue = "false", description = "Use JSON logging (ECS Layout)")
    boolean loggingJson;

    @Option(names = { "--logging-config-path" }, description = "Path to file with custom logging configuration")
    String loggingConfigPath;

    @Option(names = { "--logging-category" }, description = "Used for individual logging levels (ex: org.apache.kafka=DEBUG)")
    List<String> loggingCategory = new ArrayList<>();

    @Option(names = { "--max-messages" }, defaultValue = "0", description = "Max number of messages to process before stopping")
    int maxMessages;

    @Option(names = { "--max-seconds" }, defaultValue = "0", description = "Max seconds to run before stopping")
    int maxSeconds;

    @Option(names = { "--max-idle-seconds" }, defaultValue = "0",
            description = "For how long time in seconds Camel can be idle before stopping")
    int maxIdleSeconds;

    @Option(names = { "--reload", "--dev" },
            description = "Enables dev mode (live reload when source files are updated and saved)")
    boolean dev;

    @Option(names = { "--trace" }, defaultValue = "false",
            description = "Enables trace logging of the routed messages")
    boolean trace;

    @Option(names = { "--properties" },
            description = "comma separated list of properties file" +
                          " (ex. /path/to/file.properties,/path/to/other.properties")
    String propertiesFiles;

    @Option(names = { "--prop", "--property" }, description = "Additional properties (override existing)", arity = "0")
    String[] property;

    @Option(names = { "--stub" }, description = "Stubs all the matching endpoint with the given component name or pattern."
                                                + " Multiple names can be separated by comma. (all = everything).")
    String stub;

    @Option(names = { "--jfr" }, defaultValue = "false",
            description = "Enables Java Flight Recorder saving recording to disk on exit")
    boolean jfr;

    @Option(names = { "--jfr-profile" },
            description = "Java Flight Recorder profile to use (such as default or profile)")
    String jfrProfile;

    @Option(names = { "--local-kamelet-dir" },
            description = "Local directory (or github link) for loading Kamelets (takes precedence). Multiple directories can be specified separated by comma.")
    String localKameletDir;

    @Option(names = { "--port" }, description = "Embeds a local HTTP server on this port", defaultValue = "8080")
    int port;

    @Option(names = { "--management-port" }, description = "To use a dedicated port for HTTP management")
    int managementPort = -1;

    @Option(names = { "--console" }, defaultValue = "false",
            description = "Developer console at /q/dev on local HTTP server (port 8080 by default)")
    boolean console;

    @Option(names = { "--health" }, defaultValue = "false",
            description = "Deprecated: use --observe instead. Health check at /q/health on local HTTP server (port 8080 by default)")
    boolean health;

    @Option(names = { "--metrics" }, defaultValue = "false",
            description = "Deprecated: use --observe instead. Metrics (Micrometer and Prometheus) at /q/metrics on local HTTP server (port 8080 by default)")
    boolean metrics;

    @Option(names = { "--observe" }, defaultValue = "false",
            description = "Enable observability services")
    boolean observe;

    @Option(names = { "--modeline" }, defaultValue = "true",
            description = "Whether to support JBang style //DEPS to specify additional dependencies")
    boolean modeline = true;

    @Option(names = { "--open-api" }, description = "Adds an OpenAPI spec from the given file (json or yaml file)")
    String openapi;

    @Option(names = { "--code" }, description = "Run the given text or file as Java DSL routes")
    String code;

    @Option(names = { "--verbose" }, defaultValue = "false",
            description = "Verbose output of startup activity (dependency resolution and downloading")
    boolean verbose;

    @Option(names = { "--ignore-loading-error" }, defaultValue = "false",
            description = "Whether to ignore route loading and compilation errors (use this with care!)")
    protected boolean ignoreLoadingError;

    @Option(names = { "--lazy-bean" }, defaultValue = "false",
            description = "Whether to use lazy bean initialization (can help with complex classloading issues")
    protected boolean lazyBean;

    @Option(names = { "--prompt" }, defaultValue = "false",
            description = "Allow user to type in required parameters in prompt if not present in application")
    boolean prompt;

    @Option(names = { "--skip-plugins" }, defaultValue = "false",
            description = "Skip plugins during export")
    boolean skipPlugins;

    public Run(CamelJBangMain main) {
        super(main);
    }

    @Override
    public boolean disarrangeLogging() {
        if (exportRun) {
            return false;
        }
        if (RuntimeType.quarkus == runtime) {
            return true;
        } else if (RuntimeType.springBoot == runtime) {
            return true;
        }
        return false;
    }

    @Override
    public Integer doCall() throws Exception {
        if (!exportRun) {
            printConfigurationValues("Running integration with the following configuration:");
        }
        // run
        return run();
    }

    public Integer runExport() throws Exception {
        return runExport(false);
    }

    protected Integer runExport(boolean ignoreLoadingError) throws Exception {
        // just boot silently and exit
        this.exportRun = true;
        this.ignoreLoadingError = ignoreLoadingError;
        return run();
    }

    protected Integer runTransform(boolean ignoreLoadingError) throws Exception {
        // just boot silently and exit
        this.transformRun = true;
        this.ignoreLoadingError = ignoreLoadingError;
        this.name = "transform";
        return run();
    }

    public Integer runTransformMessage(String camelVersion, String repositories) throws Exception {
        // just boot silently an empty camel in the background and exit
        this.transformMessageRun = true;
        this.background = true;
        this.camelVersion = camelVersion;
        this.repositories = repositories;
        this.empty = true;
        this.ignoreLoadingError = true;
        this.name = "transform";
        return run();
    }

    protected Integer runScript(String file) throws Exception {
        this.files.add(file);
        this.scriptRun = true;
        return run();
    }

    protected Integer runDebug() throws Exception {
        this.debugRun = true;
        return run();
    }

    private boolean isDebugMode() {
        return jvmDebugPort > 0;
    }

    private void writeSetting(KameletMain main, Properties existing, String key, String value) {
        String val = existing != null ? existing.getProperty(key, value) : value;
        if (val != null) {
            main.addInitialProperty(key, val);
            writeSettings(key, val);
        }
    }

    private void writeSetting(KameletMain main, Properties existing, String key, Supplier<String> value) {
        String val = existing != null ? existing.getProperty(key, value.get()) : value.get();
        if (val != null) {
            main.addInitialProperty(key, val);
            writeSettings(key, val);
        }
    }

    private Properties loadProfileProperties(Path source) throws Exception {
        Properties prop = new CamelCaseOrderedProperties();
        if (Files.exists(source)) {
            try (InputStream is = Files.newInputStream(source)) {
                prop.load(is);
            }
        }

        // special for routes include pattern that we need to "fix" after reading from properties
        // to make this work in run command
        String value = prop.getProperty("camel.main.routesIncludePattern");
        if (value != null) {
            // if not scheme then must use file: as this is what run command expects
            StringJoiner sj = new StringJoiner(",");
            for (String part : value.split(",")) {
                if (!part.contains(":")) {
                    part = "file:" + part;
                }
                sj.add(part);
            }
            value = sj.toString();
            prop.setProperty("camel.main.routesIncludePattern", value);
        }

        return prop;
    }

    private int run() throws Exception {
        if (!empty && !files.isEmpty() && sourceDir != null) {
            // cannot have both files and source dir at the same time
            printer().printErr("Cannot specify both file(s) and source-dir at the same time.");
            return 1;
        }

        // special if user type: camel run .
        if (sourceDir == null && (files != null && files.size() == 1 && ".".equals(files.get(0)))) {
            RunHelper.dotToFiles(files);
        }

        if (!exportRun) {
            if (RuntimeType.quarkus == runtime) {
                return runQuarkus();
            } else if (RuntimeType.springBoot == runtime) {
                return runSpringBoot();
            }
        }

        Path work = CommandLineHelper.getWorkDir();
        removeDir(work);
        if (!Files.exists(work)) {
            try {
                Files.createDirectories(work);
            } catch (IOException e) {
                printer().println("WARN: Failed to create working directory: " + work.toAbsolutePath());
            }
        }

        Properties profileProperties = !empty ? loadProfileProperties() : null;
        configureLogging();
        if (openapi != null) {
            generateOpenApi();
        }

        // route code as option
        if (!empty && code != null) {
            // code may refer to an existing file
            String name = "CodeRoute";
            boolean file = false;
            Path codePath = Paths.get(code);
            if (Files.isRegularFile(codePath) && Files.exists(codePath)) {
                // must be a java file
                boolean java = codePath.getFileName().toString().endsWith(".java");
                if (!java) {
                    printer().printErr("Only java source files is accepted when using --code parameter");
                    return 1;
                }
                code = Files.readString(codePath);
                name = FileUtil.onlyName(codePath.getFileName().toString());
                file = true;
            }
            // store code in temporary file
            String codeFile = loadFromCode(code, name, file);
            // use code as first file
            files.add(0, codeFile);
        }

        boolean autoDetectFiles = files.isEmpty() || RUN_JAVA_SH.equals(files.get(0));

        // if no specific file to run then try to auto-detect
        if (!empty && autoDetectFiles) {
            if (sourceDir != null) {
                // silent-run then auto-detect all initial files for source-dir
                try {
                    Path sourceDirPath = Paths.get(sourceDir);
                    Files.list(sourceDirPath)
                            .forEach(p -> files.add(sourceDirPath.resolve(p.getFileName()).toString()));
                } catch (IOException e) {
                    // Ignore
                }
            } else {
                String routes
                        = profileProperties != null ? profileProperties.getProperty("camel.main.routesIncludePattern") : null;
                if (routes == null) {
                    if (!exportRun) {
                        String run = "run";
                        if (transformRun) {
                            run = "transform";
                        } else if (debugRun) {
                            run = "debug";
                        }
                        System.err
                                .println("Cannot " + run
                                         + " because application.properties file does not exist or camel.main.routesIncludePattern is not configured");
                        return 1;
                    } else {
                        // silent-run then auto-detect all files
                        try {
                            Files.list(Paths.get("."))
                                    .map(p -> p.getFileName().toString())
                                    .forEach(files::add);
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                }
            }
        }
        // filter out duplicate files
        if (!files.isEmpty()) {
            files = files.stream().distinct().collect(Collectors.toList());
        }

        final KameletMain main = createMainInstance();
        main.setProfile(profile);
        if (repositories != null && !repositories.isBlank()) {
            main.setRepositories(repositories);
        }
        main.setDownload(download);
        main.setPackageScanJars(packageScanJars);
        main.setFresh(fresh);
        main.setMavenSettings(mavenSettings);
        main.setMavenSettingsSecurity(mavenSettingsSecurity);
        main.setMavenCentralEnabled(mavenCentralEnabled);
        main.setMavenApacheSnapshotEnabled(mavenApacheSnapshotEnabled);
        main.setDownloadListener(new RunDownloadListener());
        main.setAppName("Apache Camel (JBang)");

        if (stub != null) {
            if ("all".equals(stub)) {
                stub = "*";
            }
            // we need to match by wildcard, to make it easier
            StringJoiner sj = new StringJoiner(",");
            for (String n : stub.split(",")) {
                // you can either refer to a name or a specific endpoint
                // if there is a colon then we assume its a specific endpoint then we should not add wildcard
                boolean colon = n.contains(":");
                if (!colon && !n.endsWith("*")) {
                    n = n + "*";
                }
                sj.add(n);
            }
            stub = sj.toString();
            writeSetting(main, profileProperties, "camel.jbang.stub", stub);
            main.setStubPattern(stub);
        }

        if (dev) {
            writeSetting(main, profileProperties, "camel.main.routesReloadEnabled", "true");
            // allow quick shutdown during development
            writeSetting(main, profileProperties, "camel.main.shutdownTimeout", "5");
        }
        if (sourceDir != null) {
            writeSetting(main, profileProperties, "camel.jbang.sourceDir", sourceDir);
        }
        if (trace) {
            writeSetting(main, profileProperties, "camel.main.tracing", "true");
        }
        if (modeline) {
            writeSetting(main, profileProperties, "camel.main.modeline", "true");
            // configure eager
            main.configure().withModeline(true);
        }
        if (ignoreLoadingError) {
            writeSetting(main, profileProperties, "camel.jbang.ignoreLoadingError", "true");
        }
        if (lazyBean) {
            writeSetting(main, profileProperties, "camel.jbang.lazyBean", "true");
        }
        if (prompt) {
            writeSetting(main, profileProperties, "camel.jbang.prompt", "true");
        }
        writeSetting(main, profileProperties, "camel.jbang.compileWorkDir",
                Paths.get(CommandLineHelper.CAMEL_JBANG_WORK_DIR, "compile").toString());

        if (gav != null) {
            writeSetting(main, profileProperties, "camel.jbang.gav", gav);
        }
        writeSetting(main, profileProperties, "camel.jbang.open-api", openapi);
        if (repositories != null) {
            writeSetting(main, profileProperties, "camel.jbang.repos", repositories);
        }
        writeSetting(main, profileProperties, "camel.jbang.health", health ? "true" : "false");
        writeSetting(main, profileProperties, "camel.jbang.metrics", metrics ? "true" : "false");
        writeSetting(main, profileProperties, "camel.jbang.console", console ? "true" : "false");
        writeSetting(main, profileProperties, "camel.jbang.verbose", verbose ? "true" : "false");
        // the runtime version of Camel is what is loaded via the catalog
        writeSetting(main, profileProperties, "camel.jbang.camel-version", new DefaultCamelCatalog().getCatalogVersion());
        writeSetting(main, profileProperties, "camel.jbang.springBootVersion", springBootVersion);
        writeSetting(main, profileProperties, "camel.jbang.quarkusVersion", quarkusVersion);
        writeSetting(main, profileProperties, "camel.jbang.quarkusGroupId", quarkusGroupId);
        writeSetting(main, profileProperties, "camel.jbang.quarkusArtifactId", quarkusArtifactId);

        if (observe) {
            main.addInitialProperty("camel.jbang.dependencies", "camel:observability-services");
        }

        // command line arguments
        if (property != null) {
            for (String p : property) {
                String k = StringHelper.before(p, "=");
                String v = StringHelper.after(p, "=");
                if (k != null && v != null) {
                    main.addArgumentProperty(k, v);
                    writeSettings(k, v);
                }
            }
        }

        if (exportRun) {
            if (!verbose) {
                main.setSilent(true);
            }
            main.addInitialProperty("camel.jbang.export", "true");
            // enable stub in silent mode so we do not use real components
            main.setStubPattern("*");
            // do not run for very long in silent run
            main.addInitialProperty("camel.main.autoStartup", "false");
            main.addInitialProperty("camel.main.durationMaxSeconds", "-1");
        } else if (debugRun) {
            main.addInitialProperty("camel.jbang.debug", "true");
        } else if (transformRun) {
            main.setSilent(true);
            // enable stub in silent mode so we do not use real components
            main.setStubPattern("*");
            // do not run for very long in silent run
            main.addInitialProperty("camel.main.autoStartup", "false");
            main.addInitialProperty("camel.main.durationMaxSeconds", "-1");
        } else if (transformMessageRun) {
            // do not start any routes
            main.addInitialProperty("camel.main.autoStartup", "false");
        } else if (scriptRun) {
            // auto terminate if being idle
            main.addInitialProperty("camel.main.durationMaxIdleSeconds", "1");
        }
        // any custom initial property
        doAddInitialProperty(main);

        writeSetting(main, profileProperties, "camel.main.durationMaxMessages",
                () -> maxMessages > 0 ? String.valueOf(maxMessages) : null);
        writeSetting(main, profileProperties, "camel.main.durationMaxSeconds",
                () -> maxSeconds > 0 ? String.valueOf(maxSeconds) : null);
        writeSetting(main, profileProperties, "camel.main.durationMaxIdleSeconds",
                () -> maxIdleSeconds > 0 ? String.valueOf(maxIdleSeconds) : null);
        writeSetting(main, profileProperties, "camel.server.port",
                () -> port > 0 && port != 8080 ? String.valueOf(port) : null);
        if (managementPort != -1) {
            writeSetting(main, profileProperties, "camel.management.port", () -> String.valueOf(managementPort));
        }
        writeSetting(main, profileProperties, "camel.jbang.jfr", jfr || jfrProfile != null ? "jfr" : null);
        writeSetting(main, profileProperties, "camel.jbang.jfr-profile", jfrProfile != null ? jfrProfile : null);

        writeSetting(main, profileProperties, "camel.jbang.kameletsVersion", kameletsVersion);

        StringJoiner js = new StringJoiner(",");
        StringJoiner sjReload = new StringJoiner(",");
        StringJoiner sjClasspathFiles = new StringJoiner(",");
        StringJoiner sjScriptFiles = new StringJoiner(",");
        StringJoiner sjGroovyFiles = new StringJoiner(",");
        StringJoiner sjTlsFiles = new StringJoiner(",");
        StringJoiner sjKamelets = new StringJoiner(",");
        StringJoiner sjJKubeFiles = new StringJoiner(",");

        // include generated openapi to files to run
        if (openapi != null) {
            files.add(OPENAPI_GENERATED_FILE);
        }

        // if we only run pom.xml/build.gradle then auto discover from the Maven/Gradle based project
        if (files.size() == 1 && (files.get(0).endsWith("pom.xml") || files.get(0).endsWith("build.gradle"))) {
            Path projectDescriptorPath = Path.of(files.get(0)).toAbsolutePath();
            // use a better name when running
            if (name == null || "CamelJBang".equals(name)) {
                name = RunHelper.mavenArtifactId(projectDescriptorPath);
            }
            // find source files
            files = RunHelper.scanMavenOrGradleProject(projectDescriptorPath.getParent());
            // include extra dependencies from pom.xml
            var pomDependencies = RunHelper.scanMavenDependenciesFromPom(projectDescriptorPath);
            addDependencies(pomDependencies.toArray(new String[0]));
        }

        if (profile != null) {
            // need to include profile application properties if exists
            String name = "application-" + profile + ".properties";
            if (Files.exists(Paths.get(name)) && !files.contains(name)) {
                files.add(name);
            }
        }

        for (String file : files) {
            if (file.startsWith("clipboard") && !(Files.exists(Paths.get(file)))) {
                file = loadFromClipboard(file);
            } else if (skipFile(file)) {
                continue;
            } else if (isScriptFile(file)) {
                // script files
                sjScriptFiles.add(file);
                continue;
            } else if (isGroovyFile(file)) {
                // groovy files
                sjGroovyFiles.add("file:" + file);
                if (dev) {
                    // groovy files can also be reloaded
                    sjReload.add(file);
                }
                continue;
            } else if (isTlsFile(file)) {
                // tls files
                sjTlsFiles.add(file);
                continue;
            } else if (jkubeFile(file)) {
                // jkube
                sjJKubeFiles.add(file);
                continue;
            } else if (!knownFile(file) && !file.endsWith(".properties")) {
                // unknown files to be added on classpath
                sjClasspathFiles.add(file);
                continue;
            }

            // process known files as its likely DSLs or configuration files

            // check for properties files
            if (file.endsWith(".properties")) {
                if (acceptPropertiesFile(file)) {
                    if (!ResourceHelper.hasScheme(file) && !file.startsWith("github:")) {
                        file = "file:" + file;
                    }
                    if (ObjectHelper.isEmpty(propertiesFiles)) {
                        propertiesFiles = file;
                    } else {
                        propertiesFiles = propertiesFiles + "," + file;
                    }
                    if (dev && file.startsWith("file:")) {
                        // we can only reload if file based
                        sjReload.add(file.substring(5));
                    }
                }
                continue;
            }

            // Camel DSL files
            if (!ResourceHelper.hasScheme(file) && !file.startsWith("github:")) {
                file = "file:" + file;
            }
            if (file.startsWith("file:")) {
                // check if file exist
                Path inputPath = Paths.get(file.substring(5));
                if (!Files.exists(inputPath) || !Files.isRegularFile(inputPath)) {
                    printer().printErr("File does not exist: " + file);
                    return 1;
                }
            }

            if (file.startsWith("file:") && file.endsWith(".kamelet.yaml")) {
                sjKamelets.add(file);
            }

            // automatic map github https urls to github resolver
            if (file.startsWith("https://github.com/")) {
                file = evalGithubSource(main, file);
                if (file == null) {
                    continue; // all mapped continue to next
                }
            } else if (file.startsWith("https://gist.github.com/")) {
                file = evalGistSource(main, file);
                if (file == null) {
                    continue; // all mapped continue to next
                }
            }

            if ("CamelJBang".equals(name)) {
                // no specific name was given so lets use the name from the first integration file
                // remove scheme and keep only the name (no path or ext)
                String s = StringHelper.after(file, ":");
                if (s.contains(":")) {
                    // its maybe a gist/github url so we need only the last part which has the name
                    s = StringHelper.afterLast(s, ":");
                }
                name = FileUtil.onlyName(s);
            }

            js.add(file);
            if (dev && file.startsWith("file:")) {
                // we can only reload if file based
                sjReload.add(file.substring(5));
            }
        }
        writeSetting(main, profileProperties, "camel.main.name", name);

        if (sourceDir != null) {
            // must be an existing directory
            Path dirPath = Paths.get(sourceDir);
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                printer().printErr("Directory does not exist: " + sourceDir);
                return 1;
            }
            // make it a pattern as we load all files from this directory
            // (optional=true as there may be non Camel routes files as well)
            String sdir = "file:" + sourceDir + "/**?optional=true";
            main.addInitialProperty("camel.main.routesIncludePattern", sdir);
            writeSettings("camel.main.routesIncludePattern", sdir);
        } else if (js.length() > 0) {
            main.addInitialProperty("camel.main.routesIncludePattern", js.toString());
            writeSettings("camel.main.routesIncludePattern", js.toString());
        } else {
            writeSetting(main, profileProperties, "camel.main.routesIncludePattern", () -> null);
        }
        if (sjClasspathFiles.length() > 0) {
            main.addInitialProperty("camel.jbang.classpathFiles", sjClasspathFiles.toString());
            writeSettings("camel.jbang.classpathFiles", sjClasspathFiles.toString());
        } else {
            writeSetting(main, profileProperties, "camel.jbang.classpathFiles", () -> null);
        }
        if (sjScriptFiles.length() > 0) {
            main.addInitialProperty("camel.jbang.scriptFiles", sjScriptFiles.toString());
            writeSettings("camel.jbang.scriptFiles", sjScriptFiles.toString());
        } else {
            writeSetting(main, profileProperties, "camel.jbang.scriptFiles", () -> null);
        }
        if (sjGroovyFiles.length() > 0) {
            main.addInitialProperty("camel.jbang.groovyFiles", sjGroovyFiles.toString());
            writeSettings("camel.jbang.groovyFiles", sjGroovyFiles.toString());
        } else {
            writeSetting(main, profileProperties, "camel.jbang.groovyFiles", () -> null);
        }
        if (sjTlsFiles.length() > 0) {
            main.addInitialProperty("camel.jbang.tlsFiles", sjTlsFiles.toString());
            writeSettings("camel.jbang.tlsFiles", sjTlsFiles.toString());
        } else {
            writeSetting(main, profileProperties, "camel.jbang.tlsFiles", () -> null);
        }
        if (sjJKubeFiles.length() > 0) {
            main.addInitialProperty("camel.jbang.jkubeFiles", sjJKubeFiles.toString());
            writeSettings("camel.jbang.jkubeFiles", sjJKubeFiles.toString());
        } else {
            writeSetting(main, profileProperties, "camel.jbang.jkubeFiles", () -> null);
        }

        if (sjKamelets.length() > 0) {
            String loc = main.getInitialProperties().getProperty("camel.component.kamelet.location");
            if (loc != null) {
                loc = loc + "," + sjKamelets;
            } else {
                loc = sjKamelets.toString();
            }
            main.addInitialProperty("camel.component.kamelet.location", loc);
            writeSettings("camel.component.kamelet.location", loc);
        } else {
            writeSetting(main, profileProperties, "camel.component.kamelet.location", () -> null);
        }

        // we can only reload if file based
        setupReload(main, sjReload);

        if (propertiesFiles != null) {
            String[] filesLocation = propertiesFiles.split(",");
            // sort so general application.properties comes first (we should load profile first)
            List<String> names = new ArrayList<>(List.of(filesLocation));
            names.sort((o1, o2) -> {
                // make sure application.properties is last
                if (o1.endsWith("application.properties")) {
                    return 1;
                } else if (o2.endsWith("application.properties")) {
                    return -1;
                }
                return 0;
            });
            StringBuilder locations = new StringBuilder();
            for (String file : names) {
                if (!file.startsWith("file:")) {
                    if (!file.startsWith("/")) {
                        file = Paths.get(FileSystems.getDefault().getPath("").toAbsolutePath().toString(), file).toString();
                    }
                    file = "file://" + file;
                }
                if (!locations.isEmpty()) {
                    locations.append(",");
                }
                locations.append(file);
            }
            // there may be existing properties
            String loc = main.getInitialProperties().getProperty("camel.component.properties.location");
            if (loc != null) {
                loc = loc + "," + locations;
            } else {
                loc = locations.toString();
            }
            main.addInitialProperty("camel.component.properties.location", loc);
            writeSettings("camel.component.properties.location", loc);
            main.setPropertyPlaceholderLocations(loc);
        }

        // merge existing dependencies with --deps
        addDependencies(RuntimeUtil.getDependenciesAsArray(profileProperties));
        if (!dependencies.isEmpty()) {
            var joined = String.join(",", dependencies);
            main.addInitialProperty("camel.jbang.dependencies", joined);
            writeSettings("camel.jbang.dependencies", joined);
        }

        // if we have a specific camel version then make sure we really need to switch
        if (camelVersion != null) {
            CamelCatalog catalog = new DefaultCamelCatalog();
            String v = catalog.getCatalogVersion();
            if (camelVersion.equals(v)) {
                // same version, so we use current
                camelVersion = null;
            }
        }

        // okay we have validated all input and are ready to run
        // (if exporting then we cannot run a different version)
        if (!exportRun && camelVersion != null || isDebugMode()) {
            // TODO: debug camel specific version
            boolean custom = false;
            if (camelVersion != null) {
                // run in another JVM with different camel version (foreground or background)
                custom = camelVersion.contains("-") && !camelVersion.endsWith("-SNAPSHOT");
                if (custom) {
                    // regular camel versions can also be a milestone or release candidate
                    custom = !camelVersion.matches(".*-(RC|M)\\d$");
                }
            }
            if (custom) {
                // custom camel distribution
                return runCustomCamelVersion(main);
            } else {
                // apache camel distribution or remote debug enabled
                return runCamelVersion(main);
            }
        } else if (debugRun) {
            // spawn new JVM to debug in background
            return runDebug(main);
        } else if (background) {
            // spawn new JVM to run in background
            return runBackground(main);
        } else {
            // run default in current JVM with same camel version
            try {
                return runKameletMain(main);
            } catch (FailedToCreateRouteException ex) {
                if (ignoreLoadingError) {
                    printer().printErr(ex);
                    return 0;
                }
                throw ex;
            }
        }
    }

    protected void addDependencies(String... deps) {
        var depsArray = Optional.ofNullable(deps).orElse(new String[0]);
        var depsList = Arrays.stream(depsArray).filter(tok -> !tok.isEmpty()).toList();
        dependencies.addAll(depsList);
    }

    protected int runQuarkus() throws Exception {
        if (background) {
            printer().println("Run Camel Quarkus with --background is not supported");
            return 1;
        }

        AtomicReference<Process> processRef = new AtomicReference<>();

        // create temp run dir
        Path runDirPath = Paths.get(RUN_PLATFORM_DIR, Long.toString(System.currentTimeMillis()));
        if (!this.background) {
            // Mark for deletion on exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // We need to wait for the process to exit before doing any cleanup
                    Process process = processRef.get();
                    if (process != null) {
                        process.destroy();

                        for (int i = 0; i < 30; i++) {
                            if (!process.isAlive()) {
                                break;
                            }

                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    removeDir(runDirPath);
                } catch (Exception e) {
                    // Ignore
                }
            }));
        }
        Files.createDirectories(runDirPath);

        // export to hidden folder
        ExportQuarkus eq = new ExportQuarkus(getMain());
        eq.javaLiveReload = this.dev;
        eq.symbolicLink = this.dev;
        eq.mavenWrapper = true;
        eq.gradleWrapper = false;
        eq.quarkusVersion = this.quarkusVersion;
        eq.quarkusGroupId = this.quarkusGroupId;
        eq.quarkusArtifactId = this.quarkusArtifactId;
        eq.camelVersion = this.camelVersion;
        eq.kameletsVersion = this.kameletsVersion;
        eq.exportDir = runDirPath.toString();
        eq.localKameletDir = this.localKameletDir;
        eq.excludes = this.excludes;
        eq.filePaths = this.filePaths;
        eq.files = this.files;
        eq.name = this.name;
        eq.verbose = this.verbose;
        eq.port = this.port;
        eq.managementPort = this.managementPort;
        eq.gav = this.gav;
        if (eq.gav == null) {
            if (eq.name == null) {
                eq.name = "jbang-run-dummy";
            }
            eq.gav = "org.example.project:" + eq.name + ":1.0-SNAPSHOT";
        }
        eq.dependencies = this.dependencies;
        eq.addDependencies("camel:cli-connector");
        eq.fresh = this.fresh;
        eq.download = this.download;
        eq.skipPlugins = this.skipPlugins;
        eq.packageScanJars = this.packageScanJars;
        eq.quiet = true;
        eq.logging = false;
        eq.loggingLevel = "off";
        eq.ignoreLoadingError = this.ignoreLoadingError;
        eq.lazyBean = this.lazyBean;
        eq.applicationProperties = this.property;

        printer().println("Running using Quarkus v" + eq.quarkusVersion + " (preparing and downloading files)");

        // run export
        int exit = eq.export();
        if (exit != 0) {
            return exit;
        }

        // run quarkus via maven
        String mvnw = "/mvnw";
        if (FileUtil.isWindows()) {
            mvnw = "/mvnw.cmd";
        }
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(runDirPath.toString() + mvnw, "--quiet", "--file",
                runDirPath.toRealPath().resolve("pom.xml").toString(), "package",
                "quarkus:" + (dev ? "dev" : "run"));

        pb.inheritIO(); // run in foreground (with IO so logs are visible)
        Process p = pb.start();
        processRef.set(p);
        this.spawnPid = p.pid();
        // wait for that process to exit as we run in foreground
        return p.waitFor();
    }

    protected int runSpringBoot() throws Exception {
        if (background) {
            printer().println("Run Camel Spring Boot with --background is not supported");
            return 1;
        }

        AtomicReference<Process> processRef = new AtomicReference<>();

        // create temp run dir
        Path runDirPath = Paths.get(RUN_PLATFORM_DIR, Long.toString(System.currentTimeMillis()));
        if (!this.background) {
            // Mark for deletion on exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // We need to wait for the process to exit before doing any cleanup
                    Process process = processRef.get();
                    if (process != null) {
                        process.destroy();

                        for (int i = 0; i < 30; i++) {
                            if (!process.isAlive()) {
                                break;
                            }

                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    removeDir(runDirPath);
                } catch (Exception e) {
                    // Ignore
                }
            }));
        }
        Files.createDirectories(runDirPath);

        // export to hidden folder
        ExportSpringBoot eq = new ExportSpringBoot(getMain());
        // Java codes reload is not supported in Spring Boot since it has to be recompiled to trigger the restart
        eq.javaLiveReload = false;
        eq.symbolicLink = this.dev;
        eq.mavenWrapper = true;
        eq.gradleWrapper = false;
        eq.springBootVersion = this.springBootVersion;
        eq.camelVersion = this.camelVersion;
        eq.camelSpringBootVersion = VersionHelper.getSpringBootVersion(
                () -> this.camelSpringBootVersion != null ? this.camelSpringBootVersion : this.camelVersion);
        eq.kameletsVersion = this.kameletsVersion;
        eq.exportDir = runDirPath.toString();
        eq.localKameletDir = this.localKameletDir;
        eq.excludes = this.excludes;
        eq.filePaths = this.filePaths;
        eq.files = this.files;
        eq.name = this.name;
        eq.verbose = this.verbose;
        eq.port = this.port;
        eq.managementPort = this.managementPort;
        eq.gav = this.gav;
        eq.repositories = this.repositories;
        if (eq.gav == null) {
            if (eq.name == null) {
                eq.name = "jbang-run-dummy";
            }
            eq.gav = "org.example.project:" + eq.name + ":1.0-SNAPSHOT";
        }
        eq.dependencies.addAll(this.dependencies);
        eq.addDependencies("camel:cli-connector");
        if (this.dev) {
            // hot-reload of spring-boot
            eq.addDependencies("mvn:org.springframework.boot:spring-boot-devtools");
        }
        eq.fresh = this.fresh;
        eq.download = this.download;
        eq.skipPlugins = this.skipPlugins;
        eq.packageScanJars = this.packageScanJars;
        eq.quiet = true;
        eq.logging = false;
        eq.loggingLevel = "off";
        eq.ignoreLoadingError = this.ignoreLoadingError;
        eq.lazyBean = this.lazyBean;
        eq.applicationProperties = this.property;

        printer().println("Running using Spring Boot v" + eq.springBootVersion + " (preparing and downloading files)");

        // run export
        int exit = eq.export();
        if (exit != 0) {
            return exit;
        }

        // prepare spring-boot for logging to file
        try (InputStream is = Run.class.getClassLoader().getResourceAsStream("spring-boot-logback.xml")) {
            Path logbackPath = Paths.get(eq.exportDir, "src/main/resources/logback.xml");
            Files.createDirectories(logbackPath.getParent());
            Files.copy(is, logbackPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // run spring-boot via maven
        ProcessBuilder pb = new ProcessBuilder();
        String mvnw = "/mvnw";
        if (FileUtil.isWindows()) {
            mvnw = "/mvnw.cmd";
        }
        pb.command(runDirPath.toString() + mvnw, "--quiet", "--file",
                runDirPath.toRealPath().resolve("pom.xml").toString(),
                "spring-boot:run");

        pb.inheritIO(); // run in foreground (with IO so logs are visible)
        Process p = pb.start();
        processRef.set(p);
        this.spawnPid = p.pid();
        // wait for that process to exit as we run in foreground
        return p.waitFor();
    }

    private boolean acceptPropertiesFile(String file) {
        String name = FileUtil.onlyName(file);
        if (profile != null && name.startsWith("application-")) {
            // only accept the file that matches the correct profile
            return ("application-" + profile).equals(name);
        }
        return true;
    }

    protected void doAddInitialProperty(KameletMain main) {
        // noop
    }

    private void setupReload(KameletMain main, StringJoiner sjReload) {
        if (dev && (sourceDir != null || sjReload.length() > 0)) {
            main.addInitialProperty("camel.main.routesReloadEnabled", "true");
            if (sourceDir != null) {
                main.addInitialProperty("camel.jbang.sourceDir", sourceDir);
                main.addInitialProperty("camel.main.routesReloadDirectory", sourceDir);
                main.addInitialProperty("camel.main.routesReloadPattern", "*");
                main.addInitialProperty("camel.main.routesReloadDirectoryRecursive", "true");
            } else {
                String pattern = sjReload.toString();
                String reloadDir = ".";
                // use current dir, however if we run a file that are in another folder, then we should track that folder instead
                for (String r : sjReload.toString().split(",")) {
                    String path = FileUtil.onlyPath(r);
                    if (path != null && !path.equals(".camel-jbang")) {
                        reloadDir = path;
                        break;
                    }
                }
                main.addInitialProperty("camel.main.routesReloadDirectory", reloadDir);
                main.addInitialProperty("camel.main.routesReloadPattern", pattern);
                main.addInitialProperty("camel.main.routesReloadDirectoryRecursive",
                        isReloadRecursive(pattern) ? "true" : "false");
            }
            // do not shutdown the JVM but stop routes when max duration is triggered
            main.addInitialProperty("camel.main.durationMaxAction", "stop");
        }
    }

    private Properties loadProfileProperties() throws Exception {
        Properties answer = null;

        if (transformMessageRun) {
            // do not load profile in transform message run as it should be vanilla empty
            return answer;
        }

        // application.properties
        Path profilePropertiesPath;
        if (sourceDir != null) {
            profilePropertiesPath = Paths.get(sourceDir).resolve("application.properties");
        } else {
            profilePropertiesPath = Paths.get("application.properties");
        }
        // based application-profile.properties
        answer = doLoadAndInitProfileProperties(profilePropertiesPath);

        if (profile != null) {
            if (sourceDir != null) {
                profilePropertiesPath = Paths.get(sourceDir).resolve("application-" + profile + ".properties");
            } else {
                profilePropertiesPath = Paths.get("application-" + profile + ".properties");
            }
            // application-profile.properties should override standard application.properties
            Properties override = doLoadAndInitProfileProperties(profilePropertiesPath);
            if (override != null) {
                if (answer == null) {
                    answer = override;
                } else {
                    answer.putAll(override);
                }
            }
        }

        if (kameletsVersion == null) {
            kameletsVersion = VersionHelper.extractKameletsVersion();
        }
        return answer;
    }

    private Properties doLoadAndInitProfileProperties(Path profilePropertiesPath) throws Exception {
        Properties answer = null;
        if (Files.exists(profilePropertiesPath)) {
            answer = this.loadProfileProperties((Path) profilePropertiesPath);
            // logging level/color may be configured in the properties file
            loggingLevel = answer.getProperty("loggingLevel", loggingLevel);
            loggingColor
                    = "true".equals(answer.getProperty("loggingColor", loggingColor ? "true" : "false"));
            loggingJson
                    = "true".equals(answer.getProperty("loggingJson", loggingJson ? "true" : "false"));
            repositories = answer.getProperty("camel.jbang.repos", repositories);
            mavenSettings = answer.getProperty("camel.jbang.maven-settings", mavenSettings);
            mavenSettingsSecurity = answer.getProperty("camel.jbang.maven-settings-security", mavenSettingsSecurity);
            mavenCentralEnabled = "true"
                    .equals(answer.getProperty("camel.jbang.maven-central-enabled", mavenCentralEnabled ? "true" : "false"));
            mavenApacheSnapshotEnabled = "true".equals(answer.getProperty("camel.jbang.maven-apache-snapshot-enabled",
                    mavenApacheSnapshotEnabled ? "true" : "false"));
            openapi = answer.getProperty("camel.jbang.open-api", openapi);
            download = "true".equals(answer.getProperty("camel.jbang.download", download ? "true" : "false"));
            packageScanJars
                    = "true".equals(answer.getProperty("camel.jbang.packageScanJars", packageScanJars ? "true" : "false"));
            background = "true".equals(answer.getProperty("camel.jbang.background", background ? "true" : "false"));
            backgroundWait = "true".equals(answer.getProperty("camel.jbang.backgroundWait", backgroundWait ? "true" : "false"));
            jvmDebugPort = parseJvmDebugPort(answer.getProperty("camel.jbang.jvmDebug", Integer.toString(jvmDebugPort)));
            camelVersion = answer.getProperty("camel.jbang.camel-version", camelVersion);
            kameletsVersion = answer.getProperty("camel.jbang.kameletsVersion", kameletsVersion);
            springBootVersion = answer.getProperty("camel.jbang.springBootVersion", springBootVersion);
            quarkusGroupId = answer.getProperty("camel.jbang.quarkusGroupId", quarkusGroupId);
            quarkusArtifactId = answer.getProperty("camel.jbang.quarkusArtifactId", quarkusArtifactId);
            quarkusVersion = answer.getProperty("camel.jbang.quarkusVersion", quarkusVersion);
            gav = answer.getProperty("camel.jbang.gav", gav);
            stub = answer.getProperty("camel.jbang.stub", stub);
            excludes = RuntimeUtil.getCommaSeparatedPropertyAsList(answer, "camel.jbang.excludes", excludes);
        }

        return answer;
    }

    /**
     * Parses the JVM debug port from the given value.
     * <p/>
     * The value can be {@code true} to indicate a default port which is {@code 4004}, {@code false} to indicate no
     * debug, or a number corresponding to a custom port.
     *
     * @param  value the value to parse.
     *
     * @return       the JVM debug port corresponding to the given value.
     */
    private static int parseJvmDebugPort(String value) {
        if (value == null) {
            return 0;
        } else if (value.equals("true")) {
            return 4004;
        } else if (value.equals("false")) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    protected int runCamelVersion(KameletMain main) throws Exception {
        List<String> cmds;
        if (spec != null) {
            cmds = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());
        } else {
            cmds = new ArrayList<>();
            cmds.add("run");
            if (transformMessageRun) {
                cmds.add("--empty");
            }
        }

        if (background) {
            cmds.remove("--background=true");
            cmds.remove("--background");
            cmds.remove("--background-wait");
            cmds.remove("--background-wait=false");
            cmds.remove("--background-wait=true");
        }
        if (camelVersion != null) {
            cmds.remove("--camel-version=" + camelVersion);
        }
        // need to use jbang command to specify camel version
        List<String> jbangArgs = new ArrayList<>();
        jbangArgs.add("jbang");
        jbangArgs.add("run");
        if (camelVersion != null) {
            jbangArgs.add("-Dcamel.jbang.version=" + camelVersion);
        }
        if (kameletsVersion != null) {
            jbangArgs.add("-Dcamel-kamelets.version=" + kameletsVersion);
        }
        // tooling may signal to run JMX debugger in suspended mode via JVM system property
        // which we must include in args as well
        String debugSuspend = System.getProperty(BacklogDebugger.SUSPEND_MODE_SYSTEM_PROP_NAME);
        if (debugSuspend != null) {
            jbangArgs.add("-D" + BacklogDebugger.SUSPEND_MODE_SYSTEM_PROP_NAME + "=" + debugSuspend);
        }
        if (isDebugMode()) {
            jbangArgs.add("--debug=" + jvmDebugPort); // jbang --debug=port
            cmds.removeIf(arg -> arg.startsWith("--jvm-debug"));
        }

        if (repositories != null) {
            jbangArgs.add("--repos=" + repositories);
        }
        jbangArgs.add("camel@apache/camel");
        jbangArgs.addAll(cmds);

        ProcessBuilder pb = new ProcessBuilder();
        pb.command(jbangArgs);

        if (background) {
            return runBackgroundProcess(pb, "Camel Main");
        } else {
            pb.inheritIO(); // run in foreground (with IO so logs are visible)
            Process p = pb.start();
            this.spawnPid = p.pid();
            // wait for that process to exit as we run in foreground
            return p.waitFor();
        }
    }

    protected int runBackground(KameletMain main) throws Exception {
        List<String> cmds;
        if (spec != null) {
            cmds = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());
        } else {
            cmds = new ArrayList<>();
            cmds.add("run");
            if (transformMessageRun) {
                cmds.add("--empty");
            }
        }

        cmds.remove("--background=true");
        cmds.remove("--background");
        cmds.remove("--background-wait=false");
        cmds.remove("--background-wait=true");
        cmds.remove("--background-wait");

        RunHelper.addCamelJBangCommand(cmds);

        ProcessBuilder pb = new ProcessBuilder();
        pb.command(cmds);

        return runBackgroundProcess(pb, "Camel Main");
    }

    protected int runBackgroundProcess(ProcessBuilder pb, String kind) throws Exception {
        Path logPath = null;
        if (backgroundWait) {
            // store background output in a log file to capture any error on startup
            logPath = getRunBackgroundLogFile("" + new Random().nextLong());
            try {
                Path logDir = CommandLineHelper.getCamelDir();
                Files.createDirectories(logDir); //make sure the parent dir exists
                Files.createFile(logPath);
                logPath.toFile().deleteOnExit();
            } catch (IOException e) {
                // Ignore
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(logPath.toFile());
        }

        Process p = pb.start();
        this.spawnPid = p.pid();
        if (!exportRun && !transformRun && !transformMessageRun) {
            printer().println(
                    "Running " + kind + ": " + name + " in background with PID: " + p.pid()
                              + (backgroundWait ? " (waiting to startup)" : ""));
        }

        int ec = 0;
        if (logPath != null) {
            StopWatch watch = new StopWatch();
            int state = 0; // state 5 is running
            while (p.isAlive() && watch.taken() < 20000 && state < 5) {
                JsonObject root = loadStatus(p.pid());
                if (root != null) {
                    JsonObject context = (JsonObject) root.get("context");
                    if (context != null) {
                        state = context.getInteger("phase");
                    }
                }
                if (state < 5) {
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                        // we want to exit
                        break;
                    }
                }
            }
            if (!p.isAlive()) {
                ec = p.exitValue();
                if (ec != 0) {
                    printer().println(kind + ": " + name + " startup failure");
                    printer().println("");
                    String text = Files.readString(logPath);
                    printer().print(text);
                }
            } else {
                printer().println(kind + ": " + name + " (state: " + extractState(state) + ")");
            }
        }
        if (logPath != null) {
            try {
                Files.deleteIfExists(logPath);
            } catch (IOException e) {
                // Ignore
            }
        }

        return ec;
    }

    protected int runDebug(KameletMain main) throws Exception {
        // to be implemented in Debug
        return 0;
    }

    protected int runCustomCamelVersion(KameletMain main) throws Exception {
        InputStream is = Run.class.getClassLoader().getResourceAsStream("templates/run-custom-camel-version.tmpl");
        String content = IOHelper.loadText(is);
        IOHelper.close(is);

        content = content.replaceFirst("\\{\\{ \\.JavaVersion }}", "21");
        if (repositories != null) {
            content = content.replaceFirst("\\{\\{ \\.MavenRepositories }}", "//REPOS " + repositories);
        } else {
            content = content.replaceFirst("\\{\\{ \\.MavenRepositories }}", "");
        }

        // use custom distribution of camel
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("//DEPS org.apache.camel:camel-bom:%s@pom%n", camelVersion));
        sb.append(String.format("//DEPS org.apache.camel:camel-core:%s%n", camelVersion));
        sb.append(String.format("//DEPS org.apache.camel:camel-core-engine:%s%n", camelVersion));
        sb.append(String.format("//DEPS org.apache.camel:camel-main:%s%n", camelVersion));
        sb.append(String.format("//DEPS org.apache.camel:camel-java-joor-dsl:%s%n", camelVersion));
        sb.append(String.format("//DEPS org.apache.camel:camel-kamelet:%s%n", camelVersion));
        sb.append(String.format("//DEPS org.apache.camel:camel-kamelet-main:%s%n", camelVersion));
        if (VersionHelper.isGE(camelVersion, "3.19.0")) {
            sb.append(String.format("//DEPS org.apache.camel:camel-cli-connector:%s%n", camelVersion));
        }
        content = content.replaceFirst("\\{\\{ \\.CamelDependencies }}", sb.toString());

        // use apache distribution of camel-jbang/github-resolver
        String v = camelVersion.substring(0, camelVersion.lastIndexOf('.'));
        sb = new StringBuilder();
        sb.append(String.format("//DEPS org.apache.camel:camel-jbang-core:%s%n", v));
        sb.append(String.format("//DEPS org.apache.camel:camel-resourceresolver-github:%s%n", v));
        content = content.replaceFirst("\\{\\{ \\.CamelJBangDependencies }}", sb.toString());

        sb = new StringBuilder();
        sb.append(String.format("//DEPS org.apache.camel.kamelets:camel-kamelets:%s%n", kameletsVersion));
        content = content.replaceFirst("\\{\\{ \\.CamelKameletsDependencies }}", sb.toString());

        String fn = CommandLineHelper.CAMEL_JBANG_WORK_DIR + "/CustomCamelJBang.java";
        Files.writeString(Paths.get(fn), content);

        List<String> cmds;
        if (spec != null) {
            cmds = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());
        } else {
            cmds = new ArrayList<>();
            cmds.add("run");
        }

        if (background) {
            cmds.remove("--background=true");
            cmds.remove("--background");
            cmds.remove("--background-wait=true");
            cmds.remove("--background-wait=false");
            cmds.remove("--background-wait");
        }
        if (repositories != null) {
            if (!VersionHelper.isGE(v, "3.18.1")) {
                // --repos is not supported in 3.18.0 or older, so remove
                cmds.remove("--repos=" + repositories);
            }
        }

        cmds.remove("--camel-version=" + camelVersion);
        // need to use jbang command to specify camel version
        List<String> jbangArgs = new ArrayList<>();
        jbangArgs.add("jbang");
        jbangArgs.add(CommandLineHelper.CAMEL_JBANG_WORK_DIR + "/CustomCamelJBang.java");

        jbangArgs.addAll(cmds);

        ProcessBuilder pb = new ProcessBuilder();
        pb.command(jbangArgs);
        if (background) {
            return runBackgroundProcess(pb, "Camel Main");
        } else {
            pb.inheritIO(); // run in foreground (with IO so logs are visible)
            Process p = pb.start();
            this.spawnPid = p.pid();
            // wait for that process to exit as we run in foreground
            return p.waitFor();
        }
    }

    protected int runKameletMain(KameletMain main) throws Exception {
        main.start();
        main.run();

        // cleanup and delete log file
        if (logFile != null) {
            FileUtil.deleteFile(logFile);
        }

        return main.getExitCode();
    }

    private String loadFromCode(String code, String name, boolean file) throws IOException {
        String fn = CommandLineHelper.CAMEL_JBANG_WORK_DIR + "/" + name + ".java";
        InputStream is = Run.class.getClassLoader().getResourceAsStream("templates/code-java.tmpl");
        String content = IOHelper.loadText(is);
        IOHelper.close(is);
        if (!file) {
            // need to replace single quote as double quotes (from input string)
            code = code.replace("'", "\"");
            code = code.trim();
        }
        // ensure the code ends with semicolon to finish the java statement
        if (!code.endsWith(";")) {
            code = code + ";";
        }
        content = StringHelper.replaceFirst(content, "{{ .Name }}", name);
        content = StringHelper.replaceFirst(content, "{{ .Code }}", code);
        Files.writeString(Paths.get(fn), content);
        return "file:" + fn;
    }

    private String evalGistSource(KameletMain main, String file) throws Exception {
        StringJoiner routes = new StringJoiner(",");
        StringJoiner kamelets = new StringJoiner(",");
        StringJoiner properties = new StringJoiner(",");
        fetchGistUrls(file, routes, kamelets, properties);

        if (properties.length() > 0) {
            main.addInitialProperty("camel.component.properties.location", properties.toString());
        }
        if (kamelets.length() > 0) {
            String loc = main.getInitialProperties().getProperty("camel.component.kamelet.location");
            if (loc != null) {
                // local kamelets first
                loc = kamelets + "," + loc;
            } else {
                loc = kamelets.toString();
            }
            main.addInitialProperty("camel.component.kamelet.location", loc);
        }
        if (routes.length() > 0) {
            return routes.toString();
        }
        return null;
    }

    private String evalGithubSource(KameletMain main, String file) throws Exception {
        String ext = FileUtil.onlyExt(file);
        boolean wildcard = FileUtil.onlyName(file, false).contains("*");
        if (ext != null && !wildcard) {
            // it is a single file so map to
            return asGithubSingleUrl(file);
        } else {
            StringJoiner routes = new StringJoiner(",");
            StringJoiner kamelets = new StringJoiner(",");
            StringJoiner properties = new StringJoiner(",");
            fetchGithubUrls(file, routes, kamelets, properties);

            if (properties.length() > 0) {
                main.addInitialProperty("camel.component.properties.location", properties.toString());
            }
            if (kamelets.length() > 0) {
                String loc = main.getInitialProperties().getProperty("camel.component.kamelet.location");
                if (loc != null) {
                    // local kamelets first
                    loc = kamelets + "," + loc;
                } else {
                    loc = kamelets.toString();
                }
                main.addInitialProperty("camel.component.kamelet.location", loc);
            }
            if (routes.length() > 0) {
                return routes.toString();
            }
            return null;
        }
    }

    private String loadFromClipboard(String file) throws UnsupportedFlavorException, IOException {
        // run from clipboard (not real file exists)
        String ext = FileUtil.onlyExt(file, true);
        if (ext == null || ext.isEmpty()) {
            throw new IllegalArgumentException(
                    "When running from clipboard, an extension is required to let Camel know what kind of file to use");
        }
        Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
        Object t = c.getData(DataFlavor.stringFlavor);
        if (t != null) {
            String fn = CLIPBOARD_GENERATED_FILE + "." + ext;
            if ("java".equals(ext)) {
                String fqn = determineClassName(t.toString());
                if (fqn == null) {
                    // wrap code in wrapper
                    return loadFromCode(t.toString(), "ClipboardRoute", true);
                }
                // drop package in file name
                String cn = fqn;
                if (fqn.contains(".")) {
                    cn = cn.substring(cn.lastIndexOf('.') + 1);
                }
                fn = cn + ".java";
            }
            Files.writeString(Paths.get(fn), t.toString());
            file = "file:" + fn;
        }
        return file;
    }

    private KameletMain createMainInstance() {
        KameletMain main;
        if (localKameletDir == null || localKameletDir.isEmpty()) {
            main = new KameletMain(CAMEL_INSTANCE_TYPE);
        } else {
            StringJoiner sj = new StringJoiner(",");
            String[] parts = localKameletDir.split(",");
            for (String part : parts) {
                // automatic map github https urls to github resolver
                if (part.startsWith("https://github.com/")) {
                    part = asGithubSingleUrl(part);
                } else if (part.startsWith("https://gist.github.com/")) {
                    part = asGistSingleUrl(part);
                }
                part = FileUtil.compactPath(part);
                if (!ResourceHelper.hasScheme(part) && !part.startsWith("github:")) {
                    part = "file:" + part;
                }
                sj.add(part);
            }
            main = new KameletMain(CAMEL_INSTANCE_TYPE, sj.toString());
            writeSettings("camel.jbang.localKameletDir", sj.toString());
        }
        return main;
    }

    private void configureLogging() throws Exception {
        if (logging) {
            // allow to configure individual logging levels in application.properties
            Properties prop = loadProfileProperties();
            if (prop != null) {
                for (Object obj : prop.keySet()) {
                    String key = obj.toString();
                    String value = prop.getProperty(key);
                    if (key.startsWith("logging.level.")) {
                        key = key.substring(14);
                    } else if (key.startsWith("quarkus.log.category.")) {
                        key = key.substring(21);
                        if (key.endsWith(".level")) {
                            key = key.substring(0, key.length() - 6);
                        }
                    } else {
                        continue;
                    }
                    key = StringHelper.removeLeadingAndEndingQuotes(key);
                    String line = key + "=" + value;
                    String line2 = key + " = " + value;
                    if (!loggingCategory.contains(line) && !loggingCategory.contains(line2)) {
                        loggingCategory.add(line);
                    }
                }
            }
            RuntimeUtil.configureLog(loggingLevel, loggingColor, loggingJson, scriptRun, false, loggingConfigPath,
                    loggingCategory);
            writeSettings("loggingLevel", loggingLevel);
            writeSettings("loggingColor", loggingColor ? "true" : "false");
            writeSettings("loggingJson", loggingJson ? "true" : "false");
            if (!scriptRun) {
                // remember log file
                String name = RuntimeUtil.getPid() + ".log";
                final Path logDir = CommandLineHelper.getCamelDir();
                Files.createDirectories(logDir); //make sure the parent dir exists
                logFile = logDir.resolve(name);
                try {
                    // Create an empty file that will be deleted on exit
                    Files.createFile(logFile);
                    logFile.toFile().deleteOnExit();
                } catch (IOException e) {
                    // Ignore
                }
            }
        } else {
            if (exportRun) {
                RuntimeUtil.configureLog(loggingLevel, false, false, false, true, null, null);
                writeSettings("loggingLevel", loggingLevel);
            } else {
                RuntimeUtil.configureLog("off", false, false, false, false, null, null);
                writeSettings("loggingLevel", "off");
            }
        }
    }

    private void generateOpenApi() throws Exception {
        Path filePath = Paths.get(openapi);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new FileNotFoundException("Cannot find file: " + filePath);
        }

        try (InputStream is = Run.class.getClassLoader().getResourceAsStream("templates/rest-dsl.yaml.tmpl")) {
            String content = IOHelper.loadText(is);
            String onlyName = filePath.toString();
            content = content.replaceFirst("\\{\\{ \\.Spec }}", onlyName);

            Files.writeString(Paths.get(OPENAPI_GENERATED_FILE), content);

            // we need to include the spec on the classpath
            files.add(openapi);
        }
    }

    private boolean knownFile(String file) throws Exception {
        // always include kamelets
        String ext = FileUtil.onlyExt(file, false);
        if ("kamelet.yaml".equals(ext)) {
            return true;
        }
        String ext2 = FileUtil.onlyExt(file, true);
        if (ext2 != null) {
            SourceScheme sourceScheme = SourceScheme.fromUri(file);
            // special for yaml or xml, as we need to check if they have camel or not
            if (!sourceScheme.isRemote() && ("xml".equals(ext2) || "yaml".equals(ext2))) {
                // load content into memory
                Source source = SourceHelper.resolveSource(file);
                if ("xml".equals(ext2)) {
                    XmlStreamDetector detector = new XmlStreamDetector(
                            new ByteArrayInputStream(source.content().getBytes(StandardCharsets.UTF_8)));
                    XmlStreamInfo info = detector.information();
                    if (!info.isValid()) {
                        return false;
                    }
                    return ACCEPTED_XML_ROOT_ELEMENTS.contains(info.getRootElementName());
                } else {
                    // TODO: we probably need a way to parse the content and match against the YAML DSL expected by Camel
                    // This check looks very fragile
                    return source.content().contains("- from:")
                            || source.content().contains("- route:")
                            || source.content().contains("- routeTemplate") || source.content().contains("- route-template:")
                            || source.content().contains("- routeConfiguration:")
                            || source.content().contains("- route-configuration:")
                            || source.content().contains("- rest:")
                            || source.content().contains("- beans:")
                            // also support Pipes.
                            || source.content().contains("Pipe");
                }
            }
            // if the ext is an accepted file then we include it as a potential route
            // (java files need to be included as route to support pojos/processors with routes)
            return SourceHelper.isAcceptedSourceFile(ext2);
        } else {
            // assume match as it can be wildcard or dir
            return true;
        }
    }

    private boolean skipFile(String name) {
        if (name.startsWith("github:") || name.startsWith("https://github.com/")
                || name.startsWith("https://gist.github.com/")) {
            return false;
        }

        // flatten file
        name = FileUtil.stripPath(name);

        if (OPENAPI_GENERATED_FILE.equals(name)) {
            return false;
        }
        if ("pom.xml".equalsIgnoreCase(name)) {
            return true;
        }
        if ("build.gradle".equalsIgnoreCase(name)) {
            return true;
        }
        if ("camel-runner.jar".equals(name)) {
            return true;
        }
        if ("docker-compose.yml".equals(name) || "docker-compose.yaml".equals(name) || "compose.yml".equals(name)
                || "compose.yaml".equals(name)) {
            return true;
        }
        if (name.equals("NOTICE.txt") || name.equals("LICENSE.txt")) {
            return true;
        }

        if (name.startsWith(".")) {
            // relative file is okay, otherwise we assume it's a hidden file
            boolean ok = name.startsWith("..") || name.startsWith("./");
            if (!ok) {
                return true;
            }
        }

        // is the file excluded?
        if (isExcluded(name, excludes)) {
            return true;
        }

        // skip dirs
        if (!name.startsWith("classpath:")) {
            Path path = Path.of(name);
            if (Files.exists(path) && Files.isDirectory(path)) {
                return true;
            }
        }

        if (FileUtil.onlyExt(name) == null) {
            return true;
        }

        String on = FileUtil.onlyName(name, true);
        on = on.toLowerCase(Locale.ROOT);
        if (on.startsWith("readme")) {
            return true;
        }

        return false;
    }

    private static boolean isExcluded(String name, List<String> excludes) {
        if (excludes != null) {
            for (String pattern : excludes) {
                pattern = pattern.trim();
                if (AntPathMatcher.INSTANCE.match(pattern, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isGroovyFile(String name) {
        return name.endsWith(".groovy");
    }

    private boolean isScriptFile(String name) {
        return name.endsWith(".sh");
    }

    private boolean isTlsFile(String name) {
        return name.endsWith(".crt") || name.endsWith(".key") || name.endsWith(".pem");
    }

    private boolean jkubeFile(String name) {
        return name.endsWith(".jkube.yaml") || name.endsWith(".jkube.yml");
    }

    private void writeSettings(String key, String value) {
        try {
            // use java.util.Properties to ensure the value is escaped correctly
            Properties prop = new Properties();
            prop.setProperty(key, value);
            StringWriter sw = new StringWriter();
            prop.store(sw, null);

            Path runSettingsPath = CommandLineHelper.getWorkDir().resolve(RUN_SETTINGS_FILE);

            StringBuilder content = new StringBuilder();
            String[] lines = sw.toString().split(System.lineSeparator());
            for (String line : lines) {
                // properties store timestamp as comment which we want to skip
                if (!line.startsWith("#")) {
                    content.append(line).append(System.lineSeparator());
                }
            }

            // Append to the file if it exists, otherwise create it
            if (Files.exists(runSettingsPath)) {
                Files.write(runSettingsPath, content.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND);
            } else {
                Files.write(runSettingsPath, content.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static void removeDir(Path directory) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                // Fallback to deleteOnExit if we can't delete immediately
                                try {
                                    path.toFile().deleteOnExit();
                                } catch (Exception ex) {
                                    // Ignore
                                }
                            }
                        });
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    private static void delete(Path path) {
        try {
            if (!Files.deleteIfExists(path)) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (!Files.deleteIfExists(path)) {
                    path.toFile().deleteOnExit();
                }
            }
        } catch (IOException e) {
            // Fallback to deleteOnExit
            try {
                path.toFile().deleteOnExit();
            } catch (Exception ex) {
                // Ignore
            }
        }
    }

    private static String determineClassName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        String pn = matcher.find() ? matcher.group(1) : null;

        matcher = CLASS_PATTERN.matcher(content);
        String cn = matcher.find() ? matcher.group(1) : null;

        String fqn;
        if (pn != null) {
            fqn = pn + "." + cn;
        } else {
            fqn = cn;
        }
        return fqn;
    }

    private static boolean isReloadRecursive(String reload) {
        for (String part : reload.split(",")) {
            String dir = FileUtil.onlyPath(part);
            if (dir != null) {
                return true;
            }
        }
        return false;
    }

    private class RunDownloadListener implements DownloadListener {
        final Set<String> downloaded = new HashSet<>();
        final Set<String> repos = new HashSet<>();
        final Set<String> kamelets = new HashSet<>();

        @Override
        public void onDownloadDependency(String groupId, String artifactId, String version) {
            String line = "mvn:" + groupId + ":" + artifactId;
            if (ObjectHelper.isNotEmpty(version)) {
                line += ":" + version;
            }
            if (!downloaded.contains(line)) {
                writeSettings("dependency", line);
                downloaded.add(line);
            }
        }

        @Override
        public void onAlreadyDownloadedDependency(String groupId, String artifactId, String version) {
            // we want to register everything
            onDownloadDependency(groupId, artifactId, version);
        }

        @Override
        public void onExtraRepository(String repo) {
            if (!repos.contains(repo)) {
                writeSettings("repository", repo);
                repos.add(repo);
            }
        }

        @Override
        public void onLoadingKamelet(String name) {
            if (!kamelets.contains(name)) {
                writeSettings("kamelet", name);
                kamelets.add(name);
            }
        }
    }

    static class FilesConsumer extends ParameterConsumer<Run> {
        @Override
        protected void doConsumeParameters(Stack<String> args, Run cmd) {
            String arg = args.pop();
            cmd.files.add(arg);
        }
    }

    static class DebugConsumer extends ParameterConsumer<Run> {
        private static final Pattern DEBUG_ARG_VALUE_PATTERN = Pattern.compile("\\d+|true|false");

        @Override
        protected void doConsumeParameters(Stack<String> args, Run cmd) {
            String arg = args.isEmpty() ? "" : args.peek();
            if (DEBUG_ARG_VALUE_PATTERN.asPredicate().test(arg)) {
                // The value matches with the expected format so let's assume that it is a debug argument value
                args.pop();
            } else {
                // Here we assume that the value is not a debug argument value so let's simply enable the debug mode
                arg = "true";
            }
            cmd.jvmDebugPort = parseJvmDebugPort(arg);
        }

        @Override
        protected boolean failIfEmptyArgs() {
            return false;
        }
    }

    private JsonObject loadStatus(long pid) {
        try {
            Path p = getStatusFile(Long.toString(pid));
            if (Files.exists(p)) {
                try (InputStream is = Files.newInputStream(p)) {
                    String text = IOHelper.loadText(is);
                    return (JsonObject) Jsoner.deserialize(text);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    @Override
    protected Printer printer() {
        if (exportRun && (!logging && !verbose)) {
            // Export run should be silent unless in logging or verbose mode
            if (quietPrinter == null) {
                quietPrinter = new Printer.QuietPrinter(super.printer());
            }

            CommandHelper.setPrinter(quietPrinter);
            return quietPrinter;
        }

        return super.printer();
    }

}
