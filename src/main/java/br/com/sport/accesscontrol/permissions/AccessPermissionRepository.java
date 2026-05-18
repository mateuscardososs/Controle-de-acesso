package br.com.sport.accesscontrol.permissions;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccessPermissionRepository extends JpaRepository<AccessPermission, UUID> {
}
