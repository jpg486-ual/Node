-- =============================================================================
-- V2__seed_initial_data.sql — Seeds de singletons que el schema necesita
-- =============================================================================
-- Único registro hoy: capacity_counter (id=1, contador agregado de bytes
-- ocupados por capacity admission control). PostgresCapacityPort exige que
-- esta fila exista antes del primer reserve() Sin ella lanza
-- IllegalStateException("Capacity counter row is not initialized").
-- Idempotente vía WHERE NOT EXISTS.
-- =============================================================================

INSERT INTO public.capacity_counter(id, occupied_bytes)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM public.capacity_counter WHERE id = 1);
