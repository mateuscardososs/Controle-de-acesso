package br.com.sport.accesscontrol.analytics;

public record HeatmapPoint(
        int dayOfWeek,
        String dayLabel,
        int hour,
        long count
) {}
