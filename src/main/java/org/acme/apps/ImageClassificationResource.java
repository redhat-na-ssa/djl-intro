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
package org.acme.apps;

import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.BufferedImageFactory;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;


import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.mutiny.Uni;
import org.apache.commons.imaging.Imaging;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.Response;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;

import org.jboss.logging.Logger;

@LookupIfProperty(name = "org.acme.djl.resource", stringValue = "ImageClassificationResource")
@ApplicationScoped
public class ImageClassificationResource extends BaseResource implements IApp {

    private static final String IMAGE_URL = "https://djl-ai.s3.amazonaws.com/resources/images/kitten_small.jpg";

    private static final Logger log = Logger.getLogger("ImageClassResource");
    private static final float[] MEAN = {103.939f, 116.779f, 123.68f};
    private static final float[] STD = {1f, 1f, 1f};
    private static final String TENSOR_FLOW = "TensorFlow";
    private static final String PYTORCH = "PyTorch";
    private static final String MXNET = "MXNet";

    private Image image;
    private Criteria<Image, Classifications> criteria;
    ZooModel<Image, Classifications> model;

    
    public void startResource()  {

        super.start();

        try {

            URL url = new URL(IMAGE_URL);
            try (InputStream is = url.openStream()) {
                BufferedImage bImage = Imaging.getBufferedImage(is);

                // when ai.djl.opencv:opencv is on classpath;  this factory = ai.djl.opencv.OpenCVImageFactory
                // otherwise, this factory                                  = ai.djl.modality.cv.BufferedImageFactory
                ImageFactory iFactory = ImageFactory.getInstance();
                log.info("ImageFactory = "+iFactory.getClass().getCanonicalName());


                image = iFactory.fromImage(bImage);
            }
        
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
        }catch(Exception x){
            throw new RuntimeException(x);
        }
    }


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
    
}
