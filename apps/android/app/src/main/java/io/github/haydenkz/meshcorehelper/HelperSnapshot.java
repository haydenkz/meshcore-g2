package io.github.haydenkz.meshcorehelper;

/** Radio state shared with the Compose screen; HTTP diagnostics remain separate. */
public record HelperSnapshot(String state, String detail, String name, Integer protocolVersion, Integer batteryMillivolts) {}
