package br.com.sport.accesscontrol.guests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GuestRepository extends JpaRepository<Guest, UUID> {
}
