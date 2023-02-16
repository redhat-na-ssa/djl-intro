package org.acme;

import java.io.File;

import javax.annotation.PostConstruct;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

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
