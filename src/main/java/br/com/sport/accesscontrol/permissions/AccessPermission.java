package br.com.sport.accesscontrol.permissions;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.TimestampedEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_permissions")
public class AccessPermission extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "person_type", nullable = false)
    private PersonType personType;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(nullable = false)
    private boolean active = true;

    protected AccessPermission() {
    }

    public UUID getId() {
        return id;
    }
}
