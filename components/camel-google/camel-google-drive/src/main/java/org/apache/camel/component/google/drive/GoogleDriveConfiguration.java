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
package org.apache.camel.component.google.drive;

import java.util.Collection;
import java.util.List;

import org.apache.camel.component.google.drive.internal.GoogleDriveApiName;
import org.apache.camel.spi.Configurer;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriParams;
import org.apache.camel.spi.UriPath;

/**
 * Component configuration for GoogleDrive component.
 */
@UriParams
@Configurer(extended = true)
public class GoogleDriveConfiguration {
    @UriPath
    @Metadata(required = true)
    private GoogleDriveApiName apiName;
    @UriPath(enums = "copy,delete,get,getIdForEmail,insert,list,patch,stop,touch,trash,untrash,update,watch")
    @Metadata(required = true)
    private String methodName;
    @UriParam
    private String scopes;
    @UriParam
    private String clientId;
    @UriParam(label = "security", secret = true)
    private String clientSecret;
    @UriParam(label = "security", secret = true)
    private String accessToken;
    @UriParam(label = "security", secret = true)
    private String refreshToken;
    @UriParam
    private String applicationName;
    /* Service account */
    @UriParam(label = "security")
    private String serviceAccountKey;
    @UriParam
    private String delegate;

    public GoogleDriveApiName getApiName() {
        return apiName;
    }

    /**
     * What kind of operation to perform
     */
    public void setApiName(GoogleDriveApiName apiName) {
        this.apiName = apiName;
    }

    public String getMethodName() {
        return methodName;
    }

    /**
     * What sub operation to use for the selected operation
     */
    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * Client ID of the drive application
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * Client secret of the drive application
     */
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getAccessToken() {
        return accessToken;
    }

    /**
     * OAuth 2 access token. This typically expires after an hour so refreshToken is recommended for long term usage.
     */
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    /**
     * OAuth 2 refresh token. Using this, the Google Drive component can obtain a new accessToken whenever the current
     * one expires - a necessity if the application is long-lived.
     */
    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getApplicationName() {
        return applicationName;
    }

    /**
     * Google drive application name. Example would be "camel-google-drive/1.0"
     */
    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getScopes() {
        return scopes;
    }

    public Collection<String> getScopesAsList() {
        if (scopes != null) {
            return List.of(scopes.split(","));
        } else {
            return null;
        }
    }

    /**
     * Specifies the level of permissions you want a calendar application to have to a user account. See
     * https://developers.google.com/identity/protocols/googlescopes for more info. Multiple scopes can be separated by
     * comma.
     *
     * @see com.google.api.services.drive.DriveScopes
     */
    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getServiceAccountKey() {
        return serviceAccountKey;
    }

    /**
     * Service account key in json format to authenticate an application as a service account. Accept base64 adding the
     * prefix "base64:"
     *
     * @param serviceAccountKey String file, classpath, base64, or http url
     */
    public void setServiceAccountKey(String serviceAccountKey) {
        this.serviceAccountKey = serviceAccountKey;
    }

    public String getDelegate() {
        return delegate;
    }

    /**
     * Delegate for wide-domain service account
     */
    public void setDelegate(String delegate) {
        this.delegate = delegate;
    }

}
