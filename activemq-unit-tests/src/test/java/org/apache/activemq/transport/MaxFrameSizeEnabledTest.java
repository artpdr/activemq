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
package org.apache.activemq.transport;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.MaxFrameSizeExceededException;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.transport.nio.SelectorManager;
import org.apache.activemq.util.Wait;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.*;

//Test for AMQ-8142
@RunWith(value = Parameterized.class)
public class MaxFrameSizeEnabledTest {

    public static final String KEYSTORE_TYPE = "jks";
    public static final String PASSWORD = "password";
    public static final String SERVER_KEYSTORE = "src/test/resources/org/apache/activemq/security/broker1.ks";
    public static final String TRUST_KEYSTORE = "src/test/resources/org/apache/activemq/security/broker1.ks";

    private BrokerService broker;
    private final String transportType;
    private final boolean clientSideEnabled;
    private final boolean serverSideEnabled;

    public MaxFrameSizeEnabledTest(String transportType, boolean clientSideEnabled, boolean serverSideEnabled) {
        this.transportType = transportType;
        this.clientSideEnabled = clientSideEnabled;
        this.serverSideEnabled = serverSideEnabled;
    }

    @Parameterized.Parameters(name="transportType={0},clientSideEnable={1},serverSideEnabled={2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                //Both client and server side max frame check enabled
                {"tcp", true, true},
                {"ssl", true, true},
                {"nio", true, true},
                {"nio+ssl", true, true},
                {"auto", true, true},
                {"auto+ssl", true, true},
                {"auto+nio", true, true},
                {"auto+nio+ssl", true, true},

                //Client side enabled but server side disabled
                {"tcp", true, false},
                {"ssl", true, false},
                {"nio", true, false},
                {"nio+ssl", true, false},
                {"auto", true, false},
                {"auto+ssl", true, false},
                {"auto+nio", true, false},
                {"auto+nio+ssl", true, false},

                //Client side disabled but server side enabled
                {"tcp", false, true},
                {"ssl", false, true},
                {"nio", false, true},
                {"nio+ssl", false, true},
                {"auto", false, true},
                {"auto+ssl", false, true},
                {"auto+nio", false, true},
                {"auto+nio+ssl", false, true},

                //Client side and server side disabled
                {"tcp", false, false},
                {"ssl", false, false},
                {"nio", false, false},
                {"nio+ssl", false, false},
                {"auto", false, false},
                {"auto+ssl", false, false},
                {"auto+nio", false, false},
                {"auto+nio+ssl", false, false},
        });
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        System.setProperty("javax.net.ssl.trustStore", TRUST_KEYSTORE);
        System.setProperty("javax.net.ssl.trustStorePassword", PASSWORD);
        System.setProperty("javax.net.ssl.trustStoreType", KEYSTORE_TYPE);
        System.setProperty("javax.net.ssl.keyStore", SERVER_KEYSTORE);
        System.setProperty("javax.net.ssl.keyStoreType", KEYSTORE_TYPE);
        System.setProperty("javax.net.ssl.keyStorePassword", PASSWORD);
    }

    @After
    public void after() throws Exception {
        stopBroker(broker);
    }

    public BrokerService createBroker(String connectorName, String connectorString) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        TransportConnector connector = broker.addConnector(connectorString);
        connector.setName(connectorName);
        broker.start();
        broker.waitUntilStarted();
        return broker;
    }

    public void stopBroker(BrokerService broker) throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    @Test
    public void testMaxFrameSize() throws Exception {
        broker = createBroker(transportType, transportType + "://localhost:0?wireFormat.maxFrameSize=2048" + getServerParams());
        testMaxFrameSize(transportType, (isSsl() ? "ssl" : "tcp") + "://localhost:" + broker.getConnectorByName(transportType).getConnectUri().getPort() +
                getClientParams(), false);
    }

    @Test
    public void testMaxFrameSizeCompression() throws Exception {
        // Test message body length is 99841 bytes. Compresses to ~ 48000
        broker = createBroker(transportType, transportType + "://localhost:0?wireFormat.maxFrameSize=60000" + getServerParams());
        testMaxFrameSize(transportType, (isSsl() ? "ssl" : "tcp") + "://localhost:" + broker.getConnectorByName(transportType).getConnectUri().getPort()
                + getClientParams(), true);
    }

    protected void testMaxFrameSize(String transportType, String clientUri, boolean useCompression) throws Exception {
        final List<Connection> connections = new ArrayList<>();
        final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(clientUri);
        factory.setUseCompression(useCompression);

        for (int i = 0; i < 10; i++) {
            Connection connection = factory.createConnection();
            connection.start();
            connections.add(connection);
        }

        //Generate a body that is too large
        StringBuffer body = new StringBuffer();
        Random r = new Random();
        for (int i = 0; i < 10000; i++) {
            body.append(r.nextInt());
        }

        //Try sending 10 large messages rapidly in a loop to make sure all
        //nio threads are allowed to send again and do not close server-side
        for (int i = 0; i < 10; i++) {
            boolean maxFrameSizeException = false;
            boolean otherException = false;

            Connection connection = null;
            Session session = null;
            Queue destination = null;
            MessageConsumer messageConsumer = null;
            MessageProducer producer = null;

            try {
                connection = connections.get(i);
                session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                destination = session.createQueue("TEST");
                producer = session.createProducer(destination);
                producer.send(session.createTextMessage(body.toString()));
            } catch (JMSException e) {
                if (clientSideEnabled) {
                    assertNotNull(e.getErrorCode());
                    assertEquals("41300", e.getErrorCode());
                    assertTrue(e.getCause() instanceof MaxFrameSizeExceededException);
                } else {
                    assertTrue(e.getCause() instanceof IOException);
                }
                assertNotNull(e.getCause());
                maxFrameSizeException = true;
            } catch (Exception e) {
                otherException = true;
            }

            if (maxFrameSizeEnabled() && !useCompression) {
                assertTrue("Should have gotten a jms maxframesize exception", maxFrameSizeException);
                assertFalse("Should not have gotten a transport exception", otherException);
            } else {
                assertFalse("Should not have gotten a jms maxframesize exception", maxFrameSizeException);
            }

            if (!maxFrameSizeEnabled() && otherException) {
                fail("Should not have gotten exception");
            }

            assertNotNull(connection);
            assertNotNull(session);
            assertNotNull(destination);
            assertNotNull(producer);

            if (connectionsShouldBeOpen(useCompression)) {
                // Validate we can send a valid sized message after sending a too-large of message
                boolean nextException = false;
                try {

                    messageConsumer = session.createConsumer(destination);
                    producer.send(session.createTextMessage("Hello"));

                    int maxLoops = 50;
                    boolean found = false;
                    do {
                        Message message = messageConsumer.receive(200l);
                        if (message != null) {
                            assertTrue(TextMessage.class.isAssignableFrom(message.getClass()));
                            TextMessage.class.cast(message).getText().equals("Hello");
                            found = true;
                        }
                        maxLoops++;
                    } while (!found && maxLoops <= 50);

                } catch (Exception e) {
                    nextException = true;
                }
                assertFalse("Should not have gotten an exception for the next message", nextException);
            }
        }


        if (connectionsShouldBeOpen(useCompression)) {
            //Verify that all connections are active
            assertTrue(Wait.waitFor(() -> broker.getConnectorByName(transportType).getConnections().size() == 10,
                    3000, 500));
        } else {
            //Verify that all connections are closed
            assertTrue(Wait.waitFor(() -> broker.getConnectorByName(transportType).getConnections().size() == 0,
                    3000, 500));
        }

        if (isNio() && connectionsShouldBeOpen(useCompression)) {
            //Verify one active transport connections in the selector thread pool.
            final ThreadPoolExecutor e = (ThreadPoolExecutor) SelectorManager.getInstance().getSelectorExecutor();
            assertTrue(Wait.waitFor(() -> e.getActiveCount() == 1, 3000, 500));
        }
    }

    private boolean maxFrameSizeEnabled() {
        return clientSideEnabled || serverSideEnabled;
    }

    private boolean connectionsShouldBeOpen(boolean useCompression) {
        return !maxFrameSizeEnabled() || clientSideEnabled || useCompression;
    }

    private boolean isSsl() {
        return transportType.contains("ssl");
    }

    private boolean isNio() {
        return transportType.contains("nio");
    }

    private String getServerParams() {
        if (serverSideEnabled) {
            return isSsl() ? "&transport.needClientAuth=true" : "";
        } else {
            return isSsl() ? "&transport.needClientAuth=true&wireFormat.maxFrameSizeEnabled=false" : "&wireFormat.maxFrameSizeEnabled=false";
        }
    }

    private String getClientParams() {
        if (clientSideEnabled) {
            return isSsl() ? "?socket.verifyHostName=false" : "";
        } else {
            return isSsl() ? "?socket.verifyHostName=false&wireFormat.maxFrameSizeEnabled=false" : "?wireFormat.maxFrameSizeEnabled=false";
        }
    }
}
