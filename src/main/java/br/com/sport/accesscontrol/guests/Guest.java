package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.common.TimestampedEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guests")
public class Guest extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String cpf;

    private String email;

    @Column(name = "face_photo_url")
    private String facePhotoUrl;

    @Column(name = "visit_start", nullable = false)
    private Instant visitStart;

    @Column(name = "visit_end", nullable = false)
    private Instant visitEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuestStatus status = GuestStatus.INVITED;

    protected Guest() {
    }
}
