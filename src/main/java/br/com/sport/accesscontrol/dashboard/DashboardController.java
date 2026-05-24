package br.com.sport.accesscontrol.dashboard;

import br.com.sport.accesscontrol.events.AccessEventResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    DashboardSummary summary() {
        return dashboardService.summary();
    }

    @GetMapping("/traffic-peaks")
    List<DashboardTrafficPeak> trafficPeaks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return dashboardService.trafficPeaks(date);
    }

    @GetMapping("/recent-events")
    List<AccessEventResponse> recentEvents(@RequestParam(defaultValue = "6") int size) {
        return dashboardService.recentEvents(size);
    }
}
