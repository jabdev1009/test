import org.jooq.meta.jaxb.Property

plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("nu.studer.jooq") version "8.0" // jOOQ codegen
}

group = "com.ssafy"
version = "0.0.1-SNAPSHOT"
description = "test"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // S3
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3:3.1.1")

    // flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // jooq
    jooqGenerator("org.postgresql:postgresql:42.6.0")
    jooqGenerator("org.jooq:jooq-meta-extensions:3.20.0")
    implementation("org.springframework.boot:spring-boot-starter-jooq")

    // jgltf
    implementation("de.javagl:jgltf-model:2.0.3")
    implementation("de.javagl:jgltf-impl-v2:2.0.3")
}


configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

jooq {
    version.set("3.20.0")
    configurations {
        create("main") {
            jooqConfiguration.apply {
                generator.apply {
                    name = "org.jooq.codegen.DefaultGenerator"
                    database.apply {
                        name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                        properties.add(Property().apply {
                            key = "scripts"
                            value = "src/main/resources/db-schema"
                        })
                        properties.add(Property().apply {
                            key = "sort"
                            value = "flyway"
                        })
                        properties.add(Property().apply {
                            key = "unqualifiedSchema"
                            value = "none"
                        })
                        properties.add(Property().apply {
                            key = "defaultNameCase"
                            value = "as_is"
                        })

                    }
                    generate.apply {
                        isDeprecated = false
                        isRecords = true
                        isImmutablePojos = true
                        isFluentSetters = true
                        isJavaTimeTypes = true
                    }

                    target.apply {
                        packageName = "com.example.jooq.generated"
                        directory = "build/generated-src/jooq"
                        encoding = "UTF-8"
                    }
                }
            }
        }
    }
}

sourceSets {
    main {
        java {
            srcDir("build/generated-src/jooq")
        }
    }
}
tasks.register("prepareDbAndJooq") {
    dependsOn("flywayMigrate", "generateJooq")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    dependsOn("prepareDbAndJooq")
}

tasks.named("clean") {
    doLast {
        delete("build/generated-src/jooq")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
