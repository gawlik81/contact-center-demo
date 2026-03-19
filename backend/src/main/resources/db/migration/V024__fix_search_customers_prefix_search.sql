-- =============================================================================
-- V024__fix_search_customers_prefix_search.sql
-- Naprawia fuzzy search: dodaje wyszukiwanie prefiksowe (ILIKE) obok trigram.
--
-- Problem: operator % (similarity) porównuje pełne stringi.
-- Zapytanie 'Agn' vs 'Agnieszka Kowalska' daje similarity ~0.17 < progu 0.3,
-- więc trigram nie zwraca żadnych wyników dla krótkich prefiksów.
--
-- Rozwiązanie:
--   1. ILIKE 'query%' na first_name i last_name – obsługuje wyszukiwanie prefiksowe
--   2. word_similarity (<<%) – porównuje query z każdym słowem osobno (lepsze niż %)
--   3. Zachowane exact match dla phone[] i email[]
-- =============================================================================

CREATE OR REPLACE FUNCTION search_customers(
    p_tenant_id  UUID,
    p_query      TEXT,
    p_limit      INT  DEFAULT 20
) RETURNS SETOF customer
LANGUAGE sql
STABLE
AS $$
    SELECT *
    FROM   customer
    WHERE  tenant_id   = p_tenant_id
      AND  is_deleted  = FALSE
      AND  (
              -- Prefix search na imieniu (szybkie, używa indeksu GIN trigram)
              first_name ILIKE p_query || '%'
              -- Prefix search na nazwisku
              OR last_name ILIKE p_query || '%'
              -- Fuzzy substring: word_similarity porównuje query z każdym słowem osobno
              -- <<% zwraca TRUE gdy word_similarity(query, text) >= pg_trgm.word_similarity_threshold (0.6)
              -- Działa dobrze dla zapytań >= 4 znaki
              OR p_query <<% (COALESCE(first_name,'') || ' ' || COALESCE(last_name,''))
              -- Pełny match telefonu (exact match w tablicy JSONB)
              OR phone  @> to_jsonb(p_query)
              -- Pełny match emaila
              OR email  @> to_jsonb(p_query)
           )
    ORDER BY
           -- Sortuj: najpierw exact prefix match, potem trigram
           CASE
               WHEN first_name ILIKE p_query || '%' THEN 0
               WHEN last_name  ILIKE p_query || '%' THEN 1
               ELSE 2
           END,
           word_similarity(
               p_query,
               COALESCE(first_name,'') || ' ' || COALESCE(last_name,'')
           ) DESC
    LIMIT  p_limit;
$$;

COMMENT ON FUNCTION search_customers(UUID, TEXT, INT) IS
    'Wyszukiwanie klientów po imieniu/nazwisku (prefix ILIKE + trigram word_similarity), '
    'numerze telefonu lub emailu (exact match w JSONB). '
    'ILIKE obsługuje krótkie prefiksy (np. "Agn" → "Agnieszka"). '
    'word_similarity (<<%) obsługuje fuzzy match dla dłuższych zapytań. '
    'Wymaga: idx_customer_name_trgm, idx_customer_phone_gin, idx_customer_email_gin.';
