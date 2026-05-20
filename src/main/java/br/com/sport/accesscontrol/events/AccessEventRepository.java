package br.com.sport.accesscontrol.events;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccessEventRepository extends JpaRepository<AccessEvent, UUID> {
    List<AccessEvent> findAllByOrderByEventTimeDesc();

    long countByEventTimeBetween(Instant startInclusive, Instant endExclusive);

    long countByAccessResult(AccessResult accessResult);

    boolean existsByDevice_IdAndPersonIdAndEventTimeAndOrigin(UUID deviceId, UUID personId, Instant eventTime, String origin);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM access_events
                WHERE device_id = :deviceId
                  AND origin = :origin
                  AND raw_payload ->> 'RecNo' = :recNo
            )
            """, nativeQuery = true)
    boolean existsByDeviceIdAndOriginAndIntelbrasRecNo(
            @Param("deviceId") UUID deviceId,
            @Param("origin") String origin,
            @Param("recNo") String recNo
    );

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM access_events
                WHERE device_id = :deviceId
                  AND origin = :origin
                  AND COALESCE(raw_payload ->> 'CreateTime', '') = COALESCE(:createTime, '')
                  AND COALESCE(raw_payload ->> 'UserID', '') = COALESCE(:userId, '')
                  AND COALESCE(raw_payload ->> 'Door', '') = COALESCE(:door, '')
                  AND COALESCE(raw_payload ->> 'Method', '') = COALESCE(:method, '')
            )
            """, nativeQuery = true)
    boolean existsByDeviceIdAndOriginAndIntelbrasNaturalKey(
            @Param("deviceId") UUID deviceId,
            @Param("origin") String origin,
            @Param("createTime") String createTime,
            @Param("userId") String userId,
            @Param("door") String door,
            @Param("method") String method
    );
}
