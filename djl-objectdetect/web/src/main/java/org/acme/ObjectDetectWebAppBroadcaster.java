package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseBroadcaster;
import javax.ws.rs.sse.SseEventSink;

import io.quarkus.runtime.StartupEvent;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

// https://www.baeldung.com/java-ee-jax-rs-sse

// DO NOT USE:   Attempts to send messages to SseEventSinks that have already closed
//   https://github.com/quarkusio/quarkus/issues/23997

@ApplicationScoped
@Path("djl-object-detect-broadcast")
public class ObjectDetectWebAppBroadcaster {

    Logger log = Logger.getLogger("ObjectDetectWeAppBroadcaster");

    private OutboundSseEvent.Builder eventBuilder;

    @Context
    protected Sse sse;

    private SseBroadcaster sseBroadcaster = null;


    void startup(@Observes StartupEvent event)  {
        this.eventBuilder = sse.newEventBuilder();
        this.sseBroadcaster = sse.newBroadcaster();
    }

    //@Incoming(AppUtils.LIVE_OBJECT_DETECTION)
    public void consumeLiveObjectDetect(byte[] eventByte){
        if(sseBroadcaster != null){
            log.info("consumeLiveObjectDetect() received message");

            String eventString = new String(eventByte);

            final OutboundSseEvent sseEvent = this.eventBuilder
              .mediaType(MediaType.APPLICATION_JSON_TYPE)
              .data(eventString)
              .reconnectDelay(3000)
              .build();
          sseBroadcaster.broadcast(sseEvent);
        }

    }

    // Test:   curl -N http://localhost:9080/djl-object-detect-web/event/objectDetectionStream
    @GET
    @Path("/event/objectDetectionStream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void consumeSSE (@Context SseEventSink sseEventSink) {
        log.info("Starting sseEventSink "+sseEventSink);
        this.sseBroadcaster.register(sseEventSink);
    }

}
