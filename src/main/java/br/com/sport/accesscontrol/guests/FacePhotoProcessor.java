package br.com.sport.accesscontrol.guests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/**
 * Validates and normalises a face photo before it is stored / synced to an Intelbras controller.
 *
 * <p>Pipeline: read → quality gate (brightness + sharpness + contrast) → face detection (exactly one
 * face, large enough and centred) → crop around the face with margin → resize within the controller
 * limit → JPEG compression below the byte budget (97&nbsp;KB by default).
 *
 * <p>{@link #evaluate(byte[])} runs every check and returns a structured {@link FacePhotoValidation}
 * without throwing — it is the single source of truth used by both the preview endpoint and the final
 * upload. {@link #process(byte[])} reuses {@code evaluate} and throws {@link FacePhotoRejectedException}
 * (HTTP 422) when the photo is not approved, so the stored/synced image is always validated.
 *
 * <p>When no {@link FaceDetector} is supplied (legacy/utility constructors) the validation steps are
 * skipped and the processor only normalises and compresses, preserving the previous behaviour.
 */
@Component
public class FacePhotoProcessor {

    private static final Logger log = LoggerFactory.getLogger(FacePhotoProcessor.class);

    public static final String APPROVED_MESSAGE = "Foto validada com sucesso.";
    /** Intelbras controllers accept ~100 KB; refuse to start in prod above this hard ceiling. */
    public static final int INTELBRAS_HARD_LIMIT_BYTES = 100_000;

    private final int maxBytes;
    private final int maxWidth;
    private final int maxHeight;
    private final double faceMarginRatio;
    private final double minFaceRatio;
    private final double maxCenterOffsetRatio;
    private final int minBrightness;
    private final int maxBrightness;
    private final double minSharpness;
    private final double minContrast;
    private final double secondaryFaceMinRatio;
    private final double secondaryFaceMinAreaRatio;
    private final boolean occlusionCheckEnabled;
    private final double occlusionMinLowerTexture;
    private final double occlusionLowerUpperRatio;
    private final FaceDetector faceDetector;

    @Autowired
    public FacePhotoProcessor(
            @Value("${app.uploads.faces-max-bytes:99328}") int maxBytes,
            @Value("${app.uploads.faces-max-width:640}") int maxWidth,
            @Value("${app.uploads.faces-max-height:480}") int maxHeight,
            @Value("${app.uploads.faces.face-margin-ratio:0.6}") double faceMarginRatio,
            @Value("${app.uploads.faces.min-face-ratio:0.18}") double minFaceRatio,
            @Value("${app.uploads.faces.max-center-offset-ratio:0.25}") double maxCenterOffsetRatio,
            @Value("${app.uploads.faces.min-brightness:50}") int minBrightness,
            @Value("${app.uploads.faces.max-brightness:235}") int maxBrightness,
            @Value("${app.uploads.faces.min-sharpness:55}") double minSharpness,
            @Value("${app.uploads.faces.min-contrast:20}") double minContrast,
            @Value("${app.uploads.faces.secondary-face-min-ratio:0.065}") double secondaryFaceMinRatio,
            @Value("${app.uploads.faces.secondary-face-min-area-ratio:0.12}") double secondaryFaceMinAreaRatio,
            @Value("${app.uploads.faces.occlusion-check-enabled:true}") boolean occlusionCheckEnabled,
            @Value("${app.uploads.faces.occlusion-min-lower-texture:12}") double occlusionMinLowerTexture,
            @Value("${app.uploads.faces.occlusion-lower-upper-ratio:0.25}") double occlusionLowerUpperRatio,
            FaceDetector faceDetector,
            Environment environment) {
        this.maxBytes = maxBytes;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.faceMarginRatio = faceMarginRatio;
        this.minFaceRatio = minFaceRatio;
        this.maxCenterOffsetRatio = maxCenterOffsetRatio;
        this.minBrightness = minBrightness;
        this.maxBrightness = maxBrightness;
        this.minSharpness = minSharpness;
        this.minContrast = minContrast;
        this.secondaryFaceMinRatio = secondaryFaceMinRatio;
        this.secondaryFaceMinAreaRatio = secondaryFaceMinAreaRatio;
        this.occlusionCheckEnabled = occlusionCheckEnabled;
        this.occlusionMinLowerTexture = occlusionMinLowerTexture;
        this.occlusionLowerUpperRatio = occlusionLowerUpperRatio;
        this.faceDetector = faceDetector;

        log.info("FACE_UPLOAD_CONFIG maxBytes={} maxWidth={} maxHeight={} minBrightness={} maxBrightness={} minSharpness={} minContrast={} minFaceRatio={} maxCenterOffsetRatio={} faceMarginRatio={} secondaryFaceMinRatio={} secondaryFaceMinAreaRatio={}",
                maxBytes, maxWidth, maxHeight, minBrightness, maxBrightness, minSharpness, minContrast,
                minFaceRatio, maxCenterOffsetRatio, faceMarginRatio, secondaryFaceMinRatio, secondaryFaceMinAreaRatio);

        boolean prod = environment != null && Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (prod && maxBytes > INTELBRAS_HARD_LIMIT_BYTES) {
            throw new IllegalStateException(
                    "FACE_UPLOAD_MAX_BYTES=" + maxBytes + " excede o limite seguro de " + INTELBRAS_HARD_LIMIT_BYTES
                            + " bytes para Intelbras em produção. Ajuste para <= 99328 (97 KB).");
        }
    }

