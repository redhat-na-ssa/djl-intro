package org.acme.apps;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import io.opentelemetry.api.internal.StringUtils;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.mutiny.core.eventbus.EventBus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.acme.AppUtils;
import org.apache.commons.io.FileUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Core;
import org.opencv.core.MatOfByte;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import nu.pattern.OpenCV;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.engine.Engine;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.repository.zoo.Criteria.Builder;
import ai.djl.training.util.ProgressBar;

import java.awt.image.BufferedImage;

import com.sun.security.auth.module.UnixSystem;


@LookupIfProperty(name = "org.acme.djl.resource", stringValue = "LiveObjectDetectionResource")
@ApplicationScoped
public class LiveObjectDetectionResource extends BaseResource implements IApp {

    private static final String PYTORCH="pytorch";
    private static final String TENSORFLOW="tensorflow";
    private static final String MXNET="mxnet";
    private static final String PATTERN_FORMAT = "yyyy.MM.dd HH:mm:ss";
    private static final String NO_TEST_FILE="NO_TEST_FILE";

    private static Logger log = Logger.getLogger("LiveObjectDetectionResource");

    AtomicInteger schedulerCount = new AtomicInteger();

    @ConfigProperty(name = "org.acme.objecdetection.image.directory", defaultValue="/tmp/org.acme.objectdetection")
    String oDetectionDirString;

    @ConfigProperty(name = "org.acme.objectdetection.video.capture.device.id", defaultValue = "-1")
    int videoCaptureDevice;

    @ConfigProperty(name = "org.acme.objectdetection.test.video.file", defaultValue = NO_TEST_FILE)
    String testVideoFile;

    @ConfigProperty(name = "org.acme.objectdetection.write.unadultered.image.to.disk", defaultValue = "False")
    boolean writeUnAdulateredImageToDisk;

    @ConfigProperty(name = "org.acme.objectdetection.write.modified.image.to.disk", defaultValue = "False")
    boolean writeModifiedImageToDisk;

    @ConfigProperty(name = "org.acme.objectdetection.continuousPublish", defaultValue = "False")
    boolean continuousPublish;

    @ConfigProperty(name = "org.acme.objectdetection.prediction.change.threshold", defaultValue = "0.1")
    double predictionThreshold;

    @ConfigProperty(name = "org.acme.objectdetection.video.capture.interval.millis", defaultValue = "50")
    int videoCaptureIntevalMillis;

    @ConfigProperty(name = "org.acme.djl.root.model.path", defaultValue=AppUtils.NA)
    String rootModelPathString;

    @ConfigProperty(name = "org.acme.djl.fingerprint.model.artifact.dirName", defaultValue = AppUtils.NA)
    String modelDirName;

    @Inject
    CriteriaFilter cFilters;

    @Inject
    EventBus bus;

    ZooModel<Image, DetectedObjects> model;
    File fileDir;

    VideoCapture vCapture = null;
    private VideoCapturePayload previousCapture;

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(PATTERN_FORMAT).withZone(ZoneId.systemDefault());

    Mat unboxedMat = null;
    Cancellable multiCancellable = null;


