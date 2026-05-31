package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.areas.LoungeAreaResolver;
import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.appconfig.LoungeConfig;
import br.com.sport.accesscontrol.common.CpfValidator;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestRequest;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupRequest;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestCleanupMode;
import br.com.sport.accesscontrol.guests.GuestDtos.GuestResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.PublicGuestRegistrationResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.PublicVisitorRegistrationResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.CpfValidationResponse;
import br.com.sport.accesscontrol.guests.GuestDtos.CpfCheckinResponse;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private final LoungeConfig loungeConfig;
    private final LoungeAreaResolver loungeAreaResolver;
    private final String publicBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration inviteTtl;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final ZoneId EVENT_ZONE = ZoneId.of("America/Recife");
    private static final LocalTime GUEST_ACCESS_START_TIME = LocalTime.of(15, 0);
    private static final LocalTime GUEST_ACCESS_END_TIME = LocalTime.of(4, 0);

    /** Backward-compatible constructor (testes legados sem LoungeAreaResolver). */
    public GuestService(GuestRepository guestRepository, GuestInviteRepository inviteRepository,
                        FaceStorageService faceStorageService, AuditService auditService,
                        RealtimePublisherService realtimePublisherService, MailService mailService,
                        ApplicationEventPublisher eventPublisher, LoungeConfig loungeConfig,
                        String publicBaseUrl,
                        long inviteExpirationHours) {
        this(guestRepository, inviteRepository, faceStorageService, auditService, realtimePublisherService,
                mailService, eventPublisher, loungeConfig, null, publicBaseUrl, inviteExpirationHours);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GuestService(GuestRepository guestRepository, GuestInviteRepository inviteRepository,
                        FaceStorageService faceStorageService, AuditService auditService,
                        RealtimePublisherService realtimePublisherService, MailService mailService,
                        ApplicationEventPublisher eventPublisher, LoungeConfig loungeConfig,
                        LoungeAreaResolver loungeAreaResolver,
                        @Value("${app.frontend.public-base-url:http://localhost:3000}") String publicBaseUrl,
                        @Value("${app.guests.invite-expiration-hours:72}") long inviteExpirationHours) {
        this.guestRepository = guestRepository;
        this.inviteRepository = inviteRepository;
        this.faceStorageService = faceStorageService;
        this.auditService = auditService;
        this.realtimePublisherService = realtimePublisherService;
        this.mailService = mailService;
        this.eventPublisher = eventPublisher;
        this.loungeConfig = loungeConfig;
        this.loungeAreaResolver = loungeAreaResolver;
        this.publicBaseUrl = publicBaseUrl;
        this.inviteTtl = Duration.ofHours(inviteExpirationHours);
    }

    @Transactional
    public GuestResponse create(GuestRequest request) {
        validateVisitWindow(request.visitStart(), request.visitEnd());
        validateInvitedLounge(request.invitedLounge());
        var normalizedCpf = CpfValidator.normalizeOrThrow(request.cpf());
        var guest = guestRepository.save(new Guest(
                request.fullName(), normalizedCpf, request.email(), request.phone(), request.company(),
                request.visitReason(), request.hostName(), request.visitStart(), request.visitEnd(),
                resolveInvitedDay(request.invitedDay(), request.visitStart()), canonicalInvitedLounge(request.invitedLounge())
        ));
        applyAllowedAreas(guest);
        validateResolvedAllowedAreas(guest);
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
            LocalDate invitedDay,
            String invitedLounge,
            Instant visitStart,
            Instant visitEnd,
            MultipartFile facePhoto
    ) {
        var registration = completedVisitorRegistration(
                fullName,
                cpf,
                email,
                phone,
                company,
                normalize(visitReason) == null ? "Convidado São João/Superfeito" : visitReason.trim(),
                normalize(hostName) == null ? "Credenciamento São João/Superfeito" : hostName.trim(),
                invitedDay,
                invitedLounge,
                visitStart,
                visitEnd,
                facePhoto,
                true
        );
        var guest = registration.guest();
        auditService.record("PUBLIC_GUEST_FACE_UPLOADED", "Guest", guest.getId(),
                Map.of("facePhotoUrl", registration.facePhotoUrl()), registration.oldData(), snapshot(guest));

        if (loungeAreaResolver != null && loungeAreaResolver.isCollaboratorLounge(guest.getInvitedLounge())) {
            log.info("PUBLIC_GUEST_REGISTERED_AS_COLLABORATOR guest_id={} cpf={}",
                    guest.getId(), guest.getCpf());
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
        triggerGuestAutoSyncAfterRegistration(guest, "PUBLIC_REGISTRATION", false);
        var message = "Cadastro recebido com foto facial. A equipe responsável foi notificada.";
        return PublicVisitorRegistrationResponse.from(guest, message);
    }

    @Transactional
    public GuestResponse adminVisitorRegistration(
            String fullName,
            String cpf,
            String email,
            String phone,
            LocalDate invitedDay,
            String invitedLounge,
            MultipartFile facePhoto
    ) {
        var registration = completedVisitorRegistration(
                fullName,
                cpf,
                email,
                phone,
                null,
                "Convidado São João/Superfeito",
                "Credenciamento interno",
                invitedDay,
                invitedLounge,
                null,
                null,
                facePhoto,
                false
        );
        var guest = registration.guest();
        auditService.record("ADMIN_GUEST_FACE_UPLOADED", "Guest", guest.getId(),
                Map.of("facePhotoUrl", registration.facePhotoUrl()), registration.oldData(), snapshot(guest));
        auditService.record("ADMIN_GUEST_CREATED", "Guest", guest.getId(), Map.of("source", "admin-guests"), Map.of(), snapshot(guest));
        realtimePublisherService.publishSystemAlert(new SystemAlertMessage(
                UUID.randomUUID(),
                SystemAlertMessage.Severity.INFO,
                "Visitante criado pela administração",
                "Visitante " + guest.getFullName() + " foi cadastrado com foto facial.",
                "admin-visitor-registration",
                Instant.now()
        ));
        return GuestResponse.from(guest, null);
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
        validateInvitedLounge(request.invitedLounge());
        var guest = getById(id);
        var oldData = snapshot(guest);
        var normalizedCpf = CpfValidator.normalizeOrThrow(request.cpf());
        guest.update(request.fullName(), normalizedCpf, request.email(), request.phone(), request.company(),
                request.visitReason(), request.hostName(), request.visitStart(), request.visitEnd(),
                resolveInvitedDay(request.invitedDay(), request.visitStart()), canonicalInvitedLounge(request.invitedLounge()),
                request.status());
        applyAllowedAreas(guest);
        validateResolvedAllowedAreas(guest);
        auditService.record("GUEST_UPDATED", "Guest", guest.getId(), Map.of(), oldData, snapshot(guest));
        return GuestResponse.from(guest, null);
    }

    private void applyAllowedAreas(Guest guest) {
        if (loungeAreaResolver == null) {
            return;
        }
        var areas = loungeAreaResolver.resolveForLounge(guest.getInvitedLounge());
        guest.replaceAllowedAreas(areas);
    }

    private void validateResolvedAllowedAreas(Guest guest) {
        if (loungeAreaResolver == null) {
            return;
        }
        var activeAllowedAreas = activeAllowedAreas(guest);
        if (!isBlank(guest.getInvitedLounge())) {
            if (!loungeConfig.isValid(guest.getInvitedLounge())) {
                throw new IllegalArgumentException("Camarote inválido");
            }
            if (!loungeAreaResolver.isCollaboratorLounge(guest.getInvitedLounge())) {
                // For regular lounge guests, check that Portaria and the lounge area exist.
                if (!containsAreaName(activeAllowedAreas, LoungeAreaResolver.GENERAL_AREA_NAME)) {
                    log.warn("LOUNGE_AREA_NOT_CONFIGURED guest_id={} invited_lounge={} reason=portaria_missing — área Portaria não encontrada no DB; sync continuará mas pode não atingir nenhum dispositivo.",
                            guest.getId(), guest.getInvitedLounge());
                }
                if (!containsLoungeArea(activeAllowedAreas, guest.getInvitedLounge())) {
                    log.warn("LOUNGE_AREA_NOT_CONFIGURED guest_id={} invited_lounge={} resolved_areas=[{}] — área do camarote não encontrada no DB; sync usará apenas áreas resolvidas (provavelmente Portaria). Configure uma área com o nome do camarote para rotear corretamente.",
                            guest.getId(), guest.getInvitedLounge(),
                            activeAllowedAreas.stream().map(Area::getName).collect(Collectors.joining(",")));
                    throw new IllegalArgumentException("Área ativa do camarote não configurada: "
                            + guest.getInvitedLounge());
                }
            }
        }
        if (guest.getStatus() == GuestStatus.COMPLETED && activeAllowedAreas.isEmpty()) {
            throw new IllegalArgumentException("Visitante sem áreas permitidas.");
        }
    }

    private List<Area> activeAllowedAreas(Guest guest) {
        if (guest.getAllowedAreas() == null) {
            return List.of();
        }
        return guest.getAllowedAreas().stream()
                .filter(Area::isActive)
                .toList();
    }

    private String activeAllowedAreaIds(Guest guest) {
        if (guest.getAllowedAreas() == null) return "";
        return guest.getAllowedAreas().stream()
                .filter(Area::isActive)
                .map(a -> a.getId() == null ? "null" : a.getId().toString())
                .collect(Collectors.joining(","));
    }

    private String activeAllowedAreaNames(Guest guest) {
        if (guest.getAllowedAreas() == null) return "";
        return guest.getAllowedAreas().stream()
                .filter(Area::isActive)
                .map(Area::getName)
                .collect(Collectors.joining(","));
    }

    private boolean containsAreaName(List<Area> areas, String name) {
        return areas.stream()
                .map(Area::getName)
                .anyMatch(areaName -> areaName != null && areaName.trim().equalsIgnoreCase(name.trim()));
    }

    private boolean containsLoungeArea(List<Area> areas, String invitedLounge) {
        if (loungeAreaResolver == null) {
            return containsAreaName(areas, invitedLounge);
        }
        return areas.stream().anyMatch(area -> loungeAreaResolver.matchesLoungeArea(area, invitedLounge));
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

    /**
     * Feature 3 — public CPF check-in: validates whether a pre-registered guest exists for this CPF
     * and is still awaiting the facial photo (status PENDING_REGISTRATION). Returns only the minimum
     * data needed for the welcome screen — no email/phone/other sensitive fields.
     */
    @Transactional(readOnly = true)
    public CpfValidationResponse validateCpfForCheckin(String cpf) {
        var digits = CpfValidator.onlyDigits(cpf);
        if (digits.isBlank()) {
            log.info("PUBLIC_CPF_CHECKIN_VALIDATE found=false reason=blank_cpf");
            return CpfValidationResponse.notFound();
        }
        return guestRepository.findFirstByCpfOrderByVisitStartDesc(digits)
                .map(guest -> {
                    if (guest.getStatus() != GuestStatus.PENDING_REGISTRATION) {
                        log.info("PUBLIC_CPF_CHECKIN_VALIDATE guest_id={} found=false reason=already_registered status={}",
                                guest.getId(), guest.getStatus());
                        return CpfValidationResponse.alreadyRegistered();
                    }
                    log.info("PUBLIC_CPF_CHECKIN_VALIDATE guest_id={} found=true invited_lounge={}",
                            guest.getId(), guest.getInvitedLounge());
                    return CpfValidationResponse.welcome(guest);
                })
                .orElseGet(() -> {
                    log.info("PUBLIC_CPF_CHECKIN_VALIDATE found=false reason=cpf_not_found");
                    return CpfValidationResponse.notFound();
                });
    }

    /**
     * Feature 3 — public CPF check-in completion: stores the facial photo on the existing
     * pre-registered guest, marks it COMPLETED and triggers automatic Intelbras sync.
     */
    @Transactional
    public CpfCheckinResponse completeCheckinByCpf(String cpf, String facePhotoBase64) {
        var digits = CpfValidator.onlyDigits(cpf);
        var guest = guestRepository
                .findFirstByCpfAndStatusOrderByVisitStartDesc(digits, GuestStatus.PENDING_REGISTRATION)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CPF não encontrado ou cadastro já concluído. Verifique com o organizador."));
        var oldData = snapshot(guest);
        var autoSyncAlreadyDoneOrRunning = isAutoSyncAlreadyDoneOrRunning(guest.getSyncStatus());

        // Resolve allowed areas from the pre-registered lounge before completing
        applyAllowedAreas(guest);
        validateResolvedAllowedAreas(guest);

        var facePhotoUrl = faceStorageService.storeBase64(facePhotoBase64, guest.getId());
        guest.completeRegistration(null, null, facePhotoUrl); // sets COMPLETED + markPendingSync
        guestRepository.save(guest);

        auditService.record("PUBLIC_CPF_CHECKIN_FACE_UPLOADED", "Guest", guest.getId(),
                Map.of("facePhotoUrl", facePhotoUrl), oldData, snapshot(guest));
        auditService.record("PUBLIC_CPF_CHECKIN_COMPLETED", "Guest", guest.getId(),
                Map.of("source", "cpf-checkin"), oldData, snapshot(guest));
        realtimePublisherService.publishSystemAlert(new SystemAlertMessage(
                UUID.randomUUID(),
                SystemAlertMessage.Severity.INFO,
                "Check-in facial concluído",
                "Visitante " + guest.getFullName() + " concluiu o check-in facial por CPF.",
                "public-cpf-checkin",
                Instant.now()
        ));

        triggerGuestAutoSyncAfterRegistration(guest, "PUBLIC_REGISTRATION", autoSyncAlreadyDoneOrRunning);
        return CpfCheckinResponse.done(guest);
    }

    @Transactional
    public GuestResponse completeRegistration(String token, String phone, String company, MultipartFile facePhoto) {
        var invite = validInvite(token);
        var guest = invite.getGuest();
        var oldData = snapshot(guest);
        var autoSyncAlreadyDoneOrRunning = isAutoSyncAlreadyDoneOrRunning(guest.getSyncStatus());
        var facePhotoUrl = faceStorageService.store(facePhoto, guest.getId());
        guest.completeRegistration(phone, company, facePhotoUrl);
        invite.markUsed();
        auditService.record("GUEST_FACE_UPLOADED", "Guest", guest.getId(), Map.of("facePhotoUrl", facePhotoUrl), oldData, snapshot(guest));
        auditService.record("GUEST_REGISTRATION_COMPLETED", "Guest", guest.getId(), Map.of(), oldData, snapshot(guest));
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
        triggerGuestAutoSyncAfterRegistration(guest, "PUBLIC_REGISTRATION", autoSyncAlreadyDoneOrRunning);
        return GuestResponse.from(guest, null, null, delivery.status(), delivery.message());
    }

    @Transactional
    public GuestResponse requestSync(UUID id) {
        var guest = guestRepository.findByIdWithAllowedAreas(id)
                .orElseThrow(() -> new ResourceNotFoundException("Guest not found: " + id));
        log.info("GUEST_SYNC_AREAS_LOADED guest_id={} area_count={}",
                id, guest.getAllowedAreas() == null ? 0 : guest.getAllowedAreas().size());
        var facePresent = !isBlank(guest.getFacePhotoUrl());
        var areasCount = guest.getAllowedAreas() == null ? 0 : guest.getAllowedAreas().size();
        var eligible = guest.getStatus() == GuestStatus.COMPLETED && facePresent;
        var eligibilityReason = guest.getStatus() != GuestStatus.COMPLETED ? "status_not_completed"
                : !facePresent ? "face_photo_missing" : "ok";
        log.info("GUEST_SYNC_ELIGIBILITY guest_id={} status={} sync_status={} face_photo_url_present={} invited_lounge={} allowed_area_count={} eligible={} reason={}",
                id, guest.getStatus(), guest.getSyncStatus(), facePresent,
                guest.getInvitedLounge(), areasCount, eligible, eligibilityReason);
        if (guest.getStatus() != GuestStatus.COMPLETED) {
            throw new IllegalArgumentException("Visitante precisa estar completo para sincronizar.");
        }
        if (!facePresent) {
            throw new IllegalArgumentException("Visitante precisa enviar foto facial antes da sincronização.");
        }
        var oldData = snapshot(guest);
        applyAllowedAreas(guest);
        validateResolvedAllowedAreas(guest);
        log.info("manual_sync_requested person_type=GUEST person_id={} cpf={} invited_lounge={} allowed_area_ids=[{}] allowed_area_names=[{}] operator={}",
                guest.getId(), guest.getCpf(), guest.getInvitedLounge(),
                activeAllowedAreaIds(guest), activeAllowedAreaNames(guest),
                authenticatedUser());
        guest.markPendingSync();
        guestRepository.save(guest);
        auditService.record("GUEST_SYNC_REQUESTED", "Guest", guest.getId(),
                Map.of(
                        "operator", authenticatedUser(),
                        "validFrom", guest.getVisitStart(),
                        "validUntil", guest.getVisitEnd()
                ),
                oldData,
                snapshot(guest));
        eventPublisher.publishEvent(new GuestReadyForSyncEvent(guest.getId()));
        log.info("GUEST_SYNC_ENQUEUED guest_id={} cpf={} invited_lounge={} allowed_area_count={} operator={}",
                guest.getId(), guest.getCpf(), guest.getInvitedLounge(),
                guest.getAllowedAreas() == null ? 0 : guest.getAllowedAreas().size(),
                authenticatedUser());
        return GuestResponse.from(guest, null);
    }

    private void triggerGuestAutoSyncAfterRegistration(Guest guest, String source, boolean alreadyDoneOrRunning) {
        if (guest == null || guest.getId() == null) {
            return;
        }
        if (alreadyDoneOrRunning || isAutoSyncAlreadyDoneOrRunning(guest.getSyncStatus())) {
            log.info("GUEST_AUTO_SYNC_SKIPPED guest_id={} source={} sync_status={} reason=already_synced_or_syncing",
                    guest.getId(), source, guest.getSyncStatus());
            return;
        }
        try {
            log.info("GUEST_AUTO_SYNC_TRIGGERED guest_id={} source={}", guest.getId(), source);
            requestSync(guest.getId());
        } catch (Exception exception) {
            log.warn("AUTO_SYNC_FAILED_AFTER_REGISTRATION person_type=GUEST guest_id={} source={} error={}",
                    guest.getId(), source, exception.getMessage(), exception);
        }
    }

    private boolean isAutoSyncAlreadyDoneOrRunning(SyncStatus syncStatus) {
        return syncStatus == SyncStatus.SYNCED || syncStatus == SyncStatus.SYNCING;
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

    private CompletedVisitorRegistration completedVisitorRegistration(
            String fullName,
            String cpf,
            String email,
            String phone,
            String company,
            String visitReason,
            String hostName,
            LocalDate invitedDay,
            String invitedLounge,
            Instant visitStart,
            Instant visitEnd,
            MultipartFile facePhoto,
            boolean createInvite
    ) {
        validateVisitorRegistration(fullName, cpf, email, phone, invitedDay, invitedLounge, visitStart, visitEnd, facePhoto);
        var resolvedInvitedDay = resolveInvitedDay(invitedDay, visitStart);
        var guest = guestRepository.save(new Guest(
                fullName.trim(),
                CpfValidator.normalizeOrThrow(cpf),
                normalize(email),
                normalize(phone),
                normalize(company),
                normalize(visitReason),
                normalize(hostName),
                invitedAccessStart(resolvedInvitedDay),
                invitedAccessEnd(resolvedInvitedDay),
                resolvedInvitedDay,
                canonicalInvitedLounge(invitedLounge)
        ));
        applyAllowedAreas(guest);
        validateResolvedAllowedAreas(guest);
        if (createInvite) {
            createInvite(guest);
        }

        var oldData = snapshot(guest);
        var facePhotoUrl = faceStorageService.store(facePhoto, guest.getId());
        guest.completeRegistration(phone, company, facePhotoUrl);
        return new CompletedVisitorRegistration(guest, oldData, facePhotoUrl);
    }

    private void validateVisitorRegistration(String fullName, String cpf, String email, String phone,
                                             LocalDate invitedDay, String invitedLounge, Instant visitStart,
                                             Instant visitEnd, MultipartFile facePhoto) {
        if (isBlank(fullName) || isBlank(cpf) || isBlank(phone) || isBlank(invitedLounge)) {
            throw new IllegalArgumentException("Required visitor registration fields are missing.");
        }
        if (invitedDay == null && visitStart == null) {
            throw new IllegalArgumentException("Visitor invited day is required.");
        }
        if (facePhoto == null || facePhoto.isEmpty()) {
            throw new IllegalArgumentException("Face photo is required.");
        }
        CpfValidator.normalizeOrThrow(cpf);
        if (!isBlank(email) && !EMAIL.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("Email must be valid.");
        }
        if (!loungeConfig.isValid(invitedLounge)) {
            throw new IllegalArgumentException("Camarote inválido");
        }
        if (visitStart != null && visitEnd != null) {
            validateVisitWindow(visitStart, visitEnd);
        }
    }

    private void validateInvitedLounge(String invitedLounge) {
        if (!isBlank(invitedLounge) && !loungeConfig.isValid(invitedLounge)) {
            throw new IllegalArgumentException("Camarote inválido");
        }
    }

    private record CompletedVisitorRegistration(Guest guest, Map<String, Object> oldData, String facePhotoUrl) {
    }

    private LocalDate resolveInvitedDay(LocalDate invitedDay, Instant visitStart) {
        if (invitedDay != null) {
            return invitedDay;
        }
        return visitStart == null ? null : LocalDate.ofInstant(visitStart, EVENT_ZONE);
    }

    private Instant invitedAccessStart(LocalDate invitedDay) {
        return invitedDay.atTime(GUEST_ACCESS_START_TIME).atZone(EVENT_ZONE).toInstant();
    }

    private Instant invitedAccessEnd(LocalDate invitedDay) {
        return invitedDay.plusDays(1).atTime(GUEST_ACCESS_END_TIME).atZone(EVENT_ZONE).toInstant();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String canonicalInvitedLounge(String value) {
        var canonical = loungeConfig.canonicalName(value);
        return canonical == null ? normalize(value) : canonical;
    }

    private String onlyDigits(String value) {
        return CpfValidator.onlyDigits(value);
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
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", guest.getId());
        snapshot.put("fullName", guest.getFullName());
        snapshot.put("cpf", guest.getCpf());
        snapshot.put("phone", guest.getPhone());
        snapshot.put("email", guest.getEmail());
        snapshot.put("status", guest.getStatus());
        snapshot.put("visitStart", guest.getVisitStart());
        snapshot.put("visitEnd", guest.getVisitEnd());
        snapshot.put("invitedDay", guest.getInvitedDay());
        snapshot.put("invitedLounge", guest.getInvitedLounge());
        snapshot.put("syncStatus", guest.getSyncStatus());
        return snapshot;
    }
}
