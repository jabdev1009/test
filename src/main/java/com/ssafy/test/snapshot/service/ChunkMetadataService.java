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

import static com.ssafy.test.global.exception.ErrorCode.INTERNAL_SERVER_ERROR;

@Service
@RequiredArgsConstructor
public class ChunkMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ChunkMetadataService.class);
    private final ChunkRepository repository;

    @Transactional
    public UUID getOrCreateChunkIndex(ChunkInfo chunkInfo) {
        UUID worldUuid = repository.findWorldUuidByName(chunkInfo.worldName())
                .orElseThrow(() -> new CustomException(INTERNAL_SERVER_ERROR));

        return repository.findChunkIndexUuid(
                worldUuid,
                (short) chunkInfo.lod(),
                chunkInfo.x(),
                chunkInfo.y(),
                chunkInfo.z()
        ).orElseGet(() -> createChunkIndexSafely(worldUuid, chunkInfo));
    }

    private UUID createChunkIndexSafely(UUID worldUuid, ChunkInfo chunkInfo) {
        try {
            log.info("새로운 청크 인덱스 생성: {}", chunkInfo);

            WorldLodInfo lodInfo = repository.findWorldLodInfo(worldUuid, (short) chunkInfo.lod())
                    .orElseThrow(() -> new CustomException(INTERNAL_SERVER_ERROR));

            double voxelSize = lodInfo.voxelSizeM();
            int edgeCells = lodInfo.edgeCells();

            double minX = chunkInfo.x() * edgeCells * voxelSize;
            double minY = chunkInfo.y() * edgeCells * voxelSize;
            double minZ = chunkInfo.z() * edgeCells * voxelSize;
            double maxX = minX + edgeCells * voxelSize;
            double maxY = minY + edgeCells * voxelSize;
            double maxZ = minZ + edgeCells * voxelSize;

            return repository.insertChunkIndex(
                    worldUuid, (short) chunkInfo.lod(),
                    chunkInfo.x(), chunkInfo.y(), chunkInfo.z(),
                    edgeCells, voxelSize,
                    minX, minY, minZ, maxX, maxY, maxZ
            );

        } catch (Exception e) {
            // 동시 생성 시도로 인한 UNIQUE 위반 - 재조회
            log.warn("청크 인덱스 생성 실패 (이미 존재). 재조회: {}", chunkInfo);
            return repository.findChunkIndexUuid(
                    worldUuid,
                    (short) chunkInfo.lod(),
                    chunkInfo.x(),
                    chunkInfo.y(),
                    chunkInfo.z()
            ).orElseThrow(() -> new CustomException(INTERNAL_SERVER_ERROR));
        }
    }

    @Transactional
    public long getNextSnapshotVersion(UUID chunkUuid) {
        return repository.findMaxSnapshotVersion(chunkUuid).orElse(0L) + 1;
    }

    @Transactional
    public long getNextMeshVersion(UUID chunkUuid) {
        return repository.findMaxMeshVersion(chunkUuid).orElse(0L) + 1;
    }

    @Transactional
    public UUID saveChunkSnapshot(UUID chunkUuid, long version, String storageUri,
                                  int compressedBytes, int nonEmptyCells, Instant createdAt) {
        try {
            UUID snapshotUuid = repository.insertChunkSnapshot(
                    chunkUuid, version, storageUri, compressedBytes, nonEmptyCells
            );

            log.info("스냅샷 메타데이터 저장 완료. UUID: {}, 버전: {}", snapshotUuid, version);
            return snapshotUuid;

        } catch (Exception e) {
            log.error("스냅샷 저장 실패 - 버전 충돌. Chunk: {}, Version: {}", chunkUuid, version, e);
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional
    public UUID saveChunkMesh(UUID chunkUuid, UUID snapshotUuid, long meshVersion,
                              String artifactUri, int compressedBytes, Instant createdAt) {
        try {
            UUID meshUuid = repository.insertChunkMesh(
                    chunkUuid, snapshotUuid, meshVersion, artifactUri, compressedBytes
            );

            log.info("메쉬 메타데이터 저장 완료. UUID: {}, 버전: {}", meshUuid, meshVersion);
            return meshUuid;

        } catch (Exception e) {
            log.error("메쉬 저장 실패. Chunk: {}, Version: {}", chunkUuid, meshVersion, e);
            throw new CustomException(INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional
    public void updateChunkIndexAfterSnapshot(UUID chunkUuid, UUID snapshotUuid,
                                              long version, long meshVersion, Instant lastWriteAt) {
        UUID meshUuid = repository.findMaxMeshVersion(chunkUuid)
                .map(v -> snapshotUuid)
                .orElse(null);

        int updatedRows = repository.updateChunkIndexAfterSnapshot(
                chunkUuid, snapshotUuid, version, meshUuid, meshVersion, lastWriteAt
        );

        if (updatedRows == 0) {
            log.warn("청크 인덱스 업데이트 실패. UUID: {}", chunkUuid);
        } else {
            log.info("청크 인덱스 업데이트 완료. UUID: {}", chunkUuid);
        }
    }
}