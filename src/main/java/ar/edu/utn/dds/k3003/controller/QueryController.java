package ar.edu.utn.dds.k3003.controller;

import ar.edu.utn.dds.k3003.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/consultas")
public class QueryController {
    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/donadores/{id}")
    public ResponseEntity<Map<String, Object>> donorCard(@PathVariable String id) {
        log.info("GET /consultas/donadores/{}", id);
        return queryService.donorCard(id)
                .map(card -> {
                    log.info("GET /consultas/donadores/{}: ficha armada", id);
                    return ResponseEntity.ok(card);
                })
                .orElseGet(() -> {
                    log.info("GET /consultas/donadores/{}: donador no encontrado", id);
                    return ResponseEntity.notFound().build();
                });
    }

    @GetMapping("/necesidades-urgentes")
    public List<Map<String, Object>> urgentNeeds(
            @RequestParam(defaultValue = "1") int urgenciaMinima,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String subcategoria,
            @RequestParam(required = false) String subcategoriaId) {
        log.info("GET /consultas/necesidades-urgentes urgenciaMinima={} tipo={} categoria={} subcategoria={} subcategoriaId={}",
                urgenciaMinima, tipo, categoria, subcategoria, subcategoriaId);
        List<Map<String, Object>> result =
                queryService.urgentNeeds(urgenciaMinima, tipo, categoria, subcategoria, subcategoriaId);
        log.info("GET /consultas/necesidades-urgentes: {} necesidades", result.size());
        return result;
    }

    @GetMapping("/depositos")
    public List<Map<String, Object>> warehouses(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        log.info("GET /consultas/depositos id={} desde={} hasta={}", id, desde, hasta);
        List<Map<String, Object>> result = queryService.warehouses(id, desde, hasta);
        log.info("GET /consultas/depositos: {} depositos", result.size());
        return result;
    }

    @GetMapping("/calidad")
    public Map<String, Object> donationQuality(@RequestParam(defaultValue = "10") int limiteDonadores) {
        log.info("GET /consultas/calidad limiteDonadores={}", limiteDonadores);
        Map<String, Object> result = queryService.quality(limiteDonadores);
        log.info("GET /consultas/calidad: resuelta");
        return result;
    }
}
