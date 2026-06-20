package co.pletor.nodemetrics.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction for system access to allow mocking in tests.
 */
interface SystemAccess {
  String getProperty(String key, String def);

  String getEnv(String name);

  boolean isRegularFile(Path path);

  boolean isDirectory(Path path);

  String readString(Path path) throws IOException;

  boolean isLinux();

  List<String> readLines(Path path) throws IOException;

  LinuxProcFs.CgroupInfo detectCgroup();

  static SystemAccess defaultInstance() {
    return new DefaultSystemAccess();
  }

  class DefaultSystemAccess implements SystemAccess {
    @Override
    public String getProperty(String key, String def) {
      try {
        String v = getSystemProperty(key, def);
        return v == null ? "" : v;
      } catch (SecurityException e) {
        return "";
      }
    }

    @Override
    public String getEnv(String name) {
      try {
        return getSystemEnv(name);
      } catch (SecurityException e) {
        return null;
      }
    }

    // Visible for testing
    String getSystemProperty(String key, String def) {
      return System.getProperty(key, def);
    }

    // Visible for testing
    String getSystemEnv(String name) {
      return System.getenv(name);
    }

    @Override
    public boolean isRegularFile(Path path) {
      return Files.isRegularFile(path);
    }

    @Override
    public boolean isDirectory(Path path) {
      return Files.isDirectory(path);
    }

    @Override
    public String readString(Path path) throws IOException {
      return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Override
    public boolean isLinux() {
      return LinuxProcFs.isLinux();
    }

    @Override
    public List<String> readLines(Path path) throws IOException {
      return LinuxProcFs.readLines(path);
    }

    @Override
    public LinuxProcFs.CgroupInfo detectCgroup() {
      return LinuxProcFs.detectCgroup();
    }
  }
}
