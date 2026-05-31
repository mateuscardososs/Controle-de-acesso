package br.com.sport.accesscontrol.guests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FaceStorageService {

    private static final Logger log = LoggerFactory.getLogger(FaceStorageService.class);

    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private final Path uploadRoot;

    public FaceStorageService(@Value("${app.uploads.faces-dir:uploads/faces}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file, UUID guestId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Face photo is required.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Face photo exceeds maximum size of 5MB.");
        }

        var extension = extension(file.getOriginalFilename());
        if (!EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Face photo extension is not allowed.");
        }

        try {
            var bytes = file.getBytes();
            validateImage(bytes);
            return storeBytes(bytes, extension, guestId, file.getOriginalFilename(), file.getContentType(), file.getSize());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not store face photo.");
        }
    }

    public String storeBase64(String facePhotoBase64, UUID guestId) {
        if (facePhotoBase64 == null || facePhotoBase64.isBlank()) {
            throw new IllegalArgumentException("Face photo is required.");
        }
        var parsed = parseBase64Image(facePhotoBase64);
        if (parsed.bytes().length > MAX_BYTES) {
            throw new IllegalArgumentException("Face photo exceeds maximum size of 5MB.");
        }
        validateImage(parsed.bytes());
        try {
            return storeBytes(parsed.bytes(), parsed.extension(), guestId, "face-photo-base64." + parsed.extension(),
                    parsed.contentType(), parsed.bytes().length);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not store face photo.");
        }
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new IllegalArgumentException("Face photo extension is required.");
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private void validateImage(byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes)) {
            if (ImageIO.read(input) == null) {
                throw new IllegalArgumentException("Face photo must be a valid image.");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Face photo must be a valid image.");
        }
    }

    private String storeBytes(byte[] bytes, String extension, UUID guestId, String originalFilename,
                              String contentType, long originalSizeBytes) throws IOException {
        Files.createDirectories(uploadRoot);
        var filename = guestId + "-" + UUID.randomUUID() + "." + extension;
        var target = uploadRoot.resolve(filename).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("Invalid upload path.");
        }
        Files.write(target, bytes);
        var savedSizeBytes = Files.size(target);
        var url = "/uploads/faces/" + filename;
        log.info("FACE_UPLOAD_STORED guest_id={} original_filename={} content_type={} original_size_bytes={} saved_path={} saved_size_bytes={}",
                guestId, originalFilename, contentType, originalSizeBytes, url, savedSizeBytes);
        return url;
    }

    private ParsedBase64Image parseBase64Image(String value) {
        var trimmed = value.trim();
        var contentType = "image/jpeg";
        var extension = "jpg";
        var commaIndex = trimmed.indexOf(',');
        if (trimmed.startsWith("data:") && commaIndex > 0) {
            var metadata = trimmed.substring(5, commaIndex);
            var semicolonIndex = metadata.indexOf(';');
            contentType = semicolonIndex >= 0 ? metadata.substring(0, semicolonIndex) : metadata;
            extension = extensionForContentType(contentType);
            trimmed = trimmed.substring(commaIndex + 1);
        }
        try {
            return new ParsedBase64Image(Base64.getDecoder().decode(trimmed), extension, contentType);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Face photo base64 is invalid.");
        }
    }

    private String extensionForContentType(String contentType) {
        return switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/jpg", "image/jpeg" -> "jpg";
            default -> throw new IllegalArgumentException("Face photo content type is not allowed.");
        };
    }

    private record ParsedBase64Image(byte[] bytes, String extension, String contentType) {
    }
}
