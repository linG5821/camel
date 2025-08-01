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
package org.apache.camel.component.file.remote.sftp.integration;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

@EnabledIf(value = "org.apache.camel.test.infra.ftp.services.embedded.SftpUtil#hasRequiredAlgorithms('src/test/resources/hostkey.pem')")
public class SftpSimpleConsumeWithAntIncludeIT extends SftpServerTestSupport {

    @BeforeEach
    void setup() {
        testConfigurationBuilder.withUseRouteBuilder(false);
    }

    @Test
    public void testSftpSimpleConsume() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("sftp://localhost:{{ftp.server.port}}/{{ftp.root.dir}}"
                     + "?username=admin&password=admin&delay=10000&disconnect=true"
                     + "&recursive=true&antInclude=hello.txt"
                     + "&knownHostsFile="
                     + service.getKnownHostsFile()).to("mock:result");
            }
        });
        context.start();

        try {
            String expected = "Hello World";

            // create file using regular file
            template.sendBodyAndHeader("file://" + service.getFtpRootDir(), expected, Exchange.FILE_NAME, "hello.txt");

            MockEndpoint mock = getMockEndpoint("mock:result");
            mock.expectedMessageCount(1);
            mock.expectedHeaderReceived(Exchange.FILE_NAME, "hello.txt");
            mock.expectedBodiesReceived(expected);

            MockEndpoint.assertIsSatisfied(context);
        } finally {
            context.stop();
        }
    }

    @Test
    public void testSftpSimpleConsumeWithTrailingSlash() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("sftp://localhost:{{ftp.server.port}}/{{ftp.root.dir}}/"
                     + "?username=admin&password=admin&delay=10000&disconnect=true"
                     + "&recursive=true&antInclude=hello.txt"
                     + "&knownHostsFile="
                     + service.getKnownHostsFile()).to("mock:result");
            }
        });
        context.start();

        try {
            String expected = "Hello World";

            // create file using regular file
            template.sendBodyAndHeader("file://" + service.getFtpRootDir(), expected, Exchange.FILE_NAME, "hello.txt");

            MockEndpoint mock = getMockEndpoint("mock:result");
            mock.expectedMessageCount(1);
            mock.expectedHeaderReceived(Exchange.FILE_NAME, "hello.txt");
            mock.expectedBodiesReceived(expected);

            MockEndpoint.assertIsSatisfied(context);
        } finally {
            context.stop();
        }
    }

    @Test
    public void testSftpSimpleConsumeWithSubdir() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("sftp://localhost:{{ftp.server.port}}/{{ftp.root.dir}}"
                     + "?username=admin&password=admin&delay=10000&disconnect=true"
                     + "&recursive=true&antInclude=subdir/hello.txt"
                     + "&knownHostsFile="
                     + service.getKnownHostsFile()).to("mock:result");
            }
        });
        context.start();

        try {
            String expected = "Hello World";

            // create file using regular file
            template.sendBodyAndHeader("file://" + service.getFtpRootDir(), expected, Exchange.FILE_NAME, "subdir/hello.txt");

            MockEndpoint mock = getMockEndpoint("mock:result");
            mock.expectedMessageCount(1);
            mock.expectedHeaderReceived(Exchange.FILE_NAME, "subdir/hello.txt");
            mock.expectedBodiesReceived(expected);

            MockEndpoint.assertIsSatisfied(context);
        } finally {
            context.stop();
        }
    }
}
