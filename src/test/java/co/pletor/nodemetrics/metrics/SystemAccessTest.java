package co.pletor.nodemetrics.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SystemAccessTest {

  @Test
  void testGetProperty() {
    SystemAccess sys = SystemAccess.defaultInstance();
    // Trusted property that should always exist or be settable
    String key = "java.version";
    String val = sys.getProperty(key, "default");
    assertNotNull(val);
    assertFalse(val.isEmpty());

    // Missing property
    String missing = sys.getProperty("this.prop.should.not.exist.hopefully", "default");
    assertEquals("default", missing);
  }

  @Test
  void testGetPropertySecurityException() {
    SystemAccess.DefaultSystemAccess sys = new SystemAccess.DefaultSystemAccess() {
      @Override
      String getSystemProperty(String key, String def) {
        throw new SecurityException("Access denied");
      }
    };

    String result = sys.getProperty("forbidden.prop", "default");
    // implementation returns "" on SecurityException
    assertEquals("", result, "Should return empty string on SecurityException");
  }

  @Test
  void testGetEnv() {
    SystemAccess sys = SystemAccess.defaultInstance();
    // PATH is usually present on all supported OSes
    String val = sys.getEnv("PATH");
    // If PATH is missing (rare), it might be null, but we just check calls don't crash
    // The interface contract returns null for missing envs
    if (val != null) {
      assertFalse(val.isEmpty());
    }
  }

  @Test
  void testGetEnvSecurityException() {
    SystemAccess.DefaultSystemAccess sys = new SystemAccess.DefaultSystemAccess() {
      @Override
      String getSystemEnv(String name) {
        throw new SecurityException("Access denied");
      }
    };

    String result = sys.getEnv("FORBIDDEN_ENV");
    // implementation returns null on SecurityException
    assertNull(result, "Should return null on SecurityException");
  }

  @Test
  void testFileOperations(@TempDir Path tempDir) throws IOException {
    SystemAccess sys = SystemAccess.defaultInstance();
    
    // 1. Regular File
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "hello world");
    
    assertTrue(sys.isRegularFile(file));
    assertFalse(sys.isDirectory(file));
    
    // 2. Directory
    assertTrue(sys.isDirectory(tempDir));
    assertFalse(sys.isRegularFile(tempDir));
    
    // 3. Read String
    String content = sys.readString(file);
    assertEquals("hello world", content.trim());
    
    // 4. Read Lines
    List<String> lines = sys.readLines(file);
    assertEquals(1, lines.size());
    assertEquals("hello world", lines.get(0));
  }
  
  @Test
  void testIsLinux() {
    SystemAccess sys = SystemAccess.defaultInstance();
    // We can't assert true or false deterministically across all environments,
    // but we can ensure it runs without error.
    boolean isLinux = sys.isLinux();
    
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("linux")) {
      assertTrue(isLinux, "Should report true on Linux");
    } else {
      assertFalse(isLinux, "Should report false on non-Linux");
    }
  }

  @Test
  void testDetectCgroup() {
    SystemAccess sys = SystemAccess.defaultInstance();
    LinuxProcFs.CgroupInfo info = sys.detectCgroup();
    assertNotNull(info);
    // Info fields might be null/empty depending on OS, but object must exist
    assertNotNull(info.version);
  }
}