    /**
     * Legacy/utility constructor: normalisation + compression only, no face/quality validation.
     * Package-private so it is used only by tests / same-package helpers and never picked by Spring.
     */
    FacePhotoProcessor(int maxBytes, int maxWidth, int maxHeight) {
        this(maxBytes, maxWidth, maxHeight, 0.6, 0.18, 0.25, 50, 235, 55, 20,
                0.065, 0.12, true, 12, 0.25, null, null);
    }

    /**
     * Convenience constructor for tests: full pipeline with default thresholds and a given detector.
     * Package-private so Spring sees a single autowirable (annotated) constructor.
     */
    FacePhotoProcessor(int maxBytes, int maxWidth, int maxHeight, FaceDetector faceDetector) {
        this(maxBytes, maxWidth, maxHeight, 0.6, 0.18, 0.25, 50, 235, 55, 20,
                0.065, 0.12, true, 12, 0.25, faceDetector, null);
    }

    /** Final upload path: validate and return the processed photo, throwing 422 when not approved. */
    public ProcessedFacePhoto process(byte[] originalBytes) {
        var validation = evaluate(originalBytes);
        if (!validation.approved()) {
            throw new FacePhotoRejectedException(validation.message());
        }
        return new ProcessedFacePhoto(
                validation.bytes(),
                validation.extension(),
                validation.contentType(),
                validation.width(),
                validation.height(),
                validation.originalSizeBytes(),
                validation.compressedSizeBytes(),
                validation.compressed());
    }

