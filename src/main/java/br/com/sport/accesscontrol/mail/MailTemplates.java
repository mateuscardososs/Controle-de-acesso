package br.com.sport.accesscontrol.mail;

import br.com.sport.accesscontrol.guests.Guest;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class MailTemplates {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Recife"));

    private MailTemplates() {
    }

    static String guestInvite(Guest guest, String inviteUrl) {
        return layout(
                "Complete seu cadastro de visitante",
                """
                        <p>Olá, <strong>%s</strong>.</p>
                        <p>Você foi convidado para acessar uma área controlada.</p>
                        <div class="panel">
                          <p><strong>Visita:</strong> %s até %s</p>
                          <p><strong>Responsável:</strong> %s</p>
                          <p><strong>Motivo:</strong> %s</p>
                        </div>
                        <p>Para agilizar sua entrada, complete seu cadastro e envie sua foto facial pelo link seguro abaixo.</p>
                        <p><a class="button" href="%s">Completar cadastro</a></p>
                        <p class="muted">Este link expira conforme a política de convites do sistema. Não encaminhe este e-mail.</p>
                        """.formatted(
                        escape(guest.getFullName()),
                        DATE_TIME.format(guest.getVisitStart()),
                        DATE_TIME.format(guest.getVisitEnd()),
                        escape(guest.getHostName()),
                        escape(guest.getVisitReason()),
                        inviteUrl
                )
        );
    }

    static String guestRegistrationCompleted(Guest guest) {
        return layout(
                "Cadastro de visitante concluído",
                """
                        <p>Olá, <strong>%s</strong>.</p>
                        <p>Seu cadastro de visitante foi concluído com sucesso.</p>
                        <div class="panel">
                          <p><strong>Visita:</strong> %s até %s</p>
                          <p><strong>Responsável:</strong> %s</p>
                        </div>
                        <p>Apresente-se na portaria no horário combinado.</p>
                        """.formatted(
                        escape(guest.getFullName()),
                        DATE_TIME.format(guest.getVisitStart()),
                        DATE_TIME.format(guest.getVisitEnd()),
                        escape(guest.getHostName())
                )
        );
    }

    static String guestAccessApproved(Guest guest) {
        return layout(
                "Acesso de visitante liberado",
                """
                        <p>Olá, <strong>%s</strong>. Seu cadastro foi aprovado e seu acesso foi liberado para o período informado.</p>
                        <div class="panel">
                          <p><strong>Início da visita:</strong> %s</p>
                          <p><strong>Fim da visita:</strong> %s</p>
                          <p><strong>Empresa:</strong> %s</p>
                          <p><strong>Responsável:</strong> %s</p>
                        </div>
                        <p>Compareça à portaria ou recepção no horário combinado para validação de acesso.</p>
                        """.formatted(
                        escape(guest.getFullName()),
                        DATE_TIME.format(guest.getVisitStart()),
                        DATE_TIME.format(guest.getVisitEnd()),
                        escape(guest.getCompany()),
                        escape(guest.getHostName())
                )
        );
    }

    private static String layout(String title, String body) {
        return """
                <!doctype html>
                <html lang="pt-BR">
                <head>
                  <meta charset="utf-8">
                  <style>
                    body { margin:0; background:#f3f4f6; font-family:Arial,sans-serif; color:#111827; }
                    .wrap { max-width:640px; margin:0 auto; padding:32px 18px; }
                    .card { background:#ffffff; border:1px solid #e5e7eb; border-radius:16px; overflow:hidden; box-shadow:0 10px 30px rgba(15,23,42,.08); }
                    .head { background:#111827; color:#fff; padding:28px; border-top:5px solid #8f1218; }
                    .head p { margin:0; color:#fecaca; text-transform:uppercase; font-size:12px; font-weight:700; letter-spacing:.08em; }
                    .head h1 { margin:8px 0 0; font-size:24px; line-height:1.25; }
                    .content { padding:28px; font-size:15px; line-height:1.65; }
                    .panel { background:#f8fafc; border:1px solid #e5e7eb; border-radius:12px; padding:16px; margin:18px 0; }
                    .panel p { margin:6px 0; }
                    .button { display:inline-block; background:#8f1218; color:#fff !important; text-decoration:none; padding:12px 18px; border-radius:10px; font-weight:700; }
                    .muted { color:#64748b; font-size:13px; }
                    .foot { color:#64748b; font-size:12px; padding:18px 28px 26px; border-top:1px solid #f1f5f9; }
                  </style>
                </head>
                <body>
                  <div class="wrap">
                    <div class="card">
                      <div class="head">
                        <p>Controle de Acesso</p>
                        <h1>%s</h1>
                      </div>
                      <div class="content">%s</div>
                      <div class="foot">Controle de Acesso · Mensagem transacional automática</div>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(escape(title), body);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
