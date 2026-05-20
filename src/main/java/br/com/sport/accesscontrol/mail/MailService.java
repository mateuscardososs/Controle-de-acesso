package br.com.sport.accesscontrol.mail;

import br.com.sport.accesscontrol.guests.Guest;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;

    public MailService(JavaMailSender mailSender,
                       @Value("${app.mail.enabled:false}") boolean enabled,
                       @Value("${app.mail.from:no-reply@empresa.local}") String from) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
    }

    public MailDeliveryResult sendGuestInvite(Guest guest, String inviteUrl) {
        return send(guest, "Convite de visitante - Controle de Acesso", MailTemplates.guestInvite(guest, inviteUrl));
    }

    public MailDeliveryResult sendGuestInviteResent(Guest guest, String inviteUrl) {
        return send(guest, "Reenvio do convite de visitante - Controle de Acesso", MailTemplates.guestInvite(guest, inviteUrl));
    }

    public MailDeliveryResult sendGuestRegistrationCompleted(Guest guest) {
        return send(guest, "Cadastro de visitante concluído - Controle de Acesso", MailTemplates.guestRegistrationCompleted(guest));
    }

    public MailDeliveryResult sendGuestAccessApproved(Guest guest) {
        return send(guest, "Acesso de visitante liberado", MailTemplates.guestAccessApproved(guest));
    }

    private MailDeliveryResult send(Guest guest, String subject, String html) {
        if (!enabled) {
            log.info("mail_delivery_skipped enabled=false guest_id={} email={}", guest.getId(), guest.getEmail());
            return MailDeliveryResult.skipped("Envio de e-mail desabilitado neste ambiente.");
        }
        if (guest.getEmail() == null || guest.getEmail().isBlank()) {
            log.info("mail_delivery_skipped missing_email guest_id={}", guest.getId());
            return MailDeliveryResult.skipped("E-mail do visitante não informado.");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(guest.getEmail());
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("mail_delivery_sent guest_id={} email={}", guest.getId(), guest.getEmail());
            return MailDeliveryResult.delivered();
        } catch (MailException | MessagingException exception) {
            log.warn("mail_delivery_failed guest_id={} email={} reason={}", guest.getId(), guest.getEmail(), exception.getMessage());
            return MailDeliveryResult.failed(exception.getMessage());
        }
    }
}
