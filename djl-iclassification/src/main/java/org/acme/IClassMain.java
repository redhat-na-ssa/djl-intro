package org.acme;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

import org.acme.apps.IApp;

import io.quarkus.runtime.StartupEvent;

import org.jboss.logging.Logger;

@ApplicationScoped
@Path("djl")
public class IClassMain extends DJLMain {

    Logger log = Logger.getLogger("IClassMain");

    @Inject
    Instance<IApp> djlApp;

    void startup(@Observes StartupEvent event)  {
        super.setDjlApp(djlApp);
        log.info("startup() djlApp = "+djlApp.get());
    }
}
