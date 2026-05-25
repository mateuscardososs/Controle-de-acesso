package br.com.sport.accesscontrol.permissions;

import br.com.sport.accesscontrol.common.PersonType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface AccessPermissionRepository extends JpaRepository<AccessPermission, UUID> {
    @Modifying
    @Query("delete from AccessPermission p where p.personType = :personType and p.personId in :personIds")
    int deleteByPersonTypeAndPersonIdIn(@Param("personType") PersonType personType,
                                        @Param("personIds") Collection<UUID> personIds);
}