    /**
     * Runs the full validation pipeline and returns every check result plus the compressed bytes.
     * Never throws on a "bad photo" — only on an unreadable/invalid image. Does not persist anything.
     */
    public FacePhotoValidation evaluate(byte[] originalBytes) {
        if (originalBytes == null || originalBytes.length == 0) {
            throw new IllegalArgumentException("Face photo is required.");
        }
        var source = read(originalBytes);
        var rgb = toRgb(source);

        if (faceDetector == null) {
            // Legacy/no-detector mode: normalise + compress only, no validation.
            var normalized = resizeWithinBox(rgb);
            var compressed = compress(normalized);
            boolean changed = compressed.length != originalBytes.length
                    || source.getWidth() != normalized.getWidth()
                    || source.getHeight() != normalized.getHeight();
            return new FacePhotoValidation(true, APPROVED_MESSAGE,
                    true, true, false, true, true, true, true, true, true,
                    compressed, "jpg", "image/jpeg", normalized.getWidth(), normalized.getHeight(),
                    originalBytes.length, compressed.length, maxBytes, changed);
        }

        int[][] gray = toGrayscale(rgb);
        double brightness = meanBrightness(gray);
        double sharpness = laplacianVariance(gray);
        double contrast = standardDeviation(gray);
        boolean brightnessOk = brightness >= minBrightness && brightness <= maxBrightness;
        boolean sharpnessOk = sharpness >= minSharpness;
        boolean contrastOk = contrast >= minContrast;

        List<FaceDetector.FaceBox> faces = faceDetector.detect(rgb).stream()
                .filter(face -> face != null && face.width() > 0 && face.height() > 0)
                .sorted(Comparator.comparingInt(FaceDetector.FaceBox::area).reversed())
                .toList();
        boolean faceDetected = !faces.isEmpty();

        int imgW = rgb.getWidth();
        int imgH = rgb.getHeight();
        boolean sizeOk = false;
        boolean centeredOk = false;
        boolean faceFullyVisibleOk = true; // assume visible unless the conservative heuristic flags it
        boolean secondaryFaceDetected = false;
        BufferedImage toCompress = rgb;
        if (faceDetected) {
            var face = faces.get(0);
            secondaryFaceDetected = hasRelevantSecondaryFace(faces, face, imgW, imgH);
            boolean singleFace = !secondaryFaceDetected;
            double faceRatio = Math.min(face.width() / (double) imgW, face.height() / (double) imgH);
            double offsetX = Math.abs(face.centerX() - imgW / 2.0) / imgW;
            double offsetY = Math.abs(face.centerY() - imgH / 2.0) / imgH;
            sizeOk = faceRatio >= minFaceRatio;
            centeredOk = offsetX <= maxCenterOffsetRatio && offsetY <= maxCenterOffsetRatio;
            if (singleFace && sizeOk && centeredOk) {
                if (occlusionCheckEnabled) {
                    faceFullyVisibleOk = !lowerFaceLooksOccluded(rgb, face);
                }
                toCompress = cropAroundFace(rgb, face);
            }
        }
        boolean singleFace = faceDetected && !secondaryFaceDetected;

        var normalized = resizeWithinBox(toCompress);
        var compressed = compress(normalized);
        boolean sizeBytesOk = compressed.length <= maxBytes;

        boolean approved = brightnessOk && sharpnessOk && contrastOk
                && faceDetected && singleFace && sizeOk && centeredOk && faceFullyVisibleOk && sizeBytesOk;
        String message = approved ? APPROVED_MESSAGE
                : firstFailureMessage(brightnessOk, sharpnessOk, contrastOk, faceDetected, singleFace,
                        sizeOk, centeredOk, faceFullyVisibleOk, sizeBytesOk);

        if (!approved) {
            log.info("FACE_VALIDATION_REJECTED brightness={} sharpness={} contrast={} brightness_ok={} sharpness_ok={} contrast_ok={} faces={} single={} secondary_face_detected={} size_ok={} centered_ok={} face_visible_ok={} bytes={} max_bytes={} final_size_ok={}",
                    Math.round(brightness), Math.round(sharpness), Math.round(contrast),
                    brightnessOk, sharpnessOk, contrastOk, faces.size(), singleFace, secondaryFaceDetected,
                    sizeOk, centeredOk, faceFullyVisibleOk, compressed.length, maxBytes, sizeBytesOk);
        }

        boolean changed = compressed.length != originalBytes.length
                || source.getWidth() != normalized.getWidth()
                || source.getHeight() != normalized.getHeight();
        return new FacePhotoValidation(approved, message,
                faceDetected, singleFace, secondaryFaceDetected, brightnessOk, sharpnessOk, contrastOk, centeredOk, sizeOk,
                faceFullyVisibleOk,
                compressed, "jpg", "image/jpeg", normalized.getWidth(), normalized.getHeight(),
                originalBytes.length, compressed.length, maxBytes, changed);
    }

    private boolean hasRelevantSecondaryFace(List<FaceDetector.FaceBox> faces, FaceDetector.FaceBox primary,
                                             int imgW, int imgH) {
        for (int i = 1; i < faces.size(); i++) {
            var candidate = faces.get(i);
            if (intersectionOverUnion(primary, candidate) > 0.35) {
                continue;
            }
            double ratio = Math.min(candidate.width() / (double) imgW, candidate.height() / (double) imgH);
            double areaRatio = candidate.area() / (double) Math.max(1, primary.area());
            boolean relevant = ratio >= secondaryFaceMinRatio || areaRatio >= secondaryFaceMinAreaRatio;
            if (relevant) {
                log.info("FACE_SECONDARY_DETECTED primary={}x{} secondary={}x{} secondary_ratio={} secondary_area_ratio={}",
                        primary.width(), primary.height(), candidate.width(), candidate.height(),
                        String.format(java.util.Locale.ROOT, "%.3f", ratio),
                        String.format(java.util.Locale.ROOT, "%.3f", areaRatio));
                return true;
            }
        }
        return false;
    }

