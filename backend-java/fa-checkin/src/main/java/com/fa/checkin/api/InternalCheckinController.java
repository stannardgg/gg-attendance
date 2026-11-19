package com.fa.checkin.api;

import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.app.CheckinService;
import com.fa.checkin.app.LiveBroadcaster;
import com.fa.common.api.Result;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（契约 internal.yaml）。鉴权由 InternalTokenFilter 统一完成，
 * 这里不再重复校验——重复校验会诱使后来者以为「不校验也能用」。
 */
@RestController
public class InternalCheckinController {

    private final CheckinService checkinService;
    private final LiveBroadcaster broadcaster;

    public InternalCheckinController(CheckinService checkinService, LiveBroadcaster broadcaster) {
        this.checkinService = checkinService;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/internal/sessions/{id}/context")
    public CheckinDtos.ContextResp context(@PathVariable long id,
                                           @RequestParam long studentId) {
        return checkinService.context(id, studentId);
    }

    @PostMapping("/internal/checkin/commit")
    public Result<CheckinDtos.CommitData> commit(@Valid @RequestBody CheckinDtos.CommitReq req) {
        CheckinDtos.CommitData data = checkinService.commit(req);
        // 事务已提交（service 方法带 @Transactional），此时推送大屏才算数。
        // 推送失败不影响签到：broadcast 内部吞异常只记日志。
        // studentName 必带：大屏靠它在本地名单里定位那一行并立即改色，
        // 少了它只能整页重拉，"1 秒内 +1"就要打折扣。
        broadcaster.broadcast(req.sessionId(), "EVT_CHECKIN_SUCCESS", Map.of(
                "studentId", req.studentId(),
                "studentName", data.studentName() == null ? "" : data.studentName(),
                "attendanceId", data.attendanceId(),
                "status", data.status(),
                "checkinAt", data.checkinAt().toString(),
                "similarity", req.similarity()));
        return Result.ok(data);
    }
}
