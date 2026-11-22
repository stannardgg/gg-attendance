package com.fa.agent.app.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 导出文件名。看着琐碎，但教师会把文件转发给教务，
 * 「考勤记录.xlsx」「考勤记录 (1).xlsx」分不清是哪段时间的表。
 */
class ExportToolsTest {

    @Test
    void fileNamesCarrySessionOrDateRange() {
        assertEquals("考勤记录_场次12.xlsx", ExportTools.fileName(12L, null, null));
        assertEquals("考勤记录_2026-11-21_2026-11-22.xlsx",
                ExportTools.fileName(null, LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22)));
        assertEquals("考勤记录_2026-11-22.xlsx",
                ExportTools.fileName(null, LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 22)));
        assertEquals("考勤记录_2026-11-22起.xlsx",
                ExportTools.fileName(null, LocalDate.of(2026, 11, 22), null));
        assertEquals("考勤记录_截至2026-11-22.xlsx",
                ExportTools.fileName(null, null, LocalDate.of(2026, 11, 22)));
    }
}
