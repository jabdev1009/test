package com.ssafy.test.snapshot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import com.ssafy.test.snapshot.service.SnapshotOrchestrator.ChunkProcessResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 청크별 스냅샷 처리 서비스
 * 개별 청크의 전체 처리 흐름을 관리
 */
@Service
@RequiredArgsConstructor
public class ChunkProcessorService {

    private static final Logger log = LoggerFactory.getLogger(ChunkProcessorService.class);

    private final DeltaCollectorService deltaCollector;
    private final SnapshotMergeService snapshotMerger;
    private final GLBGeneratorService glbGenerator;
    private final S3StorageService s3Storage;
    private final ChunkMetadataService chunkMetadataService;
    private final RedisOperationService redisOperation;
    private final ObjectMapper objectMapper;

    /**
     * 청크 처리 메인 메서드
     * 1. Delta 수집
     * 2. 스냅샷 병합
     * 3. GLB 생성
     * 4. S3 업로드
     * 5. 메타데이터 저장
     * 6. Redis 정리
     */
    @Transactional
    public ChunkProcessResult processChunk(String chunkKey, Instant batchStartTime) {
        log.info("청크 처리 시작: {}", chunkKey);

        try {
            // 1. 청크 정보 파싱
            ChunkInfo chunkInfo = ChunkInfo.fromKey(chunkKey);
            double maxScore = (double) batchStartTime.toEpochMilli();

            // 2. Delta 수집
            DeltaCollectorService.DeltaCollectionResult deltaResult = deltaCollector.collectDeltas(chunkKey, maxScore);
            if (deltaResult.currentDeltas().isEmpty()) {
                log.info("적용할 Delta 없음. 청크: {}", chunkKey);
                return ChunkProcessResult.success(chunkKey, 0, null, null);
            }

            log.info("수집된 Delta 수: {}", deltaResult.currentDeltas().size());

            // 3. 스냅샷 병합
            List<DeltaDTO> finalSnapshot = snapshotMerger.mergeSnapshot(
                    chunkInfo,
                    deltaResult.currentDeltas(),
                    deltaResult.tombstoneOpIds()
            );

            log.info("최종 스냅샷 Delta 수: {}", finalSnapshot.size());

            // 4. 다음 버전 번호 및 청크 UUID 조회/생성
            UUID chunkUuid = chunkMetadataService.getOrCreateChunkIndex(chunkInfo);
            long newVersion = chunkMetadataService.getNextSnapshotVersion(chunkUuid);

            // 5. 스냅샷 JSON 생성 및 S3 업로드
            String snapshotJson = objectMapper.writeValueAsString(finalSnapshot);
            String snapshotUrl = s3Storage.uploadSnapshot(chunkInfo, newVersion, snapshotJson);
            log.info("스냅샷 업로드 완료: {}", snapshotUrl);

            // 6. GLB 생성 및 S3 업로드
            byte[] glbData = glbGenerator.generateGLB(finalSnapshot);
            String glbUrl = s3Storage.uploadGLB(chunkInfo, newVersion, glbData);
            log.info("GLB 업로드 완료: {}", glbUrl);

            // 7. 스냅샷 및 메쉬 메타데이터 저장
            UUID snapshotUuid = chunkMetadataService.saveChunkSnapshot(
                    chunkUuid, newVersion, snapshotUrl, snapshotJson.length(),
                    finalSnapshot.size(), Instant.now()
            );

            long meshVersion = chunkMetadataService.getNextMeshVersion(chunkUuid);
            chunkMetadataService.saveChunkMesh(
                    chunkUuid, snapshotUuid, meshVersion, glbUrl,
                    glbData.length, Instant.now()
            );

            // 8. chunk_index 업데이트 (current_snapshot_id, current_version 등)
            chunkMetadataService.updateChunkIndexAfterSnapshot(
                    chunkUuid, snapshotUuid, newVersion, meshVersion, Instant.now()
            );

            // 9. Redis 정리
            redisOperation.cleanupProcessedData(
                    chunkKey,
                    deltaResult.opIds(),
                    deltaResult.tombstoneOpIds(),
                    maxScore
            );

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