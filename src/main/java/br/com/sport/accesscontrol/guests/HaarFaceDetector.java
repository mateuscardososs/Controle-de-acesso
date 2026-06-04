package br.com.sport.accesscontrol.guests;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pure-Java Viola-Jones frontal face detector. It parses an OpenCV Haar cascade once at startup
 * and evaluates it with integral images.
 *
 * <p>No native libraries are used, so the detector works unchanged on the Alpine/musl runtime image.
 * Accuracy is sufficient for enrolment photos (single, roughly frontal face on a clean background).
 */
@Component
public class HaarFaceDetector implements FaceDetector {

    private static final String CASCADE_RESOURCE = "face/haarcascade_frontalface_alt.xml";
    /** Down-scale the analysed image so detection cost is bounded regardless of upload resolution. */
    private static final int MAX_DETECTION_DIMENSION = 640;
    /** Pyramid step between successive window sizes. */
    private static final double SCALE_FACTOR = 1.08;
    /** Sliding-window shift, in base-window pixels, scaled with the window. */
    private static final int SHIFT = 2;
    /**
     * Minimum overlapping detections required to keep a face. A lower threshold is intentional here:
     * enrolment validation must not discard a smaller/background face and then approve a group photo.
     * The processor decides whether secondary detections are relevant enough to reject.
     */
    private static final int MIN_NEIGHBORS = 2;

    private final HaarCascadeDetector detector;

    public HaarFaceDetector() {
        this.detector = new HaarCascadeDetector(
                CASCADE_RESOURCE, MAX_DETECTION_DIMENSION, SCALE_FACTOR, SHIFT, MIN_NEIGHBORS);
    }

    @Override
    public List<FaceBox> detect(BufferedImage image) {
        return detector.detect(image);
    }
}
