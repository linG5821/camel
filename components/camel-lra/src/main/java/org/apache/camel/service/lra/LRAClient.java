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

import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.apache.camel.Exchange;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.service.lra.LRAConstants.COORDINATOR_PATH_CANCEL;
import static org.apache.camel.service.lra.LRAConstants.COORDINATOR_PATH_CLOSE;
import static org.apache.camel.service.lra.LRAConstants.COORDINATOR_PATH_START;
import static org.apache.camel.service.lra.LRAConstants.HEADER_LINK;
import static org.apache.camel.service.lra.LRAConstants.HEADER_TIME_LIMIT;
import static org.apache.camel.service.lra.LRAConstants.PARTICIPANT_PATH_COMPENSATE;
import static org.apache.camel.service.lra.LRAConstants.PARTICIPANT_PATH_COMPLETE;

public class LRAClient implements Closeable {

    public static final String CONTENT_TYPE = "Content-Type";
    public static final String TEXT_PLAIN_CONTENT = "text/plain";
    private final LRASagaService sagaService;
    private final HttpClient client;
    private final String lraUrl;

    private static final Logger LOG = LoggerFactory.getLogger(LRAClient.class);

    public LRAClient(LRASagaService sagaService) {
        this(sagaService, HttpClient.newHttpClient());
    }

    public LRAClient(LRASagaService sagaService, HttpClient client) {
        if (client == null) {
            throw new IllegalArgumentException("HttpClient must not be null");
        }

        this.sagaService = sagaService;
        this.client = client;

        lraUrl = new LRAUrlBuilder()
                .host(sagaService.getCoordinatorUrl())
                .path(sagaService.getCoordinatorContextPath())
                .build();
    }

    public CompletableFuture<URL> newLRA(Exchange exchange) {
        HttpRequest request = prepareRequest(URI.create(lraUrl + COORDINATOR_PATH_START), exchange)
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();

        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        return future.thenApply(res -> {
            if (res.statusCode() >= HttpURLConnection.HTTP_BAD_REQUEST) {
                LOG.debug("LRA coordinator responded with error code {}. Message: {}", res.statusCode(), res.body());
                throw new IllegalStateException(
                        "Cannot obtain LRA id from LRA coordinator due to response status code " + res.statusCode());
            }

            // See if there's a location header containing the LRA URL
            List<String> location = res.headers().map().get("Location");
            if (ObjectHelper.isNotEmpty(location)) {
                return toURL(location.get(0));
            }

            // If there's no location header try the Long-Running-Action header, assuming there's only one present in the response
            List<String> lraHeaders = res.headers().map().get(Exchange.SAGA_LONG_RUNNING_ACTION);
            if (ObjectHelper.isNotEmpty(lraHeaders) && lraHeaders.size() == 1) {
                return toURL(lraHeaders.get(0));
            }

            // Fallback to reading the URL from the response body
            String responseBody = res.body();
            if (ObjectHelper.isNotEmpty(responseBody)) {
                return toURL(responseBody);
            }

            throw new IllegalStateException("Cannot obtain LRA id from LRA coordinator");
        });
    }

    public CompletableFuture<Void> join(final URL lra, LRASagaStep step, Exchange exchange) {
        return CompletableFuture.supplyAsync(() -> {
            LRAUrlBuilder participantBaseUrl = new LRAUrlBuilder()
                    .host(sagaService.getLocalParticipantUrl())
                    .path(sagaService.getLocalParticipantContextPath())
                    .options(step.getOptions())
                    .compensation(step.getCompensation())
                    .completion(step.getCompletion());

            String compensationURL = participantBaseUrl.path(PARTICIPANT_PATH_COMPENSATE).build();
            String completionURL = participantBaseUrl.path(PARTICIPANT_PATH_COMPLETE).build();

            StringBuilder link = new StringBuilder();
            link.append('<').append(compensationURL).append('>').append("; rel=compensate");
            link.append(',');
            link.append('<').append(completionURL).append('>').append("; rel=complete");

            String lraEndpoint = lra.toString();
            if (step.getTimeoutInMilliseconds().isPresent()) {
                lraEndpoint = lraEndpoint + "?" + HEADER_TIME_LIMIT + "=" + step.getTimeoutInMilliseconds().get();
            }
            HttpRequest request = prepareRequest(URI.create(lraEndpoint), exchange)
                    .setHeader(HEADER_LINK, link.toString())
                    .setHeader(Exchange.SAGA_LONG_RUNNING_ACTION, lra.toString())
                    .setHeader(CONTENT_TYPE, TEXT_PLAIN_CONTENT)
                    .PUT(HttpRequest.BodyPublishers.ofString(link.toString()))
                    .build();

            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        }, sagaService.getExecutorService())
                .thenCompose(Function.identity())
                .thenApply(response -> {
                    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                        throw new RuntimeCamelException("Cannot join LRA");
                    }

                    return null;
                });
    }

    public CompletableFuture<Void> complete(URL lra, Exchange exchange) {
        HttpRequest request = prepareRequest(URI.create(lra.toString() + COORDINATOR_PATH_CLOSE), exchange)
                .setHeader(CONTENT_TYPE, TEXT_PLAIN_CONTENT)
                .PUT(HttpRequest.BodyPublishers.ofString(""))
                .build();

        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        return future.thenApply(response -> {
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new RuntimeCamelException("Cannot complete LRA");
            }

            return null;
        });
    }

    public CompletableFuture<Void> compensate(URL lra, Exchange exchange) {
        HttpRequest request = prepareRequest(URI.create(lra.toString() + COORDINATOR_PATH_CANCEL), exchange)
                .setHeader(CONTENT_TYPE, TEXT_PLAIN_CONTENT)
                .PUT(HttpRequest.BodyPublishers.ofString(""))
                .build();

        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        return future.thenApply(response -> {
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new RuntimeCamelException("Cannot compensate LRA");
            }

            return null;
        });
    }

    protected HttpRequest.Builder prepareRequest(URI uri, Exchange exchange) {
        return HttpRequest.newBuilder().uri(uri);
    }

    private URL toURL(Object url) {
        if (url == null) {
            return null;
        }
        if (url instanceof URL) {
            return URL.class.cast(url);
        }

        try {
            return URI.create(url.toString()).toURL();
        } catch (Exception ex) {
            throw new RuntimeCamelException(ex);
        }
    }

    @Override
    public void close() throws IOException {
    }
}
