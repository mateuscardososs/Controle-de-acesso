package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.common.UnprocessableEntityException;

/**
 * Raised when an uploaded face photo fails local quality/face validation before being stored.
 * Maps to HTTP 422 with a user-friendly, Portuguese message that the frontend shows as-is.
 */
public class FacePhotoRejectedException extends UnprocessableEntityException {

    public static final String NO_FACE =
            "Não detectamos um rosto. Envie uma foto frontal.";
    public static final String MULTIPLE_FACES =
            "Detectamos mais de um rosto. Envie uma foto com apenas uma pessoa.";
    public static final String LOW_QUALITY =
            "A foto está escura ou com baixa nitidez.";
    public static final String FACE_TOO_SMALL_OR_OFFSET =
            "Aproxime o rosto e use um fundo mais limpo.";
    public static final String COMPRESSION_FAILED =
            "Não foi possível comprimir a foto dentro do limite exigido. Tente outra foto.";
    public static final String FACE_OCCLUDED =
            "Mantenha o rosto totalmente visível. Remova objetos ou mãos da frente do rosto.";

    public FacePhotoRejectedException(String message) {
        super(message);
    }
}
