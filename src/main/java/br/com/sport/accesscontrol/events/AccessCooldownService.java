package br.com.sport.accesscontrol.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controla o intervalo mínimo entre liberações da mesma pessoa no mesmo dispositivo.
 * Chave: personKey + ":" + deviceId. Estado em memória (por instância de aplicação).
 */
@Service
public class AccessCooldownService {

    private final long cooldownSeconds;
    private final ConcurrentHashMap<String, Instant> lastAllowedTimes = new ConcurrentHashMap<>();

    public AccessCooldownService(
            @Value("${app.access.release-cooldown-seconds:15}") long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public boolean isInCooldown(String personKey, UUID deviceId) {
        if (personKey == null || deviceId == null) {
            return false;
        }
        var lastTime = lastAllowedTimes.get(cacheKey(personKey, deviceId));
        if (lastTime == null) {
            return false;
        }
        return Instant.now().isBefore(lastTime.plusSeconds(cooldownSeconds));
    }

    public Instant lastAllowedTime(String personKey, UUID deviceId) {
        if (personKey == null || deviceId == null) return null;
        return lastAllowedTimes.get(cacheKey(personKey, deviceId));
    }

    public void recordAllowed(String personKey, UUID deviceId) {
        if (personKey == null || deviceId == null) return;
        lastAllowedTimes.put(cacheKey(personKey, deviceId), Instant.now());
    }

    public long cooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * Resolve chave de pessoa na ordem de confiabilidade: CPF > personId > externalUserId > rawCardName.
     */
    public static String personKey(String personCpf, UUID personId, String externalUserId, String rawCardName) {
        if (personCpf != null && !personCpf.isBlank()) return "cpf:" + personCpf.replaceAll("[^0-9]", "");
        if (personId != null) return "id:" + personId;
        if (externalUserId != null && !externalUserId.isBlank()) return "ext:" + externalUserId.trim();
        if (rawCardName != null && !rawCardName.isBlank()) return "card:" + rawCardName.trim();
        return null;
    }

    private static String cacheKey(String personKey, UUID deviceId) {
        return personKey + ":" + deviceId;
    }
}
