package br.com.sport.accesscontrol.guests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuestInviteRepository extends JpaRepository<GuestInvite, UUID> {
    Optional<GuestInvite> findByToken(String token);
    List<GuestInvite> findByGuestIn(List<Guest> guests);
}
