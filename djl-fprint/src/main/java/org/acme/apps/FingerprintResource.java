package org.acme.apps;

import java.io.File;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import java.awt.Graphics;
import java.io.InputStream;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.translate.TranslateException;

import org.acme.AppUtils;
import org.acme.apps.s3.S3ModelLifecycle;
import org.acme.apps.s3.S3Notification;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;

@LookupIfProperty(name = "org.acme.djl.resource", stringValue = "FingerprintResource")
@ApplicationScoped
public class FingerprintResource extends BaseResource implements IApp {

    public static final String FINGERPRINT_IMAGE_URL = "https://github.com/redhat-na-ssa/demo-rosa-sagemaker/raw/main/serving/client/images/232__M_Right_index_finger.png";

    private static final Logger log = Logger.getLogger("FingerprintResource");
    
    @ConfigProperty(name = "org.acme.djl.fingerprint.image.url", defaultValue = FINGERPRINT_IMAGE_URL)
    String imageUrl;
    
    private Image image;

    @ConfigProperty(name = "org.acme.djl.root.model.path")
    String rootModelPathString;

    @ConfigProperty(name = "org.acme.djl.model.artifact.dirName", defaultValue = "model.savedmodel")
    String modelDirName;

    @Inject
    Instance<PredictionProducer> pProducer;
    
    @Inject
    S3ModelLifecycle modelLifecycle;
    
    ZooModel<Image, Classifications> appModel;

    @PostConstruct
    public void startResource() {
        super.start();
        loadModel();
        this.continueToPredict = true;
    }

    private void loadModel() {

        File rootModelPath = new File(rootModelPathString);
        if(!rootModelPath.exists() || !rootModelPath.isDirectory())
            throw new RuntimeException("Following root directory does not exist: "+rootModelPathString);

        File modelPath = new File(rootModelPath, modelDirName);
        if(!modelPath.exists() || !modelPath.isDirectory())
            throw new RuntimeException("Following model path directory does not exist: "+modelDirName);
        else
            log.infov("model found!   {0}, # of model files = {1}", modelPath.getAbsoluteFile().getAbsolutePath(), modelPath.listFiles().length);

        Path nioModelPath = this.findModelDir(rootModelPath.toPath(), modelDirName);
        log.infov("nioModelPath = {0}", nioModelPath);

        InputStream is = null;
        try {

            Translator<Image, Classifications> cTranslator = new Translator<Image, Classifications>() {

                @Override
                public NDList processInput(TranslatorContext ctx, Image inputImage) {

                    // Convert Image to NDArray
                    NDArray array = inputImage.toNDArray(ctx.getNDManager(), Image.Flag.GRAYSCALE);
                    Pipeline pipeline = new Pipeline();
                    pipeline.add(new CenterCrop());
                    pipeline.add(new ToTensor());

                    // Transform to NDList
                    NDList inList = pipeline.transform(new NDList(array));
                    log.debugv("inList size = {0}", inList.size());
                    return inList;
                }
            
                @Override
                public Classifications processOutput(TranslatorContext ctx, NDList list) {

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

                    // Create a Classifications with the output probabilities
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
                .optModelPath(Paths.get(this.rootModelPathString)) // search models in specified path
                .optModelName(modelDirName) // specify model file directory
                .optTranslator(cTranslator)
                .build();
        
            appModel = criteria.loadModel();

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
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }finally {

            if(is != null)
                try { is.close(); }catch(Exception x){ x.printStackTrace();}
        }

    }

    private Path findModelDir(Path modelDir, String prefix) {
        Path path = modelDir.resolve(prefix);
        if (!Files.exists(path)) {
            return null;
        }
        if (Files.isRegularFile(path)) {
            return modelDir;
        } else if (Files.isDirectory(path)) {
            Path file = path.resolve("saved_model.pb");
            if (Files.exists(file) && Files.isRegularFile(file)) {
                return path;
            }
        }
        return null;
    }

    public ZooModel<?,?> getAppModel(){
        return appModel;
    }

    public Uni<Response> predict() {

        if(!continueToPredict){
            ObjectMapper mapper = super.getObjectMapper();
            ObjectNode rNode = mapper.createObjectNode();
            rNode.put("STATUS", AppUtils.PREDICTION_INFERENCE_NOT_AVAILABLE);
            Response eRes = Response.status(Response.Status.OK).entity(rNode.toPrettyString()).build();
            return Uni.createFrom().item(eRes);
        }

        Predictor<Image, Classifications> predictor = null;
        try {

            // https://djl.ai/docs/development/inference_performance_optimization.html
            // "DJL Predictor is not designed to be thread-safe (although some implementations may be"
            predictor = appModel.newPredictor();

            Classifications result = predictor.predict(image);
            String predictionMessage = result.toJson();

            ObjectMapper mapper = super.getObjectMapper();
            ObjectNode rNode = mapper.createObjectNode();
            rNode.put(AppUtils.PROCESSED_IMAGE_URL, this.imageUrl);
            ArrayNode pNode = mapper.readValue(predictionMessage, ArrayNode.class);
            rNode.set(AppUtils.PREDICTION, pNode);

            pProducer.get().send(rNode.toPrettyString());
            Response eRes = Response.status(Response.Status.OK).entity(rNode.toPrettyString()).build();
            return Uni.createFrom().item(eRes);
        } catch (TranslateException | JsonProcessingException e) {
            e.printStackTrace(); 
            Response eRes = Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
            return Uni.createFrom().item(eRes);
        } finally {
            if(predictor != null)
                predictor.close();
        }
    }

    public Uni<Response> stopPrediction() {

        log.info("stopPrediction");
        this.continueToPredict=false;
        Response eRes = Response.status(Response.Status.OK).entity(AppUtils.PREDICTION_INFERENCE_STOPPED).build();
        return Uni.createFrom().item(eRes);
    }

     @Incoming(AppUtils.MODEL_NOTIFY)
     public void processModelStateChangeNotification(byte[] nMessageBytes) throws JsonMappingException, JsonProcessingException{
        String nMessage = new String(nMessageBytes);
        log.debugv("modelStateChangeNotification =  {0}", nMessage);

        ObjectMapper mapper = super.getObjectMapper();
        S3Notification modelN = mapper.readValue(nMessage, S3Notification.class);
        String key = modelN.key;
        
        if(AppUtils.S3_OBJECT_CREATED.equals(modelN.eventName)){

            this.stopPrediction();
            org.acme.apps.s3.Record record = modelN.records.get(0);
            String fileName = record.s3.object.key;
            String fileSize = record.s3.object.size;
            boolean success = modelLifecycle.pullAndSaveModel(fileName, Integer.parseInt(fileSize));

            if(success){
                loadModel();
                this.continueToPredict = true;
            }

        }else if(AppUtils.S3_OBJECT_DELETED.equals(modelN.eventName)) {
            log.warnv("WILL IGNORE model state change: type= {0} ; key= {1}", modelN.eventName, key);
        }else{
            log.errorv("WILL IGNORE model state change: type= {0} ; key= {1}", modelN.eventName, key);
        }

     }

}
