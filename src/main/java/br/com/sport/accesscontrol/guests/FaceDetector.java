package br.com.sport.accesscontrol.guests;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Detects human faces in an image. Implementations must be deterministic and free of native
 * dependencies so the pipeline runs on the slim Alpine runtime image.
 */
public interface FaceDetector {

    /**
     * @return the detected faces, in coordinates of the supplied image. Never {@code null}.
     */
    List<FaceBox> detect(BufferedImage image);

    /**
     * A detected face rectangle, in pixel coordinates of the analysed image.
     */
    record FaceBox(int x, int y, int width, int height) {

        int centerX() {
            return x + width / 2;
        }

        int centerY() {
            return y + height / 2;
        }

        int area() {
            return width * height;
        }
    }
}
