package com.ssafy.test.snapshot.scheduler;

import com.ssafy.test.snapshot.service.SnapshotOrchestrator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;




@Component
@RequiredArgsConstructor
class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);
    private final SnapshotOrchestrator orchestrator;

    @Scheduled(fixedRate = 10000)
    public void executeSnapshotBatch() {
        log.info("=== 스냅샷 배치 스케줄 시작 ===");
        try {
            orchestrator.executeSnapshotBatch();
            log.info("=== 스냅샷 배치 스케줄 완료 ===");
        } catch (Exception e) {
            log.error("=== 스냅샷 배치 스케줄 실패 ===", e);
        }
    }
}
