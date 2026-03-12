package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaTraceKeysTest {

    @Test
    void normalizeRejectsBlankAndLowercasesValues() {
        assertNull(LuaTraceKeys.normalize(null));
        assertNull(LuaTraceKeys.normalize("   "));
        assertEquals("event:tick", LuaTraceKeys.normalize(" Event:Tick "));
    }

    @Test
    void addRemoveAndSnapshotPreserveNormalizedInsertionOrder() {
        LuaTraceKeys keys = new LuaTraceKeys();

        assertTrue(keys.add(" Event:Tick "));
        assertFalse(keys.add("event:tick"));
        assertTrue(keys.add("Network:Chat"));
        assertTrue(keys.contains("network:chat"));
        assertEquals(List.of("event:tick", "network:chat"), List.copyOf(keys.snapshot()));

        assertTrue(keys.remove(" NETWORK:CHAT "));
        assertFalse(keys.contains("network:chat"));
        assertEquals(List.of("event:tick"), List.copyOf(keys.snapshot()));
    }
}
