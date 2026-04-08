---
name: RabbitMQ – kolejki jako @Bean w RabbitMQConfig, nie inline @QueueBinding
description: Wszystkie kolejki RabbitMQ muszą być deklarowane jako @Bean w RabbitMQConfig
type: feedback
---

Kolejki MUSZĄ być deklarowane jako `@Bean Queue` w `RabbitMQConfig`, a `@RabbitListener` musi używać `queues = RabbitMQConfig.QUEUE_NAME`.

ZAMIAST:
```java
@RabbitListener(bindings = @QueueBinding(
    value = @Queue(value = "cc.queue.foo", durable = "true", arguments = {...}),
    exchange = @Exchange(value = RabbitMQConfig.EXCHANGE_EVENTS, type = "topic"),
    key = "call.hangup"
))
```

UŻYWAJ:
```java
// W RabbitMQConfig:
public static final String QUEUE_FOO = "cc.queue.foo";

@Bean public Queue fooQueue() {
    return QueueBuilder.durable(QUEUE_FOO)
        .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
        .withArgument("x-dead-letter-routing-key", "dlq")
        .build();
}
@Bean public Binding bindingFoo(Queue fooQueue, TopicExchange eventsExchange) {
    return BindingBuilder.bind(fooQueue).to(eventsExchange).with("call.hangup");
}

// W listenerze:
@RabbitListener(queues = RabbitMQConfig.QUEUE_FOO)
public void onFoo(Event event) { ... }
```

**Why:** Spójność – wszystkie kolejki widoczne w jednym miejscu, łatwiejszy audyt konfiguracji, stała nazwę można użyć w wielu miejscach bez duplikacji stringa.

**How to apply:** Zawsze gdy dodajesz nowy @RabbitListener – najpierw zadeklaruj kolejkę i binding w RabbitMQConfig, potem użyj stałej w adnotacji.
