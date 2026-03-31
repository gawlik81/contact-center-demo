# Agent Memory — Debug Specialist

- [Twilio webhook + TenantContext — pułapka publicznego endpointu](pattern_twilio_public_endpoint_tenantcontext.md) — assertSameTenant() rzuca ISE gdy ThreadLocal pusty w wątku publicznego endpointu; persistContact() zwraca null
- [MockCallController + TwilioTelephonyAdapter — niespójna konfiguracja warunkowa](pattern_conditional_bean_twilio_mock.md) — matchIfMissing=true nie wyklucza MockCallController przy twilio.enabled=true; sesje w różnych mapach
- [CrossTenantAspect fałszywy alarm dla publicznych serwisów domenowych](pattern_crosstenant_aspect_false_alarm.md) — AuthService.login loguje ERROR mimo poprawnego działania; ignoruj przy analizie logów
- [ContactService.setDisposition — agentId=null blokuje agenta](pattern_twilio_contact_agent_id_null.md) — contact.getAgentId()=null dla połączeń Twilio; userId.equals(null)=false → InvalidOperationException 409
- [MockCallController nieobecny przy telephony.provider=twilio](pattern_mock_controller_absent_twilio_active.md) — NoResourceFoundException zamiast 404; frontend musi zmienic endpoint odbioru polaczen
- [TwilioTelephonyAdapter — niezgodność klucza sesji callSid vs contactId](pattern_twilio_session_key_mismatch.md) — sessions indeksowane po CA..., frontend wysyła UUID contactId; requireSession rzuca TelephonyException
- [JdbcTemplate + java.time.Instant — PSQLException typ niemapowany](pattern_instant_jdbc_type_mismatch.md) — pgjdbc nie inferencuje SQL type dla Instant; opakowane w BadSqlGrammarException; fix: java.sql.Timestamp.from()
- [TwilioTelephonyAdapter.hangupCall — brak aktualizacji contact.status w DB](pattern_twilio_hangup_no_db_status_update.md) — hangup nie woła updateContactStatusOnTelephonyEvent; contact.status=QUEUED po rozłączeniu; disposition zwraca 409
- [TwilioWebhookController — brak endpointu Voice URL (ErrorCode 12300)](pattern_twilio_voice_url_missing_twiml.md) — brak /twilio/voice zwracającego TwiML; HTTP 204 bez Content-Type → Twilio 12300; połączenia przychodzące zrywane
- [IvrEngineService — & w URL atrybutu action TwiML (Error 12100)](pattern_ivr_twiml_xml_ampersand.md) — buildGatherTwiml wstawia dtmfActionUrl bez escapeXml(); & → &amp; wymagane w XML; Twilio 12100 Document parse failure
- [TwilioVoiceWebhook — brak sesji adaptera przy odbieraniu](pattern_voice_webhook_no_adapter_session.md) — handleVoiceWebhook nie rejestruje sesji w sessions map adaptera; answerCall rzuca TelephonyException mimo poprawnego contact w DB
