package com.jianli.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 简历原件的私有存储目录。
 */
@ConfigurationProperties(prefix = "app.resume-storage")
public record ResumeStorageProperties(Path root) {
}