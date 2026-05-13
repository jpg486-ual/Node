# benchmarks/ — JMH microbenchmarks

Sub-proyecto Maven standalone aislado del `pom.xml` raíz, con JMH 1.37
microbenchmarks sobre los hot paths cómputo intensivo del nodo:

- **`RsCodecEncodeBenchmark`** — Reed-Solomon encode (input size 1KB / 10KB / 100KB).
- **`RsCodecReconstructBenchmark`** — Reed-Solomon reconstruct (con 0/1/2 fragmentos faltantes).
- **`SignatureValidationBenchmark`** — `buildCanonicalPayload` + ECDSA `Signature.verify()` (body 256B / 2KB / 16KB).

## Ejecutar (one-time setup + run)

```bash
# Desde la raíz del repo (Node/), instalar el artifact `node` en .m2 local:
./mvnw install -DskipTests

# Compilar y construir el uber-jar de benchmarks:
cd benchmarks
mvn clean package

# Ejecutar TODOS los benchmarks con JSON output:
java -jar target/benchmarks.jar -rf json -rff target/jmh-results.json

# Ejecutar UN benchmark específico (regex sobre nombre):
java -jar target/benchmarks.jar 'RsCodecEncodeBenchmark.encode'

# Help completo de JMH:
java -jar target/benchmarks.jar -h
```

## Configuración por defecto

- 3 warmup iterations × 5s.
- 5 measurement iterations × 5s.
- 1 fork.
- Total: ~100s por benchmark × N benchmarks.

Por benchmark: `inputSize` × `missingCount` × `bodySize` se multiplican
combinatoriamente. Los 3 benchmarks completos toman ~10-15 min.

Para un quick sanity check (1 warmup × 1s + 1 iter × 1s):

```bash
java -jar target/benchmarks.jar -wi 1 -i 1 -w 1s -r 1s -f 1 -rf json -rff target/jmh-quick.json
```

## Aislamiento del pom.xml raíz

El sub-proyecto NO se hereda del `pom.xml` raíz. Razones:

1. El raíz tiene `maven-enforcer-plugin` con `requireReleaseDeps`,
   `banDuplicatePomDependencyVersions`, `requirePluginVersions`. Las
   dependencias JMH cumplen pero la combinación con la BOM Spring Boot
   3.5.14 puede generar conflictos.
2. `Spotless` en `verify` phase aplica `google-java-format`. Las clases
   JMH benchmark contienen `@Benchmark` methods que pueden ser flagged
   como unused (warnings false positives en SpotBugs).
3. `dependency-check-maven` corre OWASP scan en cada build. Añadir JMH
   al raíz expandiría el scan sin necesidad.

Aislamiento total: `cd benchmarks && mvn ...` no afecta `./mvnw verify`
en la raíz (suite tests + Spotless verde permanecen intactos).

## Output

`target/jmh-results.json` contiene resultados estructurados parseables.
incluye los números reales capturados (cuando se ejecuta).

## Compatibility note

JMH 1.37 (release 2023-12-08) soporta oficialmente Java 8/11/17/21 LTS.
Java 25 (Amazon Corretto) puede requerir flags adicionales si emergen
warnings sobre `--add-opens` o reflection. Si el setup falla, ver doc
evidencia §"Limitaciones operativas" para fallback.

## Citas de autoridad

- **Aleksey Shipilev, *JMH — Java Microbenchmark Harness* (OpenJDK)** — autoridad oficial.
- **Brendan Gregg, *Systems Performance* (2nd ed., 2020)** ch. 12 — methodology benchmarks honest.
- **Beyer et al., *Site Reliability Engineering* (Google, 2016)** ch. 32 — Production Readiness Review (load smoke).
