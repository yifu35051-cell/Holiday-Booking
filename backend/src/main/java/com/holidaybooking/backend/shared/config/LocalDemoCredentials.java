package com.holidaybooking.backend.shared.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** 随机演示密码只存于后端本地文件；数据库仍通过IdentityService保存BCrypt哈希。 */
final class LocalDemoCredentials {
    private LocalDemoCredentials() {}

    static Properties loadOrCreate(Path path, List<String> emails) {
        try {
            Path file = path.toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());
            if (!Files.exists(file) || Files.size(file) == 0) {
                // Linux containers use 0600; Windows keeps the workspace's inherited ACL.
                // Never change a user's workspace ACL or require administrator permissions.
                if (Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class)
                        != null) {
                    if (!Files.exists(file)) {
                        Files.createFile(
                                file,
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------")));
                    } else {
                        Files.setPosixFilePermissions(
                                file, PosixFilePermissions.fromString("rw-------"));
                    }
                } else if (!Files.exists(file)) {
                    Files.createFile(file);
                }
                var random = new SecureRandom();
                var content =
                        new StringBuilder(
                                "# Local demo accounts. Do not commit, publish or paste into frontend configuration.\n");
                for (String email : emails) {
                    byte[] bytes = new byte[32];
                    random.nextBytes(bytes);
                    content.append(email)
                            .append('=')
                            .append(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
                            .append('\n');
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
            Properties result = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                result.load(reader);
            }
            if (!result.stringPropertyNames().equals(java.util.Set.copyOf(emails))) {
                throw new IOException("Unexpected demo credential entries");
            }
            for (String email : emails) {
                if (!result.getProperty(email).matches("[A-Za-z0-9_-]{43}")) {
                    throw new IOException("Incomplete demo credential entry");
                }
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read/create local demo credential file; existing account passwords were not changed",
                    failure);
        }
    }
}
