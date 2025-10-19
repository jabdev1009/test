-- ============================================
-- 1. Extensions & common helpers
-- ============================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE OR REPLACE FUNCTION set_timestamp_mod() RETURNS trigger AS $func$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$func$ LANGUAGE plpgsql;



-- ============================================
-- 2. ENUM Types
-- ============================================
-- ENUM 생성
DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'artifact_kind_enum') THEN
            CREATE TYPE artifact_kind_enum AS ENUM ('gltf+draco','gltf+meshopt','gltf','glb');
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'snapshot_kind_enum') THEN
            CREATE TYPE snapshot_kind_enum AS ENUM ('mesh-surface','sparse-voxel');
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'compaction_status_enum') THEN
            CREATE TYPE compaction_status_enum AS ENUM ('queued','running','succeeded','failed');
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'tombstone_reason_enum') THEN
            CREATE TYPE tombstone_reason_enum AS ENUM ('moderation','legal','rollback');
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'color_schema_enum') THEN
            CREATE TYPE color_schema_enum AS ENUM ('RGB1','RGB_FACES');
        END IF;
    END $$;




-- ============================================
-- 3. Tables & Triggers
-- ============================================

-- WORLD
CREATE TABLE IF NOT EXISTS world (
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
DROP TRIGGER IF EXISTS trg_world_upd ON world;
CREATE TRIGGER trg_world_upd BEFORE UPDATE ON world
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();

-- WORLD_LOD
CREATE TABLE IF NOT EXISTS world_lod (
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
DROP TRIGGER IF EXISTS trg_world_lod_upd ON world_lod;
CREATE TRIGGER trg_world_lod_upd BEFORE UPDATE ON world_lod
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();

-- CHUNK_INDEX
CREATE TABLE IF NOT EXISTS chunk_index (
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
CREATE INDEX IF NOT EXISTS idx_chunk_index_world_grid ON chunk_index(world_id, lod, ix, iy, iz);
DROP TRIGGER IF EXISTS trg_chunk_index_upd ON chunk_index;
CREATE TRIGGER trg_chunk_index_upd BEFORE UPDATE ON chunk_index
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();

-- CHUNK_SNAPSHOT
CREATE TABLE IF NOT EXISTS chunk_snapshot (
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
CREATE INDEX IF NOT EXISTS idx_chunk_snapshot_chunk_desc
    ON chunk_snapshot(chunk_id, version DESC);
DROP TRIGGER IF EXISTS trg_chunk_snapshot_upd ON chunk_snapshot;
CREATE TRIGGER trg_chunk_snapshot_upd BEFORE UPDATE ON chunk_snapshot
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();

-- CHUNK_MESH
CREATE TABLE IF NOT EXISTS chunk_mesh (
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
CREATE INDEX IF NOT EXISTS idx_chunk_mesh_chunk_desc
    ON chunk_mesh(chunk_id, mesh_version DESC);
DROP TRIGGER IF EXISTS trg_chunk_mesh_upd ON chunk_mesh;
CREATE TRIGGER trg_chunk_mesh_upd BEFORE UPDATE ON chunk_mesh
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();

-- VOXEL_DELTA
CREATE TABLE IF NOT EXISTS voxel_delta (
                                           uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                           chunk_id UUID NOT NULL REFERENCES chunk_index(uuid),
                                           op_id UUID NOT NULL,
                                           op_seq BIGINT GENERATED BY DEFAULT AS IDENTITY,
                                           timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                           voxel_id INTEGER NOT NULL,
                                           face_mask SMALLINT NOT NULL DEFAULT 63,
                                           color_schema color_schema_enum NOT NULL DEFAULT 'RGB1',
                                           color_bytes BYTEA NOT NULL,
                                           actor CITEXT,
                                           policy_tags CITEXT[],
                                           created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                           updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                           deleted_at TIMESTAMPTZ,
                                           UNIQUE (chunk_id, op_id)
);
ALTER TABLE voxel_delta
    DROP CONSTRAINT IF EXISTS chk_voxel_delta_color;
ALTER TABLE voxel_delta
    ADD CONSTRAINT chk_voxel_delta_color
        CHECK (
            (color_schema='RGB1'      AND octet_length(color_bytes) IN (3)) OR
            (color_schema='RGB_FACES' AND octet_length(color_bytes) IN (18))
            );
ALTER TABLE voxel_delta
    DROP CONSTRAINT IF EXISTS chk_voxel_delta_face_mask_range;
ALTER TABLE voxel_delta
    ADD CONSTRAINT chk_voxel_delta_face_mask_range CHECK (face_mask BETWEEN 0 AND 63);
CREATE INDEX IF NOT EXISTS idx_voxel_delta_chunk_seq   ON voxel_delta(chunk_id, op_seq);
CREATE INDEX IF NOT EXISTS idx_voxel_delta_chunk_ts    ON voxel_delta(chunk_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_voxel_delta_chunk_voxel ON voxel_delta(chunk_id, voxel_id);
CREATE INDEX IF NOT EXISTS idx_voxel_delta_chunk_voxel_seq ON voxel_delta(chunk_id, voxel_id, op_seq DESC);
CREATE INDEX IF NOT EXISTS idx_voxel_delta_policy_tags ON voxel_delta USING GIN (policy_tags);
DROP TRIGGER IF EXISTS trg_voxel_delta_upd ON voxel_delta;
CREATE TRIGGER trg_voxel_delta_upd BEFORE UPDATE ON voxel_delta
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();

-- DELTA_TOMBSTONE
CREATE TABLE IF NOT EXISTS delta_tombstone (
                                               uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                               chunk_id UUID NOT NULL REFERENCES chunk_index(uuid),
                                               target_op_id UUID NOT NULL,
                                               reason tombstone_reason_enum NOT NULL,
                                               note JSONB,
                                               timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                               updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                               deleted_at TIMESTAMPTZ,
                                               UNIQUE (chunk_id, target_op_id)
);
CREATE INDEX IF NOT EXISTS idx_tombstone_chunk ON delta_tombstone(chunk_id);
CREATE INDEX IF NOT EXISTS idx_tombstone_chunk_target ON delta_tombstone(chunk_id, target_op_id);
DROP TRIGGER IF EXISTS trg_delta_tombstone_upd ON delta_tombstone;
CREATE TRIGGER trg_delta_tombstone_upd BEFORE UPDATE ON delta_tombstone
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();

-- CHUNK_COMPACTION_JOB
CREATE TABLE IF NOT EXISTS chunk_compaction_job (
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
CREATE INDEX IF NOT EXISTS idx_compaction_chunk_status ON chunk_compaction_job(chunk_id, status);
DROP TRIGGER IF EXISTS trg_chunk_compaction_job_upd ON chunk_compaction_job;
CREATE TRIGGER trg_chunk_compaction_job_upd BEFORE UPDATE ON chunk_compaction_job
    FOR EACH ROW EXECUTE FUNCTION set_timestamp_mod();
