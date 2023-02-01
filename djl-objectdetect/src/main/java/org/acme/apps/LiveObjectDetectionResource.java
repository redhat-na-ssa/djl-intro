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
import io.smallrye.config.ConfigMapping;
import io.smallrye.mutiny.Uni;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import nu.pattern.OpenCV;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
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

    private static Logger log = Logger.getLogger("LiveObjectDetectionResource");

    @ConfigProperty(name = "org.acme.objectdetection.capture.duration.millis", defaultValue = "10000")
    int captureMillis;

    @ConfigProperty(name = "org.acme.objecdetection.image.directory", defaultValue="/tmp/org.acme.objectdetection")
    String oDetectionDirString;

    @Inject
    CriteriaFilter cFilters;

    ZooModel<Image, DetectedObjects> model;
    File fileDir;
    
    
    public void startResource() {
        
        log.info("start()");
        super.start();
        try {
            
            model = loadModel();

            fileDir = new File(oDetectionDirString);
            if(!fileDir.exists())
                fileDir.mkdirs();
            
        }catch(Exception x){
            throw new RuntimeException(x);
        }
        
    }
    
    public Uni<Response> predict() {
        Predictor<Image, DetectedObjects> predictor = null;
        VideoCapture vCapture = null;
        String rMessage = "Failed to capture image from WebCam";
        try{
            predictor = model.newPredictor();
            
            OpenCV.loadShared();
            vCapture = new VideoCapture(0);
            if (!vCapture.isOpened()) {
                throw new RuntimeException("No camera detected");
            }

            // Capture image from webcam
            Mat unboxedMat = new Mat();
            boolean captured = false;
            for (int i = 0; i < 10; ++i) {
                captured = vCapture.read(unboxedMat);
                if (captured)
                    break;
                
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignore) {
                    // ignore
                }
            }

            // Write un-boxed image to local file system
            StringBuilder rBuilder = new StringBuilder();
            File uBoxedImageFile = new File(fileDir,  new Date().getTime() +".png");
            BufferedImage uBoxedImage = toBufferedImage(unboxedMat);
            ImageIO.write(uBoxedImage, "png", uBoxedImageFile);
            rBuilder.append("UnBoxed file = "+uBoxedImageFile.getAbsolutePath()+"\n");

            // Detect presence of object in webcam captured image and draw a box around that object
            ImageFactory factory = ImageFactory.getInstance();
            Image img = factory.fromImage(unboxedMat);
            DetectedObjects detections = predictor.predict(img);
            img.drawBoundingBoxes(detections);

            // Write boxed image to local file system
            Mat boxedImage = (Mat)img.getWrappedImage();
            File boxedImageFile = new File(fileDir,  new Date().getTime() +".png");
            BufferedImage bBoxedImage = toBufferedImage(boxedImage);
            ImageIO.write(bBoxedImage, "png", boxedImageFile);
            rBuilder.append("Boxed file = "+boxedImageFile.getAbsolutePath()+"\n");
            rMessage = rBuilder.toString();

            Response eRes = Response.status(Response.Status.OK).entity(rMessage).build();
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

        Map<String, String> filters = cFilters.filters();
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
