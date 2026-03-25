---
name: BE-013 IVR Engine
description: Implementacja silnika IVR – drzewa węzłów, sesja Redis, DTMF, fallback do kolejki
type: project
---

Zaimplementowano BE-013: IVR Engine.

**Why:** Połączenia przychodzące muszą być obsługiwane przez interaktywne menu głosowe zanim trafią do agentów.

**Pliki:**
- `domain/ivr/IvrNodeType.java` – enum typów węzłów (MENU, PLAY_AUDIO, COLLECT_DTMF, QUEUE_TRANSFER, HANGUP)
- `domain/ivr/IvrOption.java` – record: klawisz DTMF → nextNodeId
- `domain/ivr/IvrNode.java` – record węzła; metoda `findOption(key)`
- `domain/ivr/IvrDefinition.java` – record: nodes + entryNodeId; metody `findNode()`, `entryNode()`
- `domain/ivr/IvrSessionData.java` – POJO sesji w Redis (callId, ivrId, currentNodeId, tenantId, retryCount)
- `domain/ivr/IvrCallListener.java` – @RabbitListener na `cc.queue.ivr-handler` (call.incoming)
- `domain/model/IvrTree.java` – encja JPA tabeli `ivr_tree` (@JdbcTypeCode JSON dla IvrDefinition)
- `domain/model/IvrAudio.java` – encja JPA tabeli `ivr_audio`
- `domain/repository/IvrTreeRepository.java` – extends TenantAwareRepository; insert/update/delete/deactivateAll
- `domain/repository/IvrAudioRepository.java` – extends TenantAwareRepository; findByAudioIdAndTenantId
- `domain/service/IvrEngineService.java` – silnik IVR; Redis sesja (ivr:session:{callId} TTL 30min); TTS cache (ivr:tts:{md5} TTL 24h)
- `domain/service/IvrService.java` – CRUD drzew IVR; aktywacja (deactivateAll + update)
- `domain/exception/ResourceNotFoundException.java` – HTTP 404
- `api/ivr/IvrController.java` – REST API /api/ivr (CRUD + activate + dtmf symulacja)
- `api/ivr/dto/` – CreateIvrRequest, UpdateIvrRequest, IvrResponse, DtmfInputRequest

**Zmiany w istniejących plikach:**
- `RabbitMQConfig`: dodano QUEUE_IVR_HANDLER + bean ivrHandlerQueue + bindingIvrHandler (call.incoming)
- `AsyncConfig`: dodano bean TaskScheduler (ThreadPoolTaskScheduler, poolSize=4) + @EnableScheduling
- `GlobalExceptionHandler`: dodano handler ResourceNotFoundException (HTTP 404)

**CallEvent**: używa Lombok @Getter (nie record) – dostęp przez `getCallId()`, `getTenantId()`

**How to apply:** IvrEngineService.executeNode() jest package-private (nie private) dla testowalności; testy jednostkowe mockują Redis przez StringRedisTemplate+ValueOperations.
