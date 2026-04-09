# Agent Memory Index

- [Twilio Conference Audio Pattern](project_twilio_conference_pattern.md) — wzorzec konferencji Twilio do zestawiania audio klient-agent z nagrywaniem (BUGFIX-TWILIO-AUDIO-RECORDING)
- [Voicebot Service BE-014](project_voicebot_be014.md) — mikrousługa Python FastAPI ASR+NLU, IvrNodeType.VOICEBOT, VoicebotClient conditional bean, Docker profile `ai`
- [Progressive Dialer BE-024](project_be024_progressive_dialer.md) — ProgressiveDialerService @RabbitListener agent.status.changed, Redis guard SET NX, FOR UPDATE SKIP LOCKED, DialerCallbackHandler, ScheduledCallback entity, V031 indeksy
- [ScheduledCallbackExecutor BE-038](project_be038_scheduled_callback_executor.md) — @Scheduled fixedDelay scheduler oddzwonień, updateStatusIfPending atomowa ochrona double-processing, ręczny TenantContext w wątku schedulera
- [Inbound Callback Endpoint BE-040](project_be040_inbound_callback.md) — POST /api/contacts/{contactId}/callback w DialerController (ścieżka absolutna), sourceType=INBOUND_CALLBACK, originContactId, logika 403/404 dla agentów
