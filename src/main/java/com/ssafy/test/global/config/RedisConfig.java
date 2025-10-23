package com.ssafy.test.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ssafy.test.snapshot.dto.DeltaDTO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

//    @Bean
//    public RedisTemplate<String, String> redisTemplateDelta(RedisConnectionFactory connectionFactory) {
//        RedisTemplate<String, String> template = new RedisTemplate<>();
//        template.setConnectionFactory(connectionFactory);
//        StringRedisSerializer stringSerializer = new StringRedisSerializer();
//
//        // ObjectMapper 설정 (직렬화 안정성 향상)
//        ObjectMapper objectMapper = JsonMapper.builder()
//                .findAndAddModules()
//                .build();
//
//        Jackson2JsonRedisSerializer<DeltaDTO> jacksonSerializer =
//                new Jackson2JsonRedisSerializer<>(objectMapper, DeltaDTO.class);
//
//        // key는 String
//        template.setKeySerializer(stringSerializer);
//        template.setHashKeySerializer(stringSerializer);
//
//        template.setValueSerializer(jacksonSerializer);
//        template.setHashValueSerializer(jacksonSerializer);
//
//        template.afterPropertiesSet();
//        return template;
//    }

    @Bean
    public RedisTemplate<String, String> redisTemplateDelta(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
