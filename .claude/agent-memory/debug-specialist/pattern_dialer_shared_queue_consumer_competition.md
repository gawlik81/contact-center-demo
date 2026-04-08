---
name: ProgressiveDialer — dwa @RabbitListener na tej samej kolejce (consumer competition)
description: RoutingService i ProgressiveDialerService słuchały na QUEUE_AGENT_STATUS; RabbitMQ round-robin → dialer dostawał ~50% eventów AVAILABLE; fix: osobna kolejka cc.queue.dialer-agent-status
type: project
---

ProgressiveDialerService i RoutingService były zarejestrowane jako dwa oddzielne `@RabbitListener` na tej samej kolejce `cc.queue.agent-status`. RabbitMQ przy zwykłej kolejce (nie fan-out) dostarcza każdą wiadomość dokładnie JEDNEMU konsumentowi w trybie round-robin. Efekt: dialer otrzymywał statystycznie ~50% eventów zmiany statusu agenta na AVAILABLE i nie inicjował połączeń dla połowy agentów. Przy małej liczbie agentów (np. jeden) mógł nie dostać żadnego eventu.

**Why:** Dwa niezależne serwisy domenowe (routing i dialer) potrzebują reagować na ten sam event domenowy. W architekturze pub-sub opartej na RabbitMQ topic exchange każdy konsument musi mieć własną, dedykowaną kolejkę podpiętą do tego samego exchange+routing-key.

**How to apply:** Zawsze gdy dwa lub więcej serwisów musi reagować na ten sam routing key w RabbitMQ: każdy dostaje własną kolejkę z własnym bindingiem. Nie współdziel kolejek między `@RabbitListener` w różnych serwisach. Wzorzec zastosowany: `cc.queue.dialer-agent-status` + `cc.queue.agent-status` obie zbindowane do `cc.events` z `agent.status.#`.

Dodatkowy problem (fixowany przy okazji): `ProgressiveDialerService` porównywał status przez `!"AVAILABLE".equals(event.newStatus().name())` zamiast `event.newStatus() != UserStatus.AVAILABLE` — mniej czytelne i podatne na błędy przy zmianie nazwy enum.
