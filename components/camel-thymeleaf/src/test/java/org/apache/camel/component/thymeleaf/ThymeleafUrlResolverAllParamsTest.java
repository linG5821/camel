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
package org.apache.camel.component.thymeleaf;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.UrlTemplateResolver;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest(httpPort = 9843)
public class ThymeleafUrlResolverAllParamsTest extends ThymeleafAbstractBaseTest {

    @Test
    public void testThymeleaf() throws Exception {
        stubFor(get("/dontcare.html").willReturn(ok(fragment())));

        MockEndpoint mock = getMockEndpoint(MOCK_RESULT);
        mock.expectedMessageCount(1);
        mock.message(0).body().contains(YOU_WILL_NOTIFIED);
        mock.message(0).header(ThymeleafConstants.THYMELEAF_TEMPLATE).isNull();
        mock.message(0).header(FIRST_NAME).isEqualTo(JANE);

        template.request(DIRECT_START, urlTemplateHeaderProcessor);

        mock.assertIsSatisfied();

        ThymeleafEndpoint thymeleafEndpoint = context.getEndpoint(
                "thymeleaf:dontcare?allowTemplateFromHeader=true&allowContextMapAll=true&cacheTimeToLive=500&cacheable=false&encoding=UTF-8&order=1&prefix=&suffix=.html&resolver=URL&templateMode=HTML",
                ThymeleafEndpoint.class);

        assertAll("properties",
                () -> assertNotNull(thymeleafEndpoint),
                () -> assertTrue(thymeleafEndpoint.isAllowContextMapAll()),
                () -> assertFalse(thymeleafEndpoint.getCacheable()),
                () -> assertEquals(CACHE_TIME_TO_LIVE, thymeleafEndpoint.getCacheTimeToLive()),
                () -> assertNull(thymeleafEndpoint.getCheckExistence()),
                () -> assertEquals(UTF_8_ENCODING, thymeleafEndpoint.getEncoding()),
                () -> assertEquals(ExchangePattern.InOut, thymeleafEndpoint.getExchangePattern()),
                () -> assertEquals(ORDER, thymeleafEndpoint.getOrder()),
                () -> assertEquals("", thymeleafEndpoint.getPrefix()),
                () -> assertEquals(ThymeleafResolverType.URL, thymeleafEndpoint.getResolver()),
                () -> assertEquals(HTML_SUFFIX, thymeleafEndpoint.getSuffix()),
                () -> assertNotNull(thymeleafEndpoint.getTemplateEngine()),
                () -> assertEquals(HTML, thymeleafEndpoint.getTemplateMode()));

        assertEquals(1, thymeleafEndpoint.getTemplateEngine().getTemplateResolvers().size());
        ITemplateResolver resolver = thymeleafEndpoint.getTemplateEngine().getTemplateResolvers().stream().findFirst().get();
        assertTrue(resolver instanceof UrlTemplateResolver);

        UrlTemplateResolver templateResolver = (UrlTemplateResolver) resolver;
        assertAll("templateResolver",
                () -> assertFalse(templateResolver.isCacheable()),
                () -> assertEquals(CACHE_TIME_TO_LIVE, templateResolver.getCacheTTLMs()),
                () -> assertEquals(UTF_8_ENCODING, templateResolver.getCharacterEncoding()),
                () -> assertFalse(templateResolver.getCheckExistence()),
                () -> assertEquals(ORDER, templateResolver.getOrder()),
                () -> assertEquals("", templateResolver.getPrefix()),
                () -> assertEquals(HTML_SUFFIX, templateResolver.getSuffix()),
                () -> assertEquals(TemplateMode.HTML, templateResolver.getTemplateMode()));
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {

            public void configure() {
                from(DIRECT_START)
                        .to("thymeleaf:dontcare?allowTemplateFromHeader=true&allowContextMapAll=true&cacheTimeToLive=500&cacheable=false&encoding=UTF-8&order=1&prefix=&suffix=.html&resolver=URL&templateMode=HTML")
                        .to(MOCK_RESULT);
            }
        };
    }

    protected String fragment() {
        return """
                <span th:fragment="test" th:remove="tag">
                You will be notified when your order ships.
                </span>
                """;
    }

}
