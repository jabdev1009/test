package com.ssafy.test.snapshot.repo;

import com.example.jooq.generated.enums.ArtifactKindEnum;
import com.example.jooq.generated.enums.SnapshotKindEnum;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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

@Repository
@RequiredArgsConstructor
public class ChunkRepository {

    private final DSLContext dsl;

    public Optional<UUID> findWorldUuidByName(String worldName) {
        return dsl.select(WORLD.UUID)
                .from(WORLD)
                .where(WORLD.NAME.eq(worldName))
                .and(WORLD.DELETED_AT.isNull())
                .fetchOptional(WORLD.UUID);
    }

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

    public Optional<Integer> findMaxSnapshotVersion(UUID chunkUuid) {
        return dsl.select(DSL.max(CHUNK_SNAPSHOT.VERSION))
                .from(CHUNK_SNAPSHOT)
                .where(CHUNK_SNAPSHOT.CHUNK_ID.eq(chunkUuid))
                .and(CHUNK_SNAPSHOT.DELETED_AT.isNull())
                .fetchOptional(0, Integer.class);
    }

    public Optional<Long> findMaxMeshVersion(UUID chunkUuid) {
        return dsl.select(DSL.max(CHUNK_MESH.MESH_VERSION))
                .from(CHUNK_MESH)
                .where(CHUNK_MESH.CHUNK_ID.eq(chunkUuid))
                .and(CHUNK_MESH.DELETED_AT.isNull())
                .fetchOptional(0, Long.class);
    }

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
                .set(CHUNK_SNAPSHOT.SNAPSHOT_KIND, SnapshotKindEnum.sparse_voxel)
                .set(CHUNK_SNAPSHOT.NON_EMPTY_CELLS, nonEmptyCells)
                .set(CHUNK_SNAPSHOT.COMPRESSED_BYTES, compressedBytes)
                .set(CHUNK_SNAPSHOT.CREATED_AT, now)
                .set(CHUNK_SNAPSHOT.UPDATED_AT, now)
                .execute();

        return snapshotUuid;
    }

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
                .set(CHUNK_MESH.ARTIFACT_KIND, ArtifactKindEnum.glb)
                .set(CHUNK_MESH.COMPRESSED_BYTES, compressedBytes)
                .set(CHUNK_MESH.CREATED_AT, now)
                .set(CHUNK_MESH.UPDATED_AT, now)
                .execute();

        return meshUuid;
    }

    public int updateChunkIndexAfterSnapshot(UUID chunkUuid, UUID snapshotUuid,
                                             long version, UUID meshUuid, long meshVersion,
                                             Instant lastWriteAt) {
        OffsetDateTime writeAt = OffsetDateTime.ofInstant(lastWriteAt, ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return dsl.update(CHUNK_INDEX)
                .set(CHUNK_INDEX.CURRENT_SNAPSHOT_ID, snapshotUuid)
                .set(CHUNK_INDEX.CURRENT_VERSION, version)
                .set(CHUNK_INDEX.CURRENT_MESH_ID, meshUuid)
                .set(CHUNK_INDEX.CURRENT_MESH_VERSION, meshVersion)
                .set(CHUNK_INDEX.LAST_WRITE_AT, writeAt)
                .set(CHUNK_INDEX.UPDATED_AT, now)
                .where(CHUNK_INDEX.UUID.eq(chunkUuid))
                .execute();
    }

    public record WorldLodInfo(UUID uuid, int edgeCells, double voxelSizeM) {}
}