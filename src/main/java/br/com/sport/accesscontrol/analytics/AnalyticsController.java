package br.com.sport.accesscontrol.analytics;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private static final ZoneId ZONE = ZoneId.of(AnalyticsFilters.ZONE_ID);

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    OverviewResponse overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod
    ) {
        return analyticsService.overview(resolve(from, to, deviceId, areaId, personType, releaseMethod));
    }

    @GetMapping("/timeline")
    List<TimelinePoint> timeline(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod,
            @RequestParam(defaultValue = "HOUR") String granularity
    ) {
        return analyticsService.timeline(resolve(from, to, deviceId, areaId, personType, releaseMethod), granularity);
    }

    @GetMapping("/heatmap")
    List<HeatmapPoint> heatmap(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod
    ) {
        return analyticsService.heatmap(resolve(from, to, deviceId, areaId, personType, releaseMethod));
    }

    @GetMapping("/auth-methods")
    AuthMethodStats authMethods(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType
    ) {
        return analyticsService.authMethods(resolve(from, to, deviceId, areaId, personType, null));
    }

    @GetMapping("/controllers")
    List<ControllerStatsItem> controllers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod
    ) {
        return analyticsService.controllers(resolve(from, to, null, areaId, personType, releaseMethod));
    }

    @GetMapping("/areas")
    List<AreaStatsItem> areas(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod
    ) {
        return analyticsService.areas(resolve(from, to, deviceId, null, personType, releaseMethod));
    }

    @GetMapping("/users")
    List<UserRankItem> users(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod
    ) {
        return analyticsService.topUsers(resolve(from, to, deviceId, areaId, personType, releaseMethod), personType);
    }

    @GetMapping("/denials")
    DenialsResponse denials(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod
    ) {
        return analyticsService.denials(resolve(from, to, deviceId, areaId, personType, releaseMethod));
    }

    @GetMapping("/presence")
    List<PresenceItem> presence(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId
    ) {
        var resolvedFrom = from != null
                ? from.atStartOfDay(ZONE).toInstant()
                : java.time.Instant.now().minus(7, ChronoUnit.DAYS);
        var future = java.time.Instant.now().plus(1, ChronoUnit.DAYS);
        return analyticsService.presence(new AnalyticsFilters(resolvedFrom, future, deviceId, areaId, null, null));
    }

    @GetMapping("/peaks")
    PeaksResponse peaks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false) String releaseMethod
    ) {
        return analyticsService.peaks(resolve(from, to, deviceId, areaId, personType, releaseMethod));
    }

    private AnalyticsFilters resolve(LocalDate from, LocalDate to,
                                     UUID deviceId, UUID areaId,
                                     String personType, String releaseMethod) {
        var today = LocalDate.now(ZONE);
        var resolvedFrom = from != null ? from.atStartOfDay(ZONE).toInstant()
                : today.minus(30, ChronoUnit.DAYS).atStartOfDay(ZONE).toInstant();
        var resolvedTo = to != null ? to.plusDays(1).atStartOfDay(ZONE).toInstant()
                : today.plusDays(1).atStartOfDay(ZONE).toInstant();
        return new AnalyticsFilters(resolvedFrom, resolvedTo, deviceId, areaId, personType, releaseMethod);
    }
}
