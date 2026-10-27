package ar.edu.utn.dds.k3003.controller;

import ar.edu.utn.dds.k3003.model.ReadDocument;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/consultas")
public class QueryController {
    private final MongoTemplate mongoTemplate;

    public QueryController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/donadores/{id}")
    public ResponseEntity<Map<String, Object>> donorCard(@PathVariable String id) {
        ReadDocument donor = mongoTemplate.findById(id, ReadDocument.class, "donadores");
        if (donor == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>(donor.getFields());
        result.put("donaciones", documentsBy("donaciones", "donadorID", id));
        result.put("quejas", documentsBy("donaciones", "donadorID", id, "estado", "CONQUEJA"));
        result.put("asignaciones", documentsBy("asignaciones", "donadorID", id));
        result.put("insignias", documentsBy("insignias", "donadorID", id));
        result.put("misionEnCurso", documentsBy("misiones", "donadorID", id));
        result.put("entidadesBeneficiadas", benefitedEntities(id));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/necesidades-urgentes")
    public List<Map<String, Object>> urgentNeeds(
            @RequestParam(defaultValue = "1") int urgenciaMinima,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String subcategoriaId) {
        Query query = Query.query(Criteria.where("fields.nivelDeUrgencia").gte(urgenciaMinima));
        if (tipo != null) {
            query.addCriteria(Criteria.where("fields.tipo").is(tipo));
        }
        if (categoria != null) {
            query.addCriteria(Criteria.where("fields.categoria").is(categoria));
        }
        if (subcategoriaId != null) {
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("fields.subcategoriaID").is(subcategoriaId),
                    Criteria.where("fields.productoSolicitadoID").is(subcategoriaId)));
        }
        List<Map<String, Object>> result = toMaps(mongoTemplate.find(query, ReadDocument.class, "necesidades"));
        for (Map<String, Object> need : result) {
            String productId = text(need, "productoSolicitadoID");
            need.put("stockPorDeposito", stockByWarehouse(productId));
            need.put("unidadesAsignadasPendientes",
                    pendingAssignments(text(need, "id"), text(need, "necesidadID")));
        }
        result.sort((left, right) -> Integer.compare(
                number(right.get("nivelDeUrgencia")), number(left.get("nivelDeUrgencia"))));
        return result;
    }

    @GetMapping("/depositos")
    public List<Map<String, Object>> warehouses(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        List<ReadDocument> documents;
        if (id == null) {
            documents = mongoTemplate.findAll(ReadDocument.class, "depositos");
        } else {
            ReadDocument document = mongoTemplate.findById(id, ReadDocument.class, "depositos");
            documents = document == null ? List.of() : List.of(document);
        }
        return documents.stream().map(document -> {
            Map<String, Object> result = new LinkedHashMap<>(document.getFields());
            result.put("asignaciones", countAssignments(document.getId(), desde, hasta));
            result.put("entidadesAtendidas", distinctEntities(document.getId(), desde, hasta));
            return result;
        }).toList();
    }

    @GetMapping("/calidad")
    public List<Document> donationQuality() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.project()
                        .and("fields.categoria").as("categoria")
                        .and("fields.subcategoria").as("subcategoria")
                        .and("fields.estado").as("estado"),
                Aggregation.group("categoria", "subcategoria")
                        .count().as("totalDonaciones")
                        .sum(ConditionalOperators.when(Criteria.where("estado").is("CONQUEJA"))
                                .then(1).otherwise(0)).as("donacionesConQueja"),
                Aggregation.project("totalDonaciones", "donacionesConQueja")
                        .and("_id.categoria").as("categoria")
                        .and("_id.subcategoria").as("subcategoria")
                        .andExpression("donacionesConQueja / totalDonaciones").as("proporcionConQueja"));
        return mongoTemplate.aggregate(aggregation, "donaciones", Document.class).getMappedResults();
    }

    private List<Map<String, Object>> documentsBy(String collection, String key, String value) {
        return documentsBy(collection, key, value, null, null);
    }

    private List<Map<String, Object>> documentsBy(String collection, String key, String value,
                                                  String secondKey, String secondValue) {
        Query query = Query.query(Criteria.where("fields." + key).is(value));
        if (secondKey != null) {
            query.addCriteria(Criteria.where("fields." + secondKey).is(secondValue));
        }
        return toMaps(mongoTemplate.find(query, ReadDocument.class, collection));
    }

    private long countAssignments(String depositId, LocalDateTime desde, LocalDateTime hasta) {
        return assignmentsFor(depositId, desde, hasta).size();
    }

    private List<Map<String, Object>> assignmentsFor(String depositId, LocalDateTime desde, LocalDateTime hasta) {
        Query query = Query.query(Criteria.where("fields.depositoID").is(depositId));
        if (desde != null) {
            query.addCriteria(Criteria.where("fields.fecha").gte(desde));
        }
        if (hasta != null) {
            query.addCriteria(Criteria.where("fields.fecha").lte(hasta));
        }
        return toMaps(mongoTemplate.find(query, ReadDocument.class, "asignaciones"));
    }

    private long distinctEntities(String depositId, LocalDateTime desde, LocalDateTime hasta) {
        return assignmentsFor(depositId, desde, hasta).stream()
                .map(item -> item.get("idEntidad"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }

    private List<Map<String, Object>> benefitedEntities(String donorId) {
        Map<String, Integer> unitsByEntity = new LinkedHashMap<>();
        for (Map<String, Object> assignment : documentsBy("asignaciones", "donadorID", donorId)) {
            String entityId = text(assignment, "idEntidad");
            if (entityId != null) {
                unitsByEntity.merge(entityId, number(assignment.get("cantidad")), Integer::sum);
            }
        }
        return unitsByEntity.entrySet().stream()
                .map(entry -> Map.<String, Object>of("entidadId", entry.getKey(), "unidades", entry.getValue()))
                .toList();
    }

    private List<Map<String, Object>> stockByWarehouse(String productId) {
        if (productId == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReadDocument warehouse : mongoTemplate.findAll(ReadDocument.class, "depositos")) {
            Object stock = warehouse.getFields().get("stockActual");
            int amount = 0;
            if (stock instanceof List<?> packages) {
                for (Object item : packages) {
                    if (item instanceof Map<?, ?> packageData
                            && productId.equals(String.valueOf(packageData.get("producto")))) {
                        amount += number(packageData.get("cantidad"));
                    }
                }
            }
            result.add(Map.of("depositoId", warehouse.getId(), "unidades", amount));
        }
        return result;
    }

    private int pendingAssignments(String needId, String alternativeNeedId) {
        int total = 0;
        for (Map<String, Object> assignment : documentsBy("asignaciones", "necesidadID",
                needId == null ? alternativeNeedId : needId)) {
            String state = text(assignment, "estado");
            if (!"ACEPTADA".equals(state) && !"ENTREGADA".equals(state)) {
                total += number(assignment.get("cantidad"));
            }
        }
        return total;
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private List<Map<String, Object>> toMaps(List<ReadDocument> documents) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReadDocument document : documents) {
            result.add(new LinkedHashMap<>(document.getFields()));
        }
        return result;
    }
}