    private double intersectionOverUnion(FaceDetector.FaceBox a, FaceDetector.FaceBox b) {
        int left = Math.max(a.x(), b.x());
        int top = Math.max(a.y(), b.y());
        int right = Math.min(a.x() + a.width(), b.x() + b.width());
        int bottom = Math.min(a.y() + a.height(), b.y() + b.height());
        int intersectionW = Math.max(0, right - left);
        int intersectionH = Math.max(0, bottom - top);
        double intersection = (double) intersectionW * intersectionH;
        double union = (double) a.area() + b.area() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    /**
     * Conservative, native-free heuristic for gross lower-face occlusion (e.g. a hand flat over the
     * mouth/chin). It compares texture (Laplacian variance) in the upper half of the detected face
     * (eyes/forehead) against the lower portion (mouth/chin). A covering object leaves the lower
     * region almost flat. Double-gated — both ABSOLUTELY flat and RELATIVELY much flatter than the
     * upper region — to keep false rejections low.
     *
     * <p><b>Limitation:</b> Haar detection + this heuristic cannot reliably detect partial occlusion.
     * It only flags blatant cases. Authoritative face-quality/occlusion rejection comes from the
     * Intelbras controller at enrolment (see {@code IntelbrasFacePhotoValidator}).
     */
    private boolean lowerFaceLooksOccluded(BufferedImage image, FaceDetector.FaceBox face) {
        try {
            int fx = Math.max(0, face.x());
            int fy = Math.max(0, face.y());
            int fw = Math.min(image.getWidth() - fx, face.width());
            int fh = Math.min(image.getHeight() - fy, face.height());
            if (fw < 12 || fh < 12) {
                return false; // too small to assess reliably — do not reject
            }
            var faceImage = image.getSubimage(fx, fy, fw, fh);
            int upperHeight = Math.max(1, (int) (fh * 0.5));
            int lowerStart = (int) (fh * 0.55);
            int lowerHeight = Math.max(1, fh - lowerStart);
            double upperTexture = laplacianVariance(toGrayscale(faceImage.getSubimage(0, 0, fw, upperHeight)));
            double lowerTexture = laplacianVariance(toGrayscale(faceImage.getSubimage(0, lowerStart, fw, lowerHeight)));
            boolean occluded = lowerTexture < occlusionMinLowerTexture
                    && lowerTexture < upperTexture * occlusionLowerUpperRatio;
            if (occluded) {
                log.info("FACE_OCCLUSION_FLAGGED lower_texture={} upper_texture={} min_lower={} ratio={}",
                        Math.round(lowerTexture), Math.round(upperTexture), occlusionMinLowerTexture, occlusionLowerUpperRatio);
            }
            return occluded;
        } catch (RuntimeException exception) {
            // Heuristic must never break the pipeline — on any error treat as visible.
            log.debug("FACE_OCCLUSION_CHECK_SKIPPED reason={}", exception.getMessage());
            return false;
        }
    }

    private String firstFailureMessage(boolean brightnessOk, boolean sharpnessOk, boolean contrastOk,
                                       boolean faceDetected, boolean singleFace, boolean sizeOk, boolean centeredOk,
                                       boolean faceFullyVisibleOk, boolean sizeBytesOk) {
        if (!brightnessOk || !sharpnessOk || !contrastOk) {
            return FacePhotoRejectedException.LOW_QUALITY;
        }
        if (!faceDetected) {
            return FacePhotoRejectedException.NO_FACE;
        }
        if (!singleFace) {
            return FacePhotoRejectedException.MULTIPLE_FACES;
        }
        if (!sizeOk || !centeredOk) {
            return FacePhotoRejectedException.FACE_TOO_SMALL_OR_OFFSET;
        }
        if (!faceFullyVisibleOk) {
            return FacePhotoRejectedException.FACE_OCCLUDED;
        }
        if (!sizeBytesOk) {
            return FacePhotoRejectedException.COMPRESSION_FAILED;
        }
        return FacePhotoRejectedException.FACE_TOO_SMALL_OR_OFFSET;
    }

    private byte[] compress(BufferedImage normalized) {
        try {
            return encodeJpegWithinLimit(normalized);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not process face photo.");
        }
    }

    private BufferedImage cropAroundFace(BufferedImage image, FaceDetector.FaceBox face) {
        int imgW = image.getWidth();
        int imgH = image.getHeight();
        int marginX = (int) Math.round(face.width() * faceMarginRatio);
        int marginY = (int) Math.round(face.height() * faceMarginRatio);
        int left = Math.max(0, face.x() - marginX);
        int top = Math.max(0, face.y() - marginY);
        int right = Math.min(imgW, face.x() + face.width() + marginX);
        int bottom = Math.min(imgH, face.y() + face.height() + marginY);
        int cropW = Math.max(1, right - left);
        int cropH = Math.max(1, bottom - top);

        var crop = new BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_RGB);
        var graphics = crop.createGraphics();
        try {
            graphics.drawImage(image.getSubimage(left, top, cropW, cropH), 0, 0, null);
        } finally {
            graphics.dispose();
        }
        log.info("FACE_UPLOAD_CROPPED original={}x{} face={}x{} crop={}x{}",
                imgW, imgH, face.width(), face.height(), cropW, cropH);
        return crop;
    }

