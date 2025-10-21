package com.ssafy.test.global.config;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RedisDummyMaker {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @PostConstruct
    public void checkConnection() {
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            System.out.println("Redis Ping Response: " + pong);

            // 연결 성공 시 스트림 데이터 생성
            String chunkId = "{world:exampleWorld}:l0:x0:y0:z0"; // 샤딩 고려한 key
            String streamKey = "stream:{chunk:" + chunkId + "}:deltas";

            // 델타 엔트리 필드 생성
            Map<String, String> fields = new HashMap<>();
            fields.put("op_id", UUID.randomUUID().toString());
            fields.put("voxel_id", "123456");
            fields.put("face_mask", "63");
            fields.put("color_schema", "RGB1");
            fields.put("color_bytes", "FF0000");  // 단순 예시. 실제로는 바이트 직렬화 권장
            fields.put("actor", "system");
            fields.put("policy_tags", "test");
            fields.put("ts", Instant.now().toString());

            // XADD (스트림 자동 생성됨)
            RecordId recordId = redisTemplate.opsForStream()
                    .add(MapRecord.create(streamKey, fields));

            System.out.println("✅ DELTAS stream created. Record ID: " + recordId.getValue());
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("❌ Redis 연결 실패");
        }
    }
}
