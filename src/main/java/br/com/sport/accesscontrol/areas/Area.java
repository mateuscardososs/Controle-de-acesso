package br.com.sport.accesscontrol.areas;

import br.com.sport.accesscontrol.common.TimestampedEntity;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "areas")
public class Area extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private boolean active = true;

    protected Area() {
    }

    public Area(String name, String description, boolean active) {
        this.name = name;
        this.description = description;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }
}
