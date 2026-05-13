-- =============================================================================
-- V3__drop_placement_manifest_fk.sql — Cierra race condition upload ↔ keep-list
-- =============================================================================
-- El FK físico `fk_client_fragment_placement_file_id` (CASCADE DELETE) impedía el
-- ordenamiento correcto en el flujo de upload distribuido:
--   1. RS-encode
--   2. POST /custody/fragments al peer (peer ya tiene el fragment)
--   3. <ventana de race con CustodianProbeWorker del peer>
--   4. INSERT client_fragment_placement (origen al fin lo conoce)
--   5. INSERT client_file_manifest (anchor del FK)
-- Si el peer probaba al origen entre 2 y 4, la respuesta no incluía el fragment
-- en la keep-list (placement aún no persistido) y el peer purgaba el fragment
-- recién recibido.
--
-- El fix correcto es persistir client_fragment_placement entre los pasos 2 y 3,
-- pero el FK CASCADE bloquea ese INSERT porque el manifest aún no existe. La
-- relación pasa a ser **lógica** (sin FK físico):
--   - Los call sites que borran un manifest (`FileSystemService.delete` vía
--     `releaseQuotaForFile`, `distributeUploadStreaming` re-upload cleanup) ya
--     llaman explícitamente a `fragmentPlacementPort.deleteByFileId(...)` antes
--     de borrar el manifest, así que el CASCADE era redundante.
--   - La integridad lógica la mantiene el código: nunca debe haber placements
--     sin manifest fuera de la ventana de upload activo.
-- =============================================================================

ALTER TABLE public.client_fragment_placement
    DROP CONSTRAINT IF EXISTS fk_client_fragment_placement_file_id;
