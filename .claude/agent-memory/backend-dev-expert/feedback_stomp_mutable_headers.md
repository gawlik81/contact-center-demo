---
name: STOMP ChannelInterceptor – mutable headers
description: W ChannelInterceptor.preSend() MessageHeaderAccessor.getAccessor() może zwrócić immutable accessor – fix: StompHeaderAccessor.wrap(message) + setLeaveMutable(true) + MessageBuilder.createMessage()
type: feedback
---

W `ChannelInterceptor.preSend()` wywołanie `MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)` zwraca accessor który jest "frozen" (immutable), gdy wiadomość została zbudowana przez `MessageBuilder.createMessage()`. Próba wywołania `accessor.setUser(principal)` rzuca `IllegalStateException: Already immutable`.

**Prawidłowy wzorzec:**

```java
StompHeaderAccessor mutableAccessor = StompHeaderAccessor.wrap(message);
mutableAccessor.setLeaveMutable(true);
mutableAccessor.setUser(principal);
return MessageBuilder.createMessage(message.getPayload(), mutableAccessor.getMessageHeaders());
```

**Why:** Spring STOMP w trybie produkcyjnym przekazuje wiadomości z immutable headers przez pipeline. Modyfikacja wymaga stworzenia nowego mutable accessora i przebudowania wiadomości.

**How to apply:** Zawsze gdy piszesz `ChannelInterceptor` modyfikujący nagłówki STOMP (setUser, setSessionId itp.), używaj `StompHeaderAccessor.wrap()` zamiast `MessageHeaderAccessor.getAccessor()`.
