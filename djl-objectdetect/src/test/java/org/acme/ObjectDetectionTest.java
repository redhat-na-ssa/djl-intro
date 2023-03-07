package org.acme;

import javax.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

/*
 * NOTE:  You'll need to start up an MQTT server to support this test.
 *        A configured MQTT server is provided in this project:   docker-compose -f etc/docker-compose.yaml up -d
 */
@QuarkusTest
public class ObjectDetectionTest {

    private static Logger log = Logger.getLogger("FPrintMainTest");
    private static ObjectMapper oMapper = new ObjectMapper();
    private final AtomicInteger mqttCount = new AtomicInteger(0);

    @Inject
    ObjectDetectionMain iMain;
    
    @Test
    public void predictionCDITest() {

        Integer vCaptureDevice = (Integer)iMain.predict().await().atMost(Duration.ofSeconds(2)).getEntity();

        /*
        TO-DO:  receive MQTT message and run tests

        while(mqttCount.get() != 1){
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
        */
        
    }

}
