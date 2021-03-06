/**
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
package org.apache.camel.component.mail.security;

import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.JndiRegistry;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.apache.camel.util.jsse.KeyManagersParameters;
import org.apache.camel.util.jsse.KeyStoreParameters;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.apache.camel.util.jsse.TrustManagersParameters;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test of integration between the mail component and JSSE Configuration Utility.
 * This test does not easily automate.  This test is therefore ignored and the
 * source is maintained here for easier development in the future.
 */
@Ignore
public class SslContextParametersMailRouteTest extends CamelTestSupport {
    
    protected static final String KEY_STORE_PASSWORD = "changeit";
    
    private String email = "USERNAME@gmail.com";
    private String username = "USERNAME@gmail.com";
    private String imapHost = "imap.gmail.com";
    private String smtpHost = "smtp.gmail.com";
    private String password = "PASSWORD";
    
    @Test
    public void testSendAndReceiveMails() throws Exception {
        
        context.addRoutes(new RouteBuilder() {
            public void configure() {
                
                from("imaps://" + imapHost + "?username=" + username + "&password=" + password
                        + "&delete=false&unseen=true&fetchSize=1&consumer.useFixedDelay=true&consumer.delay=1000")
                     .to("mock:in");
                
                from("direct:in")
                    .to("smtps://" + smtpHost + "?username=" + username + "&password=" + password);
            }
        });
        
        context.start();

        MockEndpoint resultEndpoint = getMockEndpoint("mock:in");
        resultEndpoint.expectedBodiesReceived("Test Email Body\r\n");

        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("To", email);
        headers.put("From", email);
        headers.put("Reply-to", email);
        headers.put("Subject", "SSL/TLS Test");
        
        template.sendBodyAndHeaders("direct:in", "Test Email Body", headers);

        resultEndpoint.assertIsSatisfied();
    }
    
    @Test
    public void testSendAndReceiveMailsWithCustomTrustStore() throws Exception {
        
        context.addRoutes(new RouteBuilder() {
            public void configure() {
                
                from("direct:in")
                    .to("smtps://" + smtpHost + "?username=" + username + "&password=" + password
                        + "&sslContextParameters=#sslContextParameters");
            }
        });
        
        context.start();

        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("To", email);
        headers.put("From", email);
        headers.put("Reply-to", email);
        headers.put("Subject", "SSL/TLS Test");
        
        try {
            template.sendBodyAndHeaders("direct:in", "Test Email Body", headers);
        } catch (CamelExecutionException e) {
            assertTrue(e.getCause().getCause() instanceof SSLHandshakeException);
            assertTrue(e.getCause().getCause().getMessage().contains(
                    "unable to find valid certification path to requested target"));
        }
    }
    
    @Override
    protected JndiRegistry createRegistry() throws Exception {
        JndiRegistry reg = super.createRegistry();
        
        addSslContextParametersToRegistry(reg);
    
        return reg;
    }
    
    protected void addSslContextParametersToRegistry(JndiRegistry registry) {
        KeyStoreParameters ksp = new KeyStoreParameters();
        ksp.setResource(this.getClass().getClassLoader().getResource("jsse/localhost.ks").toString());
        ksp.setPassword(KEY_STORE_PASSWORD);

        KeyManagersParameters kmp = new KeyManagersParameters();
        kmp.setKeyPassword(KEY_STORE_PASSWORD);
        kmp.setKeyStore(ksp);

        TrustManagersParameters tmp = new TrustManagersParameters();
        tmp.setKeyStore(ksp);
        
        SSLContextParameters sslContextParameters = new SSLContextParameters();
        sslContextParameters.setKeyManagers(kmp);
        sslContextParameters.setTrustManagers(tmp);

        registry.bind("sslContextParameters", sslContextParameters);
    }
    
    /**
     * Stop Camel startup.
     */
    @Override
    public boolean isUseAdviceWith() {
        return true;
    }
}
