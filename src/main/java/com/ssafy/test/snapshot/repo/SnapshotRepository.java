package com.ssafy.test.snapshot.repo;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import static com.example.jooq.generated.Tables.CHUNK_INDEX;

@Repository
@RequiredArgsConstructor
public class SnapshotRepository {

    private final DSLContext dsl;

    public UUID findLatestChunkIndexUuid(int ix, int iy, int iz) {
        return dsl.selectFrom(CHUNK_INDEX)
                .where(CHUNK_INDEX.IX.eq(ix))
                .and(CHUNK_INDEX.IY.eq(iy))
                .and(CHUNK_INDEX.IZ.eq(iz))
                .and(CHUNK_INDEX.LOD.eq((short) 0))
                .orderBy(CHUNK_INDEX.CURRENT_VERSION.desc()) // 최신 버전
                .limit(1)
                .fetchOneInto(UUID.class); // UUID만 가져오기
    }
}
