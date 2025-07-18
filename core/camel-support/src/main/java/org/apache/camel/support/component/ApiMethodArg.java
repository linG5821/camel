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
package org.apache.camel.support.component;

public class ApiMethodArg {
    private final String name;
    private final Class<?> type;
    private final String typeArgs;
    private final String rawTypeArgs;
    private final String description;
    private final boolean setter;

    public ApiMethodArg(String name, Class<?> type, String typeArgs, String rawTypeArgs, String description) {
        this.name = name;
        this.type = type;
        this.typeArgs = typeArgs;
        this.rawTypeArgs = rawTypeArgs;
        this.description = description;
        this.setter = false;
    }

    public ApiMethodArg(String name, Class<?> type, String typeArgs, String rawTypeArgs, String description, boolean setter) {
        this.name = name;
        this.type = type;
        this.typeArgs = typeArgs;
        this.rawTypeArgs = rawTypeArgs;
        this.description = description;
        this.setter = setter;
    }

    public String getName() {
        return this.name;
    }

    public Class<?> getType() {
        return this.type;
    }

    public String getTypeArgs() {
        return this.typeArgs;
    }

    public String getRawTypeArgs() {
        return rawTypeArgs;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSetter() {
        return setter;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(128);
        builder.append(type.getCanonicalName());
        if (typeArgs != null) {
            builder.append("<").append(typeArgs).append(">");
        }
        builder.append(" ").append(name);
        return builder.toString();
    }

    public static ApiMethodArg arg(String name, Class<?> type) {
        return new ApiMethodArg(name, type, null, null, null);
    }

    public static ApiMethodArg arg(String name, Class<?> type, String typeArgs) {
        return new ApiMethodArg(name, type, typeArgs, null, null);
    }

    public static ApiMethodArg arg(String name, Class<?> type, String typeArgs, String description) {
        return new ApiMethodArg(name, type, typeArgs, null, description);
    }

    public static ApiMethodArg setter(String name, Class<?> type) {
        return new ApiMethodArg(name, type, null, null, null, true);
    }
}
