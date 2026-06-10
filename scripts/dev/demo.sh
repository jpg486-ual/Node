#!/usr/bin/env bash
# demo.sh — la DEMOSTRACIÓN en vivo del flujo de recovery (apoyo de locución).
#
# Emite por pantalla las queries y llamadas
# reales que ejecuta, con sus resultados, para que sirvan de apoyo visual.
#
# Arco (1 archivo): subida y distribución → purga total del nodo origen → el sistema se
# autodefiende (RETURN_TO_TUTOR, observable) → nuevo nodo con la misma identidad → restore +
# reconstrucción desde el tutor → el cliente descarga con SHA-256 idéntico, bit a bit.
#
# REQUISITO: clúster ya levantado (idealmente con liveness acelerada para la demo):
#   GRAFANA_ADMIN_PASSWORD=localdev docker compose \
#     -f docker-compose.yml -f docker-compose.demo.yml \
#     --profile observability up -d --build
#   # espera ~30-90 s a que los 3 nodos respondan antes de empezar.
#
# Modos:
#   ./scripts/dev/demo.sh          # en vivo: pausa entre secciones (tú marcas el ritmo)
#   ./scripts/dev/demo.sh --auto   # sin pausas: ensayo / cronometraje
#   ARCHIVO=/ruta/a/fichero ./scripts/dev/demo.sh   # usa tu propio archivo
#
# Las claves ECDSA de docker/keys/ se PRESERVAN (identidad criptográfica). No ejecutes
# generate-node-keys.sh o romperás el mapping del compose.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

AUTO="false"
[[ "${1:-}" == "--auto" ]] && AUTO="true"

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.demo.yml)
BASE="http://localhost:8081"
START_TS=$(date +%s)

c_reset=$'\e[0m'; c_bold=$'\e[1m'; c_dim=$'\e[2m'
c_cyan=$'\e[36m'; c_green=$'\e[32m'; c_yellow=$'\e[33m'; c_red=$'\e[31m'; c_blue=$'\e[34m'; c_mag=$'\e[35m'

elapsed() { printf '%02d:%02d' $(( ($(date +%s)-START_TS)/60 )) $(( ($(date +%s)-START_TS)%60 )); }

# ── Lenguaje visual: secciones, narración, comandos en vivo, resultados ───────
section() {
  echo
  echo "${c_dim}────────────────────────────────────────────────────────────────── [$(elapsed)]${c_reset}"
  echo "${c_bold}${c_cyan}▶ $1${c_reset}"
  [[ -n "${2:-}" ]] && echo "${c_dim}  $2${c_reset}"
  echo
}
say()  { echo "${c_dim}  $*${c_reset}"; }                       # contexto / cue de narración
ok()   { echo "${c_green}  ✓ $*${c_reset}"; }
info() { echo "${c_blue}  • $*${c_reset}"; }
warn() { echo "${c_yellow}  ! $*${c_reset}"; }
err()  { echo "${c_red}  ✗ $*${c_reset}"; }
pause() { [[ "$AUTO" == "true" ]] || { echo; read -rp "  ${c_dim}⏎  (enter para continuar)${c_reset} " _; }; }

# Muestra una consulta SQL (legible) y la ejecuta contra el postgres del nodo indicado.
query() { # $1=node  $2=caption  $3=SQL
  local n="$1" cap="$2" sql="$3"
  info "$cap"
  printf '%s' "${c_mag}    psql@node${n} ›${c_reset} ${c_dim}"
  echo "$sql" | tr '\n' ' ' | tr -s ' '
  printf '%s' "${c_reset}"
  docker exec "node-postgres-$n" psql -U node -d node -c "$sql"
}
psql_n() { docker exec "node-postgres-$1" psql -U node -d node -tAc "$2" 2>/dev/null; }

# Muestra una llamada HTTP del cliente (verbo + ruta) antes de ejecutarla.
show_call() { echo "${c_mag}    cliente ›${c_reset} ${c_dim}$*${c_reset}"; }

# ── Archivo de prueba ─────────────────────────────────────────────────────────
ARCHIVO="${ARCHIVO:-/tmp/demo-archivo.pdf}"
if [[ ! -f "$ARCHIVO" ]]; then
  head -c 12000 /dev/urandom > "$ARCHIVO"
