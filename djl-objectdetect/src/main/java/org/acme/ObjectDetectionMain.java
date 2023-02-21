package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import org.acme.apps.IApp;
import org.acme.apps.LiveObjectDetectionResource;

import io.quarkus.arc.impl.InstanceImpl;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;

import org.jboss.logging.Logger;

//https://github.com/limadelrey/quarkus-sse/blob/master/src/main/java/org/limadelrey/quarkus/sse/SimpleSSE.java

@ApplicationScoped
@Path("djl")
public class ObjectDetectionMain extends DJLMain {

    Logger log = Logger.getLogger("ObjectDetectionMain");

    private OutboundSseEvent.Builder eventBuilder;

    @Context
    private Sse sse;

    private SseEventSink sseEventSink = null;

    @Inject
    Instance<IApp> djlApp;

    void startup(@Observes StartupEvent event)  {
        super.setDjlApp(djlApp);
        log.info("startup() djlApp = "+djlApp.get());
        djlApp.get().startResource();

        this.eventBuilder = sse.newEventBuilder();

    }

    @ConsumeEvent(AppUtils.LIVE_OBJECT_DETECTION)
    public void consumeLiveObjectDetect(String event){
        if(sseEventSink != null && !sseEventSink.isClosed()){

            final OutboundSseEvent sseEvent = this.eventBuilder
              .mediaType(MediaType.APPLICATION_JSON_TYPE)
              .data(event)
              .reconnectDelay(3000)
              .build();
          sseEventSink.send(sseEvent);
        }

    }

    // Test:   curl -N http://localhost:8080/djl/event/objectDetectionStream
    @GET
    @Path("/event/objectDetectionStream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void consume (@Context SseEventSink sseEventSink) {
        this.sseEventSink = sseEventSink;
    }

    @GET
    @Path("/stopPrediction")
    public void stopPrediction() {
        LiveObjectDetectionResource lodr = (LiveObjectDetectionResource)((InstanceImpl)djlApp).get();
        lodr.stopPrediction();
    }

}
