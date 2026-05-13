# `docker/`

Configuración Docker para levantar un cluster local de 3 nodos Node con un stack opt-in de observabilidad (Prometheus, Grafana, Tempo, Loki, Alertmanager, Promtail).

## Estructura

```
docker/
├── scripts/         # smokes de regresión + helpers + ops scripts
├── observability/   # stack Prometheus/Grafana/Tempo/Loki/Alertmanager
├── keys/            # claves ECDSA por nodo (gitignored, generado)
└── env/             # variables de entorno por nodo (gitignored, generado)
```

- [`scripts/`](scripts/) — 14 smokes end-to-end firmados + 3 helpers + 2 scripts ops standalone. Detalle completo en [`scripts/README.md`](scripts/README.md).
- [`observability/`](observability/) — configuración de el stack opt-in (`profile docker observability`). Detalle completo en [`observability/README.md`](observability/README.md).
- `keys/` — claves ECDSA P-256 por nodo (DER + base64) + nodeIds derivados. Generadas por `scripts/generate-node-keys.sh`. **Nunca** commit a git: el `.gitignore` las excluye.
- `env/` — `node{1,2,3}.env` con `NODE_IDENTITY_*` y whitelists. Generadas por el mismo script. También gitignored.

## Quickstart

```bash
# 1. Generar claves + env files (idempotente)
bash docker/scripts/generate-node-keys.sh

# 2. Levantar el cluster (3 nodos + 3 Postgres)
docker compose up -d --build

# 3. Smoke de validación end-to-end
bash docker/scripts/smoke-e2e.sh --fast --reuse-keys
```

`smoke-e2e` ejerce el flow discovery query → negotiation → recovery store/get con validación SQL contra postgres. Si pasa, el cluster está sano.

## Stack de observabilidad (opt-in)

```bash
GRAFANA_ADMIN_PASSWORD=localdev docker compose --profile observability up -d --build
```

Levanta adicionalmente Prometheus (`:9090`), Grafana (`:3000`), Alertmanager (`:9093`), Tempo (`:3200`/`:4317`/`:4318`), Loki (`:3100`) y un Promtail sidecar por nodo. `docker compose up` sin `--profile` mantiene los 3 nodos arriba sin observability — ése es el modo default para CI smoke.

Detalles operativos (puertos, dashboards, catálogo de alertas, política de recalibración de umbrales, cómo añadir métricas/alertas nuevas) en [`observability/README.md`](observability/README.md).

## Cluster local: roles y topología

Cluster determinista de 3 nodos con roles distribuidos:

| Nodo | Puerto host | Failure domain | Roles superiores |
|---|---|---|---|
| node1 | `8081` | `zone-a/rack-1` | nodo común |
| node2 | `8082` | `zone-b/rack-1` | **supernodo discovery + tutor** |
| node3 | `8083` | `zone-c/rack-1` | nodo común |

Activación de roles superiores vía feature flags en `application.properties` + env del compose (ver §2.1 de [`system-overview.md`](../docs/architecture/system-overview.md) para el mapping completo `feature flag → comportamiento`):

- `node.features.discovery-enabled=true` + `node.discovery.supernode-role-enabled=true` activan el rol servidor discovery (sólo node2).
- `node.features.recovery-enabled=true` + `node.topology.tutorAcceptedPublicKeys` activan el rol tutor (sólo node2).
- `node.features.{ingest,negotiation}-enabled` y `node.custody-liveness.enabled` se activan en los 3.

## Convenciones invariantes

- **Aislamiento management/application port**. Cada nodo expone dos puertos: `8080` (aplicación, publicado al host como `808{1,2,3}`) y `8181` (management, **no publicado al host**, sólo visible en la red docker). Las métricas Prometheus se sirven exclusivamente en el management port. Esta separación está congelada por `ActuatorSocketIsolationIntegrationTest`.
- **`docker/keys/` y `docker/env/` están gitignored**. Las claves privadas y las whitelists viajan en `env/node{N}.env` como `NODE_IDENTITY_PRIVATE_KEY_BASE64`. Nunca se versionan.
- **`SPRING_APPLICATION_JSON.custody-liveness.remote-base-urls` se sincroniza con los hashes nodales** al regenerar claves. Sin sync, custody-liveness no resuelve peers (`IllegalStateException: No remote base URL configured for nodeId ...`). El sync lo hace `generate-node-keys.sh` automáticamente.
- **Cleanup automático**. Los smokes en modo default corren `docker compose down -v` al salir; `--keep-running` (o `--fast`) los deja vivos para inspección manual.

## Documentación cruzada

| Pregunta | Doc |
|---|---|
| ¿Cómo añado una métrica/alerta nueva? | [`docker/observability/README.md`](observability/README.md) |
| ¿Qué hace exactamente cada smoke? | [`docker/scripts/README.md`](scripts/README.md) |
