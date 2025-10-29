WITH inserted_world AS (
INSERT INTO world (name)
VALUES ('exampleWorld')
    RETURNING uuid
    )
INSERT INTO world_lod (world_id, lod, chunk_edge_cells, voxel_size_m)
SELECT uuid, 0, 256, 1
FROM inserted_world;