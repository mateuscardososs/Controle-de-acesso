package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.common.UnprocessableEntityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaceStorageServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void storeCompressesPhotoToJpegUnderIntelbrasUploadLimit() throws Exception {
        var storage = new FaceStorageService(tempDir.toString());
        var original = randomPng(1000, 800);
        assertThat(original.length).isGreaterThan(204800);

        var url = storage.store(new MockMultipartFile("facePhoto", "face.png", "image/png", original), UUID.randomUUID());

        assertThat(url).startsWith("/uploads/faces/").endsWith(".jpg");
        var saved = tempDir.resolve(url.substring("/uploads/faces/".length()));
        assertThat(Files.size(saved)).isLessThanOrEqualTo(99328);
        var image = ImageIO.read(saved.toFile());
        assertThat(image.getWidth()).isLessThanOrEqualTo(640);
        assertThat(image.getHeight()).isLessThanOrEqualTo(480);
    }

    @Test
    void storeRejectsInvalidImageBytes() {
        var storage = new FaceStorageService(tempDir.toString());
        var invalid = new MockMultipartFile("facePhoto", "face.jpg", "image/jpeg", "not-an-image".getBytes());

        assertThatThrownBy(() -> storage.store(invalid, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid image");
    }

    @Test
    void validatorRejectionReturnsUnprocessableAndDoesNotPersistFile() throws Exception {
        var processor = new FacePhotoProcessor(204800, 640, 480);
        FacePhotoValidator validator = (photo, ownerId, sourceName) -> {
            throw new UnprocessableEntityException("Foto facial inválida.");
        };
        var storage = new FaceStorageService(tempDir.toString(), processor, List.of(validator));
        var image = randomPng(300, 300);

        assertThatThrownBy(() -> storage.store(
                new MockMultipartFile("facePhoto", "face.png", "image/png", image),
                UUID.randomUUID()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Foto facial inválida");

        try (var saved = Files.list(tempDir)) {
            assertThat(saved).isEmpty();
        }
    }

    @Test
    void validatePreviewReturnsResultWithoutPersistingAnyFile() throws Exception {
        var processor = new FacePhotoProcessor(99328, 640, 480, image -> List.of()); // detector finds no face
        var storage = new FaceStorageService(tempDir.toString(), processor, List.of());
        var image = randomPng(300, 300);

        var result = storage.validate(new MockMultipartFile("file", "face.png", "image/png", image));

        assertThat(result.approved()).isFalse();
        assertThat(result.faceDetected()).isFalse();
        assertThat(result.maxAllowedBytes()).isEqualTo(99328);
        try (var saved = Files.list(tempDir)) {
            assertThat(saved).as("preview must not write any file").isEmpty();
        }
    }

    private byte[] randomPng(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
