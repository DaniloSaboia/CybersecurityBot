package com.sentinel.kms.ingest;

/**
 * Wire representation of a {@link Connector}. The {@code last} key maps to the
 * entity's {@code lastLabel}; {@code config} is intentionally not exposed.
 */
public record ConnectorDto(
        String id,
        String name,
        String kind,
        String status,
        int docs,
        String last,
        boolean auto,
        java.util.Map<String, Object> sync
) {
    public ConnectorDto(String id, String name, String kind, String status, int docs, String last, boolean auto) {
        this(id,name,kind,status,docs,last,auto,java.util.Map.of());
    }
    public static ConnectorDto from(Connector c) {
        return new ConnectorDto(
                c.getId(),
                c.getName(),
                c.getKind(),
                c.getStatus(),
                c.getDocs(),
                c.getLastLabel(),
                c.isAuto()
        );
    }
}
