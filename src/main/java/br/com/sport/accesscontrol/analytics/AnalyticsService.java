package br.com.sport.accesscontrol.analytics;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final EntityManager em;

    private static final String TZ = AnalyticsFilters.ZONE_ID;
    private static final String[] DOW_LABELS = {"", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"};

    public AnalyticsService(EntityManager em) {
        this.em = em;
    }

    // ─── Overview ─────────────────────────────────────────────────────────────

    public OverviewResponse overview(AnalyticsFilters f) {
        var wc = buildWhere("ae", f);

        var sql = """
                SELECT
                    COUNT(*),
                    COUNT(*) FILTER (WHERE ae.event_type = 'ENTRY'),
                    COUNT(*) FILTER (WHERE ae.event_type = 'EXIT'),
                    COUNT(DISTINCT COALESCE(CAST(ae.person_id AS TEXT), ae.person_cpf)),
                    COUNT(*) FILTER (WHERE ae.person_type = 'GUEST'),
                    COUNT(*) FILTER (WHERE ae.person_type = 'EMPLOYEE'),
                    COUNT(*) FILTER (WHERE ae.release_method = 'FACIAL_RECOGNITION'),
                    COUNT(*) FILTER (WHERE ae.release_method = 'CARD'),
                    COUNT(*) FILTER (WHERE ae.access_result = 'DENIED')
                FROM access_events ae
                """ + wc.sql();

        var q = em.createNativeQuery(sql);
        applyParams(q, wc);

        Object[] row = (Object[]) q.getSingleResult();
        long total = toLong(row[0]);
        long denied = toLong(row[8]);
        double successRate = total == 0 ? 100.0 : Math.round((total - denied) * 1000.0 / total) / 10.0;

        long[] deviceCounts = countDeviceStatuses();
        DwellStats dwell = computeDwellTime(f);

        return new OverviewResponse(
                total, toLong(row[1]), toLong(row[2]), toLong(row[3]),
                toLong(row[4]), toLong(row[5]), toLong(row[6]), toLong(row[7]),
                denied, successRate, deviceCounts[0], deviceCounts[1],
                dwell.avg(), dwell.max(), dwell.min()
        );
    }

    // ─── Timeline ─────────────────────────────────────────────────────────────

    public List<TimelinePoint> timeline(AnalyticsFilters f, String granularity) {
        var gran = granularity == null ? "HOUR" : granularity.toUpperCase();
        var wc = buildWhere("ae", f);

        var sql = "SELECT " + labelExpr(gran) + " AS label,"
                + " COUNT(*) FILTER (WHERE ae.event_type = 'ENTRY'),"
                + " COUNT(*) FILTER (WHERE ae.event_type = 'EXIT'),"
                + " COUNT(*)"
                + " FROM access_events ae " + wc.sql()
                + " GROUP BY " + truncExpr(gran)
                + " ORDER BY " + truncExpr(gran);

        var q = em.createNativeQuery(sql);
        applyParams(q, wc);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new TimelinePoint((String) r[0], toLong(r[1]), toLong(r[2]), toLong(r[3])))
                .toList();
    }

    // ─── Heatmap ──────────────────────────────────────────────────────────────

    public List<HeatmapPoint> heatmap(AnalyticsFilters f) {
        var wc = buildWhere("ae", f);

        var sql = "SELECT"
                + " EXTRACT(ISODOW FROM ae.event_time AT TIME ZONE '" + TZ + "')::int,"
                + " EXTRACT(HOUR  FROM ae.event_time AT TIME ZONE '" + TZ + "')::int,"
                + " COUNT(*)"
                + " FROM access_events ae " + wc.sql()
                + " GROUP BY 1, 2 ORDER BY 1, 2";

        var q = em.createNativeQuery(sql);
        applyParams(q, wc);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        var sparse = rows.stream().collect(Collectors.toMap(
                r -> (int) toLong(r[0]) * 100 + (int) toLong(r[1]),
                r -> toLong(r[2])
        ));

        var result = new ArrayList<HeatmapPoint>(7 * 24);
        for (int dow = 1; dow <= 7; dow++) {
            for (int hour = 0; hour < 24; hour++) {
                result.add(new HeatmapPoint(dow, DOW_LABELS[dow], hour, sparse.getOrDefault(dow * 100 + hour, 0L)));
            }
        }
        return result;
    }

    // ─── Auth Methods ─────────────────────────────────────────────────────────

    public AuthMethodStats authMethods(AnalyticsFilters f) {
        var wc = buildWhere("ae", f);

        var sql = "SELECT"
                + " COUNT(*) FILTER (WHERE ae.release_method = 'FACIAL_RECOGNITION'),"
                + " COUNT(*) FILTER (WHERE ae.release_method = 'CARD'),"
                + " COUNT(*) FILTER (WHERE ae.release_method = 'MANUAL_ADMIN_RELEASE'),"
                + " COUNT(*) FILTER (WHERE ae.release_method NOT IN ('FACIAL_RECOGNITION','CARD','MANUAL_ADMIN_RELEASE') OR ae.release_method IS NULL),"
                + " COUNT(*)"
                + " FROM access_events ae " + wc.sql();

        var q = em.createNativeQuery(sql);
        applyParams(q, wc);

        Object[] row = (Object[]) q.getSingleResult();
        return new AuthMethodStats(toLong(row[0]), toLong(row[1]), toLong(row[2]), toLong(row[3]), toLong(row[4]));
    }

    // ─── Controllers ──────────────────────────────────────────────────────────

    public List<ControllerStatsItem> controllers(AnalyticsFilters f) {
        var jc = buildDeviceJoinConditions(f);

        var sql = "SELECT CAST(d.id AS TEXT), d.name, a.name, d.status,"
                + " d.last_seen_at, d.communication_failures,"
                + " COUNT(ae.id), COUNT(ae.id) FILTER (WHERE ae.access_result = 'DENIED'), MAX(ae.event_time)"
                + " FROM devices d"
                + " LEFT JOIN areas a ON a.id = d.area_id"
                + " LEFT JOIN access_events ae ON " + jc.sql()
                + " GROUP BY d.id, d.name, a.name, d.status, d.last_seen_at, d.communication_failures"
                + " ORDER BY COUNT(ae.id) DESC";

        var q = em.createNativeQuery(sql);
        applyParams(q, jc);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new ControllerStatsItem(
                        UUID.fromString((String) r[0]),
                        (String) r[1],
                        (String) r[2],
                        (String) r[3],
                        toInstant(r[4]),
                        toLong(r[5]),
                        toLong(r[6]),
                        toLong(r[7]),
                        toInstant(r[8])
                ))
                .toList();
    }

    // ─── Areas ────────────────────────────────────────────────────────────────

    public List<AreaStatsItem> areas(AnalyticsFilters f) {
        var ic = buildEventSubConditions(f);

        var sql = "SELECT CAST(a.id AS TEXT), a.name, COALESCE(sub.cnt, 0)"
                + " FROM areas a"
                + " LEFT JOIN ("
                + "   SELECT ae.area_id, COUNT(*) AS cnt"
                + "   FROM access_events ae"
                + "   WHERE " + ic.sql()
                + "   GROUP BY ae.area_id"
                + " ) sub ON sub.area_id = a.id"
                + " ORDER BY 3 DESC";

        var q = em.createNativeQuery(sql);
        applyParams(q, ic);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new AreaStatsItem(UUID.fromString((String) r[0]), (String) r[1], toLong(r[2])))
                .toList();
    }

    // ─── Top Users ────────────────────────────────────────────────────────────

    public List<UserRankItem> topUsers(AnalyticsFilters f, String personTypeFilter) {
        var wc = buildWhere("ae", f);
        var extra = (personTypeFilter != null && !personTypeFilter.isBlank())
                ? " AND ae.person_type = '" + personTypeFilter.replace("'", "") + "'"
                : "";

        var sql = "SELECT ae.person_type, CAST(ae.person_id AS TEXT),"
                + " MAX(ae.person_name), ae.person_cpf, COUNT(*)"
                + " FROM access_events ae " + wc.sql() + extra
                + " AND ae.access_result = 'ALLOWED'"
                + " AND (ae.person_id IS NOT NULL OR ae.person_cpf IS NOT NULL)"
                + " GROUP BY ae.person_type, ae.person_id, ae.person_cpf"
                + " ORDER BY COUNT(*) DESC LIMIT 20";

        var q = em.createNativeQuery(sql);
        applyParams(q, wc);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new UserRankItem(
                        (String) r[0],
                        r[1] != null ? UUID.fromString((String) r[1]) : null,
                        (String) r[2],
                        (String) r[3],
                        toLong(r[4])
                ))
                .toList();
    }

    // ─── Denials ──────────────────────────────────────────────────────────────

    public DenialsResponse denials(AnalyticsFilters f) {
        var wc = buildWhere("ae", f);
        var denialFilter = " AND ae.access_result = 'DENIED'";

        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM access_events ae " + wc.sql() + denialFilter);
        applyParams(countQ, wc);
        long total = toLong(countQ.getSingleResult());

        // by hour
        var hourSql = "SELECT"
                + " TO_CHAR(ae.event_time AT TIME ZONE '" + TZ + "', 'DD/MM HH24\"h\"') AS label,"
                + " 0::bigint, 0::bigint, COUNT(*)"
                + " FROM access_events ae " + wc.sql() + denialFilter
                + " GROUP BY DATE_TRUNC('hour', ae.event_time AT TIME ZONE '" + TZ + "'), label"
                + " ORDER BY DATE_TRUNC('hour', ae.event_time AT TIME ZONE '" + TZ + "')";
        var hourQ = em.createNativeQuery(hourSql);
        applyParams(hourQ, wc);
        @SuppressWarnings("unchecked")
        List<Object[]> hourRows = hourQ.getResultList();
        var byHour = hourRows.stream()
                .map(r -> new TimelinePoint((String) r[0], 0L, 0L, toLong(r[3])))
                .toList();

        // recent
        var recentSql = "SELECT CAST(ae.id AS TEXT), ae.event_time, ae.person_name, ae.person_cpf,"
                + " d.name, a.name, ae.decision_reason, ae.release_method"
                + " FROM access_events ae"
                + " LEFT JOIN devices d ON d.id = ae.device_id"
                + " LEFT JOIN areas a ON a.id = ae.area_id"
                + " " + wc.sql() + denialFilter
                + " ORDER BY ae.event_time DESC LIMIT 50";
        var recentQ = em.createNativeQuery(recentSql);
        applyParams(recentQ, wc);
        @SuppressWarnings("unchecked")
        List<Object[]> recentRows = recentQ.getResultList();
        var recent = recentRows.stream()
                .map(r -> new DenialItem(
                        UUID.fromString((String) r[0]),
                        toInstant(r[1]),
                        (String) r[2],
                        (String) r[3],
                        (String) r[4],
                        (String) r[5],
                        (String) r[6],
                        (String) r[7]
                ))
                .toList();

        return new DenialsResponse(total, byHour, recent);
    }

    // ─── Presence ─────────────────────────────────────────────────────────────

    public List<PresenceItem> presence(AnalyticsFilters f) {
        var sql = """
                WITH last_per_person AS (
                    SELECT DISTINCT ON (COALESCE(CAST(ae.person_id AS TEXT), ae.person_cpf))
                        ae.person_type,
                        CAST(ae.person_id AS TEXT) AS person_id,
                        ae.person_name,
                        ae.person_cpf,
                        ae.area_id,
                        ae.event_type,
                        ae.event_time
                    FROM access_events ae
                    WHERE ae.access_result = 'ALLOWED'
                      AND (ae.person_id IS NOT NULL OR ae.person_cpf IS NOT NULL)
                      AND ae.event_time >= :from
                    ORDER BY COALESCE(CAST(ae.person_id AS TEXT), ae.person_cpf), ae.event_time DESC
                )
                SELECT
                    lp.person_type,
                    lp.person_id,
                    lp.person_name,
                    lp.person_cpf,
                    a.name,
                    lp.event_time,
                    EXTRACT(EPOCH FROM (NOW() - lp.event_time))::bigint / 60
                FROM last_per_person lp
                JOIN areas a ON a.id = lp.area_id
                WHERE lp.event_type = 'ENTRY'
                ORDER BY lp.event_time DESC
                LIMIT 200
                """;

        var q = em.createNativeQuery(sql);
        q.setParameter("from", f.from());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new PresenceItem(
                        (String) r[0],
                        r[1] != null ? UUID.fromString((String) r[1]) : null,
                        (String) r[2],
                        (String) r[3],
                        (String) r[4],
                        toInstant(r[5]),
                        toLong(r[6])
                ))
                .toList();
    }

    // ─── Peaks ────────────────────────────────────────────────────────────────

    public PeaksResponse peaks(AnalyticsFilters f) {
        var wc = buildWhere("ae", f);
        return new PeaksResponse(
                peakFor(wc, "ENTRY"),
                peakFor(wc, "EXIT"),
                busiestPeriod(wc, "day", "DD/MM/YYYY"),
                busiestPeriod(wc, "week", "DD/MM/YYYY"),
                busiestPeriod(wc, "month", "MM/YYYY")
        );
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private PeakItem peakFor(WhereClause wc, String eventType) {
        var sql = "SELECT TO_CHAR(DATE_TRUNC('hour', ae.event_time AT TIME ZONE '" + TZ + "'), 'DD/MM/YYYY HH24\"h\"') AS lbl,"
                + " COUNT(*) AS cnt"
                + " FROM access_events ae " + wc.sql()
                + " AND ae.event_type = '" + eventType + "'"
                + " GROUP BY lbl ORDER BY cnt DESC LIMIT 1";
        var q = em.createNativeQuery(sql);
        applyParams(q, wc);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        if (rows.isEmpty()) return new PeakItem("—", 0L);
        Object[] r = rows.get(0);
        return new PeakItem((String) r[0], toLong(r[1]));
    }

    private PeakItem busiestPeriod(WhereClause wc, String truncUnit, String format) {
        var sql = "SELECT TO_CHAR(DATE_TRUNC('" + truncUnit + "', ae.event_time AT TIME ZONE '" + TZ + "'), '" + format + "') AS lbl,"
                + " COUNT(*) AS cnt"
                + " FROM access_events ae " + wc.sql()
                + " GROUP BY lbl ORDER BY cnt DESC LIMIT 1";
        var q = em.createNativeQuery(sql);
        applyParams(q, wc);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        if (rows.isEmpty()) return new PeakItem("—", 0L);
        Object[] r = rows.get(0);
        return new PeakItem((String) r[0], toLong(r[1]));
    }

    private long[] countDeviceStatuses() {
        var q = em.createNativeQuery("SELECT status, COUNT(*) FROM devices GROUP BY status");
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        long online = 0, offline = 0;
        for (Object[] r : rows) {
            String status = (String) r[0];
            long cnt = toLong(r[1]);
            if ("ONLINE".equals(status)) online += cnt;
            else offline += cnt;
        }
        return new long[]{online, offline};
    }

    private DwellStats computeDwellTime(AnalyticsFilters f) {
        var wc = buildWhere("ae", f);
        var sql = """
                WITH entries AS (
                    SELECT COALESCE(CAST(ae.person_id AS TEXT), ae.person_cpf) AS pk,
                           ae.event_time,
                           ROW_NUMBER() OVER (
                               PARTITION BY COALESCE(CAST(ae.person_id AS TEXT), ae.person_cpf)
                               ORDER BY ae.event_time
                           ) AS rn
                    FROM access_events ae
                    """ + wc.sql() + """
                      AND ae.event_type = 'ENTRY' AND ae.access_result = 'ALLOWED'
                      AND (ae.person_id IS NOT NULL OR ae.person_cpf IS NOT NULL)
                ),
                exits AS (
                    SELECT COALESCE(CAST(ae.person_id AS TEXT), ae.person_cpf) AS pk,
                           ae.event_time,
                           ROW_NUMBER() OVER (
                               PARTITION BY COALESCE(CAST(ae.person_id AS TEXT), ae.person_cpf)
                               ORDER BY ae.event_time
                           ) AS rn
                    FROM access_events ae
                    """ + wc.sql() + """
                      AND ae.event_type = 'EXIT' AND ae.access_result = 'ALLOWED'
                      AND (ae.person_id IS NOT NULL OR ae.person_cpf IS NOT NULL)
                ),
                matched AS (
                    SELECT EXTRACT(EPOCH FROM (ex.event_time - en.event_time)) / 60.0 AS minutes
                    FROM entries en
                    JOIN exits ex ON ex.pk = en.pk AND ex.rn = en.rn
                    WHERE ex.event_time > en.event_time
                      AND ex.event_time - en.event_time < INTERVAL '24 hours'
                )
                SELECT AVG(minutes)::float8, MAX(minutes)::bigint, MIN(minutes)::bigint
                FROM matched
                """;

        var q = em.createNativeQuery(sql);
        applyParams(q, wc);

        Object[] row = (Object[]) q.getSingleResult();
        if (row[0] == null) return new DwellStats(null, null, null);
        double avg = row[0] instanceof Double d ? d : ((Number) row[0]).doubleValue();
        return new DwellStats(Math.round(avg * 10.0) / 10.0, toLong(row[1]), toLong(row[2]));
    }

    // ─── WHERE clause builder ─────────────────────────────────────────────────

    private record WhereClause(String sql, Map<String, Object> params) {}
    private record DwellStats(Double avg, Long max, Long min) {}

    private WhereClause buildWhere(String alias, AnalyticsFilters f) {
        var inner = buildConditions(alias, f);
        return new WhereClause("WHERE " + inner.sql(), inner.params());
    }

    private WhereClause buildConditions(String alias, AnalyticsFilters f) {
        var sb = new StringBuilder(alias + ".event_time >= :from AND " + alias + ".event_time < :to");
        var params = new LinkedHashMap<String, Object>();
        params.put("from", f.from());
        params.put("to", f.to());
        if (f.deviceId() != null) {
            sb.append(" AND ").append(alias).append(".device_id = :deviceId");
            params.put("deviceId", f.deviceId());
        }
        if (f.areaId() != null) {
            sb.append(" AND ").append(alias).append(".area_id = :areaId");
            params.put("areaId", f.areaId());
        }
        if (f.personType() != null && !f.personType().isBlank()) {
            sb.append(" AND ").append(alias).append(".person_type = :personType");
            params.put("personType", f.personType());
        }
        if (f.releaseMethod() != null && !f.releaseMethod().isBlank()) {
            sb.append(" AND ").append(alias).append(".release_method = :releaseMethod");
            params.put("releaseMethod", f.releaseMethod());
        }
        return new WhereClause(sb.toString(), params);
    }

    private WhereClause buildEventSubConditions(AnalyticsFilters f) {
        return buildConditions("ae", f);
    }

    private WhereClause buildDeviceJoinConditions(AnalyticsFilters f) {
        var sb = new StringBuilder("ae.device_id = d.id AND ae.event_time >= :from AND ae.event_time < :to");
        var params = new LinkedHashMap<String, Object>();
        params.put("from", f.from());
        params.put("to", f.to());
        if (f.areaId() != null) {
            sb.append(" AND ae.area_id = :areaId");
            params.put("areaId", f.areaId());
        }
        if (f.personType() != null && !f.personType().isBlank()) {
            sb.append(" AND ae.person_type = :personType");
            params.put("personType", f.personType());
        }
        if (f.releaseMethod() != null && !f.releaseMethod().isBlank()) {
            sb.append(" AND ae.release_method = :releaseMethod");
            params.put("releaseMethod", f.releaseMethod());
        }
        return new WhereClause(sb.toString(), params);
    }

    private void applyParams(Query q, WhereClause wc) {
        wc.params().forEach(q::setParameter);
    }

    // ─── Type conversions ─────────────────────────────────────────────────────

    private static long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Long l) return l;
        if (val instanceof BigInteger bi) return bi.longValue();
        if (val instanceof BigDecimal bd) return bd.longValue();
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private static Instant toInstant(Object val) {
        if (val == null) return null;
        if (val instanceof Timestamp ts) return ts.toInstant();
        if (val instanceof java.util.Date d) return d.toInstant();
        if (val instanceof Instant i) return i;
        return null;
    }

    // ─── SQL helpers ──────────────────────────────────────────────────────────

    private String truncExpr(String gran) {
        return switch (gran) {
            case "DAY"   -> "DATE_TRUNC('day',   ae.event_time AT TIME ZONE '" + TZ + "')";
            case "WEEK"  -> "DATE_TRUNC('week',  ae.event_time AT TIME ZONE '" + TZ + "')";
            case "MONTH" -> "DATE_TRUNC('month', ae.event_time AT TIME ZONE '" + TZ + "')";
            default      -> "DATE_TRUNC('hour',  ae.event_time AT TIME ZONE '" + TZ + "')";
        };
    }

    private String labelExpr(String gran) {
        return switch (gran) {
            case "DAY"   -> "TO_CHAR(ae.event_time AT TIME ZONE '" + TZ + "', 'DD/MM')";
            case "WEEK"  -> "TO_CHAR(DATE_TRUNC('week', ae.event_time AT TIME ZONE '" + TZ + "'), 'DD/MM/YYYY')";
            case "MONTH" -> "TO_CHAR(ae.event_time AT TIME ZONE '" + TZ + "', 'MM/YYYY')";
            default      -> "TO_CHAR(ae.event_time AT TIME ZONE '" + TZ + "', 'DD/MM HH24\"h\"')";
        };
    }
}
