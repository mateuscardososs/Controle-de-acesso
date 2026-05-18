package br.com.sport.accesscontrol.areas;

import java.time.Instant;
import java.util.UUID;

public record AreaResponse(
        UUID id,
        String name,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    static AreaResponse from(Area area) {
        return new AreaResponse(
                area.getId(),
                area.getName(),
                area.getDescription(),
                area.isActive(),
                area.getCreatedAt(),
                area.getUpdatedAt()
        );
    }
}
