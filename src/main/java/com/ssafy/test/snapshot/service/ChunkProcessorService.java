package com.ssafy.test.snapshot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import com.ssafy.test.snapshot.service.SnapshotOrchestrator.ChunkProcessResult;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ChunkProcessorService {

    private static final Logger log = LoggerFactory.getLogger(ChunkProcessorService.class);
    private static final int LOCK_WAIT_TIME = 2;
    private static final int LOCK_LEASE_TIME = -1;

    private final DeltaCollectorService deltaCollector;
    private final SnapshotMergeService snapshotMerger;
    private final GLBGeneratorService glbGenerator;
    private final S3StorageService s3Storage;
    private final ChunkMetadataService chunkMetadataService;
    private final RedisOperationService redisOperation;
    private final RedissonLockService lockService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChunkProcessResult processChunk(String chunkKey, Instant batchStartTime) {
        log.info("청크 처리 시작: {}", chunkKey);

        try {
            ChunkInfo chunkInfo = ChunkInfo.fromKey(chunkKey);
            log.info("현재 청크 정보: {}", chunkInfo);
            double maxScore = (double) batchStartTime.toEpochMilli();

            DeltaCollectorService.DeltaCollectionResult deltaResult;
            RLock readLock = lockService.getLock(chunkKey + ":read");

            try {
                // 락 획득 시도
                boolean acquired = readLock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
                if (!acquired) {
                    log.info("읽기 락 획득 실패. 청크: {}", chunkKey);
                    return null;
                }

                log.info("읽기 락 획득 성공 (Watchdog 활성화): {}", chunkKey);

                deltaResult = deltaCollector.collectDeltas(chunkKey, maxScore);

            } finally {
                // 락 해제
                lockService.unlock(readLock);
                log.debug("읽기 락 해제: {}", chunkKey);
            }

            // 데이터 없으면 종료
            if (deltaResult.currentDeltas().isEmpty()) {
                log.info("적용할 Delta 없음. 청크: {}", chunkKey);
                return ChunkProcessResult.success(chunkKey, 0, null, null);
            }

            log.info("수집된 Delta 수: {}", deltaResult.currentDeltas().size());

            // 현재 버전
            UUID chunkUuid = chunkMetadataService.getOrCreateChunkIndex(chunkInfo);
            int curVersion = chunkMetadataService.getSnapshotVersion(chunkUuid);
            int newVersion = curVersion + 1;

            List<DeltaDTO> finalSnapshot = snapshotMerger.mergeSnapshot(
                    chunkInfo,
                    deltaResult.currentDeltas(),
                    deltaResult.tombstoneOpIds(),
                    curVersion
            );
            log.info("최종 스냅샷 Delta 수: {}", finalSnapshot.size());

            // Snapshot 생성, 업로드
            String snapshotJson = objectMapper.writeValueAsString(finalSnapshot);
            String snapshotUrl = s3Storage.uploadSnapshot(chunkInfo, newVersion, snapshotJson);
            log.info("스냅샷 업로드 완료: {}", snapshotUrl);

            // GLB 생성, 업로드
//            byte[] glbData = glbGenerator.generateGLB(finalSnapshot, chunkInfo);
            byte[] glbData = glbGenerator.generateGLBWithSeparateMeshes(finalSnapshot, chunkInfo);

            String glbUrl = s3Storage.uploadGLB(chunkInfo, newVersion, glbData);
            log.info("GLB 업로드 완료: {}", glbUrl);

            UUID snapshotUuid = chunkMetadataService.saveChunkSnapshot(
                    chunkUuid, newVersion, snapshotUrl, snapshotJson.length(),
                    finalSnapshot.size(), Instant.now()
            );

            long meshVersion = chunkMetadataService.getNextMeshVersion(chunkUuid);
            chunkMetadataService.saveChunkMesh(
                    chunkUuid, snapshotUuid, meshVersion, glbUrl,
                    glbData.length, Instant.now()
            );

            chunkMetadataService.updateChunkIndexAfterSnapshot(
                    chunkUuid, snapshotUuid, newVersion, meshVersion, Instant.now()
            );

            RLock deleteLock = lockService.getLock(chunkKey + ":delete");

            try {
                boolean acquired = deleteLock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
                if (!acquired) {
                    log.warn("삭제 락 획득 실패. 다음 배치에서 정리됨. 청크: {}", chunkKey);
                    return ChunkProcessResult.success(chunkKey, finalSnapshot.size(), snapshotUrl, glbUrl);
                }

                log.info("삭제 락 획득 성공: {}", chunkKey);

                redisOperation.cleanupProcessedData(
                        chunkKey,
                        deltaResult.opIds(),
                        deltaResult.tombstoneOpIds(),
                        maxScore
                );

            } finally {
                lockService.unlock(deleteLock);
                log.debug("삭제 락 해제: {}", chunkKey);
            }

            log.info("청크 처리 완료: {}", chunkKey);
            return ChunkProcessResult.success(chunkKey, finalSnapshot.size(), snapshotUrl, glbUrl);

        } catch (JsonProcessingException e) {
            log.error("JSON 처리 실패. 청크: {}", chunkKey, e);
            return ChunkProcessResult.failure(chunkKey, "JSON 처리 실패: " + e.getMessage());
        } catch (Exception e) {
            log.error("청크 처리 실패. 청크: {}", chunkKey, e);
            return ChunkProcessResult.failure(chunkKey, e.getMessage());
        }
    }
}