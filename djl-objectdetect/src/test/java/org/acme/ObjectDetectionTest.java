package org.acme;


import static org.awaitility.Awaitility.await;

import javax.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jboss.logging.Logger;

/*
 * NOTE:  You'll need to start up an MQTT server to support this test.
 *        A configured MQTT server is provided in this project:   docker-compose -f etc/docker-compose.yaml up -d
 */
@QuarkusTest
public class ObjectDetectionTest {

    private static Logger log = Logger.getLogger(ObjectDetectionTest.class);
    private static ObjectMapper oMapper = new ObjectMapper();
    private final AtomicInteger mqttCount = new AtomicInteger(0);
    private static MqttClient client;

    @Inject
    ObjectDetectionMain iMain;

    @ConfigProperty(name = "mp.messaging.incoming.liveObjectDetectionIncoming.host")
    String host;

    @ConfigProperty(name = "mp.messaging.incoming.liveObjectDetectionIncoming.port")
    String port;

    @ConfigProperty(name = "mp.messaging.incoming.liveObjectDetectionIncoming.username")
    String user;

    @ConfigProperty(name = "mp.messaging.incoming.liveObjectDetectionIncoming.password")
    String pwd;

    @ConfigProperty(name = "mp.messaging.incoming.liveObjectDetectionIncoming.topic")
    String topic;

    @BeforeEach
    void beforeEach() {
        if(client == null){
            try {
                client = new MqttClient("tcp://" + host + ":" + port, UUID.randomUUID().toString());
                MqttConnectOptions options = new MqttConnectOptions();
                options.setUserName(user);
                options.setPassword(pwd.toCharArray());
                client.connect(options);
                options.setKeepAliveInterval(60);
                await().until(client::isConnected);
                log.infov("beforeEach() just created mqtt connection to {0}:(1}", host, port);

            } catch (MqttException e) {
                throw new RuntimeException(e);
            }
        }
    
    }
    
    @Test
    public void predictionCDITest() {

        Integer vCaptureDevice = (Integer)iMain.predict().await().atMost(Duration.ofSeconds(2)).getEntity();

        // setup callback
        client.setCallback(new MqttCallback() {

            public void connectionLost(Throwable cause) {
                log.info("connectionLost: " + cause.getMessage());
           }

            public void messageArrived(String topic, MqttMessage message) {
                log.infov("topic: {0}  ;QoS: {1}", topic, message.getQos());
                log.info("message content: " + new String(message.getPayload()));
                mqttCount.incrementAndGet();
           }

            public void deliveryComplete(IMqttDeliveryToken token) {
                log.info("deliveryComplete---------" + token.isComplete());
           }

        });
        try {
            client.subscribe(topic, 0);
            await().atMost(5000, TimeUnit.MILLISECONDS).until( () -> mqttCount.get() == 1);
            Assertions.assertTrue(mqttCount.get() ==1);
            client.unsubscribe(topic);

        } catch (MqttException e) {
            e.printStackTrace();
        }
        
    }

}