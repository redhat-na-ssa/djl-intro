package org.acme.apps;

import java.lang.management.MemoryUsage;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.MediaType;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.util.cuda.CudaUtils;
import io.smallrye.mutiny.Uni;

public class BaseResource {

    @GET
    @Path("/logGPUDebug")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<Response> logGPUDebug() {

        Engine.debugEnvironment();
        Response eRes = Response.status(Response.Status.OK).entity("Check Logs\n").build();
        return Uni.createFrom().item(eRes);
    }

    @GET
    @Path("/gpucount")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<Response> getGpuCount() {

        Response eRes = Response.status(Response.Status.OK).entity(Engine.getInstance().getGpuCount()).build();
        return Uni.createFrom().item(eRes);
    }

    @GET
    @Path("/gpumemory")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> getGpuMemory() {

        Device dObj = Engine.getInstance().defaultDevice();
        long gpuRAM = 0L;
        if(dObj.isGpu()){
            MemoryUsage mem = CudaUtils.getGpuMemory(dObj);
            gpuRAM = mem.getMax();
        }
        Response eRes = Response.status(Response.Status.OK).entity(gpuRAM).build();
        return Uni.createFrom().item(eRes);
    }
    
}
