# scripts/ops/

Utilidades de operación del nodo: backup + restore, drill de disaster recovery,
verificación de supply-chain y observabilidad, smoke de carga.

Todas se invocan desde la raíz del repo:

```bash
./scripts/ops/<script>.sh
./scripts/ops/<script>.sh --help    # cuando aplica
```

## Scripts por categoría

### Backup + restore

El flujo canónico es: `backup-cycle.sh` orquesta los `backup-*` individuales y
luego `verify-restore-integrity.sh` valida que los artefactos resultantes son
restoreables. Los `restore-*` son las contrapartidas para un restore real.

| Script | Propósito |
|---|---|
| [`backup-cycle.sh`](backup-cycle.sh) | Orquesta un ciclo completo: backup Postgres (auth-fs y/o full) + binarios + verificación de restore-integrity. Aplica retención por días y emite report del ciclo. |
| [`backup-binaries.sh`](backup-binaries.sh) | Empaqueta directorios binarios del nodo (client-files + staging + recovery) en tar.gz. Scope independiente del backup de Postgres. |
| [`backup-users.sh`](backup-users.sh) | Vuelca la base Postgres del nodo via `pg_dump`. `BACKUP_SCOPE=auth-fs` (default) o `full`. |
| [`restore-binaries.sh`](restore-binaries.sh) | Restaura un tar.gz de binarios sobre los directorios locales del nodo. Soporta purge previa. |
| [`restore-users.sh`](restore-users.sh) | Restaura un dump de Postgres con `pg_restore`. Por defecto revoca sesiones activas tras el restore. |
| [`verify-restore-integrity.sh`](verify-restore-integrity.sh) | Valida en sandbox que los artefactos de backup son restoreables, sin tocar la base ni los directorios productivos. |

### Disaster recovery

| Script | Propósito |
|---|---|
| [`dr-drill-rpo-rto.sh`](dr-drill-rpo-rto.sh) | DR drill que mide RPO y RTO observados contra targets configurables (default RPO=6h, RTO=15min) e invoca el smoke recovery end-to-end como prueba real del flow de reconstrucción. |

### Verificación supply-chain / observabilidad

| Script | Propósito |
|---|---|
| [`verify-reedsolomon-supply-chain.sh`](verify-reedsolomon-supply-chain.sh) | Verifica SHA-256 del JAR + POM de la dependencia JavaReedSolomon en el repo Maven local contra los hashes esperados. Pensado como gate de CI contra dependency confusion / JAR sustituidos. |
| [`observability-smoke.sh`](observability-smoke.sh) | Smoke del stack de observabilidad: arranca profile observability, espera Prometheus + Grafana ready, valida alert rules con `promtool`, comprueba `up == 1` para los 3 nodos e invoca `verify-metrics-export.sh` como dual-emit check. |
| [`verify-metrics-export.sh`](verify-metrics-export.sh) | Verifica que `/actuator/prometheus` expone las métricas canónicas esperadas (discovery, custody-liveness, recovery). Por defecto hace scrape sobre node1 vía `docker compose exec`. |

### Carga

| Script | Propósito |
|---|---|
| [`load-test-smoke.sh`](load-test-smoke.sh) | Smoke de carga HTTP con Apache Bench: RPS + latencias p50/p95/p99 sobre 4 endpoints representativos. 1000 requests × 10 concurrentes × keep-alive por endpoint, configurable. |

## Notas

- Los `backup-*` y `restore-*` asumen Postgres accesible vía `DATABASE_URL`
  (default `postgresql://node:node@localhost:5432/node`).
- `verify-reedsolomon-supply-chain.sh` consulta `~/.m2/repository`. Si no
  está resuelto, ejecutar primero `./mvnw -q dependency:resolve`.
- `observability-smoke.sh` requiere `GRAFANA_ADMIN_PASSWORD` definida en el
  entorno (la valida el compose).
