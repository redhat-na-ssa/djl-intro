package org.acme;

import jakarta.inject.Inject;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.Before;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.gson.reflect.TypeToken;

import ai.djl.modality.Classifications.Classification;
import ai.djl.util.JsonUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jboss.logging.Logger;

import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.ConsumerTask;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
public class FPrintMainTest {

    private static Logger log = Logger.getLogger("FPrintMainTest");
    private static ObjectMapper oMapper = new ObjectMapper();

    @Inject
    FPrintMain iMain;

    @InjectKafkaCompanion 
    KafkaCompanion companion;

    @Before
    void start() {

    }

    @Test
    public void predictionCDITest() {

        String classificationS = (String)iMain.predict().await().atMost(Duration.ofSeconds(2)).getEntity();
        
        try {
            ArrayNode predictionNode = (ArrayNode)oMapper.readTree(classificationS).get(AppUtils.PREDICTION);
            
            Type type = new TypeToken<List<Classification>>() {}.getType();
            List<Classification> classifications = JsonUtils.GSON.fromJson(predictionNode.toString(), type);

            // Test for 2 classifications
            assertEquals(classifications.size(), 2);

            // Test probability of right-handed fingerprint
            Assertions.assertTrue(classifications.get(0).getProbability() > 0.7);

        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        
    }

    @Test
    public void predictionEventest() {
        ConsumerTask<String, String> predictions = companion.consumeStrings().fromTopics(AppUtils.FPRINT_PREDICTION_EVENT_CHANNEL, 1);
        predictions.awaitCompletion();
        ConsumerRecord<String, String> record = predictions.getRecords().get(0);

        // Test message key is not null
        Assertions.assertNotNull(record.key());

        try {
            ArrayNode predictionNode = (ArrayNode)oMapper.readTree(record.value()).get(AppUtils.PREDICTION);
            
            Type type = new TypeToken<List<Classification>>() {}.getType();
            List<Classification> classifications = JsonUtils.GSON.fromJson(predictionNode.toString(), type);

            // Test for 2 classifications
            assertEquals(classifications.size(), 2);

            // Test probability of right-handed fingerprint
            Assertions.assertTrue(classifications.get(0).getProbability() > 0.7);

        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }



    }



}
