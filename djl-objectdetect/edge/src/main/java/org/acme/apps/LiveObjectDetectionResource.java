package org.acme.apps;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;

import org.acme.AppUtils;
import org.apache.commons.io.FileUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

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

    private static Logger log = Logger.getLogger("LiveObjectDetectionResource");

    @ConfigProperty(name = "org.acme.objecdetection.image.directory", defaultValue="/tmp/org.acme.objectdetection")
    String oDetectionDirString;

    @ConfigProperty(name = "org.acme.objectdetection.video.capture.device.id", defaultValue = "0")
    int videoCaptureDevice;

    @ConfigProperty(name = "org.acme.objectdetection.write.unadulted.image.to.disk", defaultValue = "False")
    boolean writeUnAdulatedImageToDisk;

    @ConfigProperty(name = "org.acme.objectdetection.predictAtStartup", defaultValue = "True")
    boolean predict;

    @Inject
    CriteriaFilter cFilters;

    @Inject
    EventBus bus;

    ZooModel<Image, DetectedObjects> model;
    File fileDir;

    VideoCapture vCapture = null;
    private int detectionCountState = -1;

    @PostConstruct
    public void startResource() {
        
        super.start();
        try {

            /* Determine groups
             *   troubleshoot:  podman run -it --rm  --group-add keep-groups quay.io/redhat_naps_da/djl-objectdetect-pytorch:0.0.3 id -a
             */
            UnixSystem uSystem = new UnixSystem();
            long[] groups = uSystem.getGroups();
            
            // 1)  Ensure that app can write captured images to file system
            fileDir = new File(oDetectionDirString);
            if(!fileDir.exists())
            fileDir.mkdirs();
            
            if(!(fileDir).canWrite())
                throw new RuntimeException("Can not write in the following directory: "+fileDir.getAbsolutePath());

            // 2)  Load model
            model = loadModel();
            
            // 3) Enable web cam  (but don't start capturing images and executing object detection predictions on those images just yet)
            OpenCV.loadShared();
            vCapture = new VideoCapture(videoCaptureDevice);
            if(!vCapture.isOpened())
                throw new RuntimeException("Unable to access video capture device w/ id = " + this.videoCaptureDevice+" and OS groups: "+Arrays.toString(groups));
                
            /* Implementations:
             *      ai.djl.pytorch.engine.PtNDManager
             *      ai.djl.mxnet.engine.MxNDManager
             *      ai.djl.tensorflow.engine.TfNDManager
             */
            NDManager ndManager = NDManager.newBaseManager();
            log.infov("start() video capture device = {0} is open =  {1}. Using NDManager {2}  and OS groups {3}", 
                this.videoCaptureDevice, 
                vCapture.isOpened(),
                ndManager.getClass().getName(),
                Arrays.toString(groups));

        }catch(Throwable x){
            throw new RuntimeException(x);
        }finally {
            
        }
    }

    // Capture raw video device snapshots at periodic intervals
    @Scheduled(every = "{org.acme.objectdetection.delay.between.capture.seconds}" , delay = 5, delayUnit = TimeUnit.SECONDS)
    void scheduledCapture() {
        
        // Capture image from webcam
        Mat unboxedMat = new Mat();
        boolean captured = vCapture.read(unboxedMat);
        if (captured)
            bus.publish(AppUtils.CAPTURED_IMAGE, unboxedMat);

    }


    // Consume raw video snapshots and apply prediction analysis
    @ConsumeEvent(AppUtils.CAPTURED_IMAGE)
    public void processCapturedEvent(Mat unboxedMat){
        if(this.predict){
            Predictor<Image, DetectedObjects> predictor = null;
            try{
    
                // Determine presence of objects from raw video snapshot
                ImageFactory factory = ImageFactory.getInstance();
                Image img = factory.fromImage(unboxedMat);
                predictor = model.newPredictor();
                DetectedObjects detections = predictor.predict(img);
                int dCount = detections.getNumberOfObjects();
               
                // Depending if there is a state change of object detection, generate an event
                log.debugv("{0} , {1}", detectionCountState, dCount); 
                if(dCount != 0 && dCount != detectionCountState){
                    ObjectMapper oMapper = super.getObjectMapper();
                    ObjectNode rNode = oMapper.createObjectNode();
                    rNode.put(AppUtils.DETECTION_COUNT, dCount);
                    long time = new Date().getTime();
    
                    rNode.put(AppUtils.ID, time);
                    rNode.put(AppUtils.DEVICE_ID, System.getenv(AppUtils.HOSTNAME));
                    if(writeUnAdulatedImageToDisk){
                        // Write un-boxed image to local file system
                        File uBoxedImageFile = new File(fileDir,  "unAdulteredImage-"+time +".png");
                        BufferedImage uBoxedImage = toBufferedImage(unboxedMat);
                        ImageIO.write(uBoxedImage, "png", uBoxedImageFile);
                        rNode.put(AppUtils.UNADULTERED_IMAGE_FILE_PATH, uBoxedImageFile.getAbsolutePath());
                    }
    
                    Classifications.Classification dClass = detections.best();
                    rNode.put(AppUtils.DETECTED_OBJECT_CLASSIFICATION, dClass.getClassName());
                    rNode.put(AppUtils.DETECTED_OBJECT_PROBABILITY, dClass.getProbability());
    
        
                    // Annotate video capture image w/ any detected objects
                    img.drawBoundingBoxes(detections);
        
                    // Write boxed image to local file system
                    Mat boxedImage = (Mat)img.getWrappedImage();
                    File boxedImageFile = new File(fileDir,  "boxedImage-"+time +".png");
                    BufferedImage bBoxedImage = toBufferedImage(boxedImage);
                    ImageIO.write(bBoxedImage, "png", boxedImageFile);
                    rNode.put(AppUtils.DETECTED_IMAGE_FILE_PATH, boxedImageFile.getAbsolutePath());

                    // Encode binary image to Base64 string and add to payload
                    byte[] base64encodedImage = FileUtils.readFileToByteArray(boxedImageFile);
                    String stringEncodedImage = Base64.getEncoder().encodeToString(base64encodedImage);
                    rNode.put(AppUtils.BASE64_DETECTED_IMAGE, stringEncodedImage);
    
                    bus.publish(AppUtils.LIVE_OBJECT_DETECTION, rNode.toPrettyString());
                    this.detectionCountState = dCount;
                }else if(dCount == 0 && dCount != detectionCountState){
                    this.detectionCountState = 0;
                    log.info("switching detection state back to 0");
                }
            }catch(Exception x){
                x.printStackTrace();
            }finally {
                if(predictor != null)
                    predictor.close();
            }
        }
    }


    @PreDestroy
    public void shutdown() {
        if(vCapture != null && vCapture.isOpened()){
            vCapture.release();
            log.infov("shutdown() video capture device = {0}", this.videoCaptureDevice );
        }
    }

    public ZooModel<?,?> getAppModel(){
        return model;
    }

    public Uni<Response> stopPrediction() {

        log.info("stopPrediction");
        this.predict=false;
        this.detectionCountState = -1;
        Response eRes = Response.status(Response.Status.OK).entity(this.videoCaptureDevice).build();
        return Uni.createFrom().item(eRes);
    }
    
    public Uni<Response> predict() {
        log.info("will now begin to predict on video capture stream");
        this.predict=true;
        Response eRes = Response.status(Response.Status.OK).entity(this.videoCaptureDevice).build();
        return Uni.createFrom().item(eRes);
    }

    private ZooModel<Image, DetectedObjects> loadModel() throws IOException, ModelException {
        Builder<Image, DetectedObjects> builder = Criteria.builder()
            .optApplication(Application.CV.OBJECT_DETECTION)
            .setTypes(Image.class, DetectedObjects.class)
            .optProgress(new ProgressBar());

        String eType = Engine.getDefaultEngineName();
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
    
}
