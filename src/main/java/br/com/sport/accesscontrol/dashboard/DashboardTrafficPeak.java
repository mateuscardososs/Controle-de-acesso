package br.com.sport.accesscontrol.dashboard;

public record DashboardTrafficPeak(
        int hour,
        long entries,
        long exits,
        long passages,
        long allowed,
        long denied
) {
}
