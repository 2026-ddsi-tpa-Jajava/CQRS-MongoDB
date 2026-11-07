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
- `GET /consultas/donadores/{id}`: ficha integral de un donador.
- `GET /consultas/necesidades-urgentes?urgenciaMinima=1&tipo=EXTRAORDINARIA&categoria=ALIMENTOS&subcategoriaId=...`
- `GET /consultas/depositos?desde=2026-01-01T00:00:00&hasta=2026-12-31T23:59:59`
- `GET /consultas/calidad`
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
`entidades`, `insignias` y `misiones`) y `processed_events` para la
idempotencia. `GET /consultas/calidad` usa una agregación de MongoDB sobre
`donaciones`; las demás consultas combinan únicamente esas colecciones.

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