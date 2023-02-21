package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.ws.rs.Path;

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
