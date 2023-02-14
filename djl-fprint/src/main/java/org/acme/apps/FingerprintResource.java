package org.acme.apps;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.awt.image.BufferedImage;
import java.awt.Graphics;
import java.io.InputStream;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.Response;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.mutiny.Uni;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import ai.djl.Application;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.CenterCrop;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.translate.TranslateException;
import ai.djl.util.PairList;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;

@LookupIfProperty(name = "org.acme.djl.resource", stringValue = "FingerprintResource")
@ApplicationScoped
public class FingerprintResource extends BaseResource implements IApp {

    private static final String FINGERPRINT_IMAGE_URL = "https://github.com/redhat-na-ssa/demo-rosa-sagemaker/raw/main/serving/client/images/232__M_Right_index_finger.png";
    private static final String FINGERPRINT_SYNSET_ARTIFACT_NAME = "saved_model.pb";

    private static final Logger log = Logger.getLogger("FingerprintResource");
    
    @ConfigProperty(name = "org.acme.djl.fingerprint.image.url", defaultValue = FINGERPRINT_IMAGE_URL)
    String imageUrl;
    
    private Image image;

    @ConfigProperty(name = "org.acme.djl.fingerprint.model.path")
    String modelPath;

    @ConfigProperty(name = "org.acme.djl.fingerprint.model.synset.artifact.name", defaultValue = FINGERPRINT_SYNSET_ARTIFACT_NAME)
    String synsetArtificatName;
    
    ZooModel<Image, Classifications> model;

    public void startResource() {

        super.start();
        InputStream is = null;
        try {

            Translator<Image, Classifications> cTranslator = new Translator<Image, Classifications>() {

                @Override
                public NDList processInput(TranslatorContext ctx, Image input) {
                    // Convert Image to NDArray
                    NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.GRAYSCALE);
                    Pipeline pipeline = new Pipeline();
                    pipeline.add(new CenterCrop());
                    pipeline.add(new ToTensor());
                    NDList inList = null;
                    inList = pipeline.transform(new NDList(array));
                    log.debugv("inList size = {0}", inList.size());
                    return inList;
                }
            
                @Override
                public Classifications processOutput(TranslatorContext ctx, NDList list) {
                    // Create a Classifications with the output probabilities
                    for(NDArray ndA : list.getResourceNDArrays()) {

                        log.debugv("NDArray prior to softmax = {0} {1}", ndA.toDebugString(true), ndA.getName());
                    }
                    NDArray probabilities = list.singletonOrThrow().softmax(0);

                    for(NDArray ndA : probabilities.getResourceNDArrays()) {

                        log.debugv("probabilities = {0} {1}", ndA.toDebugString(true), ndA.getName());
                    }
                    
                    List<String> classNames = new ArrayList<String>();
                    classNames.add("LEFT");
                    classNames.add("RIGHT");
                    return new Classifications(classNames, probabilities);
                }
            
                @Override
                public Batchifier getBatchifier() {
                    // The Batchifier describes how to combine a batch together
                    // Stacking, the most common batchifier, takes N [X1, X2, ...] arrays to a single [N, X1, X2, ...] array
                    return Batchifier.STACK;
                }
            };
        
            Criteria<Image, Classifications> criteria = Criteria.builder()
                .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                .optProgress(new ProgressBar())
                .setTypes(Image.class, Classifications.class) // defines input and output data type
                .optModelPath(Paths.get(this.modelPath)) // search models in specified path
                .optModelName("model") // specify model file prefix
                .optTranslator(cTranslator)
                .build();
        
            model = criteria.loadModel();
            PairList<String, Shape> pListInput = model.describeInput();
            for(Entry<String, Shape> ePair : pListInput.toMap().entrySet()) {

                Shape sObj = ePair.getValue();
                log.debugv("input epair = {0} , {1}", ePair.getKey(), sObj.toString());
            }
            PairList<String, Shape> pListOutput = model.describeOutput();
            for(Entry<String, Shape> ePairO : pListOutput.toMap().entrySet()) {
                Shape sObj = ePairO.getValue();
                log.debugv("output epair = {0} , {1}", ePairO.getKey(), sObj.toString());
            }


            log.info("startResource() image classification based on: "+ imageUrl);
            URL url = new URL(imageUrl);
            is = url.openStream();
            BufferedImage origImage = Imaging.getBufferedImage(is);
            BufferedImage resizedImage = new BufferedImage(96, 96, BufferedImage.TYPE_BYTE_GRAY);
            Graphics graphics = resizedImage.createGraphics();
            graphics.drawImage(origImage, 0, 0, 96,96, null);
            graphics.dispose();

            log.debugv("Original image height, width:  {0} ; {1}", origImage.getHeight(), origImage.getWidth());
            log.debugv("Resized image height, width:  {0} ; {1}", resizedImage.getHeight(), resizedImage.getWidth());


            // when ai.djl.opencv:opencv is on classpath;  this factory = ai.djl.opencv.OpenCVImageFactory
            // otherwise, this factory                                  = ai.djl.modality.cv.BufferedImageFactory
            ImageFactory iFactory = ImageFactory.getInstance();
            
            image = iFactory.fromImage(resizedImage); // Does this need to be transformed into a numpy array ???

        } catch (ImageReadException | ModelNotFoundException | MalformedModelException | IOException e) {
            throw new RuntimeException(e.getMessage());
        }finally {

            if(is != null)
                try { is.close(); }catch(Exception x){ x.printStackTrace();}
        }

    }

    public Uni<Response> predict() {

        Predictor<Image, Classifications> predictor = null;
        try {

            // https://djl.ai/docs/development/inference_performance_optimization.html
            // "DJL Predictor is not designed to be thread-safe (although some implementations may be"
            predictor = model.newPredictor();

            Classifications result = predictor.predict(image);
            Response eRes = Response.status(Response.Status.OK).entity(result.toJson()).build();
            return Uni.createFrom().item(eRes);
        } catch (TranslateException e) {
            e.printStackTrace(); 
            Response eRes = Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
            return Uni.createFrom().item(eRes);
        } finally {
            if(predictor != null)
                predictor.close();
        }

    }

}
