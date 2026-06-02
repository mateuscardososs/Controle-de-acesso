package br.com.sport.accesscontrol.guests;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class HaarFaceDetectorTests {

    @Test
    void loadsCascadeAndRunsWithoutError() {
        assertThatCode(HaarFaceDetector::new).doesNotThrowAnyException();
    }

    @Test
    void reportsNoFaceOnBlankImage() {
        var detector = new HaarFaceDetector();
        var blank = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
        var graphics = blank.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 320, 320);
        graphics.dispose();

        assertThat(detector.detect(blank)).isEmpty();
    }

    @Test
    void handlesTinyImagesGracefully() {
        var detector = new HaarFaceDetector();
        var tiny = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        assertThat(detector.detect(tiny)).isEmpty();
        assertThat(detector.detect(null)).isEmpty();
    }
}
