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
package org.apache.camel.dataformat.deflater;

import java.io.InputStream;
import java.io.OutputStream;

import org.apache.camel.Exchange;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.spi.DataFormatName;
import org.apache.camel.spi.annotations.Dataformat;
import org.apache.camel.support.builder.OutputStreamBuilder;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.util.IOHelper;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

/**
 * GZip {@link org.apache.camel.spi.DataFormat} for reading/writing data using gzip.
 */
@Dataformat("gzipDeflater")
public class GzipDeflaterDataFormat extends ServiceSupport implements DataFormat, DataFormatName {

    @Override
    public String getDataFormatName() {
        return "gzipDeflater";
    }

    @Override
    public void marshal(final Exchange exchange, final Object graph, final OutputStream stream) throws Exception {
        InputStream is = exchange.getContext().getTypeConverter().mandatoryConvertTo(InputStream.class, exchange, graph);

        GzipCompressorOutputStream zipOutput = new GzipCompressorOutputStream(stream);
        try {
            IOHelper.copy(is, zipOutput);
        } finally {
            // must close all input streams
            IOHelper.close(is, zipOutput);
        }
    }

    @Override
    public Object unmarshal(final Exchange exchange, final InputStream inputStream) throws Exception {
        GzipCompressorInputStream unzipInput = null;

        OutputStreamBuilder osb = OutputStreamBuilder.withExchange(exchange);
        try {
            unzipInput = new GzipCompressorInputStream(inputStream, true);
            IOHelper.copy(unzipInput, osb);
            return osb.build();
        } finally {
            // must close all input streams
            IOHelper.close(osb, unzipInput, inputStream);
        }
    }

    @Override
    protected void doStart() throws Exception {
        // noop
    }

    @Override
    protected void doStop() throws Exception {
        // noop
    }
}
