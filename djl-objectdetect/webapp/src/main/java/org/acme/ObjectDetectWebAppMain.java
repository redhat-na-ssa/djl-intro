package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import org.acme.AppUtils;

//https://github.com/limadelrey/quarkus-sse/blob/master/src/main/java/org/limadelrey/quarkus/sse/SimpleSSE.java

@ApplicationScoped
@Path("djl-object-detect-webapp")
public class ObjectDetectWebAppMain {

    Logger log = Logger.getLogger("ObjectDetectWeAppMain");

    private OutboundSseEvent.Builder eventBuilder;

    @Context
    protected Sse sse;

    private SseEventSink sseEventSink = null;


    void startup(@Observes StartupEvent event)  {
        this.eventBuilder = sse.newEventBuilder();
    }

    @Incoming(AppUtils.LIVE_OBJECT_DETECTION)
    public void consumeLiveObjectDetect(byte[] eventByte){
        if(sseEventSink != null && !sseEventSink.isClosed()){
            log.info("consumeLiveObjectDetect() received message");

            String eventString = new String(eventByte);

            final OutboundSseEvent sseEvent = this.eventBuilder
              .mediaType(MediaType.APPLICATION_JSON_TYPE)
              .data(eventString)
              .reconnectDelay(3000)
              .build();
          sseEventSink.send(sseEvent);
        }

    }

    // Test:   curl -N http://localhost:9080/djl-object-detect-webapp/event/objectDetectionStream
    @GET
    @Path("/event/objectDetectionStream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void consumeSSE (@Context SseEventSink sseEventSink) {
        log.info("Starting sseEventSink "+sseEventSink);
        this.sseEventSink = sseEventSink;
    }

}
