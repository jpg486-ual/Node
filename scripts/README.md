# scripts/

Utilidades acompañantes del repo Node. Ninguna es necesaria para compilar o
testear el código (eso lo cubre `./mvnw verify`); aquí viven scripts auxiliares
para el desarrollador y para el operador del nodo.

## Subdirectorios

| Carpeta | Para quién | Uso típico |
|---|---|---|
| [`dev/`](dev/) | Desarrolladores | Demos end-to-end, smokes locales, generación de proofs. |
| [`ops/`](ops/) | Operadores | Backup + restore, drill de DR, verificación de supply-chain y observabilidad, load testing. |

## Frontera con otras carpetas

- **`docker/scripts/`** — smokes orientados al stack docker compose multi-nodo
  (partición/quórum, recovery, return-to-tutor, generación de keys). Esta
  carpeta NO los duplica. Los scripts de `dev/` y `ops/` que necesitan esos
  smokes los invocan por path relativo.
- **`src/test/java/`** — la suite JUnit cubre los invariantes de código. Lo
  que vive aquí son comprobaciones operativas (HTTP real, docker, Postgres),
  no test unitarios.

## Convención de invocación

Todos los scripts se ejecutan desde la raíz del repo:

```bash
./scripts/<dir>/<script>.sh           # ej. ./scripts/dev/demo-tfg.sh
./scripts/<dir>/<script>.sh --help    # opciones disponibles cuando aplica
```

Los scripts asumen el working directory en la raíz; si los lanzas desde otro
sitio detectan el `ROOT_DIR` por sí solos.

## Dependencias comunes

- `bash` 4+
- `curl`, `python3` (parseo JSON)
- `docker` + `docker compose` (la mayoría de smokes y proofs)
- `mvnw` funcional (algunos scripts arrancan un nodo Spring Boot local)
- `pg_dump` / `pg_restore` para los scripts de backup-users / restore-users
- `ab` (Apache Bench) solo para `ops/load-test-smoke.sh`

Cada script añade en su cabecera las dependencias específicas que requiere.
