package com.ssafy.test.snapshot.service;

import com.ssafy.test.global.exception.CustomException;
import com.ssafy.test.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 최적화된 락 전략:
 * - Redis 읽기/삭제에만 짧은 락 적용 (5초)
 * - 스냅샷 병합, GLB 생성, S3 업로드는 락 없이 수행
 * - batchStartTime 기준으로 처리 대상 결정 (중복 방지)
 */
@Service
@RequiredArgsConstructor
public class SnapshotOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SnapshotOrchestrator.class);
    private static final String OPID_PATTERN = "op_ids:*";
    public static final String OPID_PREFIX = "op_ids:";

    private final StringRedisTemplate redisTemplate;
    private final ChunkProcessorService chunkProcessor;

    public void executeSnapshotBatch() {
        Instant batchStartTime = Instant.now();
        log.info("스냅샷 배치 시작. 시간: {}", batchStartTime);

        // 배치 대상 청크 조회 -> 'op_ids:*'의 형태
        Set<String> chunkKeys = redisTemplate.keys(OPID_PATTERN);
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            log.info("처리할 청크 없음");
            return;
        }

        log.info("처리 대상 청크 수: {}", chunkKeys.size());

        List<CompletableFuture<ChunkProcessResult>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String chunkKey : chunkKeys) {
                CompletableFuture<ChunkProcessResult> future = CompletableFuture.supplyAsync(
                        () -> chunkProcessor.processChunk(chunkKey, batchStartTime),
                        executor
                );
                futures.add(future);
            }

            // TODO: 단계별 FALLBACK 고려
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            
            /// 결과 확인용 코드
            List<ChunkProcessResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();

            long successCount = results.stream().filter(ChunkProcessResult::success).count();
            long failedCount = results.stream().filter(r -> !r.success()).count();
            long skippedCount = chunkKeys.size() - results.size();

            log.info("스냅샷 배치 완료. 성공: {}, 실패: {}, 스킵: {}, 전체: {}",
                    successCount, failedCount, skippedCount, chunkKeys.size());
            ///  결과 확인용 코드
        } catch (Exception e) {
            log.error("스냅샷 배치 처리 중 오류 발생", e);
            throw new CustomException(ErrorCode.SNAPSHOT_BATCH_FAILED);
        }
    }

    public record ChunkProcessResult(
            String chunkKey,
            boolean success,
            int deltaCount,
            String snapshotUrl,
            String glbUrl,
            String errorMessage
    ) {
        public static ChunkProcessResult success(String chunkKey, int deltaCount,
                                                 String snapshotUrl, String glbUrl) {
            return new ChunkProcessResult(chunkKey, true, deltaCount, snapshotUrl, glbUrl, null);
        }

        public static ChunkProcessResult failure(String chunkKey, String errorMessage) {
            return new ChunkProcessResult(chunkKey, false, 0, null, null, errorMessage);
        }
    }
}