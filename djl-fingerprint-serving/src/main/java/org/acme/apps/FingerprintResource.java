package org.acme.apps;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.Response;

import org.jboss.logging.Logger;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.mutiny.Uni;

@LookupIfProperty(name = "org.acme.djl.resource", stringValue = "FingerprintResource")
@ApplicationScoped
public class FingerprintResource extends BaseResource implements IApp {

    private static final Logger log = Logger.getLogger("FingerprintResource");

    public void startResource() {
        super.start();
            
    }

    public Uni<Response> predict() {
        // TODO Auto-generated method stub
        return null;
    }

}