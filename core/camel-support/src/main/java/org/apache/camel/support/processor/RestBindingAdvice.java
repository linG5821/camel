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
package org.apache.camel.support.processor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExchangeException;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.spi.CamelInternalProcessorAdvice;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.spi.DataType;
import org.apache.camel.spi.DataTypeAware;
import org.apache.camel.spi.RestClientRequestValidator;
import org.apache.camel.spi.RestClientResponseValidator;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.support.ExchangeHelper;
import org.apache.camel.support.MessageHelper;
import org.apache.camel.support.service.ServiceHelper;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.util.ObjectHelper;

/**
 * Used for Rest DSL with binding to json/xml for incoming requests and outgoing responses.
 * <p/>
 * The binding uses {@link org.apache.camel.spi.DataFormat} for the actual work to transform from xml/json to Java
 * Objects and reverse again.
 * <p/>
 * The rest producer side is implemented in {@link org.apache.camel.component.rest.RestProducerBindingProcessor}
 *
 * @see RestBindingAdviceFactory
 */
public class RestBindingAdvice extends ServiceSupport implements CamelInternalProcessorAdvice<Map<String, Object>> {

    private static final String STATE_KEY_DO_MARSHAL = "doMarshal";
    private static final String STATE_KEY_ACCEPT = "accept";
    private static final String STATE_JSON = "json";
    private static final String STATE_XML = "xml";

    private final RestClientRequestValidator clientRequestValidator;
    private final RestClientResponseValidator clientResponseValidator;
    private final AsyncProcessor jsonUnmarshal;
    private final AsyncProcessor xmlUnmarshal;
    private final AsyncProcessor jsonMarshal;
    private final AsyncProcessor xmlMarshal;
    private final String consumes;
    private final String produces;
    private final String bindingMode;
    private final boolean skipBindingOnErrorCode;
    private final boolean clientRequestValidation;
    private final boolean clientResponseValidation;
    private final boolean enableCORS;
    private final boolean enableNoContentResponse;
    private final Map<String, String> corsHeaders;
    private final Map<String, String> queryDefaultValues;
    private final Map<String, String> queryAllowedValues;
    private final boolean requiredBody;
    private final Set<String> requiredQueryParameters;
    private final Set<String> requiredHeaders;
    private final Map<String, String> responseCodes;
    private final Set<String> responseHeaders;

    /**
     * Use {@link RestBindingAdviceFactory} to create.
     */
    public RestBindingAdvice(CamelContext camelContext, DataFormat jsonDataFormat, DataFormat xmlDataFormat,
                             DataFormat outJsonDataFormat, DataFormat outXmlDataFormat,
                             String consumes, String produces, String bindingMode,
                             boolean skipBindingOnErrorCode, boolean clientRequestValidation, boolean clientResponseValidation,
                             boolean enableCORS, boolean enableNoContentResponse,
                             Map<String, String> corsHeaders,
                             Map<String, String> queryDefaultValues,
                             Map<String, String> queryAllowedValues,
                             boolean requiredBody, Set<String> requiredQueryParameters,
                             Set<String> requiredHeaders,
                             Map<String, String> responseCodes, Set<String> responseHeaders,
                             RestClientRequestValidator clientRequestValidator,
                             RestClientResponseValidator clientResponseValidator) throws Exception {

        if (jsonDataFormat != null) {
            this.jsonUnmarshal = new UnmarshalProcessor(jsonDataFormat);
        } else {
            this.jsonUnmarshal = null;
        }
        if (outJsonDataFormat != null) {
            this.jsonMarshal = new MarshalProcessor(outJsonDataFormat);
        } else if (jsonDataFormat != null) {
            this.jsonMarshal = new MarshalProcessor(jsonDataFormat);
        } else {
            this.jsonMarshal = null;
        }

        if (xmlDataFormat != null) {
            this.xmlUnmarshal = new UnmarshalProcessor(xmlDataFormat);
        } else {
            this.xmlUnmarshal = null;
        }
        if (outXmlDataFormat != null) {
            this.xmlMarshal = new MarshalProcessor(outXmlDataFormat);
        } else if (xmlDataFormat != null) {
            this.xmlMarshal = new MarshalProcessor(xmlDataFormat);
        } else {
            this.xmlMarshal = null;
        }

        if (jsonMarshal != null) {
            camelContext.addService(jsonMarshal, true);
        }
        if (jsonUnmarshal != null) {
            camelContext.addService(jsonUnmarshal, true);
        }
        if (xmlMarshal != null) {
            camelContext.addService(xmlMarshal, true);
        }
        if (xmlUnmarshal != null) {
            camelContext.addService(xmlUnmarshal, true);
        }

        this.consumes = consumes;
        this.produces = produces;
        this.bindingMode = bindingMode;
        this.skipBindingOnErrorCode = skipBindingOnErrorCode;
        this.clientRequestValidation = clientRequestValidation;
        this.clientResponseValidation = clientResponseValidation;
        this.enableCORS = enableCORS;
        this.corsHeaders = corsHeaders;
        this.queryDefaultValues = queryDefaultValues;
        this.queryAllowedValues = queryAllowedValues;
        this.requiredBody = requiredBody;
        this.requiredQueryParameters = requiredQueryParameters;
        this.requiredHeaders = requiredHeaders;
        this.responseCodes = responseCodes;
        this.responseHeaders = responseHeaders;
        this.enableNoContentResponse = enableNoContentResponse;
        this.clientRequestValidator = clientRequestValidator;
        this.clientResponseValidator = clientResponseValidator;
    }

