# Agent Memory Index

- [EPIC-21 Dialer – retry i callback w kampaniach wychodzących](project_epic21_dialer.md) — Stan BE-062–BE-066, wzorce Redis dialera, markAsDialingForCallback vs markAsDialing
- [TwilioTelephonyAdapter – transfer AGENT i QUEUE](project_twilio_transfer.md) — Implementacja initiateTransfer dla AGENT (client:agent-UUID) i QUEUE (Conference TwiML redirect)
- [Attended transfer refactor – Wariant A](project_attended_transfer_refactor.md) — bridgeCalls redirect do nowej konferencji, customerCallSid w CallSession, usunięcie propagateRecordingToTransferChain
- [EPIC-25 Campaign Assignment BE-079–085](project_epic25_campaign_assignment.md) — queueId→campaignId w initiateCall, CampaignAssignmentRepository, trójpoziomowa kwalifikacja, guard OUTBOUND→QUEUE
- [EPIC-28 System pluginów](project_epic28_plugin_system.md) — installation_config AES-GCM bez AAD, dziedziczenie configu przy upgrade pluginu (BE-111), struktura plugin/plugin_version/tenant_plugin_installation
- [DbEgressClient + customer-callresult-db-sync](project_be_db_egress_client.md) — nowa kategoria uprawnień db:egress:<host>:<port> (port obowiązkowy), DriverManager bez poolingu po stronie hosta, przykładowy plugin POST_CONTACT_END/DISPOSITION_SET append-only
