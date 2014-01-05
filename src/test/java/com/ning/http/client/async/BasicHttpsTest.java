/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.async;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig.Builder;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.mortbay.jetty.security.SslSocketConnector;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static com.ning.http.client.async.AbstractBasicTest.TIMEOUT;

public class BasicHttpsTest {

    protected final static int PORT = 19999;
    protected Server server;
    protected final static Logger log = Logger.getLogger(BasicHttpsTest.class);

    protected final static String TARGET_URL = "https://127.0.0.1:18181/foo/test";

    public static class EchoHandler extends AbstractHandler {

        /* @Override */
        public void handle(String pathInContext,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse,
                           int dispatch) throws ServletException, IOException {

            httpResponse.setContentType("text/html; charset=utf-8");
            Enumeration<?> e = httpRequest.getHeaderNames();
            String param;
            while (e.hasMoreElements()) {
                param = e.nextElement().toString();

                if (param.startsWith("LockThread")) {
                    try {
                        Thread.sleep(40 * 1000);
                    } catch (InterruptedException ex) {
                    }
                }

                httpResponse.addHeader("X-" + param, httpRequest.getHeader(param));
            }

            Enumeration<?> i = httpRequest.getParameterNames();

            StringBuilder requestBody = new StringBuilder();
            while (i.hasMoreElements()) {
                param = i.nextElement().toString();
                httpResponse.addHeader("X-" + param, httpRequest.getParameter(param));
                requestBody.append(param);
                requestBody.append("_");
            }

            String pathInfo = httpRequest.getPathInfo();
            if (pathInfo != null)
                httpResponse.addHeader("X-pathInfo", pathInfo);

            String queryString = httpRequest.getQueryString();
            if (queryString != null)
                httpResponse.addHeader("X-queryString", queryString);

            httpResponse.addHeader("X-KEEP-ALIVE", httpRequest.getRemoteAddr() + ":" + httpRequest.getRemotePort());

            javax.servlet.http.Cookie[] cs = httpRequest.getCookies();
            if (cs != null) {
                for (javax.servlet.http.Cookie c : cs) {
                    httpResponse.addCookie(c);
                }
            }

            if (requestBody.length() > 0) {
                httpResponse.getOutputStream().write(requestBody.toString().getBytes());
            }

            int size = 10 * 1024;
            if (httpRequest.getInputStream().available() > 0) {
                size = httpRequest.getInputStream().available();
            }
            byte[] bytes = new byte[size];
            if (bytes.length > 0) {
                httpRequest.getInputStream().read(bytes);
                httpResponse.getOutputStream().write(bytes);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();

        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws InterruptedException, Exception {
        BasicConfigurator.resetConfiguration();        
        server.stop();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDownProps() throws InterruptedException, Exception {
        System.clearProperty("javax.net.ssl.keyStore");
        System.clearProperty("javax.net.ssl.trustStore");
    }


    public AbstractHandler configureHandler() throws Exception {
        return new EchoHandler();
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        server = new Server();
        BasicConfigurator.configure();

        SslSocketConnector connector = new SslSocketConnector();
        connector.setHost("127.0.0.1");
        connector.setPort(18181);

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        String trustStoreFile = new File(cacertsUrl.toURI()).getAbsolutePath();
        if (cacertsUrl != null) {
            connector.setTruststore(trustStoreFile);
            connector.setTrustPassword("changeit");
            connector.setTruststoreType("JKS");
        }

        log.info("SSL certs path: " + trustStoreFile);

        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        String keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
        if (keystoreUrl != null) {
            connector.setKeystore(keyStoreFile);
            connector.setKeyPassword("changeit");
            connector.setKeystoreType("JKS");
        }

        log.info("SSL keystore path: " + keyStoreFile);

        server.addConnector(connector);

        server.setHandler(configureHandler());
        server.start();
        log.info("Local HTTP server started successfully");
    }

    public static class AsyncCompletionHandlerAdapter extends AsyncCompletionHandler<Response> {

        @Override
        public Response onCompleted(Response response) throws Exception {
            return response;
        }

        /* @Override */
        public void onThrowable(Throwable t) {
            t.printStackTrace();
            Assert.fail("Unexpected exception", t);
        }

    }

    @Test
    public void multipleJavaDotDeadSSLTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();

        String body = "hello there";

        // once
        Response response = c.preparePost("https://atmosphere.dev.java.net:443/")
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertNotNull(response);
        assertEquals(response.getStatusCode(), 200);

        // twice
        response = c.preparePost("https://grizzly.dev.java.net:443/")
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getStatusCode(), 200);

    }

        @Test
    public void multipleJavaDotDeadWrongKeystoreTest() throws Throwable {
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        System.setProperty("javax.net.ssl.keyStore",keystoreUrl.toString());

        AsyncHttpClient c = new AsyncHttpClient();

        String body = "hello there";

        // once
        Response response = c.preparePost("https://atmosphere.dev.java.net:443/")
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertNull(response);
    }

    @Test
    public void multipleJavaDotDeadKeystoreTest() throws Throwable {

        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        System.setProperty("javax.net.ssl.keyStore",keystoreUrl.toString().substring("file:".length()));
        keystoreUrl = cl.getResource("ssltest-cacerts.jks");
        System.setProperty("javax.net.ssl.trustStore",keystoreUrl.toString().substring("file:".length()));

        AsyncHttpClient c = new AsyncHttpClient();

        String body = "hello there";

        // once
        Response response = c.preparePost("https://atmosphere.dev.java.net:443/")
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getStatusCode(), 200);

        // twice
        response = c.preparePost("https://grizzly.dev.java.net:443/")
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getStatusCode(), 200);
    }

    @Test
    public void multipleRequestsTest() throws Throwable {
        AsyncHttpClient c = new AsyncHttpClient();

        String body = "hello there";

        // once
        Response response = c.preparePost(TARGET_URL)
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);

        // twice
        response = c.preparePost(TARGET_URL)
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);

    }

    @Test
    public void multipleSSLRequestsTest() throws Throwable {

        SSLContext sslContext = null;
        try {
            InputStream keyStoreStream = BasicHttpsTest.class.getResourceAsStream("ssltest-cacerts.jks");
            char[] keyStorePassword = "changeit".toCharArray();
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(keyStoreStream, keyStorePassword);

            // Set up key manager factory to use our key store
            char[] certificatePassword = "changeit".toCharArray();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, certificatePassword);

            // Initialize the SSLContext to work with our key managers.
            KeyManager[] keyManagers = kmf.getKeyManagers();
            TrustManager[] trustManagers = new TrustManager[]{DUMMY_TRUST_MANAGER};
            SecureRandom secureRandom = new SecureRandom();
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, secureRandom);
        } catch (Exception e) {
            throw new Error("Failed to initialize the server-side SSLContext", e);
        }

        SSLEngine sslEngine = sslContext.createSSLEngine();
        sslEngine.setUseClientMode(true);
        AsyncHttpClient c = new AsyncHttpClient(new Builder().setSSLEngine(sslEngine).build());

        String body = "hello there";

        // once
        Response response = c.preparePost(TARGET_URL)
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);

        // twice
        response = c.preparePost(TARGET_URL)
                .setBody(body)
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);
    }

    private static final TrustManager DUMMY_TRUST_MANAGER = new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(
                X509Certificate[] chain, String authType) throws CertificateException {
        }

        public void checkServerTrusted(
                X509Certificate[] chain, String authType) throws CertificateException {
        }
    };


}
