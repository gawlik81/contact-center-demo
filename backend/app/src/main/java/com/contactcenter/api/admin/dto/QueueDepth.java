package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Głębokość jednej kolejki RabbitMQ – składowa {@link SystemResourceMetrics#queueDepths()}.
 */
@Schema(description = "Głębokość (liczba wiadomości) jednej kolejki RabbitMQ")
public record QueueDepth(

        @Schema(description = "Nazwa kolejki RabbitMQ", example = "cc.queue.call-events")
        String queueName,

        @Schema(description = "Liczba wiadomości aktualnie w kolejce", example = "0")
        int messageCount

) {
}
