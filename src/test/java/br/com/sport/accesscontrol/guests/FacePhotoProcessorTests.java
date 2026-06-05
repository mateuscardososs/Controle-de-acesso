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
        FaceDetector twoFrontalFaces = image -> List.of(
                new FaceDetector.FaceBox(80, 120, 160, 160),
                new FaceDetector.FaceBox(360, 120, 160, 160));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, twoFrontalFaces);
        assertThatThrownBy(() -> processor.process(noise(600, 600)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.MULTIPLE_FACES);
    }

    @Test
    void rejectsWhenSecondarySmallerFaceIsRelevant() throws IOException {
        FaceDetector mainFaceWithSmallerSecondary = image -> List.of(
                new FaceDetector.FaceBox(250, 230, 280, 280),
                new FaceDetector.FaceBox(80, 150, 80, 80));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, mainFaceWithSmallerSecondary);

        var v = processor.evaluate(noise(800, 800));

        assertThat(v.approved()).isFalse();
        assertThat(v.faceDetected()).isTrue();
        assertThat(v.singleFace()).isFalse();
        assertThat(v.secondaryFaceDetected()).isTrue();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.MULTIPLE_FACES);
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
        // Centered face covering ~50% of a large, sharp, well-lit image.
        // Uses proportional coordinates so the detector works correctly on compressed re-evaluation images too.
        var processor = processorWithVisibleEyes(proportionalCenteredFace());

        var processed = processor.process(noise(2000, 2000));

        assertThat(processed.extension()).isEqualTo("jpg");
        assertThat(processed.contentType()).isEqualTo("image/jpeg");
        assertThat(processed.savedSizeBytes()).isLessThanOrEqualTo(MAX_BYTES);
        assertThat(processed.bytes().length).isLessThanOrEqualTo(MAX_BYTES);
        assertThat(processed.width()).isLessThanOrEqualTo(640);
        assertThat(processed.height()).isLessThanOrEqualTo(480);
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
    void worstCaseNoise4000x4000AlwaysUnderBudget() throws IOException {
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480);
        var processed = processor.process(noise(4000, 4000));
        assertThat(processed.savedSizeBytes())
                .as("4000x4000 noise must be <= %d bytes", MAX_BYTES)
                .isLessThanOrEqualTo(MAX_BYTES);
        assertThat(processed.bytes().length)
                .as("4000x4000 noise bytes array must be <= %d bytes", MAX_BYTES)
                .isLessThanOrEqualTo(MAX_BYTES);
    }

    @Test
    void extremeLargeFileInputsAlwaysCompressUnderBudget() throws IOException {
        // Simulates real-world inputs: camera photos that can be 500KB to several MB.
        // The processor must always reduce the output to <= 99328 bytes regardless of input size.
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480);
        for (int[] dims : new int[][] {{720, 960}, {1080, 1440}, {2160, 2880}, {3000, 4000}}) {
            int w = dims[0];
            int h = dims[1];
            var input = noise(w, h);
            assertThat(input.length)
                    .as("input %dx%d must be large enough to stress the compressor", w, h)
                    .isGreaterThan(100_000);
            var processed = processor.process(input);
            assertThat(processed.savedSizeBytes())
                    .as("output for %dx%d input (inputBytes=%d) must be <= %d", w, h, input.length, MAX_BYTES)
                    .isLessThanOrEqualTo(MAX_BYTES);
            assertThat(processed.bytes().length)
                    .as("bytes array for %dx%d input must be <= %d", w, h, MAX_BYTES)
                    .isLessThanOrEqualTo(MAX_BYTES);
        }
    }

    @Test
    void processorBlocksWhenCompressedExceedsLimit() throws IOException {
        // maxBytes=1 forces the byte budget check to fail. The face+eye detectors must report a
        // valid photo so that COMPRESSION_FAILED is the actual rejection reason (not quality/geometry).
        // Legacy (no-detector) mode skips the byte-budget gate, so a detector is required here.
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(100, 100, 200, 200));
        var processor = new FacePhotoProcessor(1, 640, 480, centeredFace, visibleEyes());
        assertThatThrownBy(() -> processor.process(noise(400, 400)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.COMPRESSION_FAILED);
    }

    @Test
    void adaptiveCompressionRejectsWhenQualityDegrades() throws IOException {
        // Eye detector works on large eye regions (initial check on 2000×2000) but returns empty
        // when the region is < 300px wide (all 6 compressed sizes).  Every step that fits within
        // the byte budget still fails quality re-evaluation → COMPRESSION_DEGRADED, not approved.
        EyeDetector eyesDegradedOnCompression = image -> {
            if (image.getWidth() < 300) return List.of();
            int eyeWidth  = Math.max(8, (int) Math.round(image.getWidth()  * 0.14));
            int eyeHeight = Math.max(6, (int) Math.round(image.getHeight() * 0.18));
            int y = Math.max(0, (int) Math.round(image.getHeight() * 0.28));
            return List.of(
                    new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.20), y, eyeWidth, eyeHeight),
                    new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.64), y, eyeWidth, eyeHeight));
        };
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, proportionalCenteredFace(), eyesDegradedOnCompression);

        assertThatThrownBy(() -> processor.process(noise(2000, 2000)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.COMPRESSION_DEGRADED);
    }

    @Test
    void compressionDegradedExposesExplicitFailingCheck() throws IOException {
        // Same degraded scenario, but assert the structured result so the UI has a concrete red check.
        EyeDetector eyesDegradedOnCompression = image -> {
            if (image.getWidth() < 300) return List.of();
            int eyeWidth  = Math.max(8, (int) Math.round(image.getWidth()  * 0.14));
            int eyeHeight = Math.max(6, (int) Math.round(image.getHeight() * 0.18));
            int y = Math.max(0, (int) Math.round(image.getHeight() * 0.28));
            return List.of(
                    new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.20), y, eyeWidth, eyeHeight),
                    new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.64), y, eyeWidth, eyeHeight));
        };
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, proportionalCenteredFace(), eyesDegradedOnCompression);

        var v = processor.evaluate(noise(2000, 2000));

        assertThat(v.approved()).isFalse();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.COMPRESSION_DEGRADED);
        assertThat(v.rejectionReason()).isEqualTo(FacePhotoRejectedException.COMPRESSION_DEGRADED);
        assertThat(v.qualityAfterCompressionOk()).isFalse();
        assertThat(v.compressionOk()).isFalse();
        assertThat(v.compressionAttempts()).isEqualTo(6);
        // The original-photo checks were fine — only post-compression quality failed.
        assertThat(v.faceDetected()).isTrue();
        assertThat(v.eyesVisibleOk()).isTrue();
    }

    @Test
    void rejectedResultAlwaysHasAtLeastOneFailingCheck() throws IOException {
        // Invariant: approved=false must never coincide with every check being OK (no contradictory UI).
        record Case(String name, FacePhotoValidationSupplier supplier) {}
        java.util.List<Case> cases = java.util.List.of(
                new Case("no face", () -> new FacePhotoProcessor(MAX_BYTES, 640, 480, image -> List.of())
                        .evaluate(noise(600, 600))),
                new Case("dark", () -> new FacePhotoProcessor(MAX_BYTES, 640, 480,
                        image -> List.of(new FaceDetector.FaceBox(220, 220, 200, 200)))
                        .evaluate(solid(600, 600, new Color(12, 12, 12)))),
                new Case("hidden eyes", () -> new FacePhotoProcessor(MAX_BYTES, 640, 480,
                        proportionalCenteredFace(), image -> List.of())
                        .evaluate(noise(800, 800)))
        );

        for (var c : cases) {
            var v = c.supplier().get();
            assertThat(v.approved()).as("%s should be rejected", c.name()).isFalse();
            boolean anyFalse = !v.faceDetected() || !v.singleFace() || !v.brightnessOk() || !v.sharpnessOk()
                    || !v.contrastOk() || !v.centeredOk() || !v.faceSizeOk() || !v.faceFullyVisibleOk()
                    || !v.eyesVisibleOk() || !v.qualityAfterCompressionOk() || !v.finalCompressedSizeOk();
            assertThat(anyFalse).as("%s: a rejected photo must surface at least one failing check", c.name()).isTrue();
        }
    }

    @FunctionalInterface
    private interface FacePhotoValidationSupplier {
        FacePhotoProcessor.FacePhotoValidation get() throws IOException;
    }

    @Test
    void evaluateReturnsAllChecksApprovedForValidCenteredFace() throws IOException {
        var processor = processorWithVisibleEyes(proportionalCenteredFace());

        var v = processor.evaluate(noise(2000, 2000));

        assertThat(v.approved()).isTrue();
        assertThat(v.message()).isEqualTo(FacePhotoProcessor.APPROVED_MESSAGE);
        assertThat(v.faceDetected()).isTrue();
        assertThat(v.singleFace()).isTrue();
        assertThat(v.secondaryFaceDetected()).isFalse();
        assertThat(v.brightnessOk()).isTrue();
        assertThat(v.sharpnessOk()).isTrue();
        assertThat(v.contrastOk()).isTrue();
        assertThat(v.centeredOk()).isTrue();
        assertThat(v.faceSizeOk()).isTrue();
        assertThat(v.sizeOk()).isTrue();
        assertThat(v.eyesVisibleOk()).isTrue();
        assertThat(v.finalCompressedSizeOk()).isTrue();
        assertThat(v.compressedSizeBytes()).isLessThanOrEqualTo(MAX_BYTES);
        assertThat(v.maxAllowedBytes()).isEqualTo(MAX_BYTES);
    }

    @Test
    void evaluateRejectsWhenEyesAreNotVisible() throws IOException {
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(250, 250, 300, 300));
        EyeDetector hiddenEyes = image -> List.of();
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, centeredFace, hiddenEyes);

        var v = processor.evaluate(noise(800, 800));

        assertThat(v.faceDetected()).isTrue();
        assertThat(v.singleFace()).isTrue();
        assertThat(v.eyesVisibleOk()).isFalse();
        assertThat(v.approved()).isFalse();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.EYES_NOT_VISIBLE);
    }

    @Test
    void finalUploadRejectsWhenEyesAreNotVisible() throws IOException {
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(250, 250, 300, 300));
        EyeDetector hiddenEyes = image -> List.of();
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, centeredFace, hiddenEyes);

        assertThatThrownBy(() -> processor.process(noise(800, 800)))
                .isInstanceOf(FacePhotoRejectedException.class)
                .hasMessage(FacePhotoRejectedException.EYES_NOT_VISIBLE);
    }

    @Test
    void rejectsWhenEyePairIsTooTilted() throws IOException {
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(250, 250, 300, 300));
        EyeDetector tiltedEyes = image -> List.of(
                new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.20), 10, 34, 28),
                new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.64), 80, 34, 28));
        var processor = new FacePhotoProcessor(MAX_BYTES, 640, 480, centeredFace, tiltedEyes);

        var v = processor.evaluate(noise(800, 800));

        assertThat(v.eyesVisibleOk()).isFalse();
        assertThat(v.approved()).isFalse();
        assertThat(v.message()).isEqualTo(FacePhotoRejectedException.EYES_NOT_VISIBLE);
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
        assertThat(v.secondaryFaceDetected()).isTrue();
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
                204800, 640, 480, 0.6, 0.18, 0.25, 50, 235, 55, 20,
                0.065, 0.12, true, 12, 0.25, image -> List.of(), image -> List.of(), env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99328");
    }

    @Test
    void bootSucceedsInProdAtSafeLimit() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatCode(() -> new FacePhotoProcessor(
                99328, 640, 480, 0.6, 0.18, 0.25, 50, 235, 55, 20,
                0.065, 0.12, true, 12, 0.25, image -> List.of(), image -> List.of(), env))
                .doesNotThrowAnyException();
    }

    @Test
    void bootDoesNotGuardOutsideProd() {
        var env = new MockEnvironment(); // no "prod" profile
        assertThatCode(() -> new FacePhotoProcessor(
                204800, 640, 480, 0.6, 0.18, 0.25, 50, 235, 55, 20,
                0.065, 0.12, true, 12, 0.25, image -> List.of(), image -> List.of(), env))
                .doesNotThrowAnyException();
    }

    @Test
    void cleanFaceWithTexturedLowerRegionIsNotFlaggedAsOccluded() throws IOException {
        // Centered face whose whole box is textured (no covering) -> occlusion heuristic stays OK.
        // Uses proportional detector so re-evaluation on compressed images also succeeds.
        var processor = processorWithVisibleEyes(proportionalCenteredFace());

        var v = processor.evaluate(noise(800, 800));

        assertThat(v.faceFullyVisibleOk()).isTrue();
        assertThat(v.approved()).isTrue();
    }

    @Test
    void faceWithFlatCoveredLowerRegionIsRejectedAsOccluded() throws IOException {
        // Same valid box, but the lower part of the face is a flat patch (e.g. a hand over mouth/chin).
        FaceDetector centeredFace = image -> List.of(new FaceDetector.FaceBox(250, 250, 300, 300));
        var processor = processorWithVisibleEyes(centeredFace);

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

    private static FacePhotoProcessor processorWithVisibleEyes(FaceDetector faceDetector) {
        return new FacePhotoProcessor(MAX_BYTES, 640, 480, faceDetector, visibleEyes());
    }

    /** Face detector that always returns a proportionally-centered 50%-of-image face box.
     *  Works correctly whether the detector is called on the original or on a compressed re-evaluation image. */
    private static FaceDetector proportionalCenteredFace() {
        return image -> {
            int size = Math.min(image.getWidth(), image.getHeight()) / 2;
            return List.of(new FaceDetector.FaceBox(
                    (image.getWidth() - size) / 2,
                    (image.getHeight() - size) / 2,
                    size, size));
        };
    }

    private static EyeDetector visibleEyes() {
        return image -> {
            int eyeWidth = Math.max(8, (int) Math.round(image.getWidth() * 0.14));
            int eyeHeight = Math.max(6, (int) Math.round(image.getHeight() * 0.18));
            int y = Math.max(0, (int) Math.round(image.getHeight() * 0.28));
            return List.of(
                    new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.20), y, eyeWidth, eyeHeight),
                    new FaceDetector.FaceBox((int) Math.round(image.getWidth() * 0.64), y, eyeWidth, eyeHeight));
        };
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
