//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.utils.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.apache.log4j.Logger;

import org.apache.cloudstack.utils.security.SSLUtils;

public class NioClient extends NioConnection {
    private static final Logger s_logger = Logger.getLogger(NioClient.class);

    protected String _host;
    protected String _bindAddress;

    public NioClient(String name, String host, int port, int workers, HandlerFactory factory) {
        super(name, port, workers, factory);
        _host = host;
    }

    public void setBindAddress(String ipAddress) {
        _bindAddress = ipAddress;
    }

    @Override
    protected void init() throws IOException {
        _selector = Selector.open();
        SocketChannel sch = null;
        InetSocketAddress addr = null;

        try {
            sch = SocketChannel.open();
            sch.configureBlocking(true);
            s_logger.info("Connecting to " + _host + ":" + _port);

            if (_bindAddress != null) {
                s_logger.info("Binding outbound interface at " + _bindAddress);

                addr = new InetSocketAddress(_bindAddress, 0);
                sch.socket().bind(addr);
            }

            addr = new InetSocketAddress(_host, _port);
            sch.connect(addr);
        } catch (IOException e) {
            _selector.close();
            throw e;
        }

        SSLEngine sslEngine = null;
        try {
            // Begin SSL handshake in BLOCKING mode
            sch.configureBlocking(true);

            SSLContext sslContext = Link.initSSLContext(true);
            sslEngine = sslContext.createSSLEngine(_host, _port);
            sslEngine.setUseClientMode(true);
            sslEngine.setEnabledProtocols(SSLUtils.getSupportedProtocols(sslEngine.getEnabledProtocols()));

            Link.doHandshake(sch, sslEngine, true);
            s_logger.info("SSL: Handshake done");
            s_logger.info("Connected to " + _host + ":" + _port);
        } catch (Exception e) {
            _selector.close();
            throw new IOException("SSL: Fail to init SSL! " + e);
        }

        Task task = null;
        try {
            sch.configureBlocking(false);
            Link link = new Link(addr, this);
            link.setSSLEngine(sslEngine);
            SelectionKey key = sch.register(_selector, SelectionKey.OP_READ);
            link.setKey(key);
            key.attach(link);
            // Notice we've already connected due to the handshake, so let's get the
            // remaining task done
            task = _factory.create(Task.Type.CONNECT, link, null);
        } catch (Exception e) {
            _selector.close();
            throw new IOException("Fail to init NioClient! " + e);
        }
        _executor.execute(task);
    }

    @Override
    protected void registerLink(InetSocketAddress saddr, Link link) {
        // don't do anything.
    }

    @Override
    protected void unregisterLink(InetSocketAddress saddr) {
        // don't do anything.
    }
}
