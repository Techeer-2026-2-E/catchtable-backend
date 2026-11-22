package com.catchtable.support;

import org.flywaydb.core.api.MigrationVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** SQL을 직접 적용해 이전 스키마를 구성하는 테스트에서도 Flyway의 버전 순서를 사용한다. */
public final class MigrationSqlFiles {
    private MigrationSqlFiles() {}

    public static List<Path> all() throws IOException {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            return files.filter(path -> path.getFileName().toString().startsWith("V") && path.toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(MigrationSqlFiles::version))
                    .toList();
        }
    }

    public static List<Path> before(int exclusiveVersion) throws IOException {
        var limit = MigrationVersion.fromVersion(Integer.toString(exclusiveVersion));
        return all().stream().filter(path -> version(path).compareTo(limit) < 0).toList();
    }

    public static MigrationVersion version(Path path) {
        String name = path.getFileName().toString();
        return MigrationVersion.fromVersion(name.substring(1, name.indexOf("__")));
    }
}
