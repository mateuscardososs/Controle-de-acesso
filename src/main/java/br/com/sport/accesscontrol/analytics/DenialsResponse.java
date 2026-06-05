package br.com.sport.accesscontrol.analytics;

import java.util.List;

public record DenialsResponse(
        long total,
        List<TimelinePoint> byHour,
        List<DenialItem> recent
) {}
