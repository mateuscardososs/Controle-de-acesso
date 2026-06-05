package br.com.sport.accesscontrol.integration.intelbras.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

@Component
public class IntelbrasFaceEncoder {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasFaceEncoder.class);

    private static final int MIN_WIDTH = 150;
    private static final int MIN_HEIGHT = 300;
    private static final int MAX_WIDTH = 600;
    private static final int MAX_HEIGHT = 1200;

    private final Path uploadRoot;
    private final int maxBytes;

    public IntelbrasFaceEncoder(
            @Value("${app.uploads.faces-dir:uploads/faces}") String facesDir,
            @Value("${app.intelbras.face.max-bytes:97280}") int maxBytes) {
        this.uploadRoot = Path.of(facesDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public String toJpegBase64(String facePhotoUrl) {
        if (facePhotoUrl == null || facePhotoUrl.isBlank()) {
            throw new IllegalArgumentException("Face photo is required for Intelbras real sync.");
        }
        var path = resolve(facePhotoUrl);
        try {
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Face photo file was not found.");
            }
            long inputSizeBytes;
            try {
                inputSizeBytes = Files.size(path);
            } catch (IOException e) {
                inputSizeBytes = -1;
            }
            var source = ImageIO.read(path.toFile());
            if (source == null) {
                throw new IllegalArgumentException("Face photo must be a readable image.");
            }
            log.info("FACE_ENCODE_START photo_url={} input_size_bytes={} input_width={} input_height={} max_output_bytes={}",
                    facePhotoUrl, inputSizeBytes, source.getWidth(), source.getHeight(), maxBytes);
            var normalized = normalize(source);
            var bytes = encodeJpeg(normalized);
            log.info("FACE_ENCODE_DONE photo_url={} input_size_bytes={} output_size_bytes={} normalized_width={} normalized_height={} within_limit={}",
                    facePhotoUrl, inputSizeBytes, bytes.length, normalized.getWidth(), normalized.getHeight(), bytes.length <= maxBytes);
            if (bytes.length > maxBytes) {
                log.error("FACE_SENT_TO_INTELBRAS_BLOCKED photo_url={} sentBytes={} maxAllowedBytes={} — rejecting, will not send oversized photo to device",
                        facePhotoUrl, bytes.length, maxBytes);
                throw new IllegalArgumentException(
                        "Face photo could not be compressed within Intelbras limit (" + maxBytes + " bytes). Actual: " + bytes.length + " bytes. Photo not sent.");
            }
            log.info("FACE_SENT_TO_INTELBRAS photo_url={} sentBytes={} maxAllowedBytes={} within_limit=true",
                    facePhotoUrl, bytes.length, maxBytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not convert face photo to JPEG.");
        }
    }

    private BufferedImage normalize(BufferedImage source) {
        var rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        var cropped = cropToIntelbrasAspect(rgb);
        var scale = Math.min((double) MAX_WIDTH / cropped.getWidth(), (double) MAX_HEIGHT / cropped.getHeight());
        scale = Math.min(scale, 1.0);
        if (cropped.getWidth() < MIN_WIDTH || cropped.getHeight() < MIN_HEIGHT) {
            scale = Math.max(scale, Math.max((double) MIN_WIDTH / cropped.getWidth(), (double) MIN_HEIGHT / cropped.getHeight()));
        }
        var width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, (int) Math.round(cropped.getWidth() * scale)));
        var height = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, (int) Math.round(cropped.getHeight() * scale)));
        return resize(cropped, width, height);
    }

    private BufferedImage cropToIntelbrasAspect(BufferedImage source) {
        var maxHeight = source.getWidth() * 2;
        if (source.getHeight() <= maxHeight) {
            return source;
        }
        var y = (source.getHeight() - maxHeight) / 2;
        return source.getSubimage(0, y, source.getWidth(), maxHeight);
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

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage current = image;
        for (int resizeAttempt = 0; resizeAttempt < 6; resizeAttempt++) {
            for (float quality = 0.85f; quality >= 0.45f; quality -= 0.10f) {
                var bytes = encodeJpeg(current, quality);
                if (bytes.length <= maxBytes || quality <= 0.45f) {
                    if (bytes.length <= maxBytes || current.getWidth() <= MIN_WIDTH || current.getHeight() <= MIN_HEIGHT) {
                        return bytes;
                    }
                }
            }
            var width = Math.max(MIN_WIDTH, (int) Math.round(current.getWidth() * 0.9));
            var height = Math.max(MIN_HEIGHT, (int) Math.round(current.getHeight() * 0.9));
            if (width == current.getWidth() && height == current.getHeight()) {
                return encodeJpeg(current, 0.45f);
            }
            current = resize(current, width, height);
        }
        return encodeJpeg(current, 0.45f);
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
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private Path resolve(String facePhotoUrl) {
        var value = facePhotoUrl.trim();
        if (value.startsWith("/uploads/faces/")) {
            var filename = value.substring("/uploads/faces/".length());
            var resolved = uploadRoot.resolve(filename).normalize();
            if (!resolved.startsWith(uploadRoot)) {
                throw new IllegalArgumentException("Face photo path is invalid.");
            }
            return resolved;
        }
        var path = Path.of(value).toAbsolutePath().normalize();
        if (value.startsWith("uploads/faces/")) {
            var filename = Path.of(value).getFileName();
            if (filename == null) {
                throw new IllegalArgumentException("Face photo path is invalid.");
            }
            return uploadRoot.resolve(filename).normalize();
        }
        return path;
    }
}
