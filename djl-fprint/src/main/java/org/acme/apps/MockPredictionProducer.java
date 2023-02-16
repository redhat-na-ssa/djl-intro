package org.acme.apps;

import javax.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.arc.lookup.LookupIfProperty;

@LookupIfProperty(name = "org.acme.djl.prediction.producer", stringValue = "MockPredictionProducer")
@ApplicationScoped
public class MockPredictionProducer implements PredictionProducer {

    private static final Logger log = Logger.getLogger("MockPredictionProducer");

    public boolean send(String message) {
        log.infov("send() message = {0}", message);
        return true;
    }
    
}
