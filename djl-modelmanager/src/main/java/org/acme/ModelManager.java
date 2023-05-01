package org.acme;

import java.io.File;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;


@Path("/model-manager")
public class ModelManager {

    private static final Logger log = Logger.getLogger("ModelManager");

    @ConfigProperty(name = "org.acme.djl.root.model.path")
    String rootModelPathString;

    File rootModelPath = null;

    @PostConstruct
    public void startup() {
        rootModelPath = new File(rootModelPathString);
        if(!rootModelPath.exists() || !rootModelPath.isDirectory())
            throw new RuntimeException("Following directory does not exist: "+rootModelPathString);
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/rootFiles")
    public String listModelsInRootModelPath() {
        File[] files = rootModelPath.listFiles();
        for(File fObj : files) {
            log.infov("file = {0}", fObj.getAbsolutePath());
        }
        return "# files found = "+files.length;
    }
}
