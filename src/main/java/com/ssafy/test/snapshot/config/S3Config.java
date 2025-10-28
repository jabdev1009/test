package com.ssafy.test.snapshot.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class S3Config {
// docker run -d --name minio -p 9000:9000 -p 9001:9001 -e "MINIO_ROOT_USER=test" -e "MINIO_ROOT_PASSWORD=testtest1234" -v ~/minio/data:/data quay.io/minio/minio server /data --console-address ":9001"
    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
