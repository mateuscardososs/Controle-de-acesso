package br.com.sport.accesscontrol.guests;

import java.util.UUID;

public interface FacePhotoValidator {

    void validate(FacePhotoProcessor.ProcessedFacePhoto photo, UUID ownerId, String sourceName);
}
