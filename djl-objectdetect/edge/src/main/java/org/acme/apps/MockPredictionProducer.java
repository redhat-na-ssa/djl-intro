package org.acme.apps;

import javax.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.vertx.ConsumeEvent;

import org.acme.AppUtils;

@LookupIfProperty(name = "org.acme.djl.prediction.producer", stringValue = "MockPredictionProducer")
@ApplicationScoped
public class MockPredictionProducer implements PredictionProducer {

    private static final Logger log = Logger.getLogger("MockPredictionProducer");

    @ConsumeEvent(AppUtils.LIVE_OBJECT_DETECTION)
    public boolean send(String message) {
        log.infov("send() message = {0}", message);
        return true;
    }
    
}
