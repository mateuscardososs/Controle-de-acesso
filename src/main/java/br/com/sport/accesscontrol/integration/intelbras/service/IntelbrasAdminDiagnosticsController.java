package br.com.sport.accesscontrol.integration.intelbras.service;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/intelbras/diagnostics")
public class IntelbrasAdminDiagnosticsController {

    private final IntelbrasAdminDiagnosticsService service;

    public IntelbrasAdminDiagnosticsController(IntelbrasAdminDiagnosticsService service) {
        this.service = service;
    }

    @GetMapping
    List<IntelbrasAdminDiagnosticsService.IntelbrasDeviceDiagnosticResponse> diagnose() {
        return service.diagnoseAll();
    }
}
