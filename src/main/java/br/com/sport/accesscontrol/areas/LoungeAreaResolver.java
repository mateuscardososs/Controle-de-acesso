package br.com.sport.accesscontrol.areas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolve as áreas permitidas a partir de regras de negócio:
 * - convidado de qualquer camarote sempre recebe Portaria + a área do camarote;
 * - colaborador recebe TODAS as áreas (acesso total).
 */
@Service
public class LoungeAreaResolver {

    private static final Logger log = LoggerFactory.getLogger(LoungeAreaResolver.class);
    public static final String GENERAL_AREA_NAME = "Portaria";

    private final AreaRepository areaRepository;

    public LoungeAreaResolver(AreaRepository areaRepository) {
        this.areaRepository = areaRepository;
    }

    /**
     * Áreas permitidas para um convidado a partir do nome do camarote.
     * Sempre inclui Portaria ativa (se existir) + a área ativa cujo nome bate com o camarote.
     */
    public Set<Area> resolveForLounge(String invitedLounge) {
        var result = new LinkedHashSet<Area>();
        portariaArea().filter(Area::isActive).ifPresent(result::add);
        if (invitedLounge == null || invitedLounge.isBlank()) {
            return result;
        }
        var loungeArea = activeAreaForLounge(invitedLounge);
        if (loungeArea.isPresent()) {
            result.add(loungeArea.get());
        } else {
            log.warn("lounge_area_not_active_or_not_found lounge={} fallback=portaria_only", invitedLounge);
        }
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
        return areaRepository.findByNameIgnoreCase(invitedLounge.trim())
                .filter(Area::isActive);
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
}
