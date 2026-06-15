package com.contactcenter.domain.telephony;

import com.contactcenter.api.telephony.dto.TransferAgentResponse;
import com.contactcenter.api.telephony.dto.TransferQueueResponse;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.queue.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
class TransferServiceImpl implements TransferService {

    private final UserService userService;
    private final QueueService queueService;

    /**
     * Kolejność wyświetlania statusów na liście transferu.
     * Niższy numer = wyższy priorytet (AVAILABLE jako pierwszy).
     */
    private static final Map<AppUser.UserStatus, Integer> STATUS_ORDER = Map.of(
            AppUser.UserStatus.AVAILABLE,  0,
            AppUser.UserStatus.BUSY,       1,
            AppUser.UserStatus.ACTIVE,     2,
            AppUser.UserStatus.BREAK,      3,
            AppUser.UserStatus.INACTIVE,   4
    );

    @Override
    @Transactional(readOnly = true)
    public List<TransferAgentResponse> getAvailableAgents(UUID tenantId, UUID excludeUserId) {
        log.debug("[TransferService] Pobieranie agentów do transferu: tenant={}, exclude={}",
                tenantId, excludeUserId);

        // 1. Pobierz kandydatów do transferu – filtrowanie wykonane w SQL (brak N+1 / full-scan)
        List<AppUser> candidates = userService
                .findTransferCandidates(tenantId, excludeUserId);

        if (candidates.isEmpty()) {
            log.debug("[TransferService] Brak kandydatów do transferu dla tenant={}", tenantId);
            return Collections.emptyList();
        }

        // 2. Pobierz nazwy kolejek dla wszystkich kandydatów jednym zapytaniem (bez N+1)
        List<UUID> agentIds = candidates.stream()
                .map(AppUser::getId)
                .toList();

        Map<UUID, List<String>> queueNamesByAgent =
                queueService.findQueueNamesByAgentIds(tenantId, agentIds);

        // 3. Zamapuj na DTO i posortuj
        List<TransferAgentResponse> result = candidates.stream()
                .map(agent -> new TransferAgentResponse(
                        agent.getId(),
                        agent.getFirstName(),
                        agent.getLastName(),
                        agent.getStatus().name(),
                        queueNamesByAgent.getOrDefault(agent.getId(), Collections.emptyList())
                ))
                .sorted(Comparator
                        .comparingInt((TransferAgentResponse r) ->
                                STATUS_ORDER.getOrDefault(
                                        AppUser.UserStatus.valueOf(r.status()), 99))
                        .thenComparing(r -> r.lastName() != null ? r.lastName() : "")
                        .thenComparing(r -> r.firstName() != null ? r.firstName() : ""))
                .collect(Collectors.toList());

        log.debug("[TransferService] Znaleziono {} agentów do transferu dla tenant={}",
                result.size(), tenantId);

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferQueueResponse> getAvailableQueues(UUID tenantId) {
        log.debug("[TransferService] Pobieranie kolejek do transferu: tenant={}", tenantId);

        // 1. Aktywne kolejki tenanta
        List<Object[]> queues = queueService.findActiveQueuesForTransfer(tenantId);

        if (queues.isEmpty()) {
            log.debug("[TransferService] Brak aktywnych kolejek dla tenant={}", tenantId);
            return Collections.emptyList();
        }

        List<UUID> queueIds = queues.stream()
                .map(row -> UUID.fromString(row[0].toString()))
                .toList();

        // 2. Liczba kontaktów QUEUED per kolejka – jedno zapytanie GROUP BY
        Map<UUID, Integer> waitingByQueue =
                queueService.countWaitingContactsByQueueIds(tenantId, queueIds);

        // 3. Liczba agentów AVAILABLE per kolejka – jedno zapytanie GROUP BY
        Map<UUID, Integer> availableByQueue =
                queueService.countAvailableAgentsByQueueIds(tenantId, queueIds);

        // 4. Mapuj i sortuj
        List<TransferQueueResponse> result = queues.stream()
                .map(row -> {
                    UUID queueId = UUID.fromString(row[0].toString());
                    String name  = row[1].toString();
                    return new TransferQueueResponse(
                            queueId,
                            name,
                            waitingByQueue.getOrDefault(queueId, 0),
                            availableByQueue.getOrDefault(queueId, 0)
                    );
                })
                .sorted(Comparator.comparing(TransferQueueResponse::name,
                        String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        log.debug("[TransferService] Znaleziono {} kolejek do transferu dla tenant={}",
                result.size(), tenantId);

        return result;
    }
}
