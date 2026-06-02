package br.com.sport.accesscontrol.guests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public, stateless face-photo validation used by the camera/preview screen. Runs the real backend
 * pipeline ({@link FacePhotoProcessor}) without saving anything, so the UI's "approved" decision
 * reflects exactly what the final upload would do. The final upload still re-validates server-side.
 */
@RestController
@RequestMapping({"/api/public/face", "/public/face"})
public class PublicFaceValidationController {

    private static final Logger log = LoggerFactory.getLogger(PublicFaceValidationController.class);

    private final FaceStorageService faceStorageService;

    public PublicFaceValidationController(FaceStorageService faceStorageService) {
        this.faceStorageService = faceStorageService;
    }

    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    FaceValidationResponse validate(@RequestPart("file") MultipartFile file) {
        var result = faceStorageService.validate(file);
        log.info("FACE_PREVIEW_VALIDATED approved={} face_detected={} single_face={} size_ok={} centered_ok={} face_visible_ok={} brightness_ok={} sharpness_ok={} contrast_ok={} bytes={} max_bytes={}",
                result.approved(), result.faceDetected(), result.singleFace(), result.sizeOk(),
                result.centeredOk(), result.faceFullyVisibleOk(), result.brightnessOk(), result.sharpnessOk(),
                result.contrastOk(), result.compressedSizeBytes(), result.maxAllowedBytes());
        return FaceValidationResponse.from(result);
    }

    record FaceValidationResponse(boolean approved, String message, Checks checks) {
        static FaceValidationResponse from(FacePhotoProcessor.FacePhotoValidation v) {
            return new FaceValidationResponse(v.approved(), v.message(), new Checks(
                    v.faceDetected(), v.singleFace(), v.brightnessOk(), v.sharpnessOk(),
                    v.contrastOk(), v.centeredOk(), v.sizeOk(), v.faceFullyVisibleOk(),
                    v.compressedSizeBytes(), v.maxAllowedBytes()));
        }
    }

    record Checks(
            boolean faceDetected,
            boolean singleFace,
            boolean brightnessOk,
            boolean sharpnessOk,
            boolean contrastOk,
            boolean centeredOk,
            boolean sizeOk,
            boolean faceFullyVisibleOk,
            long compressedSizeBytes,
            long maxAllowedBytes
    ) {
    }
}
