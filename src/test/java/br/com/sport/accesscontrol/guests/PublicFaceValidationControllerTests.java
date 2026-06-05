package br.com.sport.accesscontrol.guests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicFaceValidationControllerTests {

    private FaceStorageService faceStorageService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        faceStorageService = mock(FaceStorageService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicFaceValidationController(faceStorageService)).build();
    }

    @Test
    void approvedPhotoReturnsApprovedTrue() throws Exception {
        when(faceStorageService.validate(any())).thenReturn(validation(true, FacePhotoProcessor.APPROVED_MESSAGE, true, true));

        mockMvc.perform(multipart("/api/public/face/validate").file(file()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.message").value(FacePhotoProcessor.APPROVED_MESSAGE))
                .andExpect(jsonPath("$.checks.faceDetected").value(true))
                .andExpect(jsonPath("$.checks.secondaryFaceDetected").value(false))
                .andExpect(jsonPath("$.checks.faceSizeOk").value(true))
                .andExpect(jsonPath("$.checks.eyesVisibleOk").value(true))
                .andExpect(jsonPath("$.checks.finalCompressedSizeOk").value(true))
                .andExpect(jsonPath("$.checks.maxAllowedBytes").value(99328));
    }

    @Test
    void rejectedPhotoReturnsApprovedFalseWithBackendMessage() throws Exception {
        when(faceStorageService.validate(any())).thenReturn(validation(false, FacePhotoRejectedException.NO_FACE, false, false));

        mockMvc.perform(multipart("/api/public/face/validate").file(file()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.message").value(FacePhotoRejectedException.NO_FACE))
                .andExpect(jsonPath("$.rejectionReason").value(FacePhotoRejectedException.NO_FACE))
                .andExpect(jsonPath("$.checks.faceDetected").value(false));
    }

    @Test
    void compressionDegradedExposesQualityAfterCompressionFalse() throws Exception {
        // All initial checks pass, but the photo lost quality once compressed -> must NOT show every check as OK.
        when(faceStorageService.validate(any())).thenReturn(compressionDegraded());

        mockMvc.perform(multipart("/api/public/face/validate").file(file()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.message").value(FacePhotoRejectedException.COMPRESSION_DEGRADED))
                .andExpect(jsonPath("$.rejectionReason").value(FacePhotoRejectedException.COMPRESSION_DEGRADED))
                // The new explicit check must be false so the UI has a concrete failing item to show.
                .andExpect(jsonPath("$.checks.qualityAfterCompressionOk").value(false))
                .andExpect(jsonPath("$.checks.compressionOk").value(false))
                .andExpect(jsonPath("$.checks.compressionAttempts").value(6))
                // The original-photo checks remain true (the original was fine; compression degraded it).
                .andExpect(jsonPath("$.checks.faceDetected").value(true))
                .andExpect(jsonPath("$.checks.eyesVisibleOk").value(true));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "face.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private static FacePhotoProcessor.FacePhotoValidation validation(boolean approved, String message,
                                                                     boolean faceDetected, boolean singleFace) {
        return new FacePhotoProcessor.FacePhotoValidation(
                approved, message, faceDetected, singleFace, !singleFace && faceDetected, true, true, true, true, true, true,
                true, true, approved, 1, new byte[] {1}, "jpg", "image/jpeg", 480, 480, 1000L, 5000L, 99328L, true);
    }

    /** Mirrors the backend COMPRESSION_DEGRADED outcome: every original-photo check OK, but quality lost on compression. */
    private static FacePhotoProcessor.FacePhotoValidation compressionDegraded() {
        return new FacePhotoProcessor.FacePhotoValidation(
                false, FacePhotoRejectedException.COMPRESSION_DEGRADED,
                true, true, false, true, true, true, true, true, true, true,
                false, false, 6, new byte[] {1}, "jpg", "image/jpeg", 360, 360, 2_000_000L, 85_000L, 99328L, true);
    }
}
