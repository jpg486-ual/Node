# Node

<!-- Badges CI (GitHub Actions) -->
[![CI](https://github.com/jpg486-ual/Node/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/jpg486-ual/Node/actions/workflows/ci.yml)
[![CodeQL](https://github.com/jpg486-ual/Node/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/jpg486-ual/Node/actions/workflows/codeql.yml)
[![Secrets Scan](https://github.com/jpg486-ual/Node/actions/workflows/secrets-scan.yml/badge.svg?branch=main)](https://github.com/jpg486-ual/Node/actions/workflows/secrets-scan.yml)
[![SpotBugs + FindSecBugs](https://github.com/jpg486-ual/Node/actions/workflows/spotbugs.yml/badge.svg?branch=main)](https://github.com/jpg486-ual/Node/actions/workflows/spotbugs.yml)

Backend de almacenamiento distribuido y resiliente entre pares,
construido sobre Java 25 y Spring Boot 3.5.x bajo arquitectura
hexagonal. Cada instancia se ejecuta como un único binario
configurable capaz de asumir cualquiera de los cuatro roles del
sistema (origen de archivos, custodio de fragmentos, supernodo
de *discovery* y tutor de recuperación) en función de las
propiedades y *feature flags* activos.

## Descripción

Un nodo expone dos contratos HTTP claramente separados. El
**contrato cliente** permite a un consumidor externo registrar
usuarios, autenticarse mediante JWT, manejar un árbol de
archivos del usuario, subir y descargar contenido y sincronizar
los cambios entre varios dispositivos del mismo usuario. El
**contrato inter-nodo** opera bajo firma ECDSA P-256 sobre la
curva NIST P-256 con protección *anti-replay* por *nonce* y
marca de tiempo, y cubre los flujos de *discovery*, negociación,
custodia de fragmentos, *return-to-tutor* y recovery completo
del sistema de archivos tras una pérdida.

El contenido del archivo se fragmenta en el nodo de entrada
mediante un código de borrado **Reed-Solomon**, los fragmentos
se reparten entre custodios pertenecientes a dominios de fallo
distintos y el sistema reconstruye el archivo aun ante la caída
de un subconjunto de nodos por debajo del umbral de paridad.

El sistema se entrega como **modelo cerrado o de federación
empresarial**: la confianza entre nodos participantes se sostiene
sobre admisión administrativa por *whitelist* de claves
públicas, no sobre incentivos cripto-económicos. La aplicación
inmediata es ofrecer una alternativa al almacenamiento en nube
tradicional (OneDrive, Drive, Dropbox) sin punto único de fallo
y sin dependencia de un proveedor externo, repartiendo la
redundancia entre las propias sedes de la organización.

## Arquitectura

Arquitectura **hexagonal** con trece módulos de dominio:

| Módulo | Responsabilidad |
|---|---|
| `bootstrap` | Composición Spring, salud, *readiness* y bootstrap del modo `RESTORE`. |
| `userregistration` | Registro con código de invitación, *login*/*logout*, JWT y sesiones. |
| `filesystem` | Árbol de archivos del usuario, sesiones de subida, contenido. |
| `sync` | *Change-feed* y *server-sent events* para sincronización entre dispositivos. |
| `reedsolomon` | Codificación y reconstrucción Reed-Solomon. |
| `fragmentstorage` | Custodia *peer-side* de fragmentos firmados. |
| `custodyliveness` | *Probe cycle* del custodio sobre el origen y escalado al tutor. |
| `recovery` | Custodia temporal de fragmentos huérfanos y manifiestos del cliente en el tutor. |
| `discovery` | Selección de custodios por dominio de fallo y cuota; cola de reintentos. |
| `negotiation` | Acuerdo de *placement* entre origen y candidato. |
| `identitysecurity` | Identidad criptográfica ECDSA P-256, firma y validación *inter-nodo*. |
| `persistence` | Adaptadores polimórficos memoria y PostgreSQL del mismo binario. |
| `shared` | Tipos transversales y utilidades compartidas. |

Cada módulo aísla su dominio detrás de puertos explícitos
(`ports/out/`) y conecta con la infraestructura mediante
adaptadores intercambiables (`adapters/in/web/`,
`adapters/out/memory/`, `adapters/out/postgres/`). La composición
concreta de un nodo se decide al arrancar: persistencia en
memoria o PostgreSQL, roles federativos activos o desactivados,
pila de observabilidad opcional.

## Funcionalidad implementada

- **Autenticación de usuario y sesión** con registro por código
  de invitación, *login* con BCrypt + JWT, *refresh* y *logout*
  con invalidación.
- **Árbol de archivos por usuario** con creación, renombrado,
  movido y borrado de entradas, incluyendo operaciones de
  *subtree* (`move-subtree`, `delete-subtree`).
- **Subida de contenido** en dos modos: *single-shot* en
  *streaming* y resumable por bloques con sesiones reanudables
  (`/files/upload-sessions/...`).
- **Codificación Reed-Solomon** del *payload* recibido del
  cliente con esquema interno (N, K) = (5, 3) y reconstrucción
  ante pérdidas.
- **Distribución firmada** de los fragmentos a custodios
  seleccionados por *discovery* sobre dominios de fallo
  distintos.
- **Comunicación inter-nodo** con firma ECDSA P-256 sobre carga
  útil canónica con *nonce* y marca de tiempo, validación
  *anti-replay* y *whitelists* de claves públicas separadas
  para custody y para recovery.
- **Subsistemas de custody y recovery** sobre tablas
  físicamente disjuntas (`custody_fragment`,
  `recovery_orphan_fragment`) con transición
  *return-to-tutor* como *roundtrip* HTTP explícito.
- **Replicación origen-tutor del manifiesto del cliente** en
  modo *fail-closed* tras cada subida exitosa, con `DELETE`
  cooperativo y *cross-check* desde el *worker* de renovación.
- **Modo RESTORE** del *bootstrap* que reconstruye el árbol de
  archivos, los manifiestos del cliente y los *placements*
  desde el tutor tras una pérdida de disco, con dos estrategias
  (`METADATA_ONLY` y `BYTES_FROM_TUTOR`).
- **Sincronización multi-dispositivo** vía `change-feed`
  (`/sync/changes?since=...`) y *server-sent events*
  (`/sync/events`).
- **Persistencia polimórfica** memoria/PostgreSQL del mismo
  binario, seleccionada con `node.persistence.mode`. Migraciones
  versionadas con Flyway.
- **Observabilidad de tres pilares**: métricas en formato
  Prometheus (`/actuator/prometheus`, puerto de gestión
  aislado), trazas distribuidas OpenTelemetry sobre Tempo con
  propagación W3C `traceparent` *inter-nodo*, y *logs*
  estructurados con Loki + Promtail y siete campos MDC
  (`requestId`, `traceId`, `spanId`, etc.).

## Arranque rápido

Modo desarrollo *standalone* con persistencia H2 en memoria:

```bash
./mvnw spring-boot:run
```

Modo *staging* (limitador conservador activo):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=staging
```

Modo producción:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=prod
```

Clúster multi-nodo con PostgreSQL y firma ECDSA (tres nodos):

```bash
docker/scripts/generate-node-keys.sh
docker compose up -d
```

Clúster con pila de observabilidad completa (Prometheus +
Grafana + Alertmanager + Tempo + Loki + Promtail):

```bash
GRAFANA_ADMIN_PASSWORD=localdev \
  docker compose --profile observability up -d
```

Grafana queda disponible en `http://localhost:3000` con usuario
`admin`. Detalle de la secuencia en [`docker/README.md`](docker/README.md).

## Validación

El sistema se valida en tres planos complementarios.

**TDD** con disciplina red → verde → refactor sobre el ciclo
completo del proyecto: **601 métodos `@Test` repartidos en 101
clases**, con tests unitarios sobre el dominio y tests
`@SpringBootTest` sobre el contexto completo cuando la
invariante lo exige.

```bash
./mvnw test                                  # batería completa
./mvnw -Dtest=TutorRecoveryServiceTest test  # una clase
./mvnw verify                                # tests + Spotless gate
```

**Demo end-to-end ejecutable** que orquesta seis secciones
(cliente CLI sobre H2, *signed multi-nodo* sobre clúster
Docker, *recovery byte transfer* real, *chaos engineering*
opcional, *discovery* dinámico y *snapshot* de observabilidad):

```bash
./scripts/dev/demo-tfg.sh --with-chaos --keep-running
```

**Smokes Docker** sobre el clúster de tres nodos, agrupados por
dominio funcional (discovery, custody/liveness, recovery,
capacidad, particiones, observabilidad). Inventario completo en
[`docker/scripts/`](docker/scripts/).

**Pruebas de carga cuantitativas**:

```bash
# Microbenchmarks JMH sobre Reed-Solomon y verificación ECDSA
cd benchmarks && mvn clean package && \
  java -jar target/benchmarks.jar -rf json -rff target/jmh-results.json

# Carga HTTP con Apache Bench sobre cuatro endpoints clave
./scripts/ops/load-test-smoke.sh
```

## Calidad y CI

Cuatro *workflows* de GitHub Actions cubren la disciplina de
calidad: **`ci.yml`** (build + tests + *smokes* condicionales +
CVE scan); **`spotbugs.yml`** (SpotBugs + FindSecBugs sobre
*bytecode*, report-only en SARIF); **`codeql.yml`** (CodeQL Java
*security-extended*, report-only); **`secrets-scan.yml`**
(Gitleaks con `--fail-closed` como único *gate* bloqueante en
*push* a `main`).

Formateo automático con **Spotless** + **google-java-format**
1.35.0 (estilo `GOOGLE`) como *gate* bloqueante en
`./mvnw verify`:

```bash
./mvnw spotless:check    # report-only
./mvnw spotless:apply    # aplica el formato
```

Escaneo local de secretos:

```bash
gitleaks git --staged --redact --config .gitleaks.toml --no-banner
```

## Recursos operativos

- [`docker/README.md`](docker/README.md) — arranque multi-nodo
  y secuencia de generación de claves.
- [`docker/scripts/`](docker/scripts/) — *smokes* sobre el
  clúster (E2E firmado, *discovery* dinámico, *recovery* bytes,
  *liveness*, particiones, capacidad).
- [`scripts/dev/`](scripts/dev/) — demo TFG, *proofs* de
  flujos y arranque de nodo de pruebas.
- [`scripts/ops/`](scripts/ops/) — *backup*/*restore*, *load
  test smoke* con Apache Bench, verificación de cadena de
  suministro Reed-Solomon, *smoke* de observabilidad.
- [`benchmarks/`](benchmarks/) — sub-proyecto Maven aislado
  con tres microbenchmarks JMH sobre los *hot paths* del
  sistema.
