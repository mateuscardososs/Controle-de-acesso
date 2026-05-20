package br.com.sport.accesscontrol.integration.intelbras.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

@Component
public class IntelbrasFaceEncoder {

    private final Path uploadRoot;

    public IntelbrasFaceEncoder(@Value("${app.uploads.faces-dir:uploads/faces}") String facesDir) {
        this.uploadRoot = Path.of(facesDir).toAbsolutePath().normalize();
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
            var source = ImageIO.read(path.toFile());
            if (source == null) {
                throw new IllegalArgumentException("Face photo must be a readable image.");
            }
            var jpeg = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = jpeg.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, jpeg.getWidth(), jpeg.getHeight());
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            var output = new ByteArrayOutputStream();
            ImageIO.write(jpeg, "jpg", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not convert face photo to JPEG.");
        }
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
