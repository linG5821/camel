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
package org.apache.camel.service.lra;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import org.apache.camel.Exchange;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class LRAClientTest extends CamelTestSupport {

    public LRAClientTest() {
        testConfiguration().withUseRouteBuilder(false);
    }

    @DisplayName("Tests whether LRAClient is using a default HttpClient")
    @Test
    void testCanCreateLRAClient() throws Exception {
        LRASagaService sagaService = new LRASagaService();
        applyMockProperties(sagaService);
        LRAClient client = new LRAClient(sagaService);
        Assertions.assertNotNull(client, "client must not be null after initializing");
    }

    @DisplayName("Tests whether LRAClient is using a custom set HttpClient")
    @Test
    void testCanCreateLRAClientWithCustomHttpClient() throws Exception {
        LRASagaService sagaService = new LRASagaService();
        applyMockProperties(sagaService);
        LRAClient client = new LRAClient(sagaService, HttpClient.newBuilder().build());
        Assertions.assertNotNull(client, "client must not be null after initializing");
    }

    @DisplayName("Tests whether LRAClient is throwing an exception if httpclient is null")
    @Test
    void testCannotCreateLRAClientWithoutHttpClient() throws Exception {
        LRASagaService sagaService = new LRASagaService();
        applyMockProperties(sagaService);
        Assertions.assertThrows(IllegalArgumentException.class, () -> new LRAClient(sagaService, null),
                "no client should result in IllegalArgumentException");
    }

    @DisplayName("Tests whether LRAClient is calling prepareRequest with exchange from newLRA()")
    @Test
    void testCallsPrepareRequestWithExchangeInNewLra() {
        LRASagaService sagaService = new LRASagaService();
        applyMockProperties(sagaService);
        LRAClient client = new LRAClient(sagaService) {
            protected HttpRequest.Builder prepareRequest(URI uri, Exchange exchange) {
                throw new ExchangeRuntimeException(exchange);
            }
        };
        Exchange exchange = Mockito.mock(Exchange.class);
        Assertions.assertThrows(ExchangeRuntimeException.class, () -> client.newLRA(exchange));
    }

    @DisplayName("Tests whether LRAClient is calling prepareRequest with exchange from compensate()")
    @Test
    void testCallsPrepareRequestWithExchangeInCompensate() throws MalformedURLException {
        LRASagaService sagaService = new LRASagaService();
        applyMockProperties(sagaService);
        LRAClient client = new LRAClient(sagaService) {
            protected HttpRequest.Builder prepareRequest(URI uri, Exchange exchange) {
                throw new ExchangeRuntimeException(exchange);
            }
        };
        Exchange exchange = Mockito.mock(Exchange.class);
        Assertions.assertThrows(ExchangeRuntimeException.class,
                () -> client.compensate(URI.create("https://localhost/saga").toURL(), exchange));
    }

    @DisplayName("Tests whether LRAClient is calling prepareRequest with exchange from complete()")
    @Test
    void testCallsPrepareRequestWithExchangeInComplete() throws MalformedURLException {
        LRASagaService sagaService = new LRASagaService();
        applyMockProperties(sagaService);
        LRAClient client = new LRAClient(sagaService) {
            protected HttpRequest.Builder prepareRequest(URI uri, Exchange exchange) {
                throw new ExchangeRuntimeException(exchange);
            }
        };
        Exchange exchange = Mockito.mock(Exchange.class);
        Assertions.assertThrows(ExchangeRuntimeException.class,
                () -> client.complete(URI.create("https://localhost/saga").toURL(), exchange));
    }

    @DisplayName("Tests prepare request works without exchange")
    @Test
    void testPrepareRequestWithoutExchange() throws Exception {
        LRASagaService sagaService = new LRASagaService();
        applyMockProperties(sagaService);
        LRAClient client = new LRAClient(sagaService);
        URI uri = new URI("https://lcoalhost/someURI");
        HttpRequest.Builder expected = HttpRequest.newBuilder(uri);
        HttpRequest.Builder actual = client.prepareRequest(uri, null);

        Assertions.assertEquals(actual.build(), expected.build());
    }

    private void applyMockProperties(LRASagaService sagaService) {
        sagaService.setCoordinatorUrl("mockCoordinatorUrl");
        sagaService.setLocalParticipantUrl("mockLocalParticipantUrl");
        sagaService.setLocalParticipantContextPath("mockLocalParticipantContextPath");
        sagaService.setCoordinatorContextPath("mockCoordinatorContextPath");
    }

    private static class ExchangeRuntimeException extends RuntimeException {
        private Exchange exchange;

        public ExchangeRuntimeException(Exchange exchange) {
            this.exchange = exchange;
        }

        public Exchange get() {
            return exchange;
        }
    }
}
