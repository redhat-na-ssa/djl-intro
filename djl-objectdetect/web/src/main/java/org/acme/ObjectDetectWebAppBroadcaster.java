package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import io.smallrye.mutiny.Multi;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path("djl-object-detect-web")
public class ObjectDetectWebAppBroadcaster {

    Logger log = Logger.getLogger("ObjectDetectWeAppBroadcaster");

    
    @Channel(AppUtils.LIVE_OBJECT_DETECTION)
    Multi<String> sseStream = null;


    // Test:   curl -N http://localhost:9080/djl-object-detect-web/event/objectDetectionStream
    @GET
    @Path("/event/objectDetectionStream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    //@RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> consumeSSE () {
        log.info("consumeSSE()");
        return this.sseStream;
    }

}
