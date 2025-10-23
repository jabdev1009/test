-- ============================================
-- JOOQ Compatible Schema
-- ============================================

-- ============================================
-- 1. ENUM Types (조건부 생성 제거)
-- ============================================
CREATE TYPE artifact_kind_enum AS ENUM ('gltf+draco','gltf+meshopt','gltf','glb');
CREATE TYPE snapshot_kind_enum AS ENUM ('mesh-surface','sparse-voxel');
CREATE TYPE compaction_status_enum AS ENUM ('queued','running','succeeded','failed');
CREATE TYPE tombstone_reason_enum AS ENUM ('moderation','legal','rollback');
CREATE TYPE color_schema_enum AS ENUM ('RGB1','RGB_FACES');


-- ============================================
-- 2. Tables
-- ============================================

-- WORLD
CREATE TABLE world (
                       uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       name TEXT NOT NULL,
                       description TEXT,
                       origin_x_ecef DOUBLE PRECISION DEFAULT 0,
                       origin_y_ecef DOUBLE PRECISION DEFAULT 0,
                       origin_z_ecef DOUBLE PRECISION DEFAULT 0,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                       deleted_at TIMESTAMPTZ
);

-- WORLD_LOD
CREATE TABLE world_lod (
                           uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           world_id UUID NOT NULL REFERENCES world(uuid),
                           lod SMALLINT NOT NULL,
                           chunk_edge_cells INTEGER NOT NULL CHECK (chunk_edge_cells > 0),
                           voxel_size_m DOUBLE PRECISION NOT NULL CHECK (voxel_size_m > 0),
                           morton_order BOOLEAN NOT NULL DEFAULT TRUE,
                           lod_meta JSONB,
                           created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                           updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                           deleted_at TIMESTAMPTZ,
                           UNIQUE (world_id, lod)
);

-- CHUNK_INDEX
CREATE TABLE chunk_index (
                             uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                             world_id UUID NOT NULL REFERENCES world(uuid),
                             lod SMALLINT NOT NULL,
                             ix INTEGER NOT NULL,
                             iy INTEGER NOT NULL,
                             iz INTEGER NOT NULL,
                             edge_cells INTEGER NOT NULL,
                             voxel_size_m DOUBLE PRECISION NOT NULL,
                             aabb_min_x DOUBLE PRECISION NOT NULL,
                             aabb_min_y DOUBLE PRECISION NOT NULL,
                             aabb_min_z DOUBLE PRECISION NOT NULL,
                             aabb_max_x DOUBLE PRECISION NOT NULL,
                             aabb_max_y DOUBLE PRECISION NOT NULL,
                             aabb_max_z DOUBLE PRECISION NOT NULL,
                             current_snapshot_id UUID,
                             current_mesh_id UUID,
                             current_version BIGINT NOT NULL DEFAULT 0,
                             current_mesh_version BIGINT NOT NULL DEFAULT 0,
                             last_write_at TIMESTAMPTZ,
                             last_compacted_at TIMESTAMPTZ,
                             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                             updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                             deleted_at TIMESTAMPTZ,
                             UNIQUE (world_id, lod, ix, iy, iz)
);

CREATE INDEX idx_chunk_index_world_grid ON chunk_index(world_id, lod, ix, iy, iz);

-- CHUNK_SNAPSHOT
CREATE TABLE chunk_snapshot (
                                uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                chunk_id UUID NOT NULL REFERENCES chunk_index(uuid),
                                version BIGINT NOT NULL,
                                schema_version SMALLINT NOT NULL DEFAULT 1,
                                storage_uri TEXT,
                                payload JSONB,
                                snapshot_kind snapshot_kind_enum NOT NULL DEFAULT 'mesh-surface',
                                non_empty_cells INTEGER,
                                compressed_bytes INTEGER,
                                checksum_sha256 BYTEA,
                                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                deleted_at TIMESTAMPTZ,
                                UNIQUE (chunk_id, version)
);

CREATE INDEX idx_chunk_snapshot_chunk_desc ON chunk_snapshot(chunk_id, version DESC);

-- CHUNK_MESH
CREATE TABLE chunk_mesh (
                            uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            chunk_id UUID NOT NULL REFERENCES chunk_index(uuid),
                            snapshot_id UUID REFERENCES chunk_snapshot(uuid),
                            mesh_version BIGINT NOT NULL,
                            artifact_uri TEXT NOT NULL,
                            artifact_kind artifact_kind_enum NOT NULL DEFAULT 'gltf+draco',
                            tri_count BIGINT,
                            vertex_count BIGINT,
                            compressed_bytes INTEGER,
                            checksum_sha256 BYTEA,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                            deleted_at TIMESTAMPTZ,
                            UNIQUE (chunk_id, mesh_version)
);

CREATE INDEX idx_chunk_mesh_chunk_desc ON chunk_mesh(chunk_id, mesh_version DESC);

-- CHUNK_COMPACTION_JOB
CREATE TABLE chunk_compaction_job (
                                      uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                      chunk_id UUID NOT NULL REFERENCES chunk_index(uuid),
                                      from_version BIGINT NOT NULL,
                                      to_version BIGINT,
                                      status compaction_status_enum NOT NULL DEFAULT 'queued',
                                      started_at TIMESTAMPTZ,
                                      finished_at TIMESTAMPTZ,
                                      stats JSONB,
                                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                      deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_compaction_chunk_status ON chunk_compaction_job(chunk_id, status);