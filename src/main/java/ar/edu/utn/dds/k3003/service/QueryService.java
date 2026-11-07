package ar.edu.utn.dds.k3003.service;

import ar.edu.utn.dds.k3003.model.ReadDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consultas complejas sobre el modelo de lectura. Cada documento de las colecciones se
 * devuelve como un mapa plano: los campos del evento mas el campo "id" del agregado.
 */
@Service
public class QueryService {
    static final String DELIVERED = "COMPLETADA";
    static final String COMPLAINT = "CONQUEJA";

    private final MongoTemplate mongoTemplate;

    public QueryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ------------------------------------------------------------------ 1. Ficha integral de un donador

    public Optional<Map<String, Object>> donorCard(String donorId) {
        ReadDocument donorDocument = mongoTemplate.findById(donorId, ReadDocument.class, "donadores");
        if (donorDocument == null) {
            return Optional.empty();
        }
        Map<String, Object> donor = flatten(donorDocument);

        Map<String, Object> result = new LinkedHashMap<>(donor);
        result.remove("ultimaActualizacion");
        result.remove("insignias");
        result.remove("historialCategorias");
        result.remove("misionActual");
        result.put("categoriaActual", donor.get("categoria"));
        result.put("historialCategorias", asList(donor.get("historialCategorias")));
        result.put("insignias", badgesOf(donorId, donor));
        result.put("misionEnCurso", currentMission(donorId, donor));

        List<Map<String, Object>> donations = find("donaciones", "donadorID", donorId);
        Map<String, Integer> byState = new LinkedHashMap<>();
        for (Map<String, Object> donation : donations) {
            byState.merge(textOr(donation, "estado", "SIN_ESTADO"), 1, Integer::sum);
        }
        result.put("totalDonaciones", donations.size());
        result.put("donacionesPorEstado", byState);

        List<Map<String, Object>> complaints = find("quejas", "donadorID", donorId);
        Map<String, Object> complaintsResult = new LinkedHashMap<>();
        complaintsResult.put("total", complaints.size());
        complaintsResult.put("detalle", complaints);
        result.put("quejas", complaintsResult);

        result.put("entidadesBeneficiadas", beneficiaryEntities(donations));
        return Optional.of(result);
    }

    private List<Map<String, Object>> badgesOf(String donorId, Map<String, Object> donor) {
        List<Map<String, Object>> badges = new ArrayList<>();
        for (Map<String, Object> badge : find("insignias", "donadorID", donorId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", badge.get("insigniaID"));
            item.put("nombre", badge.get("nombre"));
            item.put("descripcion", badge.get("descripcion"));
            badges.add(item);
        }
        if (badges.isEmpty()) {
            for (Object item : asList(donor.get("insignias"))) {
                if (item instanceof Map<?, ?> map) {
                    badges.add(new LinkedHashMap<>(castMap(map)));
                }
            }
        }
        return badges;
    }

    private Map<String, Object> currentMission(String donorId, Map<String, Object> donor) {
        if (!(donor.get("misionActual") instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> current = castMap(raw);
        String missionId = text(current, "id");
        Map<String, Object> mission = new LinkedHashMap<>(current);
        mission.put("misionID", missionId);
        mission.remove("id");
        if (missionId != null) {
            ReadDocument processed = mongoTemplate.findById(donorId + ":" + missionId, ReadDocument.class, "misiones");
            if (processed != null) {
                Map<String, Object> progress = flatten(processed);
                for (String key : List.of("progresoActual", "progresoRequerido", "porcentaje")) {
                    mission.put(key, progress.get(key));
                }
            }
        }
        mission.putIfAbsent("progresoActual", null);
        mission.putIfAbsent("progresoRequerido", null);
        mission.putIfAbsent("porcentaje", null);
        return mission;
    }

    /** Entidades que recibieron donaciones del donador, con las unidades efectivamente entregadas. */
    private List<Map<String, Object>> beneficiaryEntities(List<Map<String, Object>> donations) {
        Set<String> donationIds = new HashSet<>();
        donations.forEach(donation -> donationIds.add(text(donation, "id")));
        if (donationIds.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("fields.donacionID").in(donationIds)
                .and("fields.estado").is(DELIVERED));
        Map<String, Map<String, Object>> needs = byId(all("necesidades"));
        Map<String, Map<String, Object>> entities = byId(all("entidades"));

        Map<String, Integer> units = new LinkedHashMap<>();
        for (Map<String, Object> assignment : toMaps(mongoTemplate.find(query, ReadDocument.class, "asignaciones"))) {
            String entityId = entityOf(assignment, needs);
            if (entityId != null) {
                units.merge(entityId, number(assignment.get("cantidad")), Integer::sum);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        units.forEach((entityId, amount) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entidadId", entityId);
            item.put("nombre", entityName(entities.get(entityId)));
            item.put("unidadesEntregadas", amount);
            result.add(item);
        });
        result.sort(Comparator.comparing((Map<String, Object> item) -> number(item.get("unidadesEntregadas"))).reversed());
        return result;
    }

    // ------------------------------------------------------------------ 2. Necesidades urgentes sin cubrir

    public List<Map<String, Object>> urgentNeeds(int minUrgency, String type, String category,
                                                 String subcategory, String subcategoryId) {
        Map<String, Map<String, Object>> products = byId(all("productos"));
        Map<String, Map<String, Object>> entities = byId(all("entidades"));
        List<Map<String, Object>> warehouses = all("depositos");
        Map<String, List<Map<String, Object>>> assignmentsByNeed = groupBy(all("asignaciones"), "necesidadID");
        boolean productFilter = category != null || subcategory != null || subcategoryId != null;

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> need : all("necesidades")) {
            if (Boolean.TRUE.equals(need.get("eliminada")) || number(need.get("nivelDeUrgencia")) < minUrgency) {
                continue;
            }
            if (type != null && !type.equalsIgnoreCase(text(need, "tipo"))) {
                continue;
            }
            String productId = text(need, "productoSolicitadoID");
            Map<String, Object> product = productId == null ? null : products.get(productId);
            if (productFilter && !matchesProduct(product, productId, category, subcategory, subcategoryId)) {
                continue;
            }

            int delivered = 0;
            int pending = 0;
            for (Map<String, Object> assignment : assignmentsByNeed.getOrDefault(text(need, "id"), List.of())) {
                if (DELIVERED.equals(text(assignment, "estado"))) {
                    delivered += number(assignment.get("cantidad"));
                } else {
                    pending += number(assignment.get("cantidad"));
                }
            }
            int missing = Math.max(0, number(need.get("cantidadObjetivo")) - delivered);
            if (missing == 0) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", need.get("id"));
            item.put("descripcion", need.get("descripcion"));
            item.put("tipo", need.get("tipo"));
            item.put("nivelDeUrgencia", need.get("nivelDeUrgencia"));
            Map<String, Object> entity = new LinkedHashMap<>();
            String entityId = text(need, "entidadID");
            entity.put("id", entityId);
            entity.put("nombre", entityName(entityId == null ? null : entities.get(entityId)));
            item.put("entidad", entity);
            item.put("producto", productSummary(productId, product));
            item.put("cantidadObjetivo", need.get("cantidadObjetivo"));
            item.put("cantidadEntregada", delivered);
            item.put("cantidadFaltante", missing);
            item.put("unidadesAsignadasPendientes", pending);
            item.put("stockPorDeposito", stockByWarehouse(warehouses, productId));
            result.add(item);
        }
        result.sort(Comparator
                .comparing((Map<String, Object> item) -> number(item.get("nivelDeUrgencia"))).reversed()
                .thenComparing(item -> number(item.get("cantidadFaltante")), Comparator.reverseOrder()));
        return result;
    }

    private boolean matchesProduct(Map<String, Object> product, String productId,
                                   String category, String subcategory, String subcategoryId) {
        if (subcategoryId != null && subcategoryId.equals(productId)) {
            return true;
        }
        if (product == null) {
            return false;
        }
        return (category == null || category.equalsIgnoreCase(text(product, "categoria")))
                && (subcategory == null || subcategory.equalsIgnoreCase(text(product, "subcategoria")))
                && (subcategoryId == null || subcategoryId.equals(text(product, "subcategoriaID")));
    }

    private Map<String, Object> productSummary(String productId, Map<String, Object> product) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", productId);
        summary.put("nombre", product == null ? null : product.get("nombre"));
        summary.put("categoria", product == null ? null : product.get("categoria"));
        summary.put("subcategoria", product == null ? null : product.get("subcategoria"));
        summary.put("subcategoriaId", product == null ? null : product.get("subcategoriaID"));
        return summary;
    }

    private List<Map<String, Object>> stockByWarehouse(List<Map<String, Object>> warehouses, String productId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> warehouse : warehouses) {
            int amount = 0;
            for (Object item : asList(warehouse.get("stockActual"))) {
                if (item instanceof Map<?, ?> pack && productId != null
                        && productId.equals(String.valueOf(pack.get("producto")))) {
                    amount += number(pack.get("cantidad"));
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("depositoId", warehouse.get("id"));
            entry.put("nombre", warehouse.get("nombre"));
            entry.put("unidades", amount);
            result.add(entry);
        }
        return result;
    }

    // ------------------------------------------------------------------ 3. Estado de los depositos

    public List<Map<String, Object>> warehouses(String warehouseId, LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> warehouses = new ArrayList<>();
        if (warehouseId == null) {
            warehouses.addAll(all("depositos"));
        } else {
            ReadDocument document = mongoTemplate.findById(warehouseId, ReadDocument.class, "depositos");
            if (document != null) {
                warehouses.add(flatten(document));
            }
        }
        Map<String, List<Map<String, Object>>> assignmentsByWarehouse = groupBy(all("asignaciones"), "depositoID");
        Map<String, Map<String, Object>> donations = byId(all("donaciones"));
        Map<String, Map<String, Object>> needs = byId(all("necesidades"));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> warehouse : warehouses) {
            List<Map<String, Object>> assignments =
                    assignmentsByWarehouse.getOrDefault(text(warehouse, "id"), List.of());
            result.add(warehouseStatus(warehouse, assignments, donations, needs, from, to));
        }
        return result;
    }

    private Map<String, Object> warehouseStatus(Map<String, Object> warehouse,
                                                List<Map<String, Object>> assignments,
                                                Map<String, Map<String, Object>> donations,
                                                Map<String, Map<String, Object>> needs,
                                                LocalDateTime from, LocalDateTime to) {
        Map<String, Integer> stockByProduct = new LinkedHashMap<>();
        int stock = 0;
        List<?> packages = asList(warehouse.get("stockActual"));
        for (Object item : packages) {
            if (item instanceof Map<?, ?> pack) {
                int amount = number(pack.get("cantidad"));
                stock += amount;
                stockByProduct.merge(String.valueOf(pack.get("producto")), amount, Integer::sum);
            }
        }
        if (packages.isEmpty()) {
            stock = number(warehouse.get("cantidadStock"));
        }
        int capacity = number(warehouse.get("capacidadMaxima"));

        Map<String, Object> occupancy = new LinkedHashMap<>();
        occupancy.put("unidades", stock);
        occupancy.put("capacidad", capacity);
        occupancy.put("porcentaje", capacity == 0 ? null : round(stock * 100.0 / capacity, 2));

        Map<String, Integer> byState = new LinkedHashMap<>();
        byState.put("ASIGNADA", 0);
        byState.put("COMPLETADA", 0);
        Set<String> entityIds = new HashSet<>();
        int totalAssignments = 0;
        for (Map<String, Object> assignment : assignments) {
            if (!inRange(instant(assignment.get("fecha")), from, to)) {
                continue;
            }
            totalAssignments++;
            byState.merge(textOr(assignment, "estado", "ASIGNADA"), 1, Integer::sum);
            String entityId = entityOf(assignment, needs);
            if (entityId != null) {
                entityIds.add(entityId);
            }
        }

        long totalSeconds = 0;
        int deliveries = 0;
        for (Map<String, Object> assignment : assignments) {
            Instant delivered = instant(assignment.get("fechaEntrega"));
            Map<String, Object> donation = donations.get(text(assignment, "donacionID"));
            Instant entered = donation == null ? null : instant(donation.get("fechaIngreso"));
            if (delivered != null && entered != null && inRange(delivered, from, to)) {
                totalSeconds += Math.max(0, Duration.between(entered, delivered).getSeconds());
                deliveries++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", warehouse.get("id"));
        result.put("nombre", warehouse.get("nombre"));
        result.put("direccion", warehouse.get("direccion"));
        result.put("ocupacion", occupancy);
        result.put("stockPorProducto", stockByProduct);
        result.put("algoritmoMatchmaking", warehouse.get("algoritmo"));
        result.put("desde", from);
        result.put("hasta", to);
        result.put("totalAsignaciones", totalAssignments);
        result.put("asignacionesPorEstado", byState);
        result.put("entidadesAtendidas", entityIds.size());
        result.put("entregasConsideradas", deliveries);
        result.put("tiempoPromedioIngresoAEntregaHoras",
                deliveries == 0 ? null : round(totalSeconds / (double) deliveries / 3600.0, 2));
        return result;
    }

    // ------------------------------------------------------------------ 4. Calidad de lo donado

    public Map<String, Object> quality(int donorLimit) {
        Map<String, Map<String, Object>> products = byId(all("productos"));
        Map<String, Map<String, Object>> donors = byId(all("donadores"));
        List<Map<String, Object>> donations = all("donaciones");

        Map<String, long[]> groups = new LinkedHashMap<>();
        Map<String, String[]> names = new HashMap<>();
        Map<String, Integer> complaintDonations = new HashMap<>();
        for (Map<String, Object> donation : donations) {
            Map<String, Object> product = products.get(text(donation, "productoID"));
            String category = firstNonBlank(text(donation, "categoria"), product == null ? null : text(product, "categoria"));
            String subcategory = firstNonBlank(text(donation, "subcategoria"), product == null ? null : text(product, "subcategoria"));
            category = category == null ? "SIN_CATEGORIA" : category;
            subcategory = subcategory == null ? "SIN_SUBCATEGORIA" : subcategory;
            String key = category + "|" + subcategory;
            names.putIfAbsent(key, new String[]{category, subcategory});
            long[] counters = groups.computeIfAbsent(key, ignored -> new long[2]);
            counters[0]++;
            if (COMPLAINT.equals(text(donation, "estado"))) {
                counters[1]++;
                complaintDonations.merge(textOr(donation, "donadorID", ""), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> byCategory = new ArrayList<>();
        groups.forEach((key, counters) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoria", names.get(key)[0]);
            row.put("subcategoria", names.get(key)[1]);
            row.put("totalDonaciones", counters[0]);
            row.put("donacionesConQueja", counters[1]);
            row.put("proporcionConQueja", round(counters[1] / (double) counters[0], 4));
            byCategory.add(row);
        });
        byCategory.sort(Comparator
                .comparing((Map<String, Object> row) -> ((Number) row.get("proporcionConQueja")).doubleValue()).reversed());

        Map<String, Integer> complaintsByDonor = new HashMap<>();
        for (Map<String, Object> complaint : all("quejas")) {
            String donorId = text(complaint, "donadorID");
            if (donorId != null) {
                complaintsByDonor.merge(donorId, 1, Integer::sum);
            }
        }
        if (complaintsByDonor.isEmpty()) {
            complaintsByDonor.putAll(complaintDonations);
            complaintsByDonor.remove("");
        }
        List<Map<String, Object>> topDonors = new ArrayList<>();
        complaintsByDonor.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(donorLimit, 0))
                .forEach(entry -> {
                    Map<String, Object> donor = donors.get(entry.getKey());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("donadorId", entry.getKey());
                    row.put("nombre", donor == null ? null : fullName(donor));
                    row.put("estado", donor == null ? null : donor.get("estado"));
                    row.put("categoria", donor == null ? null : donor.get("categoria"));
                    row.put("cantidadQuejas", entry.getValue());
                    topDonors.add(row);
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("porCategoria", byCategory);
        result.put("donadoresConMasQuejas", topDonors);
        return result;
    }

    // ------------------------------------------------------------------ utilidades

    private String entityOf(Map<String, Object> assignment, Map<String, Map<String, Object>> needs) {
        String entityId = text(assignment, "idEntidad");
        if (entityId != null && !entityId.isBlank()) {
            return entityId;
        }
        Map<String, Object> need = needs.get(text(assignment, "necesidadID"));
        return need == null ? null : text(need, "entidadID");
    }

    private String entityName(Map<String, Object> entity) {
        return entity == null ? null : firstNonBlank(text(entity, "razonSocial"), text(entity, "nombre"));
    }

    private String fullName(Map<String, Object> donor) {
        String name = firstNonBlank(text(donor, "nombre"), "");
        String surname = firstNonBlank(text(donor, "apellido"), "");
        String full = (name + " " + surname).trim();
        return full.isEmpty() ? null : full;
    }

    private boolean inRange(Instant moment, LocalDateTime from, LocalDateTime to) {
        if (from == null && to == null) {
            return true;
        }
        if (moment == null) {
            return false;
        }
        LocalDateTime value = LocalDateTime.ofInstant(moment, ZoneOffset.UTC);
        return (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to));
    }

    /** Interpreta fechas ISO con zona (Instant) o sin zona (se asumen UTC); null si no se puede. */
    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        String text = value.toString();
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            } catch (RuntimeException alsoIgnored) {
                return null;
            }
        }
    }

    private List<Map<String, Object>> all(String collection) {
        return toMaps(mongoTemplate.findAll(ReadDocument.class, collection));
    }

    private List<Map<String, Object>> find(String collection, String field, String value) {
        Query query = Query.query(Criteria.where("fields." + field).is(value));
        return toMaps(mongoTemplate.find(query, ReadDocument.class, collection));
    }

    private List<Map<String, Object>> toMaps(Collection<ReadDocument> documents) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReadDocument document : documents) {
            result.add(flatten(document));
        }
        return result;
    }

    private Map<String, Object> flatten(ReadDocument document) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (document.getFields() != null) {
            map.putAll(document.getFields());
        }
        map.put("id", document.getId());
        return map;
    }

    private Map<String, Map<String, Object>> byId(List<Map<String, Object>> documents) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        documents.forEach(document -> result.put(text(document, "id"), document));
        return result;
    }

    private Map<String, List<Map<String, Object>>> groupBy(List<Map<String, Object>> documents, String field) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        for (Map<String, Object> document : documents) {
            String key = text(document, field);
            if (key != null) {
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(document);
            }
        }
        return result;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static String textOr(Map<String, Object> values, String key, String fallback) {
        String value = text(values, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
