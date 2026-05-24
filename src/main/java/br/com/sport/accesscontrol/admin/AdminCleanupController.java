package br.com.sport.accesscontrol.admin;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/cleanup")
public class AdminCleanupController {

    private final AdminCleanupService cleanupService;

    public AdminCleanupController(AdminCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @PostMapping("/access-events")
    AdminCleanupDtos.CleanupResponse accessEvents(@RequestBody AdminCleanupDtos.CleanupRequest request) {
        return cleanupService.accessEvents(request);
    }

    @PostMapping("/employees")
    AdminCleanupDtos.CleanupResponse employees(@RequestBody AdminCleanupDtos.CleanupRequest request, Authentication authentication) {
        return cleanupService.employees(request, authentication);
    }
}
