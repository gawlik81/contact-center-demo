package com.contactcenter.security;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Jedno miejsce rozpakowywania łańcucha przyczyn wyjątku dla warstwy STOMP (BE-236): ta sama głębokość i ochrona
 * przed cyklami dla klasyfikacji kodu błędu ({@link StompErrorCodeHandler}) i wykrywania wygasłego JWT
 * ({@link WebSocketAuthInterceptor}).
 */
final class CauseChains {

    static final int MAX_DEPTH = 16;

    private CauseChains() {
    }

    /** Wyjątek i jego przyczyny (od zewnętrznego), maks. {@value #MAX_DEPTH} poziomów, bez powtórzeń (cykle). */
    static List<Throwable> of(@Nullable Throwable ex) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = ex;
        while (current != null && chain.size() < MAX_DEPTH) {
            for (Throwable seen : chain) {
                if (seen == current) {
                    return chain;
                }
            }
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }
}
