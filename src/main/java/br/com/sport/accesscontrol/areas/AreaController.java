package br.com.sport.accesscontrol.areas;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/areas")
public class AreaController {

    private final AreaService areaService;

    public AreaController(AreaService areaService) {
        this.areaService = areaService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AreaResponse create(@Valid @RequestBody AreaRequest request) {
        return areaService.create(request);
    }

    @GetMapping
    List<AreaResponse> findAll() {
        return areaService.findAll();
    }
}
