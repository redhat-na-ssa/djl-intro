package org.acme.apps;

import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.acme.AppUtils;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

import org.jboss.logging.Logger;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;

@LookupIfProperty(name = "org.acme.djl.prediction.producer", stringValue = "KafkaPredictionProducer")
@ApplicationScoped
public class KafkaPredictionProducer implements PredictionProducer{

    private static final Logger log = Logger.getLogger("KafkaPredictionProducer");

   

    @Inject
    @Channel(AppUtils.FPRINT_PREDICTION_EVENT_CHANNEL)
    Emitter<String> eventChannel;

    public boolean send(String message) {
        log.infov("send() message = {0}", message);
        String uid = UUID.randomUUID().toString();
        Message<String> record = KafkaRecord.of(uid, message);
        
        eventChannel.send(record);
        return true;
    }
    
}
