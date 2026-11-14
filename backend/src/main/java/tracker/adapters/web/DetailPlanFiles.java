package tracker.adapters.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import tracker.application.service.DetailPlanImportService;
import tracker.application.service.DetailPlanImportService.SheetLine;
import tracker.application.service.DetailPlanImportService.SheetPerson;

/** The detail plan template: the plan's sub-tasks to change offline and import back, with the anken's members to pick from. */
final class DetailPlanFiles {
    private DetailPlanFiles() {
    }

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Ref to Summary: read only, greyed; BA to the days: what the import reads. */
    private static final int FIRST_EDITABLE = 5;
    private static final int[] WIDTHS = {10, 10, 26, 16, 48, 16, 16, 16, 11};
    private static final int FIRST_DAY = DetailPlanImportService.COLUMNS.size();
    private static final List<String> HOW_TO = List.of(
            "How to fill the detail plan",
            "",
            "1. Change BA, BAL, JM, Effort (h) or the hours of a day on the Detail plan sheet. Do not change Ref: it finds the sub-task.",
            "2. BA, BAL and JM are per function: change them on any of its lines; a line that keeps the plan's person changes nothing.",
            "   A ticket has a BA (its member) only; JM only for a function with JM Review. Pick names from the People sheet.",
            "3. The days are the columns headed YYYY-MM-DD: the hours of that day. An empty day cell is 0 hours, and clears the cell.",
            "4. An empty BA / BAL / JM / Effort cell, or one with what the plan has, changes nothing. A value that differs is set by hand.",
            "5. Detail plan › More › Import Excel / CSV: review the changes, then apply them.",
            "",
            "New functions and tickets are added on the screen (Add function, Add task), not here.");

    static byte[] template(DetailPlanImportService.Sheet sheet) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            CellStyle readOnly = wb.createCellStyle();
            readOnly.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            readOnly.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle hours = wb.createCellStyle();
            hours.setDataFormat(wb.createDataFormat().getFormat("0.##"));

            Sheet ws = wb.createSheet("Detail plan");
            Row h = ws.createRow(0);
            int width = FIRST_DAY + sheet.days().size();
            for (int c = 0; c < width; c++) {
                Cell cell = h.createCell(c);
                cell.setCellValue(c < FIRST_DAY ? DetailPlanImportService.COLUMNS.get(c) : sheet.days().get(c - FIRST_DAY));
                cell.setCellStyle(header);
                ws.setColumnWidth(c, (c < FIRST_DAY ? WIDTHS[c] : 11) * 256);
            }
            int n = 1;
            for (SheetLine l : sheet.lines()) {
                Row r = ws.createRow(n++);
                String[] text = {l.ref(), l.phase(), l.function(), l.step(), l.summary(), l.ba(), l.bal(), l.jm()};
                for (int c = 0; c < text.length; c++) {
                    Cell cell = r.createCell(c);
                    cell.setCellValue(text[c]);
                    if (c < FIRST_EDITABLE) {
                        cell.setCellStyle(readOnly);
                    }
                }
                Cell e = r.createCell(8);
                e.setCellValue(l.effortH());
                e.setCellStyle(hours);
                for (int d = 0; d < sheet.days().size(); d++) {
                    Double hrs = l.cells().get(sheet.days().get(d));
                    if (hrs != null && hrs > 0) {
                        Cell cell = r.createCell(FIRST_DAY + d);
                        cell.setCellValue(hrs);
                        cell.setCellStyle(hours);
                    }
                }
            }
            ws.createFreezePane(0, 1);
            ws.setAutoFilter(new CellRangeAddress(0, Math.max(0, n - 1), 0, width - 1));

            Sheet people = wb.createSheet("People");
            Row ph = people.createRow(0);
            String[] cols = {"Name", "Role"};
            for (int c = 0; c < cols.length; c++) {
                Cell cell = ph.createCell(c);
                cell.setCellValue(cols[c]);
                cell.setCellStyle(header);
                people.setColumnWidth(c, 20 * 256);
            }
            int p = 1;
            for (SheetPerson m : sheet.people()) {
                Row r = people.createRow(p++);
                r.createCell(0).setCellValue(m.name());
                r.createCell(1).setCellValue(m.role());
            }
            if (!sheet.people().isEmpty() && n > 1) {
                // a list to pick from; a warning only, so an id typed by hand still goes through
                DataValidationHelper dv = ws.getDataValidationHelper();
                DataValidation v = dv.createValidation(dv.createFormulaListConstraint("People!$A$2:$A$" + p),
                        new CellRangeAddressList(1, n - 1, 5, 7));
                v.setErrorStyle(DataValidation.ErrorStyle.WARNING);
                v.setShowErrorBox(true);
                ws.addValidationData(v);
            }

            Sheet help = wb.createSheet("How to");
            help.setColumnWidth(0, 120 * 256);
            for (int i = 0; i < HOW_TO.size(); i++) {
                Cell cell = help.createRow(i).createCell(0);
                cell.setCellValue(HOW_TO.get(i));
                if (i == 0) {
                    cell.setCellStyle(header);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
