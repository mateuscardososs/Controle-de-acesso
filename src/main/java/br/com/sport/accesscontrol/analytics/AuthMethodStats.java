package br.com.sport.accesscontrol.analytics;

public record AuthMethodStats(
        long facial,
        long card,
        long manual,
        long other,
        long total
) {}
