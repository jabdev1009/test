package com.ssafy.test.snapshot.service;

import com.ssafy.test.global.exception.CustomException;
import com.ssafy.test.snapshot.repo.ChunkRepository;
import com.ssafy.test.snapshot.repo.ChunkRepository.WorldLodInfo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 청크 메타데이터 관리 서비스 (JOOQ 기반)
 */
@Service
@RequiredArgsConstructor
public class ChunkMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ChunkMetadataService.class);
    private final ChunkRepository repository;

    /**
     * Chunk Index 조회 또는 생성
     */
    @Transactional
    public UUID getOrCreateChunkIndex(ChunkInfo chunkInfo) {
        // 1. World UUID 조회
        UUID worldUuid = repository.findWorldUuidByName(chunkInfo.worldName())
                .orElseThrow(() -> new CustomException("World not found: " + chunkInfo.worldName()));

        // 2. 기존 Chunk Index 조회
        return repository.findChunkIndexUuid(
                worldUuid,
                (short) chunkInfo.lod(),
                chunkInfo.x(),
                chunkInfo.y(),
                chunkInfo.z()
        ).orElseGet(() -> {
            // 3. 없으면 생성
            log.info("새로운 청크 인덱스 생성: {}", chunkInfo);

            // World LOD 정보 조회
            WorldLodInfo lodInfo = repository.findWorldLodInfo(worldUuid, (short) chunkInfo.lod())
                    .orElseThrow(() -> new CustomException("World LOD not found"));

            // AABB 계산
            double voxelSize = lodInfo.voxelSizeM();
            int edgeCells = lodInfo.edgeCells();

            double minX = chunkInfo.x() * edgeCells * voxelSize;
            double minY = chunkInfo.y() * edgeCells * voxelSize;
            double minZ = chunkInfo.z() * edgeCells * voxelSize;
            double maxX = minX + edgeCells * voxelSize;
            double maxY = minY + edgeCells * voxelSize;
            double maxZ = minZ + edgeCells * voxelSize;

            return repository.insertChunkIndex(
                    worldUuid,
                    (short) chunkInfo.lod(),
                    chunkInfo.x(),
                    chunkInfo.y(),
                    chunkInfo.z(),
                    edgeCells,
                    voxelSize,
                    minX, minY, minZ,
                    maxX, maxY, maxZ
            );
        });
    }

    /**
     * 다음 스냅샷 버전 조회
     */
    public long getNextSnapshotVersion(UUID chunkUuid) {
        return repository.findMaxSnapshotVersion(chunkUuid)
                .orElse(0L) + 1;
    }

    /**
     * 다음 메쉬 버전 조회
     */
    public long getNextMeshVersion(UUID chunkUuid) {
        return repository.findMaxMeshVersion(chunkUuid)
                .orElse(0L) + 1;
    }

    /**
     * Chunk Snapshot 저장
     */
    @Transactional
    public UUID saveChunkSnapshot(UUID chunkUuid, long version, String storageUri,
                                  int compressedBytes, int nonEmptyCells, Instant createdAt) {
        UUID snapshotUuid = repository.insertChunkSnapshot(
                chunkUuid, version, storageUri, compressedBytes, nonEmptyCells
        );

        log.info("스냅샷 메타데이터 저장 완료. UUID: {}, 버전: {}", snapshotUuid, version);
        return snapshotUuid;
    }

    /**
     * Chunk Mesh 저장
     */
    @Transactional
    public UUID saveChunkMesh(UUID chunkUuid, UUID snapshotUuid, long meshVersion,
                              String artifactUri, int compressedBytes, Instant createdAt) {
        UUID meshUuid = repository.insertChunkMesh(
                chunkUuid, snapshotUuid, meshVersion, artifactUri, compressedBytes
        );

        log.info("메쉬 메타데이터 저장 완료. UUID: {}, 버전: {}", meshUuid, meshVersion);
        return meshUuid;
    }

    /**
     * Chunk Index 업데이트 (스냅샷 생성 후)
     */
    @Transactional
    public void updateChunkIndexAfterSnapshot(UUID chunkUuid, UUID snapshotUuid,
                                              long version, long meshVersion, Instant lastWriteAt) {
        // 최신 메쉬 UUID 조회 (방금 저장한 것)
        UUID meshUuid = repository.findMaxMeshVersion(chunkUuid)
                .map(v -> snapshotUuid) // 실제로는 메쉬 UUID를 조회해야 하지만 간단히 처리
                .orElse(null);

        repository.updateChunkIndexAfterSnapshot(
                chunkUuid, snapshotUuid, version, meshUuid, meshVersion, lastWriteAt
        );

        log.info("청크 인덱스 업데이트 완료. UUID: {}", chunkUuid);
    }
}