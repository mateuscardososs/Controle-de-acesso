package br.com.sport.accesscontrol.events;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccessEventRepository extends JpaRepository<AccessEvent, UUID>, JpaSpecificationExecutor<AccessEvent> {
    List<AccessEvent> findAllByOrderByEventTimeDesc();
    List<AccessEvent> findAllByOrderByEventTimeDesc(Pageable pageable);

    long countByEventTimeBetween(Instant startInclusive, Instant endExclusive);

    long countByAccessResult(AccessResult accessResult);

    @Query(value = """
            SELECT CAST(EXTRACT(HOUR FROM event_time AT TIME ZONE :zoneId) AS INTEGER) AS hour,
                   COUNT(*) FILTER (WHERE event_type = 'ENTRY') AS entries,
                   COUNT(*) FILTER (WHERE event_type = 'EXIT') AS exits,
                   COUNT(*) FILTER (WHERE passage_status = 'PASSED') AS passages,
                   COUNT(*) FILTER (WHERE access_result = 'ALLOWED') AS allowed,
                   COUNT(*) FILTER (WHERE access_result = 'DENIED') AS denied
            FROM access_events
            WHERE event_time >= :startInclusive
              AND event_time < :endExclusive
            GROUP BY hour
            ORDER BY hour
            """, nativeQuery = true)
    List<TrafficPeakRow> trafficPeaksByHour(
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("zoneId") String zoneId
    );

    boolean existsByDevice_IdAndPersonIdAndEventTimeAndOrigin(UUID deviceId, UUID personId, Instant eventTime, String origin);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM access_events
                WHERE device_id = :deviceId
                  AND origin = :origin
                  AND COALESCE(controller_rec_no, raw_payload ->> 'RecNo') = :recNo
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

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM access_events
                WHERE device_id = :deviceId
                  AND origin = :origin
                  AND event_time BETWEEN :start AND :end
                  AND COALESCE(external_user_id, raw_payload ->> 'UserID', '') = COALESCE(:userId, '')
                  AND COALESCE(controller_door, raw_payload ->> 'Door', '') = COALESCE(:door, '')
                  AND COALESCE(controller_method, raw_payload ->> 'Method', '') = COALESCE(:method, '')
            )
            """, nativeQuery = true)
    boolean existsByDeviceIdAndOriginAndIntelbrasDedupWindow(
            @Param("deviceId") UUID deviceId,
            @Param("origin") String origin,
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("userId") String userId,
            @Param("door") String door,
            @Param("method") String method
    );

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM access_events
                WHERE device_id = :deviceId
                  AND access_result = 'ALLOWED'
                  AND cooldown_blocked = FALSE
                  AND event_time >= :since
                  AND (
                      (person_cpf IS NOT NULL AND person_cpf = :personCpf)
                   OR (person_id IS NOT NULL AND CAST(person_id AS VARCHAR) = :personId)
                   OR (external_user_id IS NOT NULL AND external_user_id = :externalUserId)
                  )
            )
            """, nativeQuery = true)
    boolean existsAllowedEventInCooldownWindow(
            @Param("deviceId") UUID deviceId,
            @Param("since") Instant since,
            @Param("personCpf") String personCpf,
            @Param("personId") String personId,
            @Param("externalUserId") String externalUserId
    );

    interface TrafficPeakRow {
        Integer getHour();

        Long getEntries();

        Long getExits();

        Long getPassages();

        Long getAllowed();

        Long getDenied();
    }
}
