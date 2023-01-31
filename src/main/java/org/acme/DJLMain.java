package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.acme.apps.IApp;

import io.quarkus.runtime.StartupEvent;

import org.jboss.logging.Logger;

@ApplicationScoped
public class DJLMain {

    Logger log = Logger.getLogger("DJLMain");

    @Inject
    IApp djlApp;

    void startup(@Observes StartupEvent event)  {
        log.info("startup() djlApp = "+djlApp);
    }
    
}
