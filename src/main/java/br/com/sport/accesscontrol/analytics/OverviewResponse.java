package br.com.sport.accesscontrol.analytics;

public record OverviewResponse(
        long totalAccesses,
        long entries,
        long exits,
        long uniqueUsers,
        long visitors,
        long employees,
        long facialRecognitions,
        long cardAccesses,
        long denials,
        double successRate,
        long onlineControllers,
        long offlineControllers,
        Double avgDwellMinutes,
        Long maxDwellMinutes,
        Long minDwellMinutes
) {}
