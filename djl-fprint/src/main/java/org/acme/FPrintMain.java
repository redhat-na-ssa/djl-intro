package org.acme;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.acme.apps.IApp;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;

import org.jboss.logging.Logger;

@ApplicationScoped
@Path("djl")
public class FPrintMain extends DJLMain {

    Logger log = Logger.getLogger("FPrintMain");

    @Inject
    Instance<IApp> djlApp;

    void startup(@Observes StartupEvent event)  {
        super.setDjlApp(djlApp);
        log.info("startup() djlApp = "+djlApp.get());
    }

}
