package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.appconfig.LoungeConfig;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.CpfValidator;
import br.com.sport.accesscontrol.guests.GuestImportDtos.ImportPreviewResponse;
import br.com.sport.accesscontrol.guests.GuestImportDtos.ImportPreviewRow;
import br.com.sport.accesscontrol.guests.GuestImportDtos.ImportReport;
import br.com.sport.accesscontrol.guests.GuestImportDtos.ImportRowError;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class GuestImportService {

    private static final Logger log = LoggerFactory.getLogger(GuestImportService.class);

    private static final int MAX_ROWS = 1000;
    private static final ZoneId EVENT_ZONE = ZoneId.of("America/Recife");
    private static final java.time.LocalTime ACCESS_START = java.time.LocalTime.of(15, 0);
    private static final java.time.LocalTime ACCESS_END = java.time.LocalTime.of(4, 0);
    private static final Map<String, String> REQUIRED_COLUMNS;

    // Normalized header alias → canonical field name
    private static final Map<String, String> HEADER_ALIASES;

    static {
        var map = new LinkedHashMap<String, String>();
        for (var h : List.of("nome", "nomecompleto", "name", "fullname", "completename", "nomevisitante"))
            map.put(h, "fullName");
        for (var h : List.of("cpf", "cpfrg", "documento", "doc", "cpfdoc"))
            map.put(h, "cpf");
        for (var h : List.of("telefone", "celular", "cel", "phone", "fone", "contato", "tel"))
            map.put(h, "phone");
        for (var h : List.of("camarote", "lounge", "camaroteconvidado", "invitedlounge", "setor"))
            map.put(h, "invitedLounge");
        for (var h : List.of("dia", "data", "diaconvidado", "dataconvidado", "datadoevento", "diaevento", "invitedday"))
            map.put(h, "invitedDay");
        HEADER_ALIASES = Collections.unmodifiableMap(map);

        var required = new LinkedHashMap<String, String>();
        required.put("fullName", "Nome Completo");
        required.put("cpf", "CPF");
        required.put("phone", "Telefone");
        required.put("invitedLounge", "Camarote");
        REQUIRED_COLUMNS = Collections.unmodifiableMap(required);
    }

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yyyy")
    );

    private final GuestRepository guestRepository;
    private final LoungeConfig loungeConfig;
    private final AuditService auditService;

    public GuestImportService(GuestRepository guestRepository,
                               LoungeConfig loungeConfig,
                               AuditService auditService) {
        this.guestRepository = guestRepository;
        this.loungeConfig = loungeConfig;
        this.auditService = auditService;
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    /** Parses the first 5 data rows for preview. No DB writes. */
    public ImportPreviewResponse preview(MultipartFile file) throws IOException {
        var rawRows = parseFile(file);
        if (rawRows.isEmpty()) {
            return new ImportPreviewResponse(0, List.of(), requiredColumnLabels(), List.of());
        }
        var colMapping = detectHeaders(rawRows.get(0));
        var detectedHeaders = new ArrayList<>(colMapping.values());
        var missingCols = missingRequiredColumns(colMapping);

        int dataStart = 1;
        int totalData = rawRows.size() - dataStart;
        var dataSlice = rawRows.subList(dataStart, Math.min(rawRows.size(), dataStart + 5));
        var preview = new ArrayList<ImportPreviewRow>();
        for (int i = 0; i < dataSlice.size(); i++) {
            var v = mapRow(dataSlice.get(i), colMapping);
            preview.add(new ImportPreviewRow(
                    dataStart + i + 1,
                    v.getOrDefault("fullName", ""),
                    v.getOrDefault("cpf", ""),
                    v.getOrDefault("phone", ""),
                    v.getOrDefault("invitedLounge", ""),
                    v.getOrDefault("invitedDay", "")
            ));
        }
        log.info("GUEST_IMPORT_PREVIEW file={} total_rows={} detected={} missing={}",
                file.getOriginalFilename(), totalData, detectedHeaders, missingCols);
        return new ImportPreviewResponse(totalData, detectedHeaders, missingCols, preview);
    }

    /** Parses and persists up to MAX_ROWS guests. Returns full report. */
    @Transactional(timeout = 30)
    public ImportReport importFile(MultipartFile file) throws IOException {
        var rawRows = parseFile(file);
        if (rawRows.isEmpty()) {
            return new ImportReport(0, 0, 0, 0, List.of());
        }
        var colMapping = detectHeaders(rawRows.get(0));
        var missingColumns = missingRequiredColumns(colMapping);
        if (!missingColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Planilha inválida: colunas obrigatórias ausentes: " + String.join(", ", missingColumns) + ".");
        }
        int dataStart = 1;
        int dataRowCount = rawRows.size() - dataStart;
        if (dataRowCount > MAX_ROWS) {
            throw new IllegalArgumentException("Limite de 1000 linhas por importação excedido.");
        }
        var dataRows = rawRows.subList(dataStart, rawRows.size());

        int created = 0, updated = 0, skipped = 0;
        var errors = new ArrayList<ImportRowError>();

        for (int i = 0; i < dataRows.size(); i++) {
            int line = dataStart + i + 1;
            var values = mapRow(dataRows.get(i), colMapping);
            var result = processRow(line, values);
            switch (result.outcome()) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case SKIPPED -> skipped++;
                case ERROR -> errors.add(new ImportRowError(line, result.cpf(), result.reason()));
            }
        }
        int total = created + updated + skipped + errors.size();
        log.info("GUEST_IMPORT_DONE file={} total={} created={} updated={} skipped={} errors={}",
                file.getOriginalFilename(), total, created, updated, skipped, errors.size());
        auditService.record("GUEST_BULK_IMPORT", "Guest", null,
                java.util.Map.of(
                        "file", Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                        "total", total,
                        "created", created,
                        "updated", updated,
                        "skipped", skipped,
                        "errors", errors.size()
                ),
                java.util.Map.of(), java.util.Map.of());
        return new ImportReport(total, created, updated, skipped, errors);
    }

    /** Generates an xlsx template with headers and 2 example rows. */
    public byte[] generateTemplate() {
        try (var wb = new XSSFWorkbook();
             var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Convidados");
            var headerStyle = wb.createCellStyle();
            var headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            var header = sheet.createRow(0);
            String[] cols = {"Nome Completo", "CPF", "Telefone", "Camarote", "Dia Convidado"};
            int[] widths = {7000, 4000, 4000, 5000, 4000};
            for (int i = 0; i < cols.length; i++) {
                var cell = header.createCell(i);
                cell.setCellValue(cols[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, widths[i]);
            }

            var validLounges = loungeConfig.getLounges();
            String lounge1 = validLounges.isEmpty() ? "Front 1" : validLounges.get(0);
            String lounge2 = validLounges.size() > 1 ? validLounges.get(1) : lounge1;
            String eventDay = LocalDate.now(EVENT_ZONE).plusDays(7).toString();

            fillExampleRow(sheet, 1, "João da Silva",    "529.982.247-25", "81 99999-0000", lounge1, eventDay);
            fillExampleRow(sheet, 2, "Maria Santos",     "111.444.777-35", "81 88888-0000", lounge2, eventDay);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao gerar template", e);
        }
    }

    // ─── Parsing ─────────────────────────────────────────────────────────────────

    private List<List<String>> parseFile(MultipartFile file) throws IOException {
        var name = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) return parseXlsx(file);
        if (name.endsWith(".xls"))  return parseXls(file);
        if (name.endsWith(".csv"))  return parseCsv(file);
        if (name.endsWith(".pdf"))  return parsePdf(file);
        throw new IllegalArgumentException(
                "Formato não suportado. Use .xlsx, .xls, .csv ou .pdf");
    }

    private List<List<String>> parseXlsx(MultipartFile file) throws IOException {
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            return readWorkbookSheet(wb);
        }
    }

    private List<List<String>> parseXls(MultipartFile file) throws IOException {
        try (Workbook wb = new HSSFWorkbook(file.getInputStream())) {
            return readWorkbookSheet(wb);
        }
    }

    private List<List<String>> readWorkbookSheet(Workbook wb) {
        Sheet sheet = wb.getSheetAt(0);
        var rows = new ArrayList<List<String>>();
        int maxCols = 0;
        for (Row row : sheet) {
            if (row != null) maxCols = Math.max(maxCols, row.getLastCellNum());
        }
        for (Row row : sheet) {
            if (row == null) continue;
            var cells = new ArrayList<String>(maxCols);
            boolean anyValue = false;
            for (int c = 0; c < maxCols; c++) {
                String v = cellValue(row.getCell(c));
                cells.add(v);
                if (!v.isBlank()) anyValue = true;
            }
            if (anyValue) rows.add(cells);
        }
        return rows;
    }

    private String cellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE);
                }
                // CPF stored as number in Excel may lose leading zero → pad to 11 digits
                long l = (long) cell.getNumericCellValue();
                String raw = String.valueOf(l);
                // Pad only if it looks like a CPF (9-11 digits)
                if (raw.length() >= 9 && raw.length() <= 11 && raw.matches("\\d+")) {
                    yield String.format("%011d", l);
                }
                yield raw;
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) { yield String.valueOf((long) cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    private List<List<String>> parseCsv(MultipartFile file) throws IOException {
        var content = new String(file.getBytes(), StandardCharsets.UTF_8);
        var fmt = CSVFormat.DEFAULT.builder()
                .setDelimiter(detectCsvDelimiter(content))
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (var reader = new StringReader(content);
             var parser = new CSVParser(reader, fmt)) {
            var rows = new ArrayList<List<String>>();
            for (var record : parser) {
                var row = new ArrayList<String>();
                for (var v : record) row.add(Objects.requireNonNullElse(v, "").trim());
                if (row.stream().anyMatch(v -> !v.isBlank())) rows.add(row);
            }
            return rows;
        }
    }

    private char detectCsvDelimiter(String content) {
        var firstLine = content == null ? "" : content.lines().findFirst().orElse("");
        return List.of(';', ',', '\t', '|').stream()
                .max(java.util.Comparator.comparingInt(delimiter -> countOccurrences(firstLine, delimiter)))
                .filter(delimiter -> countOccurrences(firstLine, delimiter) > 0)
                .orElse(',');
    }

    private List<List<String>> parsePdf(MultipartFile file) throws IOException {
        try (var doc = PDDocument.load(file.getInputStream())) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            var text = stripper.getText(doc);
            var rows = new ArrayList<List<String>>();
            for (var line : text.split("\\r?\\n")) {
                var trimmed = line.trim();
                if (trimmed.isBlank()) continue;
                var parts = splitPdfLine(trimmed);
                var row = new ArrayList<String>();
                for (var p : parts) row.add(p.trim());
                if (row.stream().anyMatch(v -> !v.isBlank())) rows.add(row);
            }
            return rows;
        }
    }

    // ─── Header detection & row mapping ──────────────────────────────────────────

    /** Returns colIndex → fieldName for recognized columns. */
    private Map<Integer, String> detectHeaders(List<String> headerRow) {
        var mapping = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < headerRow.size(); i++) {
            String field = HEADER_ALIASES.get(normalizeHeader(headerRow.get(i)));
            if (field != null && !mapping.containsValue(field)) {
                mapping.put(i, field);
            }
        }
        return mapping;
    }

    private Map<String, String> mapRow(List<String> row, Map<Integer, String> colMapping) {
        var values = new LinkedHashMap<String, String>();
        colMapping.forEach((idx, field) -> {
            String v = idx < row.size() ? row.get(idx) : "";
            values.put(field, Objects.requireNonNullElse(v, "").trim());
        });
        return values;
    }

    // ─── Row processing (upsert) ──────────────────────────────────────────────────

    private RowResult processRow(int lineNumber, Map<String, String> values) {
        var fullName     = values.getOrDefault("fullName", "").trim();
        var rawCpf       = values.getOrDefault("cpf", "").trim();
        var phone        = values.getOrDefault("phone", "");
        var lounge       = values.getOrDefault("invitedLounge", "");
        var rawDay       = values.getOrDefault("invitedDay", "");

        if (fullName.isBlank() && rawCpf.isBlank()) return RowResult.skip("linha em branco");
        if (fullName.isBlank()) return RowResult.error(rawCpf, "Nome completo é obrigatório");
        if (lounge.isBlank()) return RowResult.error(rawCpf, "Camarote é obrigatório");

        // Normalize CPF — Excel may strip leading zero, so pad to 11 digits when numeric
        String cpfDigits = CpfValidator.onlyDigits(rawCpf);
        if (!cpfDigits.isBlank() && cpfDigits.length() < 11) {
            try { cpfDigits = String.format("%011d", Long.parseLong(cpfDigits)); }
            catch (NumberFormatException ignored) { /* keep as-is */ }
        }
        if (!CpfValidator.isValid(cpfDigits)) {
            return RowResult.error(rawCpf, "CPF inválido: " + rawCpf);
        }

        if (!lounge.isBlank() && !loungeConfig.isValid(lounge)) {
            return RowResult.error(cpfDigits, "Camarote inválido: '" + lounge + "'");
        }
        String canonicalLounge = LoungeConfig.canonicalLoungeName(lounge);

        LocalDate invitedDay = parseDate(rawDay);
        LocalDate day = Objects.requireNonNullElse(invitedDay, LocalDate.now(EVENT_ZONE));
        Instant visitStart = day.atTime(ACCESS_START).atZone(EVENT_ZONE).toInstant();
        Instant visitEnd   = day.plusDays(1).atTime(ACCESS_END).atZone(EVENT_ZONE).toInstant();

        var existing = guestRepository.findFirstByCpfOrderByVisitStartDesc(cpfDigits);
        if (existing.isPresent()) {
            var guest = existing.get();
            var status = guest.getStatus();
            if (status == GuestStatus.COMPLETED
                    || guest.getSyncStatus() == SyncStatus.SYNCED
                    || guest.getSyncStatus() == SyncStatus.SYNCED_WITH_WARNINGS) {
                return RowResult.skip("já possui cadastro " + status.name().toLowerCase());
            }
            if (status == GuestStatus.PENDING_REGISTRATION || status == GuestStatus.INVITED) {
                guest.update(fullName, cpfDigits, guest.getEmail(),
                        normalizePhone(phone), guest.getCompany(),
                        guest.getVisitReason(), guest.getHostName(),
                        visitStart, visitEnd, invitedDay, canonicalLounge, null);
                guestRepository.save(guest);
                log.info("GUEST_IMPORT_UPDATED guest_id={} cpf={}", guest.getId(), cpfDigits);
                return RowResult.updated(cpfDigits);
            }
            return RowResult.skip("cadastro não atualizável: " + status.name().toLowerCase());
        }

        var guest = new Guest(fullName, cpfDigits,
                null, normalizePhone(phone), null,
                "Credenciamento", "Organizador",
                visitStart, visitEnd, invitedDay, canonicalLounge);
        guestRepository.save(guest);
        log.info("GUEST_IMPORT_CREATED guest_id={} cpf={} lounge={}", guest.getId(), cpfDigits, canonicalLounge);
        return RowResult.created(cpfDigits);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        for (var fmt : DATE_FORMATTERS) {
            try { return LocalDate.parse(value.trim(), fmt); }
            catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    private String normalizePhone(String phone) {
        return (phone == null || phone.isBlank()) ? null : phone.trim();
    }

    private static String normalizeHeader(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private List<String> missingRequiredColumns(Map<Integer, String> colMapping) {
        var missing = new ArrayList<String>();
        for (var required : REQUIRED_COLUMNS.entrySet()) {
            if (!colMapping.containsValue(required.getKey())) {
                missing.add(required.getValue());
            }
        }
        return missing;
    }

    private List<String> requiredColumnLabels() {
        return List.copyOf(REQUIRED_COLUMNS.values());
    }

    private String[] splitPdfLine(String line) {
        if (line.contains(";")) return line.split(";");
        if (line.contains("|")) return line.split("\\|");
        if (line.contains(",")) return line.split(",");
        if (line.contains("\t")) return line.split("\t");
        return line.split("\\s{2,}");
    }

    private int countOccurrences(String value, char character) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == character) {
                count++;
            }
        }
        return count;
    }

    private void fillExampleRow(Sheet sheet, int rowIdx,
                                 String name, String cpf, String phone,
                                 String lounge, String day) {
        var row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(cpf);
        row.createCell(2).setCellValue(phone);
        row.createCell(3).setCellValue(lounge);
        row.createCell(4).setCellValue(day);
    }

    // ─── Internal result type ─────────────────────────────────────────────────────

    private enum Outcome { CREATED, UPDATED, SKIPPED, ERROR }

    private record RowResult(Outcome outcome, String cpf, String reason) {
        static RowResult created(String cpf) { return new RowResult(Outcome.CREATED, cpf, null); }
        static RowResult updated(String cpf) { return new RowResult(Outcome.UPDATED, cpf, null); }
        static RowResult skip(String reason) { return new RowResult(Outcome.SKIPPED, null, reason); }
        static RowResult error(String cpf, String reason) { return new RowResult(Outcome.ERROR, cpf, reason); }
    }
}
