package com.ssafy.test.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, byte[]> redisTemplateByte(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // key serializer
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // value serializer (바이트 배열 그대로)
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashValueSerializer(RedisSerializer.byteArray());

        template.afterPropertiesSet();
        return template;
    }
}
