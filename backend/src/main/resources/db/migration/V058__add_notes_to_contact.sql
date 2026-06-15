-- DB-034: Kolumna notes w tabeli contact (EPIC-22 Notatki do kontaktów)
-- Tabela contact jest partycjonowana RANGE po started_at -- ALTER TABLE ADD COLUMN
-- propaguje automatycznie na wszystkie partycje miesięczne.

ALTER TABLE contact
    ADD COLUMN IF NOT EXISTS notes TEXT;

COMMENT ON COLUMN contact.notes IS
    'Notatka agenta wpisana podczas lub po zakończeniu kontaktu. '
    'Opcjonalna, bez limitu dlugosci. Ustawiana przez PATCH /api/contacts/{id}/disposition.';
