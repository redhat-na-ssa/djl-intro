package org.acme.apps;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Map.Entry;
import java.awt.image.BufferedImage;
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
import ai.djl.modality.cv.output.DetectedObjects.DetectedObject;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.modality.cv.translator.ImageClassificationTranslator.Builder;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Pipeline;
import ai.djl.translate.TranslateException;
import ai.djl.util.PairList;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;

@LookupIfProperty(name = "org.acme.djl.resource", stringValue = "FingerprintResource")
@ApplicationScoped
public class FingerprintResource extends BaseResource implements IApp {

    private static final String FINGERPRINT_IMAGE_URL = "https://github.com/redhat-na-ssa/demo-rosa-sagemaker/raw/main/serving/client/images/232__M_Right_index_finger.png";
    private static final String FINGERPRINT_MODEL_PATH = "/home/jbride/Downloads/fingerprint/fingerprint/2";
    private static final String FINGERPRINT_SYNSET_ARTIFACT_NAME = "fingerprint.pb";

    private static final Logger log = Logger.getLogger("FingerprintResource");
    
    @ConfigProperty(name = "org.acme.djl.fingerprint.image.url", defaultValue = FINGERPRINT_IMAGE_URL)
    String imageUrl;
    
    private Image image;

    @ConfigProperty(name = "org.acme.djl.fingerprint.model.path", defaultValue = FINGERPRINT_MODEL_PATH)
    String modelPath;

    @ConfigProperty(name = "org.acme.djl.fingerprint.model.synset.artifact.name", defaultValue = FINGERPRINT_SYNSET_ARTIFACT_NAME)
    String synsetArtificatName;
    
    ZooModel<Image, Classifications> model;

    public void startResource() {

        super.start();
        log.info("startResource() image classification based on: "+ imageUrl);
        InputStream is = null;
        try {

            URL url = new URL(imageUrl);
            is = url.openStream();
            BufferedImage bImage = Imaging.getBufferedImage(is);

            // when ai.djl.opencv:opencv is on classpath;  this factory = ai.djl.opencv.OpenCVImageFactory
            // otherwise, this factory                                  = ai.djl.modality.cv.BufferedImageFactory
            ImageFactory iFactory = ImageFactory.getInstance();
            log.info("ImageFactory = "+iFactory.getClass().getCanonicalName());

            image = iFactory.fromImage(bImage); // Does this need to be transformed into a numpy array ???


            Pipeline pipeline = new Pipeline();
            pipeline.add(new ToTensor());
            Builder tBuilder = ImageClassificationTranslator.builder()
                .optApplySoftmax(true)
                .optSynsetArtifactName(synsetArtificatName)
                .setPipeline(pipeline)
                .addTransform(new Resize(224, 224))
                .addTransform(array -> array.div(127.5f).sub(1f));


            Criteria<Image, Classifications> criteria = Criteria.builder()
            .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                .setTypes(Image.class, Classifications.class) // defines input and output data type
                .optModelPath(Paths.get(this.modelPath)) // search models in specified path
                .optModelName("model") // specify model file prefix
                .optTranslator(tBuilder.build())
                .optProgress(new ProgressBar())
                .build();
        
            model = criteria.loadModel();
            PairList<String, Shape> pListInput = model.describeInput();
            for(Entry<String, Shape> ePair : pListInput.toMap().entrySet()) {
                Shape sObj = ePair.getValue();
                log.infov("input epair = {0} , {1}", ePair.getKey(), sObj.toString());
            }
            PairList<String, Shape> pListOutput = model.describeOutput();
            for(Entry<String, Shape> ePairO : pListOutput.toMap().entrySet()) {
                Shape sObj = ePairO.getValue();
                log.infov("output epair = {0} , {1}", ePairO.getKey(), sObj.toString());
            }

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