package br.com.sport.accesscontrol.areas;

import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AreaService {

    private final AreaRepository areaRepository;

    public AreaService(AreaRepository areaRepository) {
        this.areaRepository = areaRepository;
    }

    @Transactional
    public AreaResponse create(AreaRequest request) {
        var area = new Area(request.name(), request.description(), request.active() == null || request.active());
        return AreaResponse.from(areaRepository.save(area));
    }

    @Transactional(readOnly = true)
    public List<AreaResponse> findAll() {
        return areaRepository.findAll().stream().map(AreaResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Area getById(UUID id) {
        return areaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Area not found: " + id));
    }
}
