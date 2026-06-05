package br.com.sport.accesscontrol.integration.intelbras.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntelbrasFaceEncoderTests {

    static final int DEFAULT_MAX_BYTES = 95 * 1024;

    @TempDir
    Path tempDir;

    @Test
    void convertsLocalImageToJpegBase64() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 2, 2);
        graphics.dispose();
        var path = tempDir.resolve("face.png");
        ImageIO.write(image, "png", path.toFile());

        var encoded = new IntelbrasFaceEncoder(tempDir.toString(), DEFAULT_MAX_BYTES).toJpegBase64(path.toString());
        var decoded = Base64.getDecoder().decode(encoded);

        assertThat(ImageIO.read(new ByteArrayInputStream(decoded))).isNotNull();
        assertThat(decoded[0]).isEqualTo((byte) 0xFF);
        assertThat(decoded[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void normalizesFaceImageForBioTPhotoDataLimits() throws Exception {
        var image = new BufferedImage(1600, 2400, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        var path = tempDir.resolve("large-face.jpg");
        ImageIO.write(image, "jpg", path.toFile());

        var encoded = new IntelbrasFaceEncoder(tempDir.toString(), DEFAULT_MAX_BYTES).toJpegBase64(path.toString());
        var decoded = Base64.getDecoder().decode(encoded);
        var normalized = ImageIO.read(new ByteArrayInputStream(decoded));

        assertThat(decoded.length).isLessThanOrEqualTo(DEFAULT_MAX_BYTES);
        assertThat(normalized.getWidth()).isBetween(150, 600);
        assertThat(normalized.getHeight()).isBetween(300, 1200);
        assertThat(normalized.getHeight()).isLessThanOrEqualTo(normalized.getWidth() * 2);
    }

    @Test
    void compressesLargeJpegToUnder95KB() throws Exception {
        // Write a high-quality JPEG that will be well above 95KB to simulate a real 224KB face photo
        var image = new BufferedImage(800, 1000, BufferedImage.TYPE_INT_RGB);
        var rng = new java.util.Random(42L);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                // Slight variation around a skin tone — harder to compress than solid colors
                int r = Math.max(0, Math.min(255, 200 + rng.nextInt(30) - 15));
                int g = Math.max(0, Math.min(255, 150 + rng.nextInt(30) - 15));
                int b = Math.max(0, Math.min(255, 120 + rng.nextInt(30) - 15));
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        // Save at high quality so the file is large
        var writers = ImageIO.getImageWritersByFormatName("jpg");
        var writer = writers.next();
        var buf = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(buf)) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.99f);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        var path = tempDir.resolve("large-photo.jpg");
        Files.write(path, buf.toByteArray());

        // Verify the input is indeed large (> 95KB), otherwise the test doesn't prove compression
        assertThat(buf.toByteArray().length).isGreaterThan(DEFAULT_MAX_BYTES);

        var encoded = new IntelbrasFaceEncoder(tempDir.toString(), DEFAULT_MAX_BYTES).toJpegBase64(path.toString());
        var decoded = Base64.getDecoder().decode(encoded);

        assertThat(decoded.length).isLessThanOrEqualTo(DEFAULT_MAX_BYTES);
        assertThat(decoded[0]).isEqualTo((byte) 0xFF);
        assertThat(decoded[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void encodesStoredFileUnder97280WithDefaultConfig() throws Exception {
        // Simulates a file already stored at the 99KB storage limit.
        // The encoder must re-compress it to stay under the Intelbras 97280-byte wire limit.
        int intelbrasLimit = 97_280;
        var image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        var rng = new Random(99L);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int r = Math.max(0, Math.min(255, 180 + rng.nextInt(40) - 20));
                int g = Math.max(0, Math.min(255, 140 + rng.nextInt(40) - 20));
                int b = Math.max(0, Math.min(255, 100 + rng.nextInt(40) - 20));
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        var writers = ImageIO.getImageWritersByFormatName("jpg");
        var writer = writers.next();
        var buf = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(buf)) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.95f);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        var path = tempDir.resolve("stored-face.jpg");
        Files.write(path, buf.toByteArray());

        var encoded = new IntelbrasFaceEncoder(tempDir.toString(), intelbrasLimit).toJpegBase64(path.toString());
        var decoded = Base64.getDecoder().decode(encoded);

        assertThat(decoded.length)
                .as("re-encoded bytes must be <= Intelbras limit of %d", intelbrasLimit)
                .isLessThanOrEqualTo(intelbrasLimit);
    }

    @Test
    void encoderBlocksWhenOutputExceedsLimitAfterAllAttempts() throws Exception {
        // A limit of 1 byte is impossible to satisfy — encoder must throw, not silently send.
        var image = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        var rng = new Random(7L);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, rng.nextInt(0x1000000));
            }
        }
        var path = tempDir.resolve("noise.jpg");
        ImageIO.write(image, "jpg", path.toFile());

        assertThatThrownBy(() -> new IntelbrasFaceEncoder(tempDir.toString(), 1).toJpegBase64(path.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Intelbras limit");
    }
}
