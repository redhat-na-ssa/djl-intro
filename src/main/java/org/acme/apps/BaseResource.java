package org.acme.apps;

import java.lang.management.MemoryUsage;
import java.util.Set;

import javax.ws.rs.core.Response;

import org.jboss.logging.Logger;

import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.util.cuda.CudaUtils;
import io.smallrye.mutiny.Uni;

public class BaseResource {

    private static final Logger log = Logger.getLogger("ImageClassResource");

    String engineName;

    public void start() {

        Device gpuDevice = Device.gpu();  // Returns default GPU device
        if(gpuDevice != null)
            log.info("Found default GPU device: "+gpuDevice.getDeviceId());
        else
            log.warn("NO DEFAULT GPU DEVICE FOUND!!!!");

        Set<String> engines = Engine.getAllEngines();
        for(String engine : engines){
            log.info("engine = "+engine);
        }

        engineName = Engine.getInstance().getEngineName();
        log.infov("detect() defaultEngineName = {0}, runtime engineName = {1}", Engine.getDefaultEngineName(), engineName);

    }

    public Uni<Response> logGPUDebug() {

        Engine.debugEnvironment();
        Response eRes = Response.status(Response.Status.OK).entity("Check Logs\n").build();
        return Uni.createFrom().item(eRes);
    }

    public Uni<Response> getGpuCount() {

        Response eRes = Response.status(Response.Status.OK).entity(Engine.getInstance().getGpuCount()).build();
        return Uni.createFrom().item(eRes);
    }

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
