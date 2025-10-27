package com.ssafy.test.snapshot.repo;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.example.jooq.generated.Tables.CHUNK_INDEX;
import static com.example.jooq.generated.Tables.CHUNK_SNAPSHOT;
import static com.example.jooq.generated.Tables.CHUNK_MESH;
import static com.example.jooq.generated.Tables.WORLD;
import static com.example.jooq.generated.Tables.WORLD_LOD;

/**
 * JOOQ 기반 청크 Repository
 */
@Repository
@RequiredArgsConstructor
public class ChunkRepository {

    private final DSLContext dsl;

    /**
     * World UUID 조회 (이름으로)
     */
    public Optional<UUID> findWorldUuidByName(String worldName) {
        return dsl.select(WORLD.UUID)
                .from(WORLD)
                .where(WORLD.NAME.eq(worldName))
                .and(WORLD.DELETED_AT.isNull())
                .fetchOptional(WORLD.UUID);
    }

    /**
     * World LOD 정보 조회
     */
    public Optional<WorldLodInfo> findWorldLodInfo(UUID worldUuid, short lod) {
        return dsl.select(
                        WORLD_LOD.UUID,
                        WORLD_LOD.CHUNK_EDGE_CELLS,
                        WORLD_LOD.VOXEL_SIZE_M
                )
                .from(WORLD_LOD)
                .where(WORLD_LOD.WORLD_ID.eq(worldUuid))
                .and(WORLD_LOD.LOD.eq(lod))
                .and(WORLD_LOD.DELETED_AT.isNull())
                .fetchOptional(record -> new WorldLodInfo(
                        record.value1(),
                        record.value2(),
                        record.value3()
                ));
    }

    /**
     * Chunk Index 조회
     */
    public Optional<UUID> findChunkIndexUuid(UUID worldUuid, short lod, int x, int y, int z) {
        return dsl.select(CHUNK_INDEX.UUID)
                .from(CHUNK_INDEX)
                .where(CHUNK_INDEX.WORLD_ID.eq(worldUuid))
                .and(CHUNK_INDEX.LOD.eq(lod))
                .and(CHUNK_INDEX.IX.eq(x))
                .and(CHUNK_INDEX.IY.eq(y))
                .and(CHUNK_INDEX.IZ.eq(z))
                .and(CHUNK_INDEX.DELETED_AT.isNull())
                .fetchOptional(CHUNK_INDEX.UUID);
    }

    /**
     * Chunk Index 생성
     */
    public UUID insertChunkIndex(UUID worldUuid, short lod, int x, int y, int z,
                                 int edgeCells, double voxelSizeM,
                                 double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ) {
        UUID chunkUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(CHUNK_INDEX)
                .set(CHUNK_INDEX.UUID, chunkUuid)
                .set(CHUNK_INDEX.WORLD_ID, worldUuid)
                .set(CHUNK_INDEX.LOD, lod)
                .set(CHUNK_INDEX.IX, x)
                .set(CHUNK_INDEX.IY, y)
                .set(CHUNK_INDEX.IZ, z)
                .set(CHUNK_INDEX.EDGE_CELLS, edgeCells)
                .set(CHUNK_INDEX.VOXEL_SIZE_M, voxelSizeM)
                .set(CHUNK_INDEX.AABB_MIN_X, minX)
                .set(CHUNK_INDEX.AABB_MIN_Y, minY)
                .set(CHUNK_INDEX.AABB_MIN_Z, minZ)
                .set(CHUNK_INDEX.AABB_MAX_X, maxX)
                .set(CHUNK_INDEX.AABB_MAX_Y, maxY)
                .set(CHUNK_INDEX.AABB_MAX_Z, maxZ)
                .set(CHUNK_INDEX.CURRENT_VERSION, 0L)
                .set(CHUNK_INDEX.CURRENT_MESH_VERSION, 0L)
                .set(CHUNK_INDEX.CREATED_AT, now)
                .set(CHUNK_INDEX.UPDATED_AT, now)
                .execute();

        return chunkUuid;
    }

