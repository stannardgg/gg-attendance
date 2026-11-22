package com.fa.checkin.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
                absent ? null : "MOVE_CLOSER",
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
            assertEquals("调整原因", sheet.getRow(0).getCell(7).getStringCellValue());

            assertNotHighlighted(sheet.getRow(1));   // 张三 PRESENT
            assertHighlighted(sheet.getRow(2));      // 李四 ABSENT
            assertNotHighlighted(sheet.getRow(3));   // 王五 LATE

            // 缺勤行签到时间留空但仍带样式（整行高亮）；状态列不受影响
            assertEquals(CellType.BLANK, sheet.getRow(2).getCell(6).getCellType());
            assertEquals("ABSENT", sheet.getRow(2).getCell(5).getStringCellValue());
            assertEquals("PRESENT", sheet.getRow(1).getCell(5).getStringCellValue());
        }
    }

    /**
     * 人脸分值三列（相似度/活体/身份）不得出现在导出文件里。
     *
     * <p>这不是「顺手删掉」：分值是排障与申诉裁定的内部依据，进了教师/教务的表就会被
     * 当成判定签到是否有效的概率。行数据里三项都有值，断言的是「有值也不写出去」。
     */
    @Test
    void faceScoresAreNotExported() throws IOException {
        byte[] bytes = AttendanceQueryService.toXlsx(List.of(
                row(11, "2025001", "张三", "PRESENT")));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            XSSFRow head = sheet.getRow(0);
            assertEquals(8, head.getLastCellNum(), "导出应只有 8 列");
            for (int i = 0; i < head.getLastCellNum(); i++) {
                String title = head.getCell(i).getStringCellValue();
                assertFalse(title.contains("相似"), "第 " + i + " 列不应是相似度：" + title);
                assertFalse(title.contains("活体"), "第 " + i + " 列不应是活体：" + title);
                assertFalse(title.contains("身份"), "第 " + i + " 列不应是身份：" + title);
            }
            // 数据行同理：列数一致；除「签到时间」是日期序列号（本来就以数值存储）外，
            // 其余列都不该是数值——三个分值若被写出去，必然落在某一列的小数单元格里。
            XSSFRow data = sheet.getRow(1);
            assertEquals(8, data.getLastCellNum());
            for (int i = 0; i < data.getLastCellNum(); i++) {
                if (i == 6) {
                    continue;
                }
                assertNotEquals(CellType.NUMERIC, data.getCell(i).getCellType(),
                        "第 " + i + " 列不应是数值型（分值为小数）");
            }
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
