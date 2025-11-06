
package com.ssafy.test.snapshot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static com.ssafy.test.snapshot.service.SnapshotOrchestrator.OPID_PREFIX;

@Service
@RequiredArgsConstructor
public class RedisOperationService {

    private static final Logger log = LoggerFactory.getLogger(RedisOperationService.class);
    private static final String DELTAS_PREFIX = "deltas:";
    private static final String TOMBSTONE_PREFIX = "tombstone:";

    private final StringRedisTemplate redisTemplate;

    @Transactional
    public void cleanupProcessedData(String chunkKey, Set<String> opIds,
                                     Set<String> tombstoneOpIds, double maxScore) {
        try {
            List<Object> txResults = redisTemplate.execute((RedisCallback<List<Object>>) connection -> {
                var zSetOps = connection.zSetCommands();
                var hashOps = connection.hashCommands();

                connection.multi();

                // delta 제거
                if (opIds != null && !opIds.isEmpty()) {
                    byte[] chunkKeyBytes = redisTemplate.getStringSerializer().serialize(chunkKey);
                    zSetOps.zRemRangeByScore(chunkKeyBytes, Range.closed(Double.NEGATIVE_INFINITY, maxScore));

                    String deltaKey = DELTAS_PREFIX + chunkKey.substring(OPID_PREFIX.length());
                    byte[] deltaKeyBytes = redisTemplate.getStringSerializer().serialize(deltaKey);
                    byte[][] fields = opIds.stream()
                            .map(id -> redisTemplate.getStringSerializer().serialize(id))
                            .toArray(byte[][]::new);
                    hashOps.hDel(deltaKeyBytes, fields);
                }

                // tombstone 제거
                if (tombstoneOpIds != null && !tombstoneOpIds.isEmpty()) {
                    String tombKey = TOMBSTONE_PREFIX + chunkKey.substring(OPID_PREFIX.length());
                    byte[] tombKeyBytes = redisTemplate.getStringSerializer().serialize(tombKey);
                    zSetOps.zRemRangeByScore(tombKeyBytes, Range.closed(Double.NEGATIVE_INFINITY, maxScore));
                }

                return connection.exec(); // 트랜잭션 실행
            });

            if (txResults == null) {
                log.warn("Redis 트랜잭션이 중단되었습니다 (EXEC 결과 null). 청크: {}", chunkKey);
                return;
            }

            log.info("Redis 트랜잭션 완료 ✅ 청크: {}, 작업 개수: {}", chunkKey, txResults.size());

        } catch (Exception e) {
            log.error("Redis 트랜잭션 실패 ❌ 청크: {}", chunkKey, e);
        }
    }
}
