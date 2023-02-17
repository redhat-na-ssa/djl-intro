package org.acme;


import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

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
        djlApp.get().startResource();
    }

}
