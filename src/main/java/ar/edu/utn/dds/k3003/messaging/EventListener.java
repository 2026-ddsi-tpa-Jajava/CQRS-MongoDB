package ar.edu.utn.dds.k3003.messaging;

import ar.edu.utn.dds.k3003.events.IntegrationEvent;
import ar.edu.utn.dds.k3003.service.ReadModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

import static ar.edu.utn.dds.k3003.config.RabbitConfig.DONACIONES_QUEUE;

@Component
public class EventListener {
    private final ReadModelService readModelService;
    private final ObjectMapper objectMapper;

    public EventListener(ReadModelService readModelService, ObjectMapper objectMapper) {
        this.readModelService = readModelService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = DONACIONES_QUEUE)
    public void receive(Map<String, Object> message) {
        JsonNode node = objectMapper.valueToTree(message);
        String id = text(node, "id", text(node, "donacionID", null));
        if (id == null) {
            throw new IllegalArgumentException("El mensaje de RabbitMQ no contiene id ni donacionID");
        }
        String type = text(node, "tipo", "DONACION_REGISTRADA");
        Long version = node.has("version") ? node.get("version").asLong() : 0L;
        Instant occurredAt = node.has("ocurridoEn")
                ? objectMapper.convertValue(node.get("ocurridoEn"), Instant.class)
                : Instant.now();
        readModelService.apply(new IntegrationEvent(id, type, version, occurredAt, node));
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }
}
