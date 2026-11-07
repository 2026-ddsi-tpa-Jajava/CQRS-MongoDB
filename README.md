# Servicio de Consultas - DonaTrack

Servicio de lectura centralizado para la Entrega 6. Mantiene un modelo de lectura
en MongoDB, actualizado asincrónicamente a partir de eventos publicados por los
cuatro módulos del sistema.

## Ejecutar localmente

Requisitos: Java 21, Maven y MongoDB. RabbitMQ es opcional en desarrollo; se
utiliza para consumir eventos desde `consultas.queue`.

```powershell
mvn spring-boot:run
```

Variables principales:

| Variable | Valor por defecto | Uso |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/donatrack_read` | Conexión a MongoDB |
| `RABBITMQ_HOST` | `localhost` | Host de RabbitMQ |
| `RABBITMQ_PORT` | `5672` | Puerto de RabbitMQ |
| `RABBITMQ_USERNAME` | `guest` | Usuario de RabbitMQ |
| `RABBITMQ_PASSWORD` | `guest` | Contraseña de RabbitMQ |
| `PORT` | `8080` | Puerto HTTP |

## API

- `POST /eventos`: recibe un evento de integración y actualiza el modelo de lectura.
- `GET /consultas/donadores/{id}`: ficha integral. Devuelve estado, `categoriaActual`,
  `historialCategorias`, `insignias`, `misionEnCurso` (con `progresoActual`,
  `progresoRequerido` y `porcentaje`), `donacionesPorEstado`, `quejas` (`total` y `detalle`)
  y `entidadesBeneficiadas` (`entidadId`, `nombre` y `unidadesEntregadas`; solo asignaciones
  `COMPLETADA`).
- `GET /consultas/necesidades-urgentes?urgenciaMinima=1&tipo=EXTRAORDINARIA&categoria=ALIMENTOS&subcategoria=Granos&subcategoriaId=...`:
  necesidades con faltante > 0, ordenadas por urgencia. Cada una trae la entidad (id y nombre),
  el producto, `cantidadFaltante` (objetivo menos unidades entregadas),
  `unidadesAsignadasPendientes` (asignadas y todavía no entregadas) y `stockPorDeposito`.
- `GET /consultas/depositos?id=1&desde=2026-01-01T00:00:00&hasta=2026-12-31T23:59:59`:
  `ocupacion` (unidades, capacidad, porcentaje), `algoritmoMatchmaking`,
  `asignacionesPorEstado`, `entidadesAtendidas` y `tiempoPromedioIngresoAEntregaHoras`.
  Las asignaciones se filtran por su fecha de creación y el tiempo promedio por la fecha de
  entrega. `desde` y `hasta` se interpretan en UTC.
- `GET /consultas/calidad?limiteDonadores=10`: objeto con `porCategoria` (total, con queja y
  proporción por categoría/subcategoría) y `donadoresConMasQuejas` (con estado y categoría).
- `DELETE /reset`: limpia el modelo de lectura (solo para desarrollo/demo).

Los eventos deben tener la forma:

```json
{
  "id": "evento-123",
  "tipo": "DONACION_REGISTRADA",
  "version": 1,
  "ocurridoEn": "2026-10-26T20:00:00Z",
  "payload": {
    "id": "12",
    "donadorID": "4",
    "productoID": "7",
    "cantidad": 30
  }
}
```

Todos los campos del envelope (`id`, `tipo`, `version`, `ocurridoEn` y
`payload`) son obligatorios. `id` es la clave de idempotencia; `version` y
`ocurridoEn` se usan para
ignorar eventos repetidos o más viejos que el estado ya proyectado.

La cola `donaciones.queue` pertenece al flujo de workers de Logística y no debe
compartirse con el modelo de lectura. Según la configuración compartida por el
equipo, los eventos que consume este servicio llegan a la cola durable
`consultas.queue`.

Para activar el consumidor RabbitMQ:

```powershell
$env:RABBITMQ_ENABLED = "true"
mvn spring-boot:run
```

## Puesta a punto para la demo

1. Configurar `MONGODB_URI` con la base central de lectura y verificar la
   conexión.
2. Configurar las variables `RABBITMQ_*`. La cola durable
   `consultas.queue` se declara al iniciar el servicio.
3. Levantar este servicio con `RABBITMQ_ENABLED=true` antes de ejecutar
   comandos en los cuatro módulos.
4. Confirmar `GET /actuator/health` y ejecutar las consultas únicamente contra
   este servicio.

El modelo de lectura usa una colección por agregado (`donadores`,
`necesidades`, `depositos`, `donaciones`, `asignaciones`, `productos`,
`entidades`, `insignias`, `misiones` y `quejas`) y `processed_events` para la
idempotencia. Las consultas combinan únicamente esas colecciones. Los eventos de
`DONADOR_*` se ordenan solo por `ocurridoEn`, porque Donadores e Incentivos los publican
con numeraciones de `version` independientes.

## Logging centralizado

El servicio puede enviar sus logs a Better Stack mediante Logtail. En Render
configurá `BETTERSTACK_SOURCE_TOKEN` con el Source token de la fuente de CQRS.
El token no debe commitearse ni incluirse en este repositorio.

Variables opcionales:

```text
BETTERSTACK_SOURCE_TOKEN=...
BETTERSTACK_INGEST_URL=https://in.logs.betterstack.com
APP_NAME=donatrack-cqrs
```

Sin `BETTERSTACK_SOURCE_TOKEN`, los logs continúan saliendo por consola.
