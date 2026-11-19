package com.platform.contacts;

import com.platform.shared.api.ApiException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Parses CSV (auto-detected delimiter, UTF-8 with/without BOM) and XLSX (first sheet) into header + rows. */
public final class ImportParser {
    public static final int MAX_ROWS = 50_000;

    private ImportParser() {}

    public record Table(List<String> headers, List<List<String>> rows) {}

    public static Table parse(byte[] content, String format) {
        return "XLSX".equals(format) ? xlsx(content) : csv(content);
    }

    static Table csv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = java.util.stream.Stream.of(';', '\t', ',').max(Comparator.comparingLong(c -> firstLine.chars().filter(x -> x == c).count())).orElse(',');
        try (Reader r = new InputStreamReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
             CSVParser p = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(true).get().parse(r)) {
            List<List<String>> all = new ArrayList<>();
            for (CSVRecord rec : p) {
                if (all.size() > MAX_ROWS) throw ApiException.badRequest("Files are limited to " + MAX_ROWS + " rows");
                all.add(rec.toList());
            }
            if (all.isEmpty()) throw ApiException.badRequest("The file is empty");
            return new Table(all.getFirst(), all.subList(1, all.size()));
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            throw ApiException.badRequest("Could not read CSV: " + e.getMessage());
        }
    }

    static Table xlsx(byte[] content) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            List<List<String>> all = new ArrayList<>();
            for (Row row : sheet) {
                if (all.size() > MAX_ROWS) throw ApiException.badRequest("Files are limited to " + MAX_ROWS + " rows");
                List<String> cells = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), 0);
                for (int i = 0; i < last; i++) {
                    Cell c = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (c == null) cells.add("");
                    else if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) cells.add(c.getLocalDateTimeCellValue().toString());
                    else cells.add(fmt.formatCellValue(c).trim());
                }
                if (cells.stream().allMatch(String::isEmpty)) continue;
                all.add(cells);
            }
            if (all.isEmpty()) throw ApiException.badRequest("The sheet is empty");
            return new Table(all.getFirst(), all.subList(1, all.size()));
        } catch (java.io.IOException | org.apache.poi.EmptyFileException | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw ApiException.badRequest("Could not read XLSX file");
        }
    }

    /** Lenient date parsing; day/month order chosen by the organization's country (US = month first). */
    public static Optional<Instant> parseInstant(String raw, String country, ZoneId zone) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.trim();
        try { return Optional.of(Instant.parse(v)); } catch (DateTimeParseException ignored) { }
        try { return Optional.of(OffsetDateTime.parse(v).toInstant()); } catch (DateTimeParseException ignored) { }
        try { return Optional.of(LocalDateTime.parse(v).atZone(zone).toInstant()); } catch (DateTimeParseException ignored) { }
        return parseDate(v, country).map(d -> d.atStartOfDay(zone).toInstant());
    }

    public static Optional<LocalDate> parseDate(String raw, String country) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.trim();
        List<String> patterns = "US".equals(country)
                ? List.of("yyyy-MM-dd", "M/d/yyyy", "M/d/yy", "d.M.yyyy")
                : List.of("yyyy-MM-dd", "d/M/yyyy", "d/M/yy", "d.M.yyyy", "d-M-yyyy");
        for (String p : patterns) {
            try { return Optional.of(LocalDate.parse(v.length() > 10 && p.equals("yyyy-MM-dd") ? v.substring(0, 10) : v, DateTimeFormatter.ofPattern(p))); }
            catch (DateTimeParseException ignored) { }
        }
        return Optional.empty();
    }

}
