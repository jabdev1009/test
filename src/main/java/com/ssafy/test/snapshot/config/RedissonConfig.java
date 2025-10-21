package com.ssafy.test.snapshot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonReactiveClient redissonReactiveClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://localhost:6379"); // 필요 시 password 추가
        return Redisson.createReactive(config);
    }
}

