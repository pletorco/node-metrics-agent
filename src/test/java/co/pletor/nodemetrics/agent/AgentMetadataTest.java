package co.pletor.nodemetrics.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentMetadataTest {

    @Test
    void testMetadataLoading() {
        // When the test is run via Gradle, generateVersionProperties task should have run,
        // so we expect real values instead of "unknown".
        String version = AgentMetadata.getVersion();
        String commitId = AgentMetadata.getCommitId();

        assertNotNull(version, "Version should not be null");
        assertNotNull(commitId, "Commit ID should not be null");

        assertEquals("0.8.0", version, "Version should be loaded from generated properties");
        
        // Basic pattern checks
        assertNotEquals("", version.trim(), "Version should not be empty");
        assertNotEquals("", commitId.trim(), "Commit ID should not be empty");
    }
}
