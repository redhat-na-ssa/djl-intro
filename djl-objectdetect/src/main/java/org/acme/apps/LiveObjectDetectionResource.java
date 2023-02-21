package org.acme.apps;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;

import javax.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;

import org.acme.AppUtils;
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
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.repository.zoo.Criteria.Builder;
import ai.djl.training.util.ProgressBar;

import java.awt.image.BufferedImage;


@LookupIfProperty(name = "org.acme.djl.resource", stringValue = "LiveObjectDetectionResource")
@ApplicationScoped
public class LiveObjectDetectionResource extends BaseResource implements IApp {

    private static final String PYTORCH="pytorch";
    private static final String TENSORFLOW="tensorflow";
    private static final String MXNET="mxnet";

    private static Logger log = Logger.getLogger("LiveObjectDetectionResource");

    @ConfigProperty(name = "org.acme.objectdetection.capture.duration.millis", defaultValue = "10000")
    int captureMillis;

    @ConfigProperty(name = "org.acme.objecdetection.image.directory", defaultValue="/tmp/org.acme.objectdetection")
    String oDetectionDirString;

    @ConfigProperty(name = "org.acme.objectdetection.video.capture.device.id", defaultValue = "0")
    int videoCaptureDevice;

    @ConfigProperty(name = "org.acme.objectdetection.write.unadulted.image.to.disk", defaultValue = "False")
    boolean writeUnAdulatedImageToDisk;

    @Inject
    CriteriaFilter cFilters;

    @Inject
    EventBus bus;

    ZooModel<Image, DetectedObjects> model;
    File fileDir;

    boolean continueToCapture = true;
    
    public void startResource() {
        
        super.start();
        VideoCapture vCapture = null;
        try {
            
            model = loadModel();
            
            fileDir = new File(oDetectionDirString);
            if(!fileDir.exists())
            fileDir.mkdirs();
            
            OpenCV.loadShared();  // Link org.opencv.* related JNI classes (as found in: opencv-4.5.1-2.jar )

            vCapture = new VideoCapture(videoCaptureDevice);
            if(!vCapture.isOpened())
                throw new RuntimeException("Unable to access video capture device w/ id = " + this.videoCaptureDevice);
                
            log.infov("start() video capture device = {0} is open =  {1}", this.videoCaptureDevice, vCapture.isOpened() );
        }catch(Exception x){
            throw new RuntimeException(x);
        }finally {
            if(vCapture != null && vCapture.isOpened())
                vCapture.release();
        }
        
    }

    public ZooModel<?,?> getAppModel(){
        return model;
    }
    
    public Uni<Response> predict() {
        Predictor<Image, DetectedObjects> predictor = null;
        VideoCapture vCapture = null;
        try{
            
            vCapture = new VideoCapture(videoCaptureDevice);
            if (!vCapture.isOpened()) {
                throw new RuntimeException("No camera detected");
            }

            // Capture image from webcam
            Mat unboxedMat = new Mat();
            boolean captured = false;
            while(continueToCapture){
                captured = vCapture.read(unboxedMat);
                if (captured)
                    break;
                
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                    // ignore
                }
            }

            // Detect presence of object in webcam captured image
            ImageFactory factory = ImageFactory.getInstance();
            Image img = factory.fromImage(unboxedMat);
            predictor = model.newPredictor();
            DetectedObjects detections = predictor.predict(img);
            int dCount = detections.getNumberOfObjects();

            ObjectMapper oMapper = super.getObjectMapper();
            ObjectNode rNode = oMapper.createObjectNode();
            rNode.put("detectionCount", dCount);

            if(dCount > 0){
                long time = new Date().getTime();

                rNode.put(AppUtils.ID, time);
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

                String dJson = detections.toJson();
                //log.infov("detection = {0]", dJson);
    
                // Annotate video capture image w/ any detected objects
                img.drawBoundingBoxes(detections);
    
                // Write boxed image to local file system
                Mat boxedImage = (Mat)img.getWrappedImage();
                File boxedImageFile = new File(fileDir,  "boxedImage-"+time +".png");
                BufferedImage bBoxedImage = toBufferedImage(boxedImage);
                ImageIO.write(bBoxedImage, "png", boxedImageFile);
                rNode.put(AppUtils.DETECTED_IMAGE_FILE_PATH, boxedImageFile.getAbsolutePath());

                bus.publish(AppUtils.LIVE_OBJECT_DETECTION, rNode.toPrettyString());
            }

            Response eRes = Response.status(Response.Status.OK).entity(rNode.toPrettyString()).build();
            return Uni.createFrom().item(eRes);
        }catch(Exception x){
            Response eRes = Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(x.getMessage()).build();
            return Uni.createFrom().item(eRes);
        }finally {
            if(predictor != null)
                predictor.close();
            if(vCapture != null && vCapture.isOpened())
                vCapture.release();
        }
        

    }

    private ZooModel<Image, DetectedObjects> loadModel() throws IOException, ModelException {
        Builder<Image, DetectedObjects> builder = Criteria.builder()
            .optApplication(Application.CV.OBJECT_DETECTION)
            .setTypes(Image.class, DetectedObjects.class)
            .optProgress(new ProgressBar());

        String eType = Engine.getInstance().getDefaultEngineName();
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
