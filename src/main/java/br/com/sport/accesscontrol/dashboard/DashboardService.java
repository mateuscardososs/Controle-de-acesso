package br.com.sport.accesscontrol.dashboard;

import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.events.AccessEventResponse;
import br.com.sport.accesscontrol.events.AccessResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class DashboardService {

    private final EmployeeRepository employeeRepository;
    private final DeviceRepository deviceRepository;
    private final AccessEventRepository accessEventRepository;
    private final Clock clock;
    private static final ZoneId EVENT_ZONE = ZoneId.of("America/Recife");

    public DashboardService(
            EmployeeRepository employeeRepository,
            DeviceRepository deviceRepository,
            AccessEventRepository accessEventRepository
    ) {
        this.employeeRepository = employeeRepository;
        this.deviceRepository = deviceRepository;
        this.accessEventRepository = accessEventRepository;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        var startOfDay = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
        var endOfDay = startOfDay.plusSeconds(86_400);
        return new DashboardSummary(
                employeeRepository.count(),
                deviceRepository.count(),
                accessEventRepository.countByEventTimeBetween(startOfDay, endOfDay),
                accessEventRepository.countByAccessResult(AccessResult.DENIED)
        );
    }

    @Transactional(readOnly = true)
    public List<DashboardTrafficPeak> trafficPeaks(LocalDate date) {
        var targetDate = date == null ? LocalDate.now(clock.withZone(EVENT_ZONE)) : date;
        var start = targetDate.atStartOfDay(EVENT_ZONE).toInstant();
        var end = targetDate.plusDays(1).atStartOfDay(EVENT_ZONE).toInstant();
        var rowsByHour = accessEventRepository.trafficPeaksByHour(start, end, EVENT_ZONE.getId()).stream()
                .collect(Collectors.toMap(AccessEventRepository.TrafficPeakRow::getHour, row -> row));

        return IntStream.range(0, 24)
                .mapToObj(hour -> toTrafficPeak(hour, rowsByHour))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccessEventResponse> recentEvents(int size) {
        var safeSize = Math.min(Math.max(size, 1), 50);
        return accessEventRepository.findAllByOrderByEventTimeDesc(PageRequest.of(0, safeSize)).stream()
                .map(AccessEventResponse::from)
                .toList();
    }

    private DashboardTrafficPeak toTrafficPeak(int hour, Map<Integer, AccessEventRepository.TrafficPeakRow> rowsByHour) {
        var row = rowsByHour.get(hour);
        if (row == null) {
            return new DashboardTrafficPeak(hour, 0, 0, 0, 0, 0);
        }
        return new DashboardTrafficPeak(
                hour,
                value(row.getEntries()),
                value(row.getExits()),
                value(row.getPassages()),
                value(row.getAllowed()),
                value(row.getDenied())
        );
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }
}
