package br.com.sport.accesscontrol.guests;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FacePhotoProcessorTests {

    private static final int MAX_BYTES = 99328; // 97 KB safe target for Intelbras controllers.

    @Test
    void rejectsWhenNoFaceDetected() throws IOException {
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, image -> List.of());
        assertThatThrownBy(() -> processor.process(noise(600, 600)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.NO_FACE);
    }

    @Test
    void rejectsWhenMoreThanOneFaceDetected() throws IOException {
        FaceDetector twoFaces = image -> List.of(
                new FaceDetector.FaceBox(80, 120, 160, 160),
                new FaceDetector.FaceBox(360, 120, 160, 160));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, twoFaces);
        assertThatThrownBy(() -> processor.process(noise(600, 600)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.MULTIPLE_FACES);
    }

    @Test
    void rejectsWhenFaceTooSmall() throws IOException {
        // 30px face in a 600px image => ~5% ratio, below the 12% minimum.
        FaceDetector tinyFace = image -> List.of(new FaceDetector.FaceBox(285, 285, 30, 30));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, tinyFace);
        assertThatThrownBy(() -> processor.process(noise(600, 600)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.FACE_TOO_SMALL_OR_OFFSET);
    }

    @Test
    void rejectsWhenFaceOffCenter() throws IOException {
        FaceDetector cornerFace = image -> List.of(new FaceDetector.FaceBox(0, 0, 160, 160));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, cornerFace);
        assertThatThrownBy(() -> processor.process(noise(600, 600)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.FACE_TOO_SMALL_OR_OFFSET);
    }

    @Test
    void rejectsWhenImageTooDark() throws IOException {
        FaceDetector anyFace = image -> List.of(new FaceDetector.FaceBox(220, 220, 200, 200));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, anyFace);
        assertThatThrownBy(() -> processor.process(solid(600, 600, new Color(12, 12, 12))))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.LOW_QUALITY);
    }

    @Test
    void rejectsWhenImageBlurryOrFlat() throws IOException {
        FaceDetector anyFace = image -> List.of(new FaceDetector.FaceBox(220, 220, 200, 200));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, anyFace);
        // A flat mid-gray image has zero Laplacian variance => fails the sharpness gate.
        assertThatThrownBy(() -> processor.process(solid(600, 600, new Color(128, 128, 128))))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.LOW_QUALITY);
    }

    @Test
    void validFaceIsCroppedResizedAndCompressedUnderBudget() throws IOException {
        // Centered face covering ~33% of a large, sharp, well-lit image.
        FaceDetector centeredFace = image ->
                List.of(new FaceDetector.FaceBox(700, 700, 600, 600));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, centeredFace);

        var processed = processor.process(noise(2000, 2000));

        assertThat(processed.extension()).isEqualTo("jpg");
        assertThat(processed.contentType()).isEqualTo("image/jpeg");
        assertThat(processed.savedSizeBytes()).isLessThanOrEqualTo(MAX_BYTES);
        assertThat(processed.bytes().length).isLessThanOrEqualTo(MAX_BYTES);
        assertThat(processed.width()).isLessThanOrEqualTo(640);
        assertThat(processed.height()).isLessThanOrEqualTo(480);
        // The crop around a centered 600px face must be smaller than the 2000px source.
        var decoded = ImageIO.read(new ByteArrayInputStream(processed.bytes()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isLessThanOrEqualTo(640);
    }

    @Test
    void worstCaseNoiseAlwaysCompressesUnderBudget() throws IOException {
        // No detector => normalisation + compression only; stresses the byte-budget guarantee.
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480);
        for (int dimension : new int[] {800, 1600, 3000}) {
            var processed = processor.process(noise(dimension, dimension));
            assertThat(processed.savedSizeBytes())
                    .as("noise %dx%d must stay under %d bytes", dimension, dimension, MAX_BYTES)
                    .isLessThanOrEqualTo(MAX_BYTES);
        }
    }

    @Test
    void evaluateReturnsAllChecksApprovedForValidCenteredFace() throws IOException {
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(700, 700, 600, 600));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, centeredFace);

        var v = processor.evaluate(noise(2000, 2000));

        assertThat(v.approved()).isTrue();
        assertThat(v.message()).isEqualTo(FacePhotoProcessor.APPROVED_MESSAGE);
        assertThat(v.faceDetected()).isTrue();
        assertThat(v.singleFace()).isTrue();
        assertThat(v.brightnessOk()).isTrue();
        assertThat(v.sharpnessOk()).isTrue();
        assertThat(v.contrastOk()).isTrue();
        assertThat(v.centeredOk()).isTrue();
        assertThat(v.sizeOk()).isTrue();
        assertThat(v.compressedSizeBytes()).isLessThanOrEqualTo(MAX_BYTES);
        assertThat(v.maxAllowedBytes()).isEqualTo(MAX_BYTES);
    }

    @Test
    void evaluateReportsNoFaceWithoutThrowing() throws IOException {
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, image -> List.of());

        var v = processor.evaluate(noise(600, 600));

        assertThat(v.approved()).isFalse();
        assertThat(v.faceDetected()).isFalse();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.NO_FACE);
    }

    @Test
    void evaluateReportsMultipleFaces() throws IOException {
        FaceDetector twoFaces = image -> List.of(
                new FaceDetector.FaceBox(80, 120, 160, 160),
                new FaceDetector.FaceBox(360, 120, 160, 160));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, twoFaces);

        var v = processor.evaluate(noise(600, 600));

        assertThat(v.approved()).isFalse();
        assertThat(v.singleFace()).isFalse();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.MULTIPLE_FACES);
    }

    @Test
    void evaluateReportsLowQualityForDarkImage() throws IOException {
        FaceDetector anyFace = image -> List.of(new FaceDetector.FaceBox(220, 220, 200, 200));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, anyFace);

        var v = processor.evaluate(solid(600, 600, new Color(12, 12, 12)));

        assertThat(v.approved()).isFalse();
        assertThat(v.brightnessOk()).isFalse();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.LOW_QUALITY);
    }

    @Test
    void bootFailsInProdWhenLimitExceedsIntelbrasCeiling() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> new FacePhotoProcessor(
                204800, 640, 480, 0.6, 0.12, 0.34, 50, 235, 55, 20, true, 12, 0.25, image -> List.of(), env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99328");
    }

    @Test
    void bootSucceedsInProdAtSafeLimit() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatCode(() -> new FacePhotoProcessor(
                99328, 640, 480, 0.6, 0.12, 0.34, 50, 235, 55, 20, true, 12, 0.25, image -> List.of(), env))
                .doesNotThrowAnyException();
    }

    @Test
    void bootDoesNotGuardOutsideProd() {
        var env = new MockEnvironment(); // no "prod" profile
        assertThatCode(() -> new FacePhotoProcessor(
                204800, 640, 480, 0.6, 0.12, 0.34, 50, 235, 55, 20, true, 12, 0.25, image -> List.of(), env))
                .doesNotThrowAnyException();
    }

    @Test
    void cleanFaceWithTexturedLowerRegionIsNotFlaggedAsOccluded() throws IOException {
        // Centered face whose whole box is textured (no covering) -> occlusion heuristic stays OK.
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(250, 250, 300, 300));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, centeredFace);

        var v = processor.evaluate(noise(800, 800));

        assertThat(v.faceFullyVisibleOk()).isTrue();
        assertThat(v.approved()).isTrue();
    }

    @Test
    void faceWithFlatCoveredLowerRegionIsRejectedAsOccluded() throws IOException {
        // Same valid box, but the lower part of the face is a flat patch (e.g. a hand over mouth/chin).
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(250, 250, 300, 300));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, centeredFace);

        var image = noiseImage(800, 800);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(120, 120, 120)); // flat, textureless lower-face region
        graphics.fillRect(250, 415, 300, 135);       // lower ~45% of the face box
        graphics.dispose();

        var v = processor.evaluate(toPng(image));

        assertThat(v.faceDetected()).isTrue();
        assertThat(v.sizeOk()).isTrue();
        assertThat(v.centeredOk()).isTrue();
        assertThat(v.faceFullyVisibleOk()).as("flat lower face should be flagged as occluded").isFalse();
        assertThat(v.approved()).isFalse();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.FACE_OCCLUDED);
    }

    private static byte[] noise(int width, int height) throws IOException {
        return toPng(noiseImage(width, height));
    }

    private static BufferedImage noiseImage(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }

    private static byte[] solid(int width, int height, Color color) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return toPng(image);
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
