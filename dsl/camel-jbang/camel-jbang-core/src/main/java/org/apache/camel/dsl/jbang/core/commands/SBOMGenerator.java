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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.camel.dsl.jbang.core.common.CommandLineHelper;
import org.apache.camel.dsl.jbang.core.common.RuntimeType;
import org.apache.camel.dsl.jbang.core.common.RuntimeUtil;
import org.apache.camel.util.CamelCaseOrderedProperties;
import org.apache.camel.util.FileUtil;
import picocli.CommandLine;

@CommandLine.Command(name = "sbom",
                     description = "Generate a CycloneDX or SPDX SBOM for a specific project", sortOptions = false,
                     showDefaultValues = true)
public class SBOMGenerator extends Export {

    protected static final String EXPORT_DIR = CommandLineHelper.CAMEL_JBANG_WORK_DIR + "/export";

    protected static final String CYCLONEDX_FORMAT = "cyclonedx";
    protected static final String SPDX_FORMAT = "spdx";
    protected static final String SBOM_JSON_FORMAT = "json";
    protected static final String SBOM_XML_FORMAT = "xml";

    @CommandLine.Option(names = { "--output-directory" }, description = "Directory where the SBOM will be saved",
                        defaultValue = ".")
    protected String outputDirectory = ".";

    @CommandLine.Option(names = { "--output-name" }, description = "Output name of the SBOM file",
                        defaultValue = "sbom")
    protected String outputName = "sbom";

    @CommandLine.Option(names = { "--cyclonedx-plugin-version" }, description = "The CycloneDX Maven Plugin version",
                        defaultValue = "2.9.1")
    protected String cyclonedxPluginVersion = "2.9.1";

    @CommandLine.Option(names = { "--spdx-plugin-version" }, description = "The SPDX Maven Plugin version",
                        defaultValue = "0.7.4")
    protected String spdxPluginVersion = "0.7.4";

    @CommandLine.Option(names = { "--sbom-format" }, description = "The SBOM format, possible values are cyclonedx or spdx",
                        defaultValue = CYCLONEDX_FORMAT)
    protected String sbomFormat = CYCLONEDX_FORMAT;

    @CommandLine.Option(names = { "--sbom-output-format" },
                        description = "The SBOM output format, possible values are json or xml",
                        defaultValue = SBOM_JSON_FORMAT)
    protected String sbomOutputFormat = SBOM_JSON_FORMAT;

    public SBOMGenerator(CamelJBangMain main) {
        super(main);
    }

    @Override
    public Integer doCall() throws Exception {
        this.quiet = true; // be quiet and generate from fresh data to ensure the output is up-to-date
        return super.doCall();
    }

    @Override
    protected Integer export() throws Exception {
        Integer answer = doExport();
        if (answer == 0) {
            Path buildDir = Paths.get(EXPORT_DIR);
            String mvnProgramCall;
            if (FileUtil.isWindows()) {
                mvnProgramCall = "cmd /c mvn";
            } else {
                mvnProgramCall = "mvn";
            }
            boolean done;
            if (sbomFormat.equalsIgnoreCase(CYCLONEDX_FORMAT)) {
                String outputDirectoryParameter = "-DoutputDirectory=";
                if (Paths.get(outputDirectory).isAbsolute()) {
                    outputDirectoryParameter += outputDirectory;
                } else {
                    outputDirectoryParameter += "../../" + outputDirectory;
                }
                ProcessBuilder pb = new ProcessBuilder(
                        mvnProgramCall,
                        "org.cyclonedx:cyclonedx-maven-plugin:" + cyclonedxPluginVersion + ":makeAggregateBom",
                        outputDirectoryParameter,
                        "-DoutputName=" + outputName,
                        "-DoutputFormat=" + sbomOutputFormat);

                pb.directory(buildDir.toFile());

                Process p = pb.start();
                done = p.waitFor(60, TimeUnit.SECONDS);
                if (!done) {
                    answer = 1;
                }
                if (p.exitValue() != 0) {
                    answer = p.exitValue();
                }
            } else if (sbomFormat.equalsIgnoreCase(SPDX_FORMAT)) {
                String outputDirectoryParameter = null;
                String outputFormat = null;
                if (Paths.get(outputDirectory).isAbsolute()) {
                    outputDirectoryParameter = outputDirectory;
                } else {
                    outputDirectoryParameter = "../../" + outputDirectory;
                }
                if (sbomOutputFormat.equalsIgnoreCase(SBOM_JSON_FORMAT)) {
                    outputFormat = "JSON";
                } else if (sbomOutputFormat.equalsIgnoreCase(SBOM_XML_FORMAT)) {
                    outputFormat = "RDF/XML";
                }
                ProcessBuilder pb = new ProcessBuilder(
                        mvnProgramCall,
                        "org.spdx:spdx-maven-plugin:" + spdxPluginVersion + ":createSPDX",
                        "-DspdxFileName=" + Paths.get(outputDirectoryParameter, outputName + "." + sbomOutputFormat),
                        "-DoutputFormat=" + outputFormat);
                pb.directory(buildDir.toFile());

                Process p = pb.start();
                done = p.waitFor(60, TimeUnit.SECONDS);
                if (!done) {
                    answer = 1;
                }
                if (p.exitValue() != 0) {
                    answer = p.exitValue();
                }
            }
            // cleanup dir after complete
            org.apache.camel.dsl.jbang.core.common.PathUtils.deleteDirectory(buildDir);
        }
        return answer;
    }

    protected Integer doExport() throws Exception {
        // read runtime and gav from properties if not configured
        Path profile = Paths.get("application.properties");
        if (Files.exists(profile)) {
            Properties prop = new CamelCaseOrderedProperties();
            RuntimeUtil.loadProperties(prop, profile);
            if (this.runtime == null && prop.containsKey("camel.jbang.runtime")) {
                this.runtime = RuntimeType.fromValue(prop.getProperty("camel.jbang.runtime"));
            }
            if (this.gav == null) {
                this.gav = prop.getProperty("camel.jbang.gav");
            }
            // allow configuring versions from profile
            this.javaVersion = prop.getProperty("camel.jbang.javaVersion", this.javaVersion);
            this.camelVersion = prop.getProperty("camel.jbang.camelVersion", this.camelVersion);
            this.kameletsVersion = prop.getProperty("camel.jbang.kameletsVersion", this.kameletsVersion);
            this.localKameletDir = prop.getProperty("camel.jbang.localKameletDir", this.localKameletDir);
            this.quarkusGroupId = prop.getProperty("camel.jbang.quarkusGroupId", this.quarkusGroupId);
            this.quarkusArtifactId = prop.getProperty("camel.jbang.quarkusArtifactId", this.quarkusArtifactId);
            this.quarkusVersion = prop.getProperty("camel.jbang.quarkusVersion", this.quarkusVersion);
            this.springBootVersion = prop.getProperty("camel.jbang.springBootVersion", this.springBootVersion);
        }

        // use temporary export dir
        exportDir = EXPORT_DIR;
        if (gav == null) {
            gav = "org.example.project:camel-jbang-export:1.0";
        }
        if (runtime == null) {
            runtime = RuntimeType.main;
        }

        switch (runtime) {
            case springBoot -> {
                return export(new ExportSpringBoot(getMain()));
            }
            case quarkus -> {
                return export(new ExportQuarkus(getMain()));
            }
            case main -> {
                return export(new ExportCamelMain(getMain()));
            }
            default -> {
                printer().printErr("Unknown runtime: " + runtime);
                return 1;
            }
        }
    }
}
