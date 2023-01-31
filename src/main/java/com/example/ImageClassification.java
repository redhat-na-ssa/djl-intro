/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.example;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.ModelException;
import ai.djl.engine.Engine;
import ai.djl.engine.EngineProvider;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.util.cuda.CudaUtils;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.event.Observes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.MemoryUsage;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;

import org.jboss.logging.Logger;

@Path("/")
public class ImageClassification {

    private static final String IMAGE_URL = "https://djl-ai.s3.amazonaws.com/resources/images/kitten_small.jpg";

    private static final Logger log = Logger.getLogger("ImageClassification");
    private static final float[] MEAN = {103.939f, 116.779f, 123.68f};
    private static final float[] STD = {1f, 1f, 1f};
    private static final String TENSOR_FLOW = "TensorFlow";
    private static final String PYTORCH = "PyTorch";
    private static final String MXNET = "MXNet";

    private Image image;
    private Criteria<Image, Classifications> criteria;
    ZooModel<Image, Classifications> model;
    Device gpuDevice;

    void startup(@Observes StartupEvent event) throws IOException, ImageReadException, ModelNotFoundException, MalformedModelException {

        URL url = new URL(IMAGE_URL);
        try (InputStream is = url.openStream()) {
            image = ImageFactory.getInstance().fromImage(Imaging.getBufferedImage(is));
        }
        gpuDevice = Device.gpu();  // Returns default GPU device
        if(gpuDevice != null)
            log.info("Found default GPU device: "+gpuDevice.getDeviceId());
        else
            log.warn("NO DEFAULT GPU DEVICE FOUND!!!!");

        Set<String> engines = Engine.getAllEngines();
        for(String engine : engines){
            log.info("engine = "+engine);
        }

        String engineName = Engine.getInstance().getEngineName();
        log.infov("detect() defaultEngineName = {0}, runtime engineName = {1}", Engine.getDefaultEngineName(), engineName);

    
        if (TENSOR_FLOW.equals(engineName)) {
            Translator<Image, Classifications> translator = ImageClassificationTranslator.builder()
                    .addTransform(new Resize(224))
                    .addTransform(new Normalize(MEAN, STD))
                    .build();
            criteria = Criteria.builder()
                            .setTypes(Image.class, Classifications.class) // define input & output
                            .optArtifactId("resnet")
                            .optTranslator(translator)
                            .optProgress(new ProgressBar())
                            .build();
        } else if (PYTORCH.equals(engineName)) {
            criteria = Criteria.builder()
                            .setTypes(Image.class, Classifications.class) // define input & output
                            .optArtifactId("resnet")
                            .optProgress(new ProgressBar())
                            .optEngine("PyTorch")
                            .build();
        } else if (MXNET.equals(engineName)) {
            criteria = Criteria.builder()
                .setTypes(Image.class, Classifications.class) // define input & output
                .optArtifactId("resnet")
                .optProgress(new ProgressBar())
                .build();
        } else {
            throw new RuntimeException("Unknown engine type: "+engineName);
        }

        model  = ModelZoo.loadModel(criteria);

        log.infov("startup*() .... completed !");
    }

    @GET
    @Path("/predict")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> predict() {

        try (
            Predictor<Image, Classifications> predictor = model.newPredictor()) {

            Classifications result = predictor.predict(image);
            Response eRes = Response.status(Response.Status.OK).entity(result.toJson()).build();
            return Uni.createFrom().item(eRes);
        } catch (TranslateException e) {
            e.printStackTrace();
            Response eRes = Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
            return Uni.createFrom().item(eRes);
        }
    }       
    
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