    @PostConstruct
    public void startResource() {
        
        super.start();
        try {


            
            // 1)  Ensure that app can write captured images to file system
            fileDir = new File(oDetectionDirString);
            if(!fileDir.exists())
            fileDir.mkdirs();
            
            if(!(fileDir).canWrite())
                throw new RuntimeException("Can not write in the following directory: "+fileDir.getAbsolutePath());

            
            // 2) Enable web cam  (but don't start capturing images and executing object detection predictions on those images just yet)
            instantiateVideoCapture();
                
            /* Implementations:
             *      ai.djl.pytorch.engine.PtNDManager
             *      ai.djl.mxnet.engine.MxNDManager
             *      ai.djl.tensorflow.engine.TfNDManager
             */
            NDManager ndManager = NDManager.newBaseManager();

            unboxedMat = new Mat();

            // 3)  Load model
            model = loadModel();

            continueToPredict = true;

            // 4)  Keep pace with video buffer by reading frames from it at a configurable number of millis
            // On a different thread, this app will periodically evaluate the latest captured frame at that instant in time
            Multi<Long> vCaptureStreamer = Multi.createFrom().ticks().every((Duration.ofMillis(videoCaptureIntevalMillis))).onCancellation().invoke( () -> {
                log.info("just cancelled video capture streamer");
            });
            multiCancellable = vCaptureStreamer.subscribe().with( (i) -> {
                vCapture.read(unboxedMat);
            });


        }catch(RuntimeException x) {
            throw x;
        }catch(Throwable x){
            throw new RuntimeException(x);
        }finally {
            
        }
    }

    // Evaluate raw video device snapshots at periodic intervals
    @Scheduled(every = "{org.acme.objectdetection.delay.between.evaluation.seconds}" , delayed = "{org.acme.objectdetection.initial.capture.delay.seconds}", delayUnit = TimeUnit.SECONDS)
    void scheduledCapture() {
        
        if (continueToPredict && !unboxedMat.empty()) {
            Instant startCaptureTime = Instant.now();

            Mat matCopy = unboxedMat.clone();

            VideoCapturePayload cPayload = new VideoCapturePayload();
            int captureCount = schedulerCount.incrementAndGet();
            cPayload.setCaptureCount(captureCount);
            cPayload.setStartCaptureTime(startCaptureTime);
            cPayload.setMat(matCopy);

            bus.publish(AppUtils.CAPTURED_IMAGE, cPayload);
        }

    }

    // Consume raw video snapshots and apply prediction analysis
    @ConsumeEvent(AppUtils.CAPTURED_IMAGE)
    public void processCapturedEvent(VideoCapturePayload capturePayload){

        Instant startCaptureTime = capturePayload.getStartCaptureTime();
        int captureCount = capturePayload.getCaptureCount();
        Predictor<Image, DetectedObjects> predictor = null;
        try{

            // Determine presence of objects from raw video snapshot
            ImageFactory factory = ImageFactory.getInstance();
            Mat unboxedMat = capturePayload.getMat();
            Image img = factory.fromImage(unboxedMat);
            predictor = model.newPredictor();
            DetectedObjects detections = predictor.predict(img);
            capturePayload.setDetectionCount(detections.getNumberOfObjects());
           
            try {
                Classifications.Classification dClass = detections.best();
                capturePayload.setDetectedObjectClassification(dClass.getClassName());
                capturePayload.setDetected_object_probability(dClass.getProbability());
                
                // Depending if there is an object detection state change, generate an event
                if(continuousPublish || (isDifferent(capturePayload))){
                    ObjectMapper oMapper = super.getObjectMapper();
                    ObjectNode rNode = oMapper.createObjectNode();

                    if(writeUnAdulateredImageToDisk){
                        // Write un-boxed image to local file system
                        File uBoxedImageFile = new File(fileDir,  "unAdulteredImage-"+startCaptureTime.getEpochSecond() +".png");
                        BufferedImage uBoxedImage = toBufferedImage(unboxedMat);
                        ImageIO.write(uBoxedImage, "png", uBoxedImageFile);
                        rNode.put(AppUtils.UNADULTERED_IMAGE_FILE_PATH, uBoxedImageFile.getAbsolutePath());
                    }

                    rNode.put(AppUtils.DETECTION_COUNT, capturePayload.getDetectionCount());
                    rNode.put(AppUtils.DETECTED_OBJECT_CLASSIFICATION, capturePayload.getDetectedObjectClassification());
                    rNode.put(AppUtils.DETECTED_OBJECT_PROBABILITY, capturePayload.getDetected_object_probability());
                    
                    // Annotate video capture image w/ any detected objects
                    img.drawBoundingBoxes(detections);

                     // Encode binary image to Base64 string and add to payload
                    Mat boxedImage = (Mat)img.getWrappedImage();
                    BufferedImage bBoxedImage = toBufferedImage(boxedImage);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(bBoxedImage, "png", baos);
                    byte[] bytes = baos.toByteArray();
                    String stringEncodedImage = Base64.getEncoder().encodeToString(bytes);
                    rNode.put(AppUtils.BASE64_DETECTED_IMAGE, stringEncodedImage);
                    
                    if(writeModifiedImageToDisk) {
                        File boxedImageFile = new File(fileDir,  "boxedImage-"+ startCaptureTime.getEpochSecond()+".png");
                        ImageIO.write(bBoxedImage, "png", boxedImageFile);
                        rNode.put(AppUtils.DETECTED_IMAGE_FILE_PATH, boxedImageFile.getAbsolutePath());
                    }
                    
                    rNode.put(AppUtils.ID, capturePayload.getStartCaptureTime().getEpochSecond());
                    rNode.put(AppUtils.DEVICE_ID, System.getenv(AppUtils.HOSTNAME));
                    rNode.put(AppUtils.CAPTURE_COUNT, captureCount);
                    rNode.put(AppUtils.CAPTURE_TIMESTAMP, formatter.format(startCaptureTime));
                    bus.publish(AppUtils.LIVE_OBJECT_DETECTION, rNode.toPrettyString());
                    this.previousCapture = capturePayload;
                }else {
                    log.debug("no change");
                }
            }catch(NoSuchElementException x) {
                log.warn("Caught NoSuchElementException when attempting to classify objects in image");
                this.previousCapture = null;
            }
        }catch(Exception x){
            x.printStackTrace();
        }finally {
            if(predictor != null)
                predictor.close();
        }
        Duration timeElapsed = Duration.between(startCaptureTime, Instant.now());
        log.info(captureCount + " : "+ timeElapsed); 
    }


