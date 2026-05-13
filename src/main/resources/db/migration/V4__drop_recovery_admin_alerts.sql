-- Drop dead-code table recovery_admin_alerts.
--
-- Razón: la tabla era append-only sin consumidor real (cero REST endpoints,
-- cero workers leen findUnacknowledged/findById/acknowledge) y sin política
-- de purga. Los 3 event codes que albergaba (FILE_RECOMPOSED, FILE_RECOMPOSE_FAILED,
-- FILE_UNRECOVERABLE) ya tienen cobertura redundante en counters Prometheus
-- (node_recovery_file_integrity_*) + logs estructurados WARN/INFO consumibles
-- por Loki. Además la FK fk_recovery_admin_alerts_file_id apuntaba a
-- recovery_file_manifest (tutor-side) cuando el único productor era el
-- FileIntegrityRiskOrchestrator origen-side, causando violaciones en cluster
-- real cada vez que un fichero llegaba a riesgo grave.
--
-- DROP TABLE elimina implícitamente la FK constraint y los índices asociados.

DROP TABLE IF EXISTS public.recovery_admin_alerts;
