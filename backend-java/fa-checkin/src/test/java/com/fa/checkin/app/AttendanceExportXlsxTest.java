package com.fa.checkin.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fa.checkin.api.dto.AttendanceDtos;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 导出高亮逻辑：缺勤（ABSENT）行整行红底 + 深红字，其余行不加底色。
 *
 * <p>只测 toXlsx 构建器，不碰数据库——JDBC 查询口径（应到名单基表 + COALESCE ABSENT）
 * 由 CI 集成测试覆盖。
 */
class AttendanceExportXlsxTest {

    private static AttendanceDtos.Row row(long studentId, String no, String name, String status) {
        boolean absent = "ABSENT".equals(status);
        return new AttendanceDtos.Row(
                absent ? null : 9001L,          // 未签到的人还没有 attendance 记录，id 为空
                1L, "第一次课",
                1L, "软件1班",
                1L, "Java 程序设计",
                studentId, no, name, status,
                absent ? null : LocalDateTime.of(2026, 11, 19, 9, 1, 30),
                absent ? null : 0.9876,
                absent ? null : 0.99,
                absent ? null : 0.98,
                absent ? null : 2,
                absent ? null : "MOUTH_ONCE",
                null, "", null);
    }

    @Test
    void absentRowsHighlightedOthersNot() throws IOException {
        byte[] bytes = AttendanceQueryService.toXlsx(List.of(
                row(11, "2025001", "张三", "PRESENT"),
                row(12, "2025002", "李四", "ABSENT"),
                row(13, "2025003", "王五", "LATE")));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals("考勤记录", wb.getSheetName(0));
            assertEquals("学号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("调整原因", sheet.getRow(0).getCell(10).getStringCellValue());

            assertNotHighlighted(sheet.getRow(1));   // 张三 PRESENT
            assertHighlighted(sheet.getRow(2));      // 李四 ABSENT
            assertNotHighlighted(sheet.getRow(3));   // 王五 LATE

            // 正常行数值列为数值；缺勤行数值列留空但仍带样式（整行高亮）
            assertEquals(0.9876, sheet.getRow(1).getCell(7).getNumericCellValue(), 1e-9);
            assertEquals(CellType.BLANK, sheet.getRow(2).getCell(7).getCellType());
            assertEquals("ABSENT", sheet.getRow(2).getCell(5).getStringCellValue());
        }
    }

    private static void assertHighlighted(XSSFRow row) {
        XSSFCellStyle style = row.getCell(0).getCellStyle();
        assertEquals(FillPatternType.SOLID_FOREGROUND, style.getFillPattern());
        assertEquals(IndexedColors.ROSE.getIndex(), style.getFillForegroundColor());
        assertEquals(IndexedColors.DARK_RED.getIndex(), style.getFont().getColor());
    }

    private static void assertNotHighlighted(XSSFRow row) {
        XSSFCellStyle style = row.getCell(0).getCellStyle();
        assertEquals(FillPatternType.NO_FILL, style.getFillPattern());
    }
}