    @PreDestroy
    public void shutdown() {
        multiCancellable.cancel();
        if(vCapture != null && vCapture.isOpened()){
            vCapture.release();
            log.infov("shutdown() video capture device = {0}", this.videoCaptureDevice );
        }
    }

    private boolean isDifferent(VideoCapturePayload latest) {
        if(previousCapture == null){
            //log.info("previous was null");
            return true;
        }

        if(previousCapture.getDetectionCount() != latest.getDetectionCount()){
            //log.info("capture count different: "+previousCapture.getDetectionCount()+" : "+latest.getDetectionCount());
            return true;
        }
        if(!previousCapture.getDetectedObjectClassification().equals(latest.getDetectedObjectClassification()))
            return true;
        
        double pProb = previousCapture.getDetected_object_probability();
        double cProb = latest.getDetected_object_probability();
        double diff = cProb - pProb;
        double positiveDiff = Math.abs(diff);
        if(positiveDiff > this.predictionThreshold){
            log.info("Just exceeded max probability threshold: "+this.predictionThreshold +" : "+positiveDiff);
            return true;
        }
        
        return false;
    }

    public ZooModel<?,?> getAppModel(){
        return model;
    }

    public Uni<Response> stopPrediction() {

        log.info("stopPrediction");
        this.continueToPredict=false;
        this.previousCapture=null;
        Response eRes = Response.status(Response.Status.OK).entity(this.videoCaptureDevice).build();
        return Uni.createFrom().item(eRes);
    }
    
    public Uni<Response> predict() {
        log.info("will now begin to predict on video capture stream");
        this.continueToPredict=true;
        Response eRes = Response.status(Response.Status.OK).entity(this.videoCaptureDevice).build();
        return Uni.createFrom().item(eRes);
    }

