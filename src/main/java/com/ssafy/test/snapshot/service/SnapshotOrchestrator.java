package com.ssafy.test.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class SnapshotOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SnapshotOrchestrator.class);
    private static final String OPID_PATTERN = "op_ids:*";

    private final StringRedisTemplate redisTemplate;
    private final ChunkProcessorService chunkProcessor;

    /**
     * 스냅샷 배치 실행
     * 1. 배치 시작 시간 기록
     * 2. 처리 대상 청크 조회
     * 3. 청크별 병렬 처리
     * 4. 모든 작업 완료 대기
     */
    public void executeSnapshotBatch() {
        Instant batchStartTime = Instant.now();
        log.info("스냅샷 배치 시작. 시간: {}", batchStartTime);

        // 처리 대상 청크 조회
        Set<String> chunkKeys = redisTemplate.keys(OPID_PATTERN);
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            log.info("처리할 청크 없음");
            return;
        }

        log.info("처리 대상 청크 수: {}", chunkKeys.size());

        // 청크별 병렬 처리
        List<CompletableFuture<ChunkProcessResult>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String chunkKey : chunkKeys) {
                CompletableFuture<ChunkProcessResult> future = CompletableFuture.supplyAsync(
                        () -> chunkProcessor.processChunk(chunkKey, batchStartTime),
                        executor
                );
                futures.add(future);
            }

            // 모든 작업 완료 대기
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );

            allFutures.join();

            // 결과 집계
            long successCount = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(ChunkProcessResult::success)
                    .count();

            log.info("스냅샷 배치 완료. 성공: {}/{}", successCount, chunkKeys.size());

        } catch (Exception e) {
            log.error("스냅샷 배치 처리 중 오류 발생", e);
            throw new CustomException("SNAPSHOT_BATCH_FAILED", e);
        }
    }

    /**
     * 청크 처리 결과
     */
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