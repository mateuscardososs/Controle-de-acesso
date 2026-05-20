package br.com.sport.accesscontrol.integration.intelbras.model;

import java.time.Instant;
import java.util.Map;

public record IntelbrasAccessControlCardRecord(
        String cardName,
        String userId,
        String status,
        String type,
        Instant createTime,
        String url,
        String errorCode,
        String door,
        String readerId,
        Map<String, Object> raw
) {
}
