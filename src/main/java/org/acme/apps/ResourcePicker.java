package org.acme.apps;

import javax.enterprise.inject.Produces;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

public class ResourcePicker {

    public static final String IMAGE_CLASSIFICICATION_RESOURCE="ImageClassificationResource";
    public static final String LIVE_OBJECT_DETECTION_RESOURCE="LiveObjectDetectionResource";

    @ConfigProperty(name = "org.acme.djl.resource", defaultValue = IMAGE_CLASSIFICICATION_RESOURCE)
    String djlResource;

    @Produces
    @Singleton
    public IApp getResource() {
        if(djlResource.equals(IMAGE_CLASSIFICICATION_RESOURCE)){
            return new ImageClassificationResource();
        }else {
            throw new RuntimeException("No implementation of: " + djlResource);
        }
    }
    
}
