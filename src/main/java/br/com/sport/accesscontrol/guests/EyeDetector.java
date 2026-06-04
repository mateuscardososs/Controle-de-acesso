package br.com.sport.accesscontrol.guests;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Detects visible eyes in an image region. Coordinates are relative to the supplied image.
 */
public interface EyeDetector {

    List<FaceDetector.FaceBox> detect(BufferedImage image);
}
