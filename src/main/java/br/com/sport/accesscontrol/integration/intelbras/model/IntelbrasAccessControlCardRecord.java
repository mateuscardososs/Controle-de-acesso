package br.com.sport.accesscontrol.integration.intelbras.model;

import java.time.Instant;
import java.util.Map;

public record IntelbrasAccessControlCardRecord(
        String recNo,
        String cardName,
        String userId,
        String status,
        String method,
        String type,
        Instant createTime,
        String url,
        String errorCode,
        String door,
        String readerId,
        Map<String, Object> raw
) {
}
