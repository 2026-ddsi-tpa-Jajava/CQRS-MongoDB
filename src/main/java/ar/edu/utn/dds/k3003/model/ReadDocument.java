package ar.edu.utn.dds.k3003.model;

import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.Map;

public class ReadDocument {
    @Id
    private String id;
    private long version;
    private Instant updatedAt;
    private Map<String, Object> fields;

    public ReadDocument() {
    }

    public ReadDocument(String id, long version, Instant updatedAt, Map<String, Object> fields) {
        this.id = id;
        this.version = version;
        this.updatedAt = updatedAt;
        this.fields = fields;
    }

    public String getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields;
    }
}