    private ZooModel<Image, DetectedObjects> loadModel() throws IOException, ModelException {

        if(!AppUtils.NA.equals(this.modelDirName)){
            log.info("loadModel() will load from "+this.modelDirName);
            return null;
        }else{
           
            Builder<Image, DetectedObjects> builder = Criteria.builder()
                .optApplication(Application.CV.OBJECT_DETECTION)
                .setTypes(Image.class, DetectedObjects.class)
                .optProgress(new ProgressBar());
    
            String eType = Engine.getDefaultEngineName();
            log.info("loadModel() will load from model zoo specific to: "+eType);

            Map<String, String> filters = null;
            if(PYTORCH.equalsIgnoreCase(eType)) {
                filters = cFilters.pytorch();
            }else if(TENSORFLOW.equalsIgnoreCase(eType)){
                filters = cFilters.tensorflow();
            }else if(MXNET.equalsIgnoreCase(eType)) {
                filters = cFilters.mxnet();
            }else {
                throw new RuntimeException("Unknown engine type: "+eType);
            }
            for(Entry<String, String> eObj : filters.entrySet() ){
                builder = builder.optFilter(eObj.getKey(), eObj.getValue());
            }
    
            Criteria<Image, DetectedObjects> criteria = builder.build();
            return criteria.loadModel();
        }

    }


    private static BufferedImage toBufferedImage(Mat mat) {
        int width = mat.width();
        int height = mat.height();
        int type =
                mat.channels() != 1 ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY;
        
        if (type == BufferedImage.TYPE_3BYTE_BGR) {
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2RGB);
        }

        byte[] data = new byte[width * height * (int) mat.elemSize()];

        mat.get(0, 0, data);

        BufferedImage ret = new BufferedImage(width, height, type);
        ret.getRaster().setDataElements(0, 0, width, height, data);

        return ret;
    }
    
    private void instantiateVideoCapture() throws Exception {

        if(videoCaptureDevice > -1){

            OpenCV.loadShared();

            /* Determine groups
             *   troubleshoot:  podman run -it --rm  --group-add keep-groups quay.io/redhat_naps_da/djl-objectdetect-pytorch:0.0.3 id -a
             */
            UnixSystem uSystem = new UnixSystem();
            long[] groups = uSystem.getGroups();

            vCapture = new VideoCapture(videoCaptureDevice);
            if(!vCapture.isOpened())
                throw new RuntimeException("Unable to access video capture device w/ id = " + this.videoCaptureDevice+" and OS groups: "+Arrays.toString(groups));

            log.infov("start() video capture device = {0} is open =  {1}. Using NDManager {2}", 
                this.videoCaptureDevice, 
                vCapture.isOpened());

        }else if(!StringUtils.isNullOrEmpty(this.testVideoFile)){

            log.info("Working Directory = " + System.getProperty("user.dir"));

            // Not actually needed
            // Just ensure opencv-java gstreamer1-plugin-libav packages are installed and "java.library.path" includes path to those installed C++ libraries
            //System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

            OpenCV.loadShared();

            vCapture = new VideoCapture(this.testVideoFile, Videoio.CAP_ANY);
            log.infov("vCapture props: {0} {1} [2] [3]",
                vCapture.get(Videoio.CAP_PROP_FOURCC),
                vCapture.get(Videoio.CAP_PROP_FPS),
                vCapture.get(Videoio.CAP_PROP_FRAME_WIDTH),
                vCapture.get(Videoio.CAP_PROP_FRAME_HEIGHT) );
            if(!vCapture.isOpened())
                throw new RuntimeException("Unable to access test video = " + this.testVideoFile+" .  Do you have opencv-java & gstreamer packages installed (ie: dnf install opencv-java gstreamer1-plugin-libav) ?");

            log.infov("start() video streaming on file = {0} is open =  {1}. Using NDManager {2}", 
                this.testVideoFile, 
                vCapture.isOpened());
        }else {
            throw new Exception("need to specify either a video capture device or a video file");
        }
    }
}
