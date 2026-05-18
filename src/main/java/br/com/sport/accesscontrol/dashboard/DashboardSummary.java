package br.com.sport.accesscontrol.dashboard;

public record DashboardSummary(
        long totalEmployees,
        long totalDevices,
        long todayEvents,
        long deniedAccesses
) {
}
