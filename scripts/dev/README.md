# scripts/dev/

Utilidades para el desarrollador: demos end-to-end del sistema, smokes locales
del cliente CLI, generación de proofs sobre cluster docker.

Todas se invocan desde la raíz del repo:

```bash
./scripts/dev/<script>.sh
./scripts/dev/<script>.sh --help    # cuando aplica
```

## Scripts por categoría

### Demo + smokes E2E

| Script | Propósito |
|---|---|
| [`demo-tfg.sh`](demo-tfg.sh) | Orquesta la demo end-to-end completa con captura de evidencia estructurada. Secciones modulares (cliente CLI, multi-nodo, recovery, discovery dinámico, snapshot observabilidad) + flag `--with-chaos` para discovery liveness chaos. |
| [`smoke-client-login-sync-upload-download-logout.sh`](smoke-client-login-sync-upload-download-logout.sh) | Smoke E2E del cliente CLI contra un nodo: login → sync → upload → download → logout, con validación byte-a-byte. Soporta nodo Postgres docker o H2 in-memory (`USE_H2=true`). |

### Proofs multi-nodo

| Script | Propósito |
|---|---|
| [`proof-full-negotiation-fragments.sh`](proof-full-negotiation-fragments.sh) | Genera evidencia del flow de negociación full sobre cluster docker de 3 nodos (supernodo discovery + dos nodos comunes), demostrando topología del fragment exchange. |
| [`proof-n3k3-client-file.sh`](proof-n3k3-client-file.sh) | Genera y firma proof artifacts vía cluster docker de 3 nodos con esquema Reed-Solomon (3,3). |

### Bootstrap

| Script | Propósito |
|---|---|
| [`start-client-test-node.sh`](start-client-test-node.sh) | Bootstrapea un nodo aislado para tests del cliente. Genera material de identidad si falta, desactiva features no requeridas (discovery, negotiation, custody, recovery) y arranca el nodo en background. |