    /**
     * 최신 스냅샷 버전 조회
     */
    public Optional<Long> findMaxSnapshotVersion(UUID chunkUuid) {
        return dsl.select(CHUNK_SNAPSHOT.VERSION.max())
                .from(CHUNK_SNAPSHOT)
                .where(CHUNK_SNAPSHOT.CHUNK_ID.eq(chunkUuid))
                .and(CHUNK_SNAPSHOT.DELETED_AT.isNull())
                .fetchOptional(0, Long.class);
    }

    /**
     * 최신 메쉬 버전 조회
     */
    public Optional<Long> findMaxMeshVersion(UUID chunkUuid) {
        return dsl.select(CHUNK_MESH.MESH_VERSION.max())
                .from(CHUNK_MESH)
                .where(CHUNK_MESH.CHUNK_ID.eq(chunkUuid))
                .and(CHUNK_MESH.DELETED_AT.isNull())
                .fetchOptional(0, Long.class);
    }

    /**
     * Chunk Snapshot 저장
     */
    public UUID insertChunkSnapshot(UUID chunkUuid, long version, String storageUri,
                                    int compressedBytes, int nonEmptyCells) {
        UUID snapshotUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(CHUNK_SNAPSHOT)
                .set(CHUNK_SNAPSHOT.UUID, snapshotUuid)
                .set(CHUNK_SNAPSHOT.CHUNK_ID, chunkUuid)
                .set(CHUNK_SNAPSHOT.VERSION, version)
                .set(CHUNK_SNAPSHOT.SCHEMA_VERSION, (short) 1)
                .set(CHUNK_SNAPSHOT.STORAGE_URI, storageUri)
                .set(CHUNK_SNAPSHOT.SNAPSHOT_KIND, "sparse-voxel")
                .set(CHUNK_SNAPSHOT.NON_EMPTY_CELLS, nonEmptyCells)
                .set(CHUNK_SNAPSHOT.COMPRESSED_BYTES, compressedBytes)
                .set(CHUNK_SNAPSHOT.CREATED_AT, now)
                .set(CHUNK_SNAPSHOT.UPDATED_AT, now)
                .execute();

        return snapshotUuid;
    }

    /**
     * Chunk Mesh 저장
     */
    public UUID insertChunkMesh(UUID chunkUuid, UUID snapshotUuid, long meshVersion,
                                String artifactUri, int compressedBytes) {
        UUID meshUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        dsl.insertInto(CHUNK_MESH)
                .set(CHUNK_MESH.UUID, meshUuid)
                .set(CHUNK_MESH.CHUNK_ID, chunkUuid)
                .set(CHUNK_MESH.SNAPSHOT_ID, snapshotUuid)
                .set(CHUNK_MESH.MESH_VERSION, meshVersion)
                .set(CHUNK_MESH.ARTIFACT_URI, artifactUri)
                .set(CHUNK_MESH.ARTIFACT_KIND, "glb")
                .set(CHUNK_MESH.COMPRESSED_BYTES, compressedBytes)
                .set(CHUNK_MESH.CREATED_AT, now)
                .set(CHUNK_MESH.UPDATED_AT, now)
                .execute();

        return meshUuid;
    }

    /**
     * Chunk Index 업데이트 (스냅샷 생성 후)
     */
    public void updateChunkIndexAfterSnapshot(UUID chunkUuid, UUID snapshotUuid,
                                              long version, UUID meshUuid, long meshVersion,
                                              Instant lastWriteAt) {
        OffsetDateTime writeAt = OffsetDateTime.ofInstant(lastWriteAt, ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        dsl.update(CHUNK_INDEX)
                .set(CHUNK_INDEX.CURRENT_SNAPSHOT_ID, snapshotUuid)
                .set(CHUNK_INDEX.CURRENT_VERSION, version)
                .set(CHUNK_INDEX.CURRENT_MESH_ID, meshUuid)
                .set(CHUNK_INDEX.CURRENT_MESH_VERSION, meshVersion)
                .set(CHUNK_INDEX.LAST_WRITE_AT, writeAt)
                .set(CHUNK_INDEX.UPDATED_AT, now)
                .where(CHUNK_INDEX.UUID.eq(chunkUuid))
                .execute();
    }

    /**
     * World LOD 정보
     */
    public record WorldLodInfo(UUID uuid, int edgeCells, double voxelSizeM) {}
}