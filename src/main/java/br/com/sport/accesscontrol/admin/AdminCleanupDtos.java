package br.com.sport.accesscontrol.admin;

public final class AdminCleanupDtos {

    private AdminCleanupDtos() {
    }

    public record CleanupRequest(String confirmation) {
    }

    public record CleanupResponse(long removedCount, String message) {
    }
}