    private BufferedImage read(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes)) {
            var image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalArgumentException("Face photo must be a valid image.");
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Face photo must be a valid image.");
        }
    }

    private BufferedImage toRgb(BufferedImage source) {
        var rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private BufferedImage resizeWithinBox(BufferedImage rgb) {
        var scale = Math.min((double) maxWidth / rgb.getWidth(), (double) maxHeight / rgb.getHeight());
        scale = Math.min(scale, 1.0);
        if (scale >= 1.0) {
            return rgb;
        }
        var width = Math.max(1, (int) Math.round(rgb.getWidth() * scale));
        var height = Math.max(1, (int) Math.round(rgb.getHeight() * scale));
        return resize(rgb, width, height);
    }

    private byte[] encodeJpegWithinLimit(BufferedImage image) throws IOException {
        var current = image;
        for (int resizeAttempt = 0; resizeAttempt < 6; resizeAttempt++) {
            byte[] smallest = null;
            for (float quality = 0.88f; quality >= 0.48f; quality -= 0.08f) {
                var encoded = encodeJpeg(current, quality);
                smallest = encoded;
                if (encoded.length <= maxBytes) {
                    return encoded;
                }
            }
            if (current.getWidth() <= 160 || current.getHeight() <= 120) {
                return smallest == null ? encodeJpeg(current, 0.48f) : smallest;
            }
            current = resize(current,
                    Math.max(160, (int) Math.round(current.getWidth() * 0.85)),
                    Math.max(120, (int) Math.round(current.getHeight() * 0.85)));
        }
        return encodeJpeg(current, 0.45f);
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        var resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        }
        var writer = writers.next();
        var output = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static int[][] toGrayscale(BufferedImage image) {
        // Bounded-size copy keeps the quality scan cheap on large uploads.
        int maxDim = 480;
        double scale = Math.min(1.0, (double) maxDim / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int[][] gray = new int[height][width];
        double sx = (double) image.getWidth() / width;
        double sy = (double) image.getHeight() / height;
        for (int y = 0; y < height; y++) {
            int srcY = Math.min(image.getHeight() - 1, (int) (y * sy));
            for (int x = 0; x < width; x++) {
                int srcX = Math.min(image.getWidth() - 1, (int) (x * sx));
                int rgb = image.getRGB(srcX, srcY);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[y][x] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        return gray;
    }

    private static double meanBrightness(int[][] gray) {
        long total = 0;
        long count = 0;
        for (int[] row : gray) {
            for (int value : row) {
                total += value;
                count++;
            }
        }
        return count == 0 ? 0 : (double) total / count;
    }

    /** Standard deviation of luma — a simple contrast measure (a flat image scores ~0). */
    private static double standardDeviation(int[][] gray) {
        double sum = 0;
        double sumSq = 0;
        long count = 0;
        for (int[] row : gray) {
            for (int value : row) {
                sum += value;
                sumSq += (double) value * value;
                count++;
            }
        }
        if (count == 0) {
            return 0;
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0, sumSq / count - mean * mean));
    }

    /** Variance of the Laplacian — a standard, fast focus/blur measure. Lower means blurrier. */
    private static double laplacianVariance(int[][] gray) {
        int height = gray.length;
        int width = gray[0].length;
        if (width < 3 || height < 3) {
            return Double.MAX_VALUE;
        }
        double sum = 0;
        double sumSq = 0;
        long count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int laplacian = 4 * gray[y][x]
                        - gray[y - 1][x] - gray[y + 1][x] - gray[y][x - 1] - gray[y][x + 1];
                sum += laplacian;
                sumSq += (double) laplacian * laplacian;
                count++;
            }
        }
        double mean = sum / count;
        return sumSq / count - mean * mean;
    }

    /** Final processed photo handed to storage/sync. */
    public record ProcessedFacePhoto(
            byte[] bytes,
            String extension,
            String contentType,
            int width,
            int height,
            long originalSizeBytes,
            long savedSizeBytes,
            boolean compressed
    ) {
    }

    /** Full validation outcome — every check plus the compressed bytes/size. Never persisted by itself. */
    public record FacePhotoValidation(
            boolean approved,
            String message,
            boolean faceDetected,
            boolean singleFace,
            boolean secondaryFaceDetected,
            boolean brightnessOk,
            boolean sharpnessOk,
            boolean contrastOk,
            boolean centeredOk,
            boolean sizeOk,
            boolean faceFullyVisibleOk,
            byte[] bytes,
            String extension,
            String contentType,
            int width,
            int height,
            long originalSizeBytes,
            long compressedSizeBytes,
            long maxAllowedBytes,
            boolean compressed
    ) {
        public boolean faceSizeOk() {
            return sizeOk;
        }

        public boolean finalCompressedSizeOk() {
            return compressedSizeBytes <= maxAllowedBytes;
        }
    }
}
