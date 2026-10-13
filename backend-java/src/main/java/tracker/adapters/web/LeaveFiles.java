package tracker.adapters.web;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import tracker.application.AppError.Invalid;
import tracker.application.service.CalendarService.ExportRow;
import tracker.domain.Numbers;

/** Spreadsheet I/O for leave import/export: reading CSV/XLSX rows and writing the Excel-readable export. */
final class LeaveFiles {
    private LeaveFiles() {
    }

    static final String TEMPLATE_CSV = "﻿Member,From (YYYY-MM-DD),To (YYYY-MM-DD),Type,Note\n"
            + "An,2026-11-05,2026-11-06,All day,Family trip\nBình,2026-11-10,2026-11-10,Half day,\n"
            + "Dũng,2026-11-16,2026-11-16,All day (tentative),\nTanaka,2026-11-24,2026-11-24,Half day (tentative),\n";

    static final String SAMPLE_FILE = "leave-november.xlsx";
    static final List<List<String>> SAMPLE_ROWS = List.of(
            List.of("An", "2026-11-05", "2026-11-06", "All day", "Family trip"), List.of("Bình", "2026-11-10", "2026-11-10", "All day", ""),
            List.of("Chi", "2026-11-12", "2026-11-11", "All day", ""), List.of("Duc", "2026-11-13", "2026-11-13", "Half day (tentative)", ""),
            List.of("Dũng", "2026-11-16", "2026-11-16", "Half day", "Morning off"), List.of("Minh", "2026-11-18", "2026-11-20", "All day", ""),
            List.of("Tanaka", "2026-11-24", "2026-11-24", "Half day (tentative)", ""), List.of("An", "2026-10-16", "2026-10-16", "All day", ""),
            List.of("Bình", "2026/11/25", "2026/11/25", "All day", ""), List.of("Chi", "2026-11-26", "2026-11-27", "Remote", ""));

    /** Data rows (header skipped) of the first sheet of an .xlsx, or of a .csv. */
    static List<List<String>> readRows(String name, byte[] raw) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            List<List<String>> rows = new ArrayList<>();
            for (List<String> r : Csv.parse(decodeUtf8Sig(raw))) {
                if (r.stream().anyMatch(x -> !x.strip().isEmpty())) {
                    rows.add(r);
                }
            }
            return rows.stream().skip(1).map(r -> r.stream().map(String::strip).toList()).toList();
        }
        if (lower.endsWith(".xlsx")) {
            List<List<String>> rows = new ArrayList<>();
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(raw))) {
                Sheet ws = wb.getSheetAt(0);
                int width = 0;
                for (Row r : ws) {
                    width = Math.max(width, r.getLastCellNum());
                }
                for (int i = 0; i <= ws.getLastRowNum(); i++) {
                    Row r = ws.getRow(i);
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < width; c++) {
                        cells.add(cell(r == null ? null : r.getCell(c)));
                    }
                    rows.add(cells);
                }
            } catch (Exception e) {
                throw new Invalid("Could not read " + name + ": " + e.getMessage(), "file");
            }
            return rows.stream().skip(1).filter(r -> r.stream().anyMatch(x -> !x.isEmpty())).toList();
        }
        throw new Invalid("Choose a .xlsx or .csv file", "file");
    }

    /** UTF-8 with an optional BOM; undecodable bytes become U+FFFD. */
    private static String decodeUtf8Sig(byte[] raw) {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            String s = dec.decode(ByteBuffer.wrap(raw)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cell(Cell c) {
        if (c == null) {
            return "";
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(c)) {
                    LocalDateTime t = c.getLocalDateTimeCellValue();
                    yield t.toLocalDate().toString();
                }
                double v = c.getNumericCellValue();
                yield Numbers.format(v);
            }
            case STRING -> c.getStringCellValue().strip();
            case BOOLEAN -> c.getBooleanCellValue() ? "True" : "False";
            case ERROR -> "#ERROR";
            default -> "";
        };
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;");
    }

    /** An HTML table Excel opens as a sheet (no extra dependency, keeps Vietnamese/Japanese text intact). */
    static String exportXls(List<ExportRow> rows) {
        StringBuilder body = new StringBuilder();
        for (ExportRow r : rows) {
            body.append("<tr><td>").append(escape(r.who())).append("</td><td>").append(r.date()).append("</td><td>")
                    .append(escape(r.type())).append("</td><td>").append(escape(r.note())).append("</td></tr>");
        }
        return "<html><head><meta charset=\"utf-8\"></head><body><table border=\"1\"><tr><th>Member</th><th>Date</th><th>Type</th>"
                + "<th>Note</th></tr>" + body + "</table></body></html>";
    }

    /** CSV as Python's csv.reader reads it (excel dialect: comma, double quotes, "" escapes). */
    static final class Csv {
        private Csv() {
        }

        static List<List<String>> parse(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean afterQuote = false;
            boolean fieldStarted = false;
            int i = 0;
            while (i < text.length()) {
                char ch = text.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                            continue;
                        }
                        quoted = false;
                        afterQuote = true;
                    } else {
                        field.append(ch);
                    }
                    i++;
                    continue;
                }
                if (ch == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = true;
                    afterQuote = false;
                } else if (ch == '\r' || ch == '\n') {
                    if (fieldStarted || field.length() > 0 || !row.isEmpty() || afterQuote) {
                        row.add(field.toString());
                    }
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                    fieldStarted = false;
                    afterQuote = false;
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                } else if (ch == '"' && field.length() == 0 && !afterQuote) {
                    quoted = true;
                    fieldStarted = true;
                } else {
                    field.append(ch);
                    fieldStarted = true;
                }
                i++;
            }
            if (fieldStarted || field.length() > 0 || !row.isEmpty() || afterQuote) {
                row.add(field.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