fi
SHA_ORIG=$(shasum -a 256 "$ARCHIVO" | awk '{print $1}')

echo
echo "${c_bold}${c_cyan}  Node · almacenamiento que sobrevive a la caída de sus nodos${c_reset}"
echo "${c_dim}  Demostración en vivo · Grafana/Prometheus/Loki/Tempo operativos de fondo${c_reset}"

# ── 1 · Clúster federado + observabilidad viva ────────────────────────────────
section "1 · Tres nodos federados, observabilidad viva" \
        "Punto de partida: un clúster sano con métricas, trazas y logs correlacionados."
if ! curl -fsS --max-time 3 http://localhost:3000/api/health >/dev/null 2>&1; then
  err "Grafana no responde en :3000 — ¿está el clúster con --profile observability?"
  exit 1
fi
ok "Grafana en http://localhost:3000  ·  Prometheus en http://localhost:9090"
# Reintenta unos segundos: si el clúster está recién arrancado, Prometheus puede no haber
# hecho aún el primer scrape (evita mostrar un '0' engañoso al abrir la demo).
UP=0
for _ in $(seq 1 8); do
  UP=$(curl -fsS --max-time 3 -G 'http://localhost:9090/api/v1/query' \
        --data-urlencode 'query=sum(up{job="node"})' 2>/dev/null \
        | python3 -c 'import json,sys
try: print(int(float(json.load(sys.stdin)["data"]["result"][0]["value"][1])))
except Exception: print(0)' 2>/dev/null)
  [[ "${UP:-0}" -ge 3 ]] 2>/dev/null && break
  sleep 2
done
info "nodos 'up' en Prometheus (job=node): ${UP:-0} / 3"
say  "De fondo: dashboard 'Node', los 3 nodos en ACTIVE."
pause

# ── 2 · Subida y distribución: RS(3,2) ────────────────────────────────────────
section "2 · El cliente sube un archivo y se reparte en fragmentos" \
        "Reed-Solomon RS(3,2): 3 fragmentos, uno por nodo, en dominios de fallo distintos."
INV="DEMO-$$-$(elapsed | tr -d ':')"
docker exec -i node-postgres-1 psql -U node -d node >/dev/null 2>&1 <<SQL
INSERT INTO registration_code(code, quota_mb, expires_at, used, used_at, created_at)
VALUES ('$INV', 256, NOW() + INTERVAL '2 days', FALSE, NULL, NOW());
SQL
show_call "POST /auth/register  +  POST /auth/login   (usuario jose)"
curl -sS -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"invitationCode\":\"$INV\",\"username\":\"jose\",\"password\":\"Demo2026!\"}" >/dev/null
TOKEN=$(curl -sS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"jose","password":"Demo2026!"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
[[ -n "$TOKEN" ]] && ok "sesión iniciada" || { err "login falló"; exit 1; }

NAME=$(basename "$ARCHIVO"); SIZE=$(wc -c < "$ARCHIVO" | tr -d ' ')
ENTRY=$(curl -sS -X POST "$BASE/fs/entries" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"path\":\"/$NAME\",\"entryType\":\"FILE\",\"sizeBytes\":$SIZE,\"checksum\":\"$SHA_ORIG\",\"deleted\":false}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["entryId"])')
show_call "PUT /files/entries/$ENTRY/content   ($SIZE bytes)"
HTTP_UP=$(curl -sS -X PUT "$BASE/files/entries/$ENTRY/content" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/octet-stream' --data-binary "@$ARCHIVO" -o /dev/null -w '%{http_code}')
ok "subida HTTP $HTTP_UP  ·  sha256 original = ${SHA_ORIG:0:24}…"
echo
query 1 "Distribución en el origen: 3 fragmentos, en 3 custodios distintos" \
"SELECT entry_id, COUNT(*) AS fragments, COUNT(DISTINCT custodian_base_url) AS distinct_peers
   FROM client_fragment_placement p
   JOIN client_file_manifest m ON p.file_id = m.file_id
  GROUP BY entry_id;"
query 2 "El mapa del archivo (manifest) se replicó al tutor (node2) en la propia subida" \
"SELECT file_id, requester_node_id, original_file_name FROM recovery_file_manifest;"
pause

# ── 3 · Backup de usuarios (preparación) ──────────────────────────────────────
section "3 · Copia de seguridad de cuentas (auth)" \
        "Solo auth/fs: NO guarda placements — esos se reconstruirán solos."
DUMP_HOST="backups/demo-backup.dump"; mkdir -p backups
show_call "pg_dump auth-fs  (user_account, registration_code, user_session, fs_entry)"
if docker exec node-postgres-1 pg_dump --format=custom -U node -d node \
      --table=user_account --table=registration_code --table=user_session --table=fs_entry \
      -f /tmp/demo-backup.dump 2>/dev/null \
   && docker cp node-postgres-1:/tmp/demo-backup.dump "$DUMP_HOST" >/dev/null 2>&1; then
  ok "backup creado → $DUMP_HOST ($(wc -c < "$DUMP_HOST" | tr -d ' ') bytes)"
else
  err "backup falló"
fi
pause

# ── 4 · Purga TOTAL del nodo origen ───────────────────────────────────────────
section "4 · Matamos el nodo origen por completo" \
        "No es un 'stop': borramos contenedor, base de datos y logs. Pérdida real. Solo se preservan las claves."
show_call "docker compose stop+rm node1 postgres-node1  +  docker volume rm (datos)"
"${COMPOSE[@]}" stop node1 postgres-node1 >/dev/null 2>&1
"${COMPOSE[@]}" rm -f node1 postgres-node1 >/dev/null 2>&1
# postgres-node1 usa volumen NOMBRADO (node_node_postgres_data1): rm -f del contenedor NO lo
# borra. Borrarlo es lo que hace la pérdida REAL. El detach es asíncrono → reintentamos.
# (node_nodelogs1 lo comparte el sidecar promtail; intentamos sin exigir.)
docker volume rm node_nodelogs1 >/dev/null 2>&1 || true
for _ in 1 2 3 4 5 6; do
  docker volume rm node_node_postgres_data1 >/dev/null 2>&1 && break
  docker volume ls --format '{{.Name}}' | grep -qx node_node_postgres_data1 || break
  sleep 1
done
if docker ps -a --format '{{.Names}}' | grep -qiE 'distributed-node-1|node-postgres-1' \
   || docker volume ls --format '{{.Name}}' | grep -qx node_node_postgres_data1; then
  err "aún queda el contenedor o el volumen de datos de node1"
else
  ok "purga total: sin contenedor, sin base de datos, sin logs. La identidad (claves) sobrevive."
fi
pause

# ── 5 · El sistema se autodefiende (el corazón) ───────────────────────────────
section "5 · El sistema reacciona solo: RETURN_TO_TUTOR" \
        "Las sondas detectan la caída; los custodios devuelven sus fragmentos al tutor. Observable en los 3 pilares."
echo
# RS(3,2): el fragmento que vivía en node1 se pierde con la purga; node2 y node3 devuelven los
# suyos al tutor → 2 huérfanos. k=2 es justo lo que RS necesita para reconstruir.
ESCALATION_START=$(date +%s); ORPHANS=0
for _ in $(seq 1 40); do
  ORPHANS=$(psql_n 2 "SELECT COUNT(*) FROM recovery_orphan_fragment;"); ORPHANS=${ORPHANS:-0}
  printf "\r  ${c_blue}•${c_reset} esperando la escalation…  huérfanos en el tutor = %s   (t+%ss)   " \
         "$ORPHANS" "$(( $(date +%s)-ESCALATION_START ))"
  [[ "$ORPHANS" -ge 2 ]] 2>/dev/null && break
  sleep 5
done
echo
if [[ "${ORPHANS:-0}" -ge 2 ]] 2>/dev/null; then
  ok "escalation completa en ~$(( $(date +%s)-ESCALATION_START ))s — el tutor custodia $ORPHANS fragmentos (k=2)"
  say "El fragmento de node1 se perdió, pero sobreviven 2 = k: justo lo que Reed-Solomon necesita."
else
  warn "solo $ORPHANS huérfanos tras la espera (esperaba ≥2). Revisar thresholds/escalation-policy."
fi
TID=$(docker logs distributed-node-3 2>&1 | grep -iE "return_to_tutor|TutorReturnCustody" \
      | grep -oE 'tid=[0-9a-f]{32}' | tail -1)
if [[ -n "$TID" ]]; then
  info "trace de la operación (para saltar a Tempo): ${TID#tid=}"
  say  "En Grafana → Explore → Loki:  {job=\"node\"} |~ \"$TID\"  →  View trace in Tempo"
fi
pause

# ── 6 · Nuevo nodo, misma identidad, sin datos ────────────────────────────────
section "6 · Levantamos un node1 nuevo: misma identidad criptográfica, cero datos" \
        "Reusa las claves originales → mismo nodeId. Pero su base de datos arranca vacía."
show_call "docker compose up -d node1 postgres-node1   (claves originales)"
"${COMPOSE[@]}" up -d --no-deps node1 postgres-node1 >/dev/null 2>&1
for i in $(seq 1 40); do
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 2 "$BASE/auth/me" 2>/dev/null)
  [[ "$code" == "401" || "$code" == "200" ]] && { ok "node1 de nuevo en pie (intento $i)"; break; }
  sleep 2
done
N1=$(shasum -a 256 docker/keys/node1-public.der | awk '{print substr($1,1,24)}')
info "nodeId derivado de las claves originales: node-$N1  (idéntico al de antes)"
echo
query 1 "Estado del nodo recién resucitado" \
"SELECT (SELECT COUNT(*) FROM user_account) AS users,
        (SELECT COUNT(*) FROM client_fragment_placement) AS placements;"
say "users=0 (la BD se perdió). Si ves placements>0, es el bootstrap de recovery que ya"
say "empezó a recuperar el mapa del archivo desde el tutor nada más arrancar."
pause

# ── 7 · Restore + el recovery reconstruye desde el tutor ──────────────────────
section "7 · Restauramos las cuentas y el recovery repuebla los fragmentos" \
        "El usuario vuelve; en modo RESTORE/BYTES_FROM_TUTOR el nodo recupera los fragmentos del tutor."
show_call "pg_restore (cuentas)"
docker cp "$DUMP_HOST" node-postgres-1:/tmp/demo-backup.dump >/dev/null 2>&1
docker exec node-postgres-1 pg_restore --clean --if-exists --no-owner \
  -U node -d node /tmp/demo-backup.dump >/dev/null 2>&1 || true   # --clean sobre BD vacía → warnings benignos
JOSE=$(psql_n 1 "SELECT username FROM user_account WHERE username='jose';")
[[ "$JOSE" == "jose" ]] && ok "cuenta de jose restaurada" || err "jose no restaurado"
# El RESTORE_REUPLOAD re-distribuye 3 fragmentos → necesita que node1 vuelva a ser
# candidato FRESCO de discovery (1-por-nodo en clúster de 3). Esperamos su re-registro:
# de paso, en el directorio el contador vuelve a subir a 3.
info "esperando a que node1 se re-registre en el directorio de discovery…"
for i in $(seq 1 20); do
  FRESH=$(psql_n 2 "SELECT COUNT(*) FROM discovery_candidate WHERE node_id='node-$N1' AND last_seen_at > now() - interval '20 seconds';")
  [[ "${FRESH:-0}" -ge 1 ]] 2>/dev/null && { ok "node1 de nuevo en el directorio (3 candidatos)"; break; }
  printf "\r  ${c_blue}•${c_reset} esperando re-registro…   (intento %s)   " "$i"; sleep 2
done
echo
show_call "reinicio de node1 en modo recovery (RESTORE/BYTES_FROM_TUTOR)"
# Completa cuando el tutor se queda SIN huérfanos (re-distribución ACKed). Si el primer
# intento corrió antes de que discovery tuviera 3 candidatos frescos, reintentamos.
recovered=false
for attempt in 1 2; do
  "${COMPOSE[@]}" restart node1 >/dev/null 2>&1
  for i in $(seq 1 20); do
    ORPH=$(psql_n 2 "SELECT COUNT(*) FROM recovery_orphan_fragment;"); ORPH=${ORPH:-9}
    PLACE=$(psql_n 1 "SELECT COUNT(*) FROM client_fragment_placement;"); PLACE=${PLACE:-0}
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 2 "$BASE/auth/me" 2>/dev/null)
    printf "\r  ${c_blue}•${c_reset} reconstruyendo…  placements=%s · huérfanos en tutor=%s   (intento %s.%s)   " \
           "$PLACE" "$ORPH" "$attempt" "$i"
    [[ "$PLACE" -ge 1 ]] 2>/dev/null && [[ "$ORPH" -eq 0 ]] 2>/dev/null \
      && { [[ "$code" == "401" || "$code" == "200" ]] && { recovered=true; break; }; }
    sleep 3
  done
  $recovered && break
  warn "re-distribución incompleta (node1 aún no era candidato) — reintentando…"
done
echo
$recovered && ok "re-distribución completa: el tutor ya no custodia huérfanos" \
            || warn "el recovery no drenó los huérfanos del tutor — revisar custodios disponibles"
query 1 "Reconstrucción completa: entries, manifests y placements de vuelta" \
"SELECT (SELECT COUNT(*) FROM fs_entry WHERE deleted=false) AS entries,
        (SELECT COUNT(*) FROM client_file_manifest)         AS manifests,
        (SELECT COUNT(*) FROM client_fragment_placement)    AS placements;"
pause

# ── 8 · La prueba: el cliente descarga, SHA-256 idéntico ──────────────────────
section "8 · El cliente descarga el archivo — bit a bit idéntico al original" \
        "El entryId se preservó a través del recovery. Reusamos el de la subida."
TOKEN=$(curl -sS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"jose","password":"Demo2026!"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])' 2>/dev/null)
show_call "GET /fs/tree   (listado del cliente)"
curl -sS "$BASE/fs/tree" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import json,sys
d=json.load(sys.stdin)
for e in d["entries"]:
    print("    -", e["path"], e["entryId"], "("+str(e["sizeBytes"])+" bytes)")' 2>/dev/null
echo
show_call "GET /files/entries/$ENTRY/content"
# El recovery de bytes (BYTES_FROM_TUTOR) es ASÍNCRONO. Mientras no haya k fragmentos
# alcanzables, la descarga responde 503 (FILE_IRRECOVERABLE / INSUFFICIENT_CUSTODIANS): el
# sistema se NIEGA a servir bytes parciales/corruptos en vez de entregar basura. Esperamos a
# que el contenido sea servible (200) y solo entonces comparamos, nunca hasheamos un error.
DL_CODE=""; DL_SIZE=0
for i in $(seq 1 25); do
  DL_CODE=$(curl -sS "$BASE/files/entries/$ENTRY/content" -H "Authorization: Bearer $TOKEN" \
            -D /tmp/recovered-demo.hdr -o /tmp/recovered-demo -w '%{http_code}')
  DL_SIZE=$(wc -c < /tmp/recovered-demo | tr -d ' ')
  [[ "$DL_CODE" == "200" ]] && break
  printf "\r  ${c_blue}•${c_reset} el sistema aún no sirve el archivo (HTTP %s) — reconstruyendo bytes… (intento %s)   " \
         "$DL_CODE" "$i"
  sleep 2
done
echo
if [[ "$DL_CODE" != "200" ]]; then
  err "descarga HTTP $DL_CODE tras esperar (cuerpo de error de $DL_SIZE bytes — NO es el archivo)"
  say "El sistema sigue sin poder reconstruir: revisar que placements ≥ k (RS necesita 2 de 3)."
  echo
  echo "${c_cyan}═══ Demo terminada con recovery incompleto · $(elapsed) ═══${c_reset}"
  exit 1
fi
ok "descarga HTTP 200 · $DL_SIZE bytes"
SHA_REC=$(shasum -a 256 /tmp/recovered-demo | awk '{print $1}')
echo
echo "    original   ${c_bold}$SHA_ORIG${c_reset}"
echo "    recuperado ${c_bold}$SHA_REC${c_reset}"
echo
if [[ "$SHA_ORIG" == "$SHA_REC" ]]; then
  ok "${c_bold}IDÉNTICOS.${c_reset} El archivo sobrevivió a la pérdida total del nodo origen."
  say "Mientras sobrevivan k fragmentos, el archivo se reconstruye."
else
  err "los hashes difieren con HTTP 200 — esto sí sería un fallo de reconstrucción a investigar."
fi
say "De fondo, en Grafana, node1 vuelve a estar ACTIVE: el clúster se ha sanado solo."
echo
echo "${c_cyan}═══ Demostración completa · $(elapsed) ═══${c_reset}"
