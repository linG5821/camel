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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.apache.camel.dsl.jbang.core.common.CommandLineHelper;
import org.apache.camel.dsl.jbang.core.common.RuntimeType;
import org.apache.camel.dsl.jbang.core.common.XmlHelper;
import org.apache.camel.tooling.maven.MavenGav;
import org.apache.camel.util.CamelCaseOrderedProperties;
import picocli.CommandLine;

@CommandLine.Command(name = "list",
                     description = "Displays all Camel dependencies required to run", sortOptions = false,
                     showDefaultValues = true)
public class DependencyList extends Export {

    protected static final String EXPORT_DIR = CommandLineHelper.CAMEL_JBANG_WORK_DIR + "/export";

    @CommandLine.Option(names = { "--output" }, description = "Output format (gav, maven, jbang)", defaultValue = "gav")
    protected String output;

    public DependencyList(CamelJBangMain main) {
        super(main);
    }

    @Override
    public Integer doCall() throws Exception {
        this.quiet = true; // be quiet and generate from fresh data to ensure the output is up-to-date
        return super.doCall();
    }

    @Override
    protected Integer export() throws Exception {
        if (!"gav".equals(output) && !"maven".equals(output) && !"jbang".equals(output)) {
            printer().printErr("--output must be either gav or maven, was: " + output);
            return 1;
        }

        // automatic detect maven/gradle based projects and use that
        if (files.isEmpty()) {
            if (Files.exists(Paths.get("pom.xml"))) {
                files.add("pom.xml");
            } else if (Files.exists(Paths.get("build.gradle"))) {
                files.add("build.gradle");
            }
        }

        Integer answer = doExport();
        if (answer == 0) {
            // read pom.xml
            Path pom = Paths.get(EXPORT_DIR).resolve("pom.xml");
            if (Files.exists(pom)) {
                DocumentBuilderFactory dbf = XmlHelper.createDocumentBuilderFactory();
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document dom = db.parse(Files.newInputStream(pom));
                NodeList nl = dom.getElementsByTagName("dependency");
                List<MavenGav> gavs = new ArrayList<>();
                String camelVersion = null;
                String springBootVersion = null;
                String quarkusVersion = null;
                for (int i = 0; i < nl.getLength(); i++) {
                    Element node = (Element) nl.item(i);

                    // must be child at <project/dependencyManagement> or <project/dependencies>
                    String p = node.getParentNode().getNodeName();
                    String p2 = node.getParentNode().getParentNode().getNodeName();
                    boolean accept = ("dependencyManagement".equals(p2) || "project".equals(p2)) && (p.equals("dependencies"));
                    if (!accept) {
                        continue;
                    }

                    String g = node.getElementsByTagName("groupId").item(0).getTextContent();
                    String a = node.getElementsByTagName("artifactId").item(0).getTextContent();
                    String v = null;
                    NodeList vl = node.getElementsByTagName("version");
                    if (vl.getLength() > 0) {
                        v = vl.item(0).getTextContent();
                    }

                    // BOMs
                    if ("org.apache.camel".equals(g) && "camel-bom".equals(a)) {
                        camelVersion = v;
                        continue;
                    }
                    if ("org.apache.camel.springboot".equals(g) && "camel-spring-boot-bom".equals(a)) {
                        camelVersion = v;
                        continue;
                    }
                    if ("org.springframework.boot".equals(g) && "spring-boot-dependencies".equals(a)) {
                        springBootVersion = v;
                        continue;
                    }
                    if (("${quarkus.platform.group-id}".equals(g) || "io.quarkus.platform".equals(g)) &&
                            ("${quarkus.platform.artifact-id}".equals(a) || "quarkus-bom".equals(a))) {
                        if ("${quarkus.platform.version}".equals(v)) {
                            quarkusVersion = dom.getElementsByTagName("quarkus.platform.version").item(0).getTextContent();
                        } else {
                            quarkusVersion = v;
                        }
                        continue;
                    }

                    // scope
                    String scope = null;
                    NodeList sl = node.getElementsByTagName("scope");
                    if (sl.getLength() > 0) {
                        scope = sl.item(0).getTextContent();
                    }
                    if ("test".equals(scope) || "import".equals(scope)) {
                        // skip test/BOM import scopes
                        continue;
                    }

                    // version
                    if (v == null && g.equals("org.apache.camel")) {
                        v = camelVersion;
                    }
                    if (v == null && g.equals("org.apache.camel.springboot")) {
                        v = camelVersion;
                    }
                    if (v == null && g.equals("org.springframework.boot")) {
                        v = springBootVersion;
                    }
                    if (v == null && (g.equals("io.quarkus") || g.equals("org.apache.camel.quarkus"))) {
                        v = quarkusVersion;
                    }

                    if (skipArtifact(g, a, v)) {
                        continue;
                    }
                    if (v != null) {
                        gavs.add(MavenGav.parseGav(g + ":" + a + ":" + v));
                    } else {
                        gavs.add(MavenGav.parseGav(g + ":" + a));
                    }
                }
                // sort GAVs
                gavs.sort(mavenGavComparator());
                int i = 0;
                int total = gavs.size();
                for (MavenGav gav : gavs) {
                    outputGav(gav, i, total);
                    i++;
                }
            }
            // cleanup dir after complete
            Path buildDir = Paths.get(EXPORT_DIR);
            try {
                Files.walk(buildDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                // ignore
                            }
                        });
            } catch (IOException e) {
                // ignore
            }
        }
        return answer;
    }

    protected void outputGav(MavenGav gav, int index, int total) {
        if ("gav".equals(output)) {
            outPrinter().println(String.valueOf(gav));
        } else if ("maven".equals(output)) {
            outPrinter().println("<dependency>");
            outPrinter().printf("    <groupId>%s</groupId>%n", gav.getGroupId());
            outPrinter().printf("    <artifactId>%s</artifactId>%n", gav.getArtifactId());
            outPrinter().printf("    <version>%s</version>%n", gav.getVersion());
            if (gav.getScope() != null) {
                outPrinter().printf("    <scope>%s</scope>%n", gav.getScope());
            }
            outPrinter().println("</dependency>");
        } else if ("jbang".equals(output)) {
            if (index == 0) {
                outPrinter().println("//DEPS org.apache.camel:camel-bom:" + gav.getVersion() + "@pom");
            }
            if (gav.getGroupId().equals("org.apache.camel")) {
                // jbang has version in @pom so we should remove this
                gav.setVersion(null);
            }
            outPrinter().println("//DEPS " + gav);
        }
    }

    protected Integer doExport() throws Exception {
        // read runtime and gav from properties if not configured
        Path profile = Paths.get("application.properties");
        if (Files.exists(profile)) {
            Properties prop = new CamelCaseOrderedProperties();
            try (InputStream is = Files.newInputStream(profile)) {
                prop.load(is);
            } catch (IOException e) {
                // ignore
            }
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
            gav = "org.example.project:camel-jbang-dummy:1.0";
        }
        if (runtime == null) {
            runtime = RuntimeType.main;
        }

        // turn off noise
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

    protected boolean skipArtifact(String groupId, String artifactId, String version) {
        // skip jansi which is used for color logging
        if ("org.fusesource.jansi".equals(groupId)) {
            return true;
        }
        // skip logging framework
        if ("org.apache.logging.log4j".equals(groupId)) {
            return true;
        }

        return false;
    }

}
