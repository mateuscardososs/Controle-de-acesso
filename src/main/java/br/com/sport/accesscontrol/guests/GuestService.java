package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestRequest;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupRequest;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupMode;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.PublicGuestRegistrationResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.PublicVisitorRegistrationResponse;
import br.com.sport.accesscontrol.mail.MailDeliveryResult;
import br.com.sport.accesscontrol.mail.MailService;
import br.com.sport.accesscontrol.integration.sync.GuestReadyForSyncEvent;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import br.com.sport.accesscontrol.realtime.RealtimePublisherService;
import br.com.sport.accesscontrol.realtime.dto.SystemAlertMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GuestService {

    private static final Logger log = LoggerFactory.getLogger(GuestService.class);
    private final GuestRepository guestRepository;
    private final GuestInviteRepository inviteRepository;
    private final FaceStorageService faceStorageService;
    private final AuditService auditService;
    private final RealtimePublisherService realtimePublisherService;
    private final MailService mailService;
    private final ApplicationEventPublisher eventPublisher;
    private final String publicBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration inviteTtl;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public GuestService(GuestRepository guestRepository, GuestInviteRepository inviteRepository,
                        FaceStorageService faceStorageService, AuditService auditService,
                        RealtimePublisherService realtimePublisherService, MailService mailService,
                        ApplicationEventPublisher eventPublisher,
                        @Value("${app.frontend.public-base-url:http://localhost:3000}") String publicBaseUrl,
                        @Value("${app.guests.invite-expiration-hours:72}") long inviteExpirationHours) {
        this.guestRepository = guestRepository;
        this.inviteRepository = inviteRepository;
        this.faceStorageService = faceStorageService;
        this.auditService = auditService;
        this.realtimePublisherService = realtimePublisherService;
        this.mailService = mailService;
        this.eventPublisher = eventPublisher;
        this.publicBaseUrl = publicBaseUrl;
        this.inviteTtl = Duration.ofHours(inviteExpirationHours);
    }

    @Transactional
    public GuestResponse create(GuestRequest request) {
        validateVisitWindow(request.visitStart(), request.visitEnd());
        var guest = guestRepository.save(new Guest(
                request.fullName(), request.cpf(), request.email(), request.phone(), request.company(),
                request.visitReason(), request.hostName(), request.visitStart(), request.visitEnd()
        ));
        var invite = createInvite(guest);
        var inviteUrl = inviteUrl(invite);
        var delivery = sendInviteEmail(guest, inviteUrl, false);
        auditService.record("GUEST_CREATED", "Guest", guest.getId(), Map.of("status", guest.getStatus()), Map.of(), snapshot(guest));
        realtimePublisherService.publishSystemAlert(SystemAlertMessage.warning(
                "Novo visitante pendente",
                "Visitante " + guest.getFullName() + " precisa completar o cadastro facial.",
                "guest-workflow"
        ));
        return GuestResponse.from(guest, invite, inviteUrl, delivery.status(), delivery.message());
    }

    @Transactional
    public PublicVisitorRegistrationResponse publicVisitorRegistration(
            String fullName,
            String cpf,
            String email,
            String phone,
            String company,
            String visitReason,
            String hostName,
            Instant visitStart,
            Instant visitEnd,
            MultipartFile facePhoto
    ) {
        validatePublicRegistration(fullName, cpf, email, visitReason, hostName, visitStart, visitEnd);
        var guest = guestRepository.save(new Guest(
                fullName.trim(),
                onlyDigits(cpf),
                normalize(email),
                normalize(phone),
                normalize(company),
                visitReason.trim(),
                hostName.trim(),
                visitStart,
                visitEnd
        ));
        createInvite(guest);

        var oldData = snapshot(guest);
        if (facePhoto != null && !facePhoto.isEmpty()) {
            var facePhotoUrl = faceStorageService.store(facePhoto, guest.getId());
            guest.completeRegistration(phone, company, facePhotoUrl);
            auditService.record("PUBLIC_GUEST_FACE_UPLOADED", "Guest", guest.getId(), Map.of("facePhotoUrl", facePhotoUrl), oldData, snapshot(guest));
            eventPublisher.publishEvent(new GuestReadyForSyncEvent(guest.getId()));
        }

        auditService.record("PUBLIC_GUEST_CREATED", "Guest", guest.getId(), Map.of("source", "public-home"), Map.of(), snapshot(guest));
        realtimePublisherService.publishSystemAlert(new SystemAlertMessage(
                UUID.randomUUID(),
                SystemAlertMessage.Severity.INFO,
                "Novo cadastro público de visitante",
                "Novo visitante realizou cadastro público: " + guest.getFullName(),
                "public-visitor-registration",
                Instant.now()
        ));
        var message = guest.getStatus() == GuestStatus.COMPLETED
                ? "Cadastro recebido com foto facial. A equipe responsável foi notificada."
                : "Solicitação recebida. A equipe responsável foi notificada.";
        return PublicVisitorRegistrationResponse.from(guest, message);
    }

    @Transactional(readOnly = true)
    public List<GuestResponse> findAll() {
        return guestRepository.findAll().stream().map(guest -> GuestResponse.from(guest, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<GuestResponse> today() {
        var now = Instant.now();
        return guestRepository.findByVisitStartLessThanEqualAndVisitEndGreaterThanEqual(now, now)
                .stream().map(guest -> GuestResponse.from(guest, null)).toList();
    }

    @Transactional(readOnly = true)
    public GuestResponse get(UUID id) {
        return GuestResponse.from(getById(id), null);
    }

    @Transactional
    public GuestResponse update(UUID id, GuestRequest request) {
        validateVisitWindow(request.visitStart(), request.visitEnd());
        var guest = getById(id);
        var oldData = snapshot(guest);
        guest.update(request.fullName(), request.cpf(), request.email(), request.phone(), request.company(),
                request.visitReason(), request.hostName(), request.visitStart(), request.visitEnd(), request.status());
        auditService.record("GUEST_UPDATED", "Guest", guest.getId(), Map.of(), oldData, snapshot(guest));
        return GuestResponse.from(guest, null);
    }

    @Transactional
    public GuestResponse cancel(UUID id) {
        var guest = getById(id);
        var oldData = snapshot(guest);
        guest.cancel();
        auditService.record("GUEST_CANCELLED", "Guest", guest.getId(), Map.of(), oldData, snapshot(guest));
        realtimePublisherService.publishSystemAlert(SystemAlertMessage.warning(
                "Convite cancelado",
                "Convite de " + guest.getFullName() + " foi cancelado.",
                "guest-workflow"
        ));
        return GuestResponse.from(guest, null);
    }

    @Transactional
    public GuestResponse resendInvite(UUID id) {
        var guest = getById(id);
        guest.markInvited();
        var invite = createInvite(guest);
        var inviteUrl = inviteUrl(invite);
        var delivery = sendInviteEmail(guest, inviteUrl, true);
        auditService.record("GUEST_INVITE_RESENT", "Guest", guest.getId(), Map.of("expiresAt", invite.getExpiresAt()), Map.of(), snapshot(guest));
        return GuestResponse.from(guest, invite, inviteUrl, delivery.status(), delivery.message());
    }

    @Transactional
    public GuestCleanupResponse cleanup(GuestCleanupRequest request) {
        var criteria = cleanupCriteria(request);
        var authenticatedUser = authenticatedUser();
        log.info("cleanup_requested filters={} authenticatedUser={}", criteria, authenticatedUser);

        if (request.mode() == GuestCleanupMode.ALL && !"LIMPAR".equals(request.confirmationPhrase())) {
            throw new IllegalArgumentException("Digite LIMPAR para confirmar a limpeza de todos os visitantes.");
        }

        var guests = request.mode() == null ? legacyCleanupGuests(request) : modeCleanupGuests(request.mode());
        var removedCount = guests.size();
        log.info("cleanup_requested filters={} authenticatedUser={} removedCount={}", criteria, authenticatedUser, removedCount);

        var message = removedCount == 0
                ? "Nenhum visitante encontrado para os filtros informados"
                : removedCount + " visitantes removidos";

        if (guests.isEmpty()) {
            auditService.record("GUEST_CLEANUP", "Guest", null,
                    Map.of("removedCount", 0, "criteria", criteria, "authenticatedUser", authenticatedUser),
                    Map.of(), Map.of());
            return new GuestCleanupResponse(0, message);
        }

        var invites = inviteRepository.findByGuestIn(guests);
        inviteRepository.deleteAllInBatch(invites);
        guestRepository.deleteAllInBatch(guests);
        auditService.record("GUEST_CLEANUP", "Guest", null,
                Map.of("removedCount", removedCount, "criteria", criteria, "authenticatedUser", authenticatedUser),
                Map.of("removedIds", guests.stream().map(Guest::getId).map(UUID::toString).toList()), Map.of());
        return new GuestCleanupResponse(removedCount, message);
    }

    private List<Guest> modeCleanupGuests(GuestCleanupMode mode) {
        return guestRepository.findAll().stream()
                .filter(guest -> switch (mode) {
                    case CANCELLED -> guest.getStatus() == GuestStatus.CANCELLED;
                    case FAILED -> guest.getSyncStatus() == SyncStatus.SYNC_FAILED;
                    case TEST_RECORDS -> isTestRecord(guest);
                    case ALL -> true;
                })
                .toList();
    }

    private List<Guest> legacyCleanupGuests(GuestCleanupRequest request) {
        var statuses = request.status() == null ? List.<GuestStatus>of() : request.status();
        var syncStatuses = request.integrationStatus() == null ? List.<SyncStatus>of() : request.integrationStatus();
        var olderThanDays = Math.max(0, request.olderThanDays());
        var cutoff = olderThanDays > 0 ? Instant.now().minus(Duration.ofDays(olderThanDays)) : null;
        if (statuses.isEmpty() && syncStatuses.isEmpty() && !request.onlyTestRecords() && cutoff == null) {
            throw new IllegalArgumentException("Informe ao menos um critério para limpar a lista de visitantes.");
        }

        return guestRepository.findAll().stream()
                .filter(guest -> statuses.isEmpty() || statuses.contains(guest.getStatus()))
                .filter(guest -> syncStatuses.isEmpty() || syncStatuses.contains(guest.getSyncStatus()))
                .filter(guest -> cutoff == null || guest.getCreatedAt().isBefore(cutoff))
                .filter(guest -> !request.onlyTestRecords() || isTestRecord(guest))
                .toList();
    }

    @Transactional(readOnly = true)
    public PublicGuestRegistrationResponse publicRegistration(String token) {
        return PublicGuestRegistrationResponse.from(validInvite(token).getGuest());
    }

    @Transactional
    public GuestResponse completeRegistration(String token, String phone, String company, MultipartFile facePhoto) {
        var invite = validInvite(token);
        var guest = invite.getGuest();
        var oldData = snapshot(guest);
        var facePhotoUrl = faceStorageService.store(facePhoto, guest.getId());
        guest.completeRegistration(phone, company, facePhotoUrl);
        invite.markUsed();
        auditService.record("GUEST_FACE_UPLOADED", "Guest", guest.getId(), Map.of("facePhotoUrl", facePhotoUrl), oldData, snapshot(guest));
        auditService.record("GUEST_REGISTRATION_COMPLETED", "Guest", guest.getId(), Map.of(), oldData, snapshot(guest));
        eventPublisher.publishEvent(new GuestReadyForSyncEvent(guest.getId()));
        var delivery = mailService.sendGuestRegistrationCompleted(guest);
        auditMailResult(guest, "GUEST_REGISTRATION_CONFIRMATION_EMAIL", delivery);
        realtimePublisherService.publishSystemAlert(new SystemAlertMessage(
                UUID.randomUUID(),
                SystemAlertMessage.Severity.INFO,
                "Visitante cadastrado",
                "Visitante " + guest.getFullName() + " concluiu o cadastro facial.",
                "guest-workflow",
                Instant.now()
        ));
        return GuestResponse.from(guest, null, null, delivery.status(), delivery.message());
    }

    @Transactional
    public List<GuestResponse> expireOverdueGuests() {
        var now = Instant.now();
        return guestRepository.findByStatusNotAndVisitEndBefore(GuestStatus.CANCELLED, now).stream()
                .filter(guest -> guest.getStatus() != GuestStatus.EXPIRED && guest.getStatus() != GuestStatus.COMPLETED)
                .map(guest -> {
                    guest.expire();
                    auditService.record("GUEST_EXPIRED", "Guest", guest.getId(), Map.of(), Map.of(), snapshot(guest));
                    realtimePublisherService.publishSystemAlert(SystemAlertMessage.warning(
                            "Convite expirado",
                            "Convite de " + guest.getFullName() + " expirou.",
                            "guest-workflow"
                    ));
                    return GuestResponse.from(guest, null);
                }).toList();
    }

    private Guest getById(UUID id) {
        return guestRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Guest not found: " + id));
    }

    private GuestInvite validInvite(String token) {
        var invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Guest invite not found."));
        if (!invite.isUsable(Instant.now()) || invite.getGuest().getStatus() == GuestStatus.CANCELLED) {
            throw new IllegalArgumentException("Guest invite is expired or already used.");
        }
        return invite;
    }

    private GuestInvite createInvite(Guest guest) {
        return inviteRepository.save(new GuestInvite(guest, token(), Instant.now().plus(inviteTtl)));
    }

    private String inviteUrl(GuestInvite invite) {
        return publicBaseUrl.replaceAll("/+$", "") + "/guest-registration/" + invite.getToken();
    }

    private MailDeliveryResult sendInviteEmail(Guest guest, String inviteUrl, boolean resent) {
        auditService.record(resent ? "GUEST_INVITE_EMAIL_RESEND_ATTEMPTED" : "GUEST_INVITE_EMAIL_ATTEMPTED",
                "Guest", guest.getId(), Map.of("inviteUrl", inviteUrl), Map.of(), snapshot(guest));
        var delivery = resent ? mailService.sendGuestInviteResent(guest, inviteUrl) : mailService.sendGuestInvite(guest, inviteUrl);
        auditMailResult(guest, resent ? "GUEST_INVITE_EMAIL_RESEND" : "GUEST_INVITE_EMAIL", delivery);
        return delivery;
    }

    private void auditMailResult(Guest guest, String actionPrefix, MailDeliveryResult delivery) {
        var action = delivery.sent() ? actionPrefix + "_SENT" : actionPrefix + "_" + delivery.status();
        auditService.record(action, "Guest", guest.getId(),
                Map.of("status", delivery.status(), "message", delivery.message() == null ? "" : delivery.message()), Map.of(), snapshot(guest));
    }

    private String token() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validateVisitWindow(Instant start, Instant end) {
        if (end.isBefore(start) || end.equals(start)) {
            throw new IllegalArgumentException("visitEnd must be after visitStart.");
        }
    }

    private void validatePublicRegistration(String fullName, String cpf, String email, String visitReason,
                                            String hostName, Instant visitStart, Instant visitEnd) {
        if (isBlank(fullName) || isBlank(cpf) || isBlank(email) || isBlank(visitReason) || isBlank(hostName)
                || visitStart == null || visitEnd == null) {
            throw new IllegalArgumentException("Required visitor registration fields are missing.");
        }
        if (onlyDigits(cpf).length() != 11) {
            throw new IllegalArgumentException("CPF must contain 11 digits.");
        }
        if (!EMAIL.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("Email must be valid.");
        }
        validateVisitWindow(visitStart, visitEnd);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String onlyDigits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private boolean isTestRecord(Guest guest) {
        return containsTestToken(guest.getFullName())
                || containsTestToken(guest.getEmail())
                || containsTestToken(guest.getCompany())
                || containsTestToken(guest.getVisitReason())
                || containsTestToken(guest.getHostName())
                || "00000000000".equals(onlyDigits(guest.getCpf()))
                || "12345678900".equals(onlyDigits(guest.getCpf()))
                || "12345678901".equals(onlyDigits(guest.getCpf()));
    }

    private boolean containsTestToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        var normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("teste")
                || normalized.contains("test")
                || normalized.contains("mock")
                || normalized.contains("demo")
                || normalized.contains("exemplo")
                || normalized.contains("example");
    }

    private Map<String, Object> cleanupCriteria(GuestCleanupRequest request) {
        var statuses = request.status() == null ? List.<GuestStatus>of() : request.status();
        var syncStatuses = request.integrationStatus() == null ? List.<SyncStatus>of() : request.integrationStatus();
        return Map.of(
                "mode", request.mode() == null ? "LEGACY" : request.mode().name(),
                "status", statuses.stream().map(Enum::name).toList(),
                "integrationStatus", syncStatuses.stream().map(Enum::name).toList(),
                "olderThanDays", Math.max(0, request.olderThanDays()),
                "onlyTestRecords", request.onlyTestRecords()
        );
    }

    private String authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        return authentication.getName();
    }

    private Map<String, Object> snapshot(Guest guest) {
        return Map.of(
                "id", guest.getId(),
                "fullName", guest.getFullName(),
                "status", guest.getStatus(),
                "visitStart", guest.getVisitStart(),
                "visitEnd", guest.getVisitEnd()
        );
    }
}
