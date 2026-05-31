package br.com.sport.accesscontrol.areas;

import br.com.sport.accesscontrol.appconfig.LoungeConfig;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolve as áreas permitidas a partir de regras de negócio:
 * - convidado de qualquer camarote sempre recebe Portaria + a área do camarote;
 * - colaborador (LoungeConfig.COLLABORATOR_LOUNGE) recebe TODAS as áreas ativas (acesso total).
 */
@Service
public class LoungeAreaResolver {

    private static final Logger log = LoggerFactory.getLogger(LoungeAreaResolver.class);
    public static final String GENERAL_AREA_NAME = "Portaria";
    private static final Map<String, List<String>> AREA_ALIASES_BY_LOUNGE = Map.of(
            "Front 1", List.of("Front 1"),
            "Front 2", List.of("Front 2", "Front 3"),
            "Institucional 1", List.of("Institucional 1", "Instrucional 1"),
            "Institucional Vereadores", List.of("Institucional Vereadores", "Instrucional Vereadores")
    );

    private final AreaRepository areaRepository;
    private final DeviceRepository deviceRepository;

    public LoungeAreaResolver(AreaRepository areaRepository) {
        this(areaRepository, null);
    }

    @Autowired
    public LoungeAreaResolver(AreaRepository areaRepository, DeviceRepository deviceRepository) {
        this.areaRepository = areaRepository;
        this.deviceRepository = deviceRepository;
    }

    /** Returns true when the lounge value identifies a collaborator (full-access). */
    public boolean isCollaboratorLounge(String invitedLounge) {
        return LoungeConfig.COLLABORATOR_LOUNGE.equalsIgnoreCase(
                invitedLounge == null ? "" : invitedLounge.trim());
    }

    /**
     * Áreas permitidas para um convidado a partir do nome do camarote.
     * - "Colaborador" → todas as áreas ativas (acesso total, mesma lógica de colaborador).
     * - Demais → Portaria ativa + área ativa cujo nome bate com o camarote.
     */
    public Set<Area> resolveForLounge(String invitedLounge) {
        log.info("LOUNGE_RESOLUTION_REQUEST invited_lounge={}", nullToBlank(invitedLounge));
        var normalized = LoungeConfig.canonicalLoungeName(invitedLounge);
        var acceptedAliases = acceptedAreaAliasesForLounge(invitedLounge);
        log.info("LOUNGE_RESOLUTION_ALIAS original={} normalized={} accepted_aliases=[{}]",
                nullToBlank(invitedLounge), nullToBlank(normalized), String.join(",", acceptedAliases));

        if (isCollaboratorLounge(invitedLounge)) {
            log.info("lounge_area_resolve_collaborator lounge={} result=all_active_areas", invitedLounge);
            var allAreas = resolveAllForEmployee();
            logResolutionResult(invitedLounge, allAreas);
            return allAreas;
        }
        var result = new LinkedHashSet<Area>();
        portariaArea().filter(Area::isActive).ifPresent(result::add);
        if (invitedLounge == null || invitedLounge.isBlank()) {
            logResolutionResult(invitedLounge, result);
            return result;
        }
        var loungeArea = activeAreaForLounge(invitedLounge);
        if (loungeArea.isPresent()) {
            result.add(loungeArea.get());
        } else {
            log.warn("lounge_area_not_active_or_not_found lounge={} normalized={} accepted_aliases=[{}] fallback=portaria_only",
                    invitedLounge, nullToBlank(normalized), String.join(",", acceptedAliases));
        }
        logResolutionResult(invitedLounge, result);
        return result;
    }

    /**
     * Todas as áreas ativas para acesso total de colaborador.
     */
    public Set<Area> resolveAllForEmployee() {
        return new LinkedHashSet<>(areaRepository.findAllByActiveTrue());
    }

    public Optional<Area> portariaArea() {
        return areaRepository.findByNameIgnoreCase(GENERAL_AREA_NAME);
    }

    public Optional<Area> activeAreaForLounge(String invitedLounge) {
        if (invitedLounge == null || invitedLounge.isBlank()) {
            return Optional.empty();
        }
        for (var alias : acceptedAreaAliasesForLounge(invitedLounge)) {
            var area = areaRepository.findByNameIgnoreCase(alias)
                    .filter(Area::isActive);
            if (area.isPresent()) {
                return area;
            }
        }
        return Optional.empty();
    }

    public List<String> acceptedAreaAliasesForLounge(String invitedLounge) {
        var canonical = LoungeConfig.canonicalLoungeName(invitedLounge);
        if (canonical == null || isCollaboratorLounge(canonical)) {
            return List.of();
        }
        return AREA_ALIASES_BY_LOUNGE.getOrDefault(canonical, List.of(canonical));
    }

    public boolean matchesLoungeArea(Area area, String invitedLounge) {
        if (area == null || area.getName() == null) {
            return false;
        }
        return acceptedAreaAliasesForLounge(invitedLounge).stream()
                .anyMatch(alias -> area.getName().trim().equalsIgnoreCase(alias));
    }

    public boolean isEmployeeFullAccess(Set<Area> allowedAreas) {
        if (allowedAreas == null || allowedAreas.isEmpty()) {
            return false;
        }
        var allActive = areaRepository.findAllByActiveTrue();
        if (allActive.isEmpty()) {
            return false;
        }
        var allowedIds = allowedAreas.stream().map(Area::getId).toList();
        return allActive.stream().allMatch(a -> allowedIds.contains(a.getId()));
    }

    public List<Area> findAllByIds(List<java.util.UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return areaRepository.findAllById(ids);
    }

    private void logResolutionResult(String invitedLounge, Set<Area> areas) {
        log.info("LOUNGE_RESOLUTION_RESULT invited_lounge={} area_ids=[{}] area_names=[{}] devices_count={}",
                nullToBlank(invitedLounge), areaIds(areas), areaNames(areas), activeDevicesCount(areas));
    }

    private String areaIds(Collection<Area> areas) {
        if (areas == null || areas.isEmpty()) {
            return "";
        }
        return areas.stream()
                .map(Area::getId)
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.joining(","));
    }

    private String areaNames(Collection<Area> areas) {
        if (areas == null || areas.isEmpty()) {
            return "";
        }
        return areas.stream()
                .map(Area::getName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(","));
    }

    private int activeDevicesCount(Set<Area> areas) {
        if (deviceRepository == null || areas == null || areas.isEmpty()) {
            return 0;
        }
        var areaIds = areas.stream()
                .map(Area::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (areaIds.isEmpty()) {
            return 0;
        }
        var devices = deviceRepository.findAllWithArea();
        if (devices == null || devices.isEmpty()) {
            devices = deviceRepository.findAll();
        }
        return (int) devices.stream()
                .filter(Device::isActive)
                .filter(device -> device.getArea() != null && areaIds.contains(device.getArea().getId()))
                .count();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