    @Override
    public Map<String, Object> before(Exchange exchange) throws Exception {
        Map<String, Object> state = new HashMap<>();
        if (isOptionsMethod(exchange, state)) {
            return state;
        }
        unmarshal(exchange, state);
        return state;
    }

    @Override
    public void after(Exchange exchange, Map<String, Object> state) throws Exception {
        if (enableCORS) {
            setCORSHeaders(exchange);
        }
        if (state.get(STATE_KEY_DO_MARSHAL) != null) {
            marshal(exchange, state);
        }
    }

    private boolean isOptionsMethod(Exchange exchange, Map<String, Object> state) {
        String method = exchange.getIn().getHeader(Exchange.HTTP_METHOD, String.class);
        if ("OPTIONS".equalsIgnoreCase(method)) {
            // for OPTIONS methods then we should not route at all as its part of CORS
            exchange.setRouteStop(true);
            return true;
        }
        return false;
    }

    private void unmarshal(Exchange exchange, Map<String, Object> state) {
        boolean isXml = false;
        boolean isJson = false;

        String contentType = ExchangeHelper.getContentType(exchange);
        if (contentType != null) {
            isXml = contentType.toLowerCase(Locale.ENGLISH).contains("xml");
            isJson = contentType.toLowerCase(Locale.ENGLISH).contains("json");
        }
        // if content type could not tell us if it was json or xml, then fallback to if the binding was configured with
        // that information in the consumes
        if (!isXml && !isJson) {
            isXml = consumes != null && consumes.toLowerCase(Locale.ENGLISH).contains("xml");
            isJson = consumes != null && consumes.toLowerCase(Locale.ENGLISH).contains("json");
        }

        // set data type if in use
        if (exchange.getContext().isUseDataType()) {
            if (exchange.getIn() instanceof DataTypeAware && (isJson || isXml)) {
                ((DataTypeAware) exchange.getIn()).setDataType(new DataType(isJson ? "json" : "xml"));
            }
        }

        // only allow xml/json if the binding mode allows that
        isXml &= bindingMode.equals("auto") || bindingMode.contains("xml");
        isJson &= bindingMode.equals("auto") || bindingMode.contains("json");

        // if we do not yet know if its xml or json, then use the binding mode to know the mode
        if (!isJson && !isXml) {
            isXml = bindingMode.equals("auto") || bindingMode.contains("xml");
            isJson = bindingMode.equals("auto") || bindingMode.contains("json");
        }

        String accept = exchange.getMessage().getHeader("Accept", String.class);
        state.put(STATE_KEY_ACCEPT, accept);

        // add missing default values which are mapped as headers
        if (queryDefaultValues != null) {
            for (Map.Entry<String, String> entry : queryDefaultValues.entrySet()) {
                if (exchange.getIn().getHeader(entry.getKey()) == null) {
                    exchange.getIn().setHeader(entry.getKey(), entry.getValue());
                }
            }
        }

        // perform client request validation
        RestClientRequestValidator.ValidationError error = doClientRequestValidation(exchange);
        if (error != null) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, error.statusCode());
            exchange.getMessage().setBody(error.body());
            exchange.setRouteStop(true);
            return;
        }

        String body = null;
        if (ObjectHelper.isNotEmpty(exchange.getIn().getBody())) {
            // okay we have a binding mode, so need to check for empty body as that can cause the marshaller to fail
            // as they assume a non-empty body
            if (isXml || isJson) {
                // we have binding enabled, so we need to know if there body is empty or not
                // so force reading the body as a String which we can work with
                body = MessageHelper.extractBodyAsString(exchange.getIn());
                if (ObjectHelper.isNotEmpty(body)) {
                    if (exchange.getIn() instanceof DataTypeAware dataTypeAware) {
                        dataTypeAware.setBody(body, new DataType(isJson ? "json" : "xml"));
                    } else {
                        exchange.getIn().setBody(body);
                    }
                    if (isXml && isJson) {
                        // we have still not determined between xml or json, so check the body if its xml based or not
                        isXml = body.startsWith("<");
                        isJson = !isXml;
                    }
                }
            }
        }

        // favor json over xml
        if (isJson && jsonUnmarshal != null) {
            // add reverse operation
            state.put(STATE_KEY_DO_MARSHAL, STATE_JSON);
            if (ObjectHelper.isNotEmpty(body)) {
                try {
                    jsonUnmarshal.process(exchange);
                    ExchangeHelper.prepareOutToIn(exchange);
                } catch (Exception e) {
                    exchange.setException(e);
                }
                if (exchange.isFailed()) {
                    // we want to indicate that this is a bad request instead of 500 due to parsing error
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                }
            }
            if (clientRequestValidation && exchange.isFailed()) {
                // this is a bad request, the client included message body that cannot be parsed to json
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                exchange.getMessage().setBody("Invalid JSon payload.");
                // clear exception
                exchange.setException(null);
                // stop routing and return
                exchange.setRouteStop(true);
                return;
            }
            return;
        } else if (isXml && xmlUnmarshal != null) {
            // add reverse operation
            state.put(STATE_KEY_DO_MARSHAL, STATE_XML);
            if (ObjectHelper.isNotEmpty(body)) {
                try {
                    xmlUnmarshal.process(exchange);
                    ExchangeHelper.prepareOutToIn(exchange);
                } catch (Exception e) {
                    exchange.setException(e);
                }
                if (exchange.isFailed()) {
                    // we want to indicate that this is a bad request instead of 500 due to parsing error
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                }
            }
            if (clientRequestValidation && exchange.isFailed()) {
                // this is a bad request, the client included message body that cannot be parsed to XML
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                exchange.getMessage().setBody("Invalid XML payload.");
                // clear exception
                exchange.setException(null);
                // stop routing and return
                exchange.setRouteStop(true);
                return;
            }
            return;
        }

        // we could not bind
        if ("off".equals(bindingMode) || bindingMode.equals("auto")) {
            // okay for auto we do not mind if we could not bind
            state.put(STATE_KEY_DO_MARSHAL, STATE_JSON);
        } else {
            if (bindingMode.contains("xml")) {
                exchange.setException(
                        new CamelExchangeException("Cannot bind to xml as message body is not xml compatible", exchange));
            } else {
                exchange.setException(
                        new CamelExchangeException("Cannot bind to json as message body is not json compatible", exchange));
            }
        }

    }

    private void marshal(Exchange exchange, Map<String, Object> state) {
        // only marshal if there was no exception
        if (exchange.getException() != null) {
            return;
        }

        if (skipBindingOnErrorCode) {
            Integer code = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
            // if there is a custom http error code then skip binding
            if (code != null && code >= 300) {
                return;
            }
        }

        boolean isXml = false;
        boolean isJson = false;

        // accept takes precedence
        String accept = (String) state.get(STATE_KEY_ACCEPT);
        if (accept != null) {
            isXml = accept.toLowerCase(Locale.ENGLISH).contains("xml");
            isJson = accept.toLowerCase(Locale.ENGLISH).contains("json");
        }
        // fallback to content type if still undecided
        if (!isXml && !isJson) {
            String contentType = ExchangeHelper.getContentType(exchange);
            if (contentType != null) {
                isXml = contentType.toLowerCase(Locale.ENGLISH).contains("xml");
                isJson = contentType.toLowerCase(Locale.ENGLISH).contains("json");
            }
        }
        // if content type could not tell us if it was json or xml, then fallback to if the binding was configured with
        // that information in the consumes
        if (!isXml && !isJson) {
            isXml = produces != null && produces.toLowerCase(Locale.ENGLISH).contains("xml");
            isJson = produces != null && produces.toLowerCase(Locale.ENGLISH).contains("json");
        }

        // only allow xml/json if the binding mode allows that (when off we still want to know if its xml or json)
        if (bindingMode != null) {
            isXml &= bindingMode.equals("off") || bindingMode.equals("auto") || bindingMode.contains("xml");
            isJson &= bindingMode.equals("off") || bindingMode.equals("auto") || bindingMode.contains("json");

            // if we do not yet know if its xml or json, then use the binding mode to know the mode
            if (!isJson && !isXml) {
                isXml = bindingMode.equals("auto") || bindingMode.contains("xml");
                isJson = bindingMode.equals("auto") || bindingMode.contains("json");
            }
        }

        // in case we have not yet been able to determine if xml or json, then use the same as in the unmarshaller
        if (isXml && isJson) {
            isXml = state.get(STATE_KEY_DO_MARSHAL).equals(STATE_XML);
            isJson = !isXml;
        }

        // need to prepare exchange first
        ExchangeHelper.prepareOutToIn(exchange);

        // ensure there is a content type header (even if binding is off)
        ensureHeaderContentType(produces, isXml, isJson, exchange);

        if (bindingMode == null || "off".equals(bindingMode)) {
            // binding is off, so no message body binding
            return;
        }

        // is there any marshaller at all
        if (jsonMarshal == null && xmlMarshal == null) {
            return;
        }

        // is the body empty
        if (exchange.getMessage().getBody() == null) {
            return;
        }

        String contentType = exchange.getIn().getHeader(Exchange.CONTENT_TYPE, String.class);
        // need to lower-case so the contains check below can match if using upper case
        contentType = contentType.toLowerCase(Locale.US);
        try {
            // favor json over xml
            if (isJson && jsonMarshal != null) {
                // only marshal if its json content type
                if (contentType.contains("json")) {
                    jsonMarshal.process(exchange);
                    setOutputDataType(exchange, new DataType("json"));

                    if (enableNoContentResponse) {
                        String body = MessageHelper.extractBodyAsString(exchange.getMessage());
                        if (ObjectHelper.isNotEmpty(body) && (body.equals("[]") || body.equals("{}"))) {
                            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 204);
                            exchange.getMessage().setBody("");
                        }
                    }
                }
            } else if (isXml && xmlMarshal != null) {
                // only marshal if its xml content type
                if (contentType.contains("xml")) {
                    xmlMarshal.process(exchange);
                    setOutputDataType(exchange, new DataType("xml"));

                    if (enableNoContentResponse) {
                        String body = MessageHelper.extractBodyAsString(exchange.getMessage()).replace("\n", "");
                        if (ObjectHelper.isNotEmpty(body)) {
                            int open = 0;
                            int close = body.indexOf('>');
                            // xml declaration
                            if (body.startsWith("<?xml")) {
                                open = close;
                                close = body.indexOf('>', close + 1);
                            }
                            // empty root element <el/> or <el></el>
                            if (body.length() == close + 1 || body.length() == (open + 1 + 2 * (close - open) + 1)) {
                                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 204);
                                exchange.getMessage().setBody("");
                            }
                        }
                    }
                }
            } else {
                // we could not bind
                if (bindingMode.contains("xml")) {
                    exchange.setException(new CamelExchangeException(
                            "Cannot bind to xml as message body is not xml compatible", exchange));
                } else if (!bindingMode.equals("auto")) {
                    // okay for auto we do not mind if we could not bind
                    exchange.setException(new CamelExchangeException(
                            "Cannot bind to json as message body is not json compatible", exchange));
                }
            }
        } catch (Exception e) {
            exchange.setException(e);
        }

        // perform client response validation
        RestClientResponseValidator.ValidationError error = doClientResponseValidation(exchange);
        if (error != null) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, error.statusCode());
            exchange.getMessage().setBody(error.body());
        }
    }

    private void setOutputDataType(Exchange exchange, DataType type) {
        Message target = exchange.getMessage();
        if (target instanceof DataTypeAware dataTypeAware) {
            dataTypeAware.setDataType(type);
        }
    }

    private void ensureHeaderContentType(String contentType, boolean isXml, boolean isJson, Exchange exchange) {
        // favor given content type
        if (contentType != null) {
            String type = ExchangeHelper.getContentType(exchange);
            if (type == null) {
                exchange.getIn().setHeader(Exchange.CONTENT_TYPE, contentType);
            }
        }

        // favor json over xml
        if (isJson) {
            // make sure there is a content-type with json
            String type = ExchangeHelper.getContentType(exchange);
            if (type == null) {
                exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
            }
        } else if (isXml) {
            // make sure there is a content-type with xml
            String type = ExchangeHelper.getContentType(exchange);
            if (type == null) {
                exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/xml");
            }
        }
    }

    private void setCORSHeaders(Exchange exchange) {
        // add the CORS headers after routing, but before the consumer writes the response
        Message msg = exchange.getMessage();

        // use default value if none has been configured
        String allowOrigin = corsHeaders != null ? corsHeaders.get("Access-Control-Allow-Origin") : null;
        if (allowOrigin == null) {
            allowOrigin = RestConfiguration.CORS_ACCESS_CONTROL_ALLOW_ORIGIN;
        }
        String allowMethods = corsHeaders != null ? corsHeaders.get("Access-Control-Allow-Methods") : null;
        if (allowMethods == null) {
            allowMethods = RestConfiguration.CORS_ACCESS_CONTROL_ALLOW_METHODS;
        }
        String allowHeaders = corsHeaders != null ? corsHeaders.get("Access-Control-Allow-Headers") : null;
        if (allowHeaders == null) {
            allowHeaders = RestConfiguration.CORS_ACCESS_CONTROL_ALLOW_HEADERS;
        }
        String maxAge = corsHeaders != null ? corsHeaders.get("Access-Control-Max-Age") : null;
        if (maxAge == null) {
            maxAge = RestConfiguration.CORS_ACCESS_CONTROL_MAX_AGE;
        }
        String allowCredentials = corsHeaders != null ? corsHeaders.get("Access-Control-Allow-Credentials") : null;

        // Restrict the origin if credentials are allowed.
        // https://www.w3.org/TR/cors/ - section 6.1, point 3
        String origin = exchange.getIn().getHeader("Origin", String.class);
        if ("true".equalsIgnoreCase(allowCredentials) && "*".equals(allowOrigin) && origin != null) {
            allowOrigin = origin;
        }

        msg.setHeader("Access-Control-Allow-Origin", allowOrigin);
        msg.setHeader("Access-Control-Allow-Methods", allowMethods);
        msg.setHeader("Access-Control-Allow-Headers", allowHeaders);
        msg.setHeader("Access-Control-Max-Age", maxAge);
        if (allowCredentials != null) {
            msg.setHeader("Access-Control-Allow-Credentials", allowCredentials);
        }
    }

    /**
     * Performs the client request validation (if enabled)
     *
     * @param  exchange the exchange
     * @return          null if success otherwise an error is returned
     */
    public RestClientRequestValidator.ValidationError doClientRequestValidation(Exchange exchange) {
        if (clientRequestValidation && clientRequestValidator != null) {
            RestClientRequestValidator.ValidationContext vc = new RestClientRequestValidator.ValidationContext(
                    consumes, produces, requiredBody, queryDefaultValues, queryAllowedValues, requiredQueryParameters,
                    requiredHeaders);
            return clientRequestValidator.validate(exchange, vc);
        }
        return null;
    }

    /**
     * Performs the client response validation (if enabled)
     *
     * @param  exchange the exchange
     * @return          null if success otherwise an error is returned
     */
    public RestClientResponseValidator.ValidationError doClientResponseValidation(Exchange exchange) {
        if (clientResponseValidation && clientResponseValidator != null && !exchange.isFailed()) {
            RestClientResponseValidator.ValidationContext vc = new RestClientResponseValidator.ValidationContext(
                    consumes, produces, responseCodes, responseHeaders);
            return clientResponseValidator.validate(exchange, vc);
        }
        return null;
    }

    @Override
    protected void doStart() throws Exception {
        ServiceHelper.startService(jsonUnmarshal, xmlUnmarshal, jsonMarshal, xmlMarshal);
    }

    @Override
    protected void doStop() throws Exception {
        ServiceHelper.stopService(jsonUnmarshal, xmlUnmarshal, jsonMarshal, xmlMarshal);
    }
}
