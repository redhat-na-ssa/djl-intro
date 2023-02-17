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

public abstract class DJLMain {

    Logger log = Logger.getLogger("DJLMain");

    private Instance<IApp> djlApp;

    public void setDjlApp(Instance<IApp> x){
        this.djlApp = x;
    }

    @POST
    @Path("/predict")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> predict() {
        return djlApp.get().predict();
        
    }

    @GET
    @Path("/logGPUDebug")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<Response> logGPUDebug() {
        return djlApp.get().logGPUDebug();
    }

    @GET
    @Path("/gpucount")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<Response> getGpuCount() {
        return djlApp.get().getGpuCount();
    }

    @GET
    @Path("/gpumemory")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> getGpuMemory() {
        return djlApp.get().getGpuMemory();
    }

    @GET
    @Path("/listModelAppSignatures")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> listModelZooAppSignatures() {
        return djlApp.get().listModelZooAppSignatures();
    }
    
}
