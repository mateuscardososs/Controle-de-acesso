package br.com.sport.accesscontrol.guests;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FaceStorageService {

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
            if (ImageIO.read(file.getInputStream()) == null) {
                throw new IllegalArgumentException("Face photo must be a valid image.");
            }
            Files.createDirectories(uploadRoot);
            var filename = guestId + "-" + UUID.randomUUID() + "." + extension;
            var target = uploadRoot.resolve(filename).normalize();
            if (!target.startsWith(uploadRoot)) {
                throw new IllegalArgumentException("Invalid upload path.");
            }
            file.transferTo(target);
            return "/uploads/faces/" + filename;
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
}
