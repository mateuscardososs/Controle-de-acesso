package br.com.sport.accesscontrol.analytics;

public record TimelinePoint(
        String label,
        long entries,
        long exits,
        long total
) {}
