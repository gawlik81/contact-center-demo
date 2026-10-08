-- =============================================================================
-- V125__force_rls_seven_class_a_tenant_tables.sql
-- DB-081: FORCE ROW LEVEL SECURITY na 7 tabelach klasy TENANT A z DB-071
-- (domkniecie historycznej dziury V012) -- EPIC-30.
--
-- Migracja: Flyway V125
-- Zaleznosci: V012 (row_level_security -- wlaczyla RLS i polityki ALL/4-komendowe
--             na tych tabelach, ale NIE ustawila FORCE dla wiekszosci z nich)
-- Blokuje: brak
--
-- KONTEKST (odkrycie BE-138, rownolegla tura generalizacji RlsValidationService):
-- DB-071 klasyfikowal te 7 tabel jako klasa TENANT A ("pelne pokrycie 4 komend,
-- RLS OK"), bo raport sprawdzal WYLACZNIE pokrycie komend w pg_policies, nie
-- relforcerowsecurity. Zweryfikowane bezposrednio w pg_class PRZED ta migracja:
-- wszystkie 7 majа relrowsecurity=TRUE, relforcerowsecurity=FALSE.
--
-- Przyczyna historyczna: V012 ustawila FORCE tylko dla customer/contact/
-- campaign/queue (linie 83-86 tamtej migracji). email_message/social_message
-- dostaly FORCE pozniej, w V099 (DB-064). audit_log/app_user -- w V122/V123
-- (DB-074). ivr_tree -- w V121 (DB-074, odnotowane tam jako "jedyna z klasy B
-- bez FORCE od V012"). Pozostale 7 tabel z klasy A nigdy nie dostaly FORCE:
-- agent_break, agent_group, phone_number, phone_routing_rule,
-- scheduled_callback, social_integration, tenant_twilio_config.
--
-- DLACZEGO TO MA ZNACZENIE (mimo ze ccapp ma dzis BYPASSRLS i produkcyjnie nic
-- sie nie zmienia): FORCE ROW LEVEL SECURITY kontroluje, czy polityka RLS
-- obowiazuje rowniez WLASCICIELA tabeli (gdy ten wlasciciel NIE ma BYPASSRLS).
-- Bez FORCE, przyszla rola migracyjna/serwisowa bedaca wlascicielem tych tabel,
-- ale bez BYPASSRLS, calkowicie omijalaby RLS na tych 7 tabelach, mimo
-- kompletnych polityk -- ten sam typ dziury jak "obejscie po nazwie partycji"
-- naprawiany wczesniej w tym epiku (DB-080), tylko na poziomie FORCE, nie GRANT.
--
-- ZAKRES: wylacznie ALTER TABLE ... FORCE ROW LEVEL SECURITY. Polityki i
-- relrowsecurity NIE sa dotykane (juz kompletne wg DB-071 klasa A) -- brak
-- zmiany zachowania pod rolami BYPASSRLS/superuser (ccapp, testy Testcontainers)
-- i brak zmiany zachowania pod app_user (nie jest wlascicielem tych tabel).
-- =============================================================================

ALTER TABLE agent_break         FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_group         FORCE ROW LEVEL SECURITY;
ALTER TABLE phone_number        FORCE ROW LEVEL SECURITY;
ALTER TABLE phone_routing_rule  FORCE ROW LEVEL SECURITY;
ALTER TABLE scheduled_callback  FORCE ROW LEVEL SECURITY;
ALTER TABLE social_integration  FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_twilio_config FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa: wszystkie 7 tabel musza miec relforcerowsecurity=TRUE.
-- Blad tutaj powoduje ROLLBACK calej migracji (Flyway -> jedna transakcja).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_missing_force_count INT;
    v_missing_tables TEXT;
BEGIN
    SELECT COUNT(*), string_agg(relname, ', ' ORDER BY relname)
    INTO v_missing_force_count, v_missing_tables
    FROM pg_class
    WHERE relname IN (
        'agent_break', 'agent_group', 'phone_number', 'phone_routing_rule',
        'scheduled_callback', 'social_integration', 'tenant_twilio_config'
    )
    AND relforcerowsecurity = FALSE;

    IF v_missing_force_count > 0 THEN
        RAISE EXCEPTION 'V125: % z 7 tabel nadal nie ma FORCE ROW LEVEL SECURITY: %',
            v_missing_force_count, v_missing_tables;
    END IF;

    RAISE NOTICE 'V125: OK -- wszystkie 7 tabel klasy TENANT A (agent_break, agent_group, '
        'phone_number, phone_routing_rule, scheduled_callback, social_integration, '
        'tenant_twilio_config) maja FORCE ROW LEVEL SECURITY';
END $$;
