package br.com.sport.accesscontrol.dashboard;

import br.com.sport.accesscontrol.devices.DeviceRepository;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEventRepository;
import br.com.sport.accesscontrol.events.AccessResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
public class DashboardService {

    private final EmployeeRepository employeeRepository;
    private final DeviceRepository deviceRepository;
    private final AccessEventRepository accessEventRepository;
    private final Clock clock;

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
}
