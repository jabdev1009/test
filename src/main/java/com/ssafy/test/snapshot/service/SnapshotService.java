package com.ssafy.test.snapshot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.Executors;
import java.time.Instant;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private final StringRedisTemplate redisTemplate;

    public SnapshotService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     1. 배치 시작 시 timestamp 기준 범위를 정함
     2. Redis에서 op_ids:<chunkId> 범위 조회 → batch에 포함될 op_id 확보
     3. 해당 op_id로 DELTAS Hash 조회 → delta JSON 확보
     4. tombstones:<chunkId> 범위 조회 → 삭제된 op_id 목록 확보
     5. Postgres에서 해당 청크의 가장 최근 snapshot 조회
     6. 최근 snapshot, delta JSON에서 tombstone에 있는 op_id 제거 → 최종 적용할 delta 리스트 완성
     7. S3에 최종 delta 리스트 업로드 -> 반환된 주소 정보를 Postgres snopshot 테이블에 추가
     7. GLB 생성
     8. 처리 완료 시 op_ids:<chunkId>와 tombstones:<chunkId>에서 사용한 op_id 제거, DELTAS hash에서 field 제거
     */
    @Scheduled(fixedRateString = "60000")
    public void makeSnapshot() {
        // 1. 배치 시작 시 timestamp 기준 범위를 정함
        Instant batchStartTime = Instant.now();
        log.info("스냅샷 배치 시작 시간: {}", batchStartTime);

        Set<String> chunkKeys = redisTemplate.keys("op_ids:*");
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            log.info("처리할 chunk 없음");
            return;
        }

        log.info("스냅샷 생성 시작. 대상 청크: {}", chunkKeys);
        // try-with-resources
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String chunkKey : chunkKeys) {
                // batchStartTime을 processChunk로 전달하거나, processChunk 내에서 활용
                executor.submit(() -> processChunk(chunkKey, batchStartTime));
            }
        }

        log.info("스냅샷 생성 완료.");
    }

    private void processChunk(String chunkKey, Instant batchStartTime) {
        try {
            log.info("현재 작업 청크: {}, 배치 시작 시간: {}", chunkKey, batchStartTime);
            // batchStartTime를 기준으로 op_id 목록 조회
            Set<String> opIds = redisTemplate.opsForSet().members(chunkKey);




            log.info("청크 처리 완료: {}", chunkKey);
        } catch (Exception e) {
            log.error("청크 처리 중 오류 발생: {}", chunkKey, e);
        }
    }
}
