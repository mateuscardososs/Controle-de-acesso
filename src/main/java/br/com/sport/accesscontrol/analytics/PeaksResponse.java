package br.com.sport.accesscontrol.analytics;

public record PeaksResponse(
        PeakItem peakEntry,
        PeakItem peakExit,
        PeakItem busiestDay,
        PeakItem busiestWeek,
        PeakItem busiestMonth
) {}
