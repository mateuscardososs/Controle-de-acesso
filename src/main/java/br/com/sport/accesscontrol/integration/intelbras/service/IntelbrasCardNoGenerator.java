package br.com.sport.accesscontrol.integration.intelbras.service;

import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.guests.GuestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Gera CardNo aleatório único de 10 dígitos para fluxos legados que ainda armazenam
 * um identificador Intelbras dedicado. O fluxo CGI atual usa o cartão físico real
 * quando ele existe; sem cartão físico, usa o CPF completo como CardNo.
 */
@Component
public class IntelbrasCardNoGenerator {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasCardNoGenerator.class);

    private static final long CARD_MIN   = 1_000_000_000L;
    private static final long CARD_RANGE = 9_000_000_000L;
    private static final int  MAX_ATTEMPTS = 10;

    private final GuestRepository    guestRepository;
    private final EmployeeRepository employeeRepository;
    private final SecureRandom       random = new SecureRandom();

    public IntelbrasCardNoGenerator(GuestRepository guestRepository,
                                    EmployeeRepository employeeRepository) {
        this.guestRepository    = guestRepository;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Gera um CardNo aleatório de 10 dígitos (1_000_000_000 – 9_999_999_999),
     * garantindo unicidade global entre guests e employees.
     *
     * @throws IllegalStateException se não conseguir gerar valor único em {@value MAX_ATTEMPTS} tentativas
     */
    public String generateUnique() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long number = CARD_MIN + (long) (random.nextDouble() * CARD_RANGE);
            var cardNo  = String.valueOf(number);
            if (!guestRepository.existsByIntelbrasCardNo(cardNo)
                    && !employeeRepository.existsByIntelbrasCardNo(cardNo)) {
                log.info("CARDNO_GENERATED cardNo={}", cardNo);
                return cardNo;
            }
            log.warn("CARDNO_COLLISION_RETRY attempt={} cardNo={}", attempt + 1, cardNo);
        }
        throw new IllegalStateException(
                "Não foi possível gerar CardNo único após " + MAX_ATTEMPTS + " tentativas");
    }
}
