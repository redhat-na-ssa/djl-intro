package org.acme.apps;

import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import javax.ws.rs.core.Response;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.djl.Application;
import ai.djl.Device;
import ai.djl.engine.Engine;
import ai.djl.repository.Artifact;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.util.cuda.CudaUtils;
import io.smallrye.mutiny.Uni;

public class BaseResource {

    private static final Logger log = Logger.getLogger("ImageClassResource");

    String engineName;

    ObjectMapper oMapper;

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

        oMapper = new ObjectMapper();

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

    public Uni<Response> listModelAppSignatures() {
        
        Map<Application, List<Artifact>> models;
        Response eRes = null;;
        try {
            ObjectNode rNode = oMapper.createObjectNode();
            models = ModelZoo.listModels();
            Set<Entry<Application, List<Artifact>>> eSets = models.entrySet();
            for(Entry<Application, List<Artifact>> entryS : eSets) {
                Application app = entryS.getKey();
                ArrayNode artNode = rNode.putArray(app.getPath());
                List<Artifact> artifacts = entryS.getValue();
                for(Artifact aObj : artifacts){
                    artNode.add(aObj.getName());
                    log.info(app.getPath() + " : " + aObj.getName() );
                }
            }
            String modelsJson = rNode.toPrettyString();
            eRes = Response.status(Response.Status.OK).entity(modelsJson).build();
        } catch (Exception e) {
            eRes = Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
        return Uni.createFrom().item(eRes);
    }
    
}
