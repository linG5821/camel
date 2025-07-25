/*
 * Camel ApiMethod Enumeration generated by camel-api-component-maven-plugin
 */
package org.apache.camel.component.twilio.internal;

import java.lang.reflect.Method;
import java.util.List;

import com.twilio.rest.api.v2010.account.Key;

import org.apache.camel.support.component.ApiMethod;
import org.apache.camel.support.component.ApiMethodArg;
import org.apache.camel.support.component.ApiMethodImpl;

import static org.apache.camel.support.component.ApiMethodArg.arg;
import static org.apache.camel.support.component.ApiMethodArg.setter;

/**
 * Camel {@link ApiMethod} Enumeration for com.twilio.rest.api.v2010.account.Key
 */
public enum KeyApiMethod implements ApiMethod {

    DELETER(
        com.twilio.rest.api.v2010.account.KeyDeleter.class,
        "deleter",
        arg("pathSid", String.class)),

    DELETER_1(
        com.twilio.rest.api.v2010.account.KeyDeleter.class,
        "deleter",
        arg("pathAccountSid", String.class),
        arg("pathSid", String.class)),

    FETCHER(
        com.twilio.rest.api.v2010.account.KeyFetcher.class,
        "fetcher",
        arg("pathSid", String.class)),

    FETCHER_1(
        com.twilio.rest.api.v2010.account.KeyFetcher.class,
        "fetcher",
        arg("pathAccountSid", String.class),
        arg("pathSid", String.class)),

    READER(
        com.twilio.rest.api.v2010.account.KeyReader.class,
        "reader"),

    READER_1(
        com.twilio.rest.api.v2010.account.KeyReader.class,
        "reader",
        arg("pathAccountSid", String.class)),

    UPDATER(
        com.twilio.rest.api.v2010.account.KeyUpdater.class,
        "updater",
        arg("pathSid", String.class)),

    UPDATER_1(
        com.twilio.rest.api.v2010.account.KeyUpdater.class,
        "updater",
        arg("pathAccountSid", String.class),
        arg("pathSid", String.class));

    private final ApiMethod apiMethod;

    KeyApiMethod(Class<?> resultType, String name, ApiMethodArg... args) {
        this.apiMethod = new ApiMethodImpl(Key.class, resultType, name, args);
    }

    @Override
    public String getName() { return apiMethod.getName(); }

    @Override
    public Class<?> getResultType() { return apiMethod.getResultType(); }

    @Override
    public List<String> getArgNames() { return apiMethod.getArgNames(); }

    @Override
    public List<String> getSetterArgNames() { return apiMethod.getSetterArgNames(); }

    @Override
    public List<Class<?>> getArgTypes() { return apiMethod.getArgTypes(); }

    @Override
    public Method getMethod() { return apiMethod.getMethod(); }
}
