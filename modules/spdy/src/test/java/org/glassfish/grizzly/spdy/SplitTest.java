/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.spdy;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.URLDecoder;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.*;

/**
 * Test derived from http-server's "potential split vulnerability".
 * Looks like SPDY shouldn't worry about split vulnerability, cause headers are sent
 * with length prefix, so this test is just making sure headers are passed properly
 * without changes.
 * 
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
@RunWith(Parameterized.class)
public class SplitTest extends AbstractSpdyTest {
    private static final int PORT = 18302;
    
    private final SpdyMode spdyMode;
    private final boolean isSecure;
    
    public SplitTest(final SpdyMode spdyMode,
            final boolean isSecure) {
        this.spdyMode = spdyMode;
        this.isSecure = isSecure;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getSpdyModes() {
        return AbstractSpdyTest.getSpdyModes();
    }
    
    @Test
    public void testSplitReasonPhrase() throws Exception {
        final String reasonPhrase = "OK\r\nContent-Length: 14\r\n\r\nBroken content";
        final HttpPacket request = createRequest("/index.html", null);
        final HttpContent response = doTest(request, 10, new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                response.setStatus(200, reasonPhrase);
                response.getWriter().write("Expected content");
            }
        });

        final String responseContent = response.getContent().toStringContent();

        assertEquals(reasonPhrase,
                ((HttpResponsePacket) response.getHttpHeader()).getReasonPhrase());
        assertEquals("Expected content", responseContent);
    }
    
    @Test
    public void testSplitHeaders() throws Exception {
        final String headerName = "foo";
        final String headerValue = "bar%0D%0AContent-Length:%2014%0D%0A%0D%0ABroken-content";
        final String headerValueDecoded = URLDecoder.decode(headerValue);
        
        final HttpPacket request = createRequest("/index.html?" + headerName + "=" + headerValue, null);
        final HttpContent response = doTest(request, 10, new HttpHandler() {

            @Override
            public void service(Request request, Response response) throws Exception {
                final String fooValue = request.getParameter(headerName);
                if (fooValue == null) {
                    response.getWriter().write("param not found");
                    return;
                }
                
                response.addHeader(headerName, fooValue);
                response.getWriter().write("Expected content");
            }
        });

        final String responseContent = response.getContent().toStringContent();
        
        assertEquals(headerValueDecoded,
                response.getHttpHeader().getHeader(headerName));
        assertEquals("Expected content", responseContent);        
    }
    
    private HttpPacket createRequest(String uri, Map<String, String> headers) {

        HttpRequestPacket.Builder b = HttpRequestPacket.builder();
        b.method(Method.GET).protocol(Protocol.HTTP_1_1).uri(uri).header("Host", "localhost:" + PORT);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                b.header(entry.getKey(), entry.getValue());
            }
        }

        return b.build();
    }

    private HttpContent doTest(
            final HttpPacket request,
            final int timeout,
            final HttpHandler... httpHandlers)
            throws Exception {

        final TCPNIOTransport clientTransport =
                TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createWebServer(httpHandlers);

        try {
            final FutureImpl<HttpContent> testResultFuture = SafeFutureImpl.create();

            server.start();
            clientTransport.setFilterChain(createClientFilterChain(spdyMode, isSecure,
                    new ClientFilter(testResultFuture)));

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(timeout, TimeUnit.SECONDS);
                connection.write(request);
                return testResultFuture.get(timeout, TimeUnit.SECONDS);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.stop();
            server.shutdownNow();
        }
    }

    private HttpServer createWebServer(final HttpHandler... httpHandlers) {

        final HttpServer server = createServer(null, PORT, spdyMode, isSecure);
        final NetworkListener listener = server.getListener("grizzly");
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);

        server.getServerConfiguration().addHttpHandler(httpHandlers[0], "/");

        for (int i = 1; i < httpHandlers.length; i++) {
            // associate handlers with random context-roots
            server.getServerConfiguration().addHttpHandler(httpHandlers[i], "/" + i + "/*");
        }
        return server;

    }


    private static class ClientFilter extends BaseFilter {
        private final static Logger logger = Grizzly.logger(ClientFilter.class);

        private FutureImpl<HttpContent> testFuture;

        // -------------------------------------------------------- Constructors


        public ClientFilter(FutureImpl<HttpContent> testFuture) {

            this.testFuture = testFuture;

        }


        // ------------------------------------------------- Methods from Filter

        @Override
        public NextAction handleRead(FilterChainContext ctx)
                throws IOException {

            // Cast message to a HttpContent
            final HttpContent httpContent = (HttpContent) ctx.getMessage();

            logger.log(Level.FINE, "Got HTTP response chunk");

            // Get HttpContent's Buffer
            final Buffer buffer = httpContent.getContent();

            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "HTTP content size: {0}", buffer.remaining());
            }

            if (!httpContent.isLast()) {
                return ctx.getStopAction(httpContent);
            }

            testFuture.result(httpContent);

            return ctx.getStopAction();
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx)
                throws IOException {
            close();
            return ctx.getStopAction();
        }

        private void close() throws IOException {
            //noinspection ThrowableInstanceNeverThrown
            testFuture.failure(new IOException("Connection was closed"));
        }

    } // END ClientFilter    
}
