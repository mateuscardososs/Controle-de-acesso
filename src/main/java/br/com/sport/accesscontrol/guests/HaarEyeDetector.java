package br.com.sport.accesscontrol.guests;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pure-Java Haar eye detector used only inside the already detected face region.
 */
@Component
public class HaarEyeDetector implements EyeDetector {

    private static final String CASCADE_RESOURCE = "face/haarcascade_eye.xml";
    private static final int MAX_DETECTION_DIMENSION = 360;
    private static final double SCALE_FACTOR = 1.05;
    private static final int SHIFT = 1;
    private static final int MIN_NEIGHBORS = 2;

    private final HaarCascadeDetector detector;

    public HaarEyeDetector() {
        this.detector = new HaarCascadeDetector(
                CASCADE_RESOURCE, MAX_DETECTION_DIMENSION, SCALE_FACTOR, SHIFT, MIN_NEIGHBORS);
    }

    @Override
    public List<FaceDetector.FaceBox> detect(BufferedImage image) {
        return detector.detect(image);
    }
}
