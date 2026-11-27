package com.platform.notifications;

import com.platform.shared.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/** Localized platform emails in the six platform languages (pt, en, es, fr, de, it). */
@Service
public class PlatformEmails {
    private final PlatformMailer mailer;
    private final AppProperties props;

    private static final Map<String, Map<String, String[]>> T = Map.of(
            "verify", Map.of(
                    "en", new String[]{"Confirm your email", "Confirm your email address to activate your Vendocas account:", "Confirm email"},
                    "pt", new String[]{"Confirme seu e-mail", "Confirme seu endereço de e-mail para ativar sua conta Vendocas:", "Confirmar e-mail"},
                    "es", new String[]{"Confirma tu correo", "Confirma tu dirección de correo para activar tu cuenta de Vendocas:", "Confirmar correo"},
                    "fr", new String[]{"Confirmez votre e-mail", "Confirmez votre adresse e-mail pour activer votre compte Vendocas :", "Confirmer l'e-mail"},
                    "de", new String[]{"Bestätigen Sie Ihre E-Mail", "Bestätigen Sie Ihre E-Mail-Adresse, um Ihr Vendocas-Konto zu aktivieren:", "E-Mail bestätigen"},
                    "it", new String[]{"Conferma la tua e-mail", "Conferma il tuo indirizzo e-mail per attivare il tuo account Vendocas:", "Conferma e-mail"}),
            "reset", Map.of(
                    "en", new String[]{"Reset your password", "Use the link below to choose a new password. It expires in 30 minutes. If you did not ask for this, ignore this email.", "Reset password"},
                    "pt", new String[]{"Redefina sua senha", "Use o link abaixo para escolher uma nova senha. Ele expira em 30 minutos. Se não foi você, ignore este e-mail.", "Redefinir senha"},
                    "es", new String[]{"Restablece tu contraseña", "Usa el enlace para elegir una nueva contraseña. Caduca en 30 minutos. Si no lo pediste, ignora este correo.", "Restablecer contraseña"},
                    "fr", new String[]{"Réinitialisez votre mot de passe", "Utilisez le lien ci-dessous pour choisir un nouveau mot de passe. Il expire dans 30 minutes. Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail.", "Réinitialiser le mot de passe"},
                    "de", new String[]{"Passwort zurücksetzen", "Über den Link unten können Sie ein neues Passwort wählen. Er läuft in 30 Minuten ab. Wenn Sie das nicht angefordert haben, ignorieren Sie diese E-Mail.", "Passwort zurücksetzen"},
                    "it", new String[]{"Reimposta la password", "Usa il link qui sotto per scegliere una nuova password. Scade tra 30 minuti. Se non sei stato tu, ignora questa e-mail.", "Reimposta password"}),
            "exists", Map.of(
                    "en", new String[]{"Sign-up attempt", "Someone tried to create a Vendocas account with this email. If it was you, sign in or reset your password:", "Sign in"},
                    "pt", new String[]{"Tentativa de cadastro", "Alguém tentou criar uma conta Vendocas com este e-mail. Se foi você, entre ou redefina sua senha:", "Entrar"},
                    "es", new String[]{"Intento de registro", "Alguien intentó crear una cuenta de Vendocas con este correo. Si fuiste tú, inicia sesión o restablece tu contraseña:", "Iniciar sesión"},
                    "fr", new String[]{"Tentative d'inscription", "Quelqu'un a essayé de créer un compte Vendocas avec cet e-mail. Si c'était vous, connectez-vous ou réinitialisez votre mot de passe :", "Se connecter"},
                    "de", new String[]{"Registrierungsversuch", "Jemand hat versucht, mit dieser E-Mail ein Vendocas-Konto zu erstellen. Wenn Sie das waren, melden Sie sich an oder setzen Sie Ihr Passwort zurück:", "Anmelden"},
                    "it", new String[]{"Tentativo di registrazione", "Qualcuno ha provato a creare un account Vendocas con questa e-mail. Se eri tu, accedi o reimposta la password:", "Accedi"}),
            "invite", Map.of(
                    "en", new String[]{"You were invited to Vendocas", "You were invited to join an organization on Vendocas:", "Accept invitation"},
                    "pt", new String[]{"Você foi convidado para a Vendocas", "Você foi convidado a participar de uma organização na Vendocas:", "Aceitar convite"},
                    "es", new String[]{"Te invitaron a Vendocas", "Te invitaron a unirte a una organización en Vendocas:", "Aceptar invitación"},
                    "fr", new String[]{"Vous êtes invité sur Vendocas", "Vous avez été invité à rejoindre une organisation sur Vendocas :", "Accepter l'invitation"},
                    "de", new String[]{"Sie wurden zu Vendocas eingeladen", "Sie wurden eingeladen, einer Organisation auf Vendocas beizutreten:", "Einladung annehmen"},
                    "it", new String[]{"Sei stato invitato su Vendocas", "Sei stato invitato a unirti a un'organizzazione su Vendocas:", "Accetta invito"}),
            "notice", Map.of(
                    "en", new String[]{"", "", "Open dashboard"},
                    "pt", new String[]{"", "", "Abrir painel"},
                    "es", new String[]{"", "", "Abrir panel"},
                    "fr", new String[]{"", "", "Ouvrir le tableau de bord"},
                    "de", new String[]{"", "", "Dashboard öffnen"},
                    "it", new String[]{"", "", "Apri la dashboard"}));

    public PlatformEmails(PlatformMailer mailer, AppProperties props) {
        this.mailer = mailer;
        this.props = props;
    }

    public void verifyEmail(String to, String locale, String token) { send("verify", to, locale, link("/verify-email?token=" + token)); }

    public void resetPassword(String to, String locale, String token) { send("reset", to, locale, link("/reset-password?token=" + token)); }

    public void accountExists(String to, String locale) { send("exists", to, locale, link("/login")); }

    public void invitation(String to, String locale, String orgName, String token) {
        send("invite", to, locale, link("/invitations/accept?token=" + token), orgName);
    }

    /** Operational notice (payment failed, low balance, channel degraded…), body already localized by caller. */
    public void notice(String to, String locale, String subject, String body) {
        String[] t = pick("notice", locale);
        String url = link("/app");
        mailer.send(to, subject, body + "\n\n" + url, html(subject, body, t[2], url));
    }

    private void send(String key, String to, String locale, String url, String... extra) {
        String[] t = pick(key, locale);
        String intro = extra.length > 0 ? t[1] + " " + extra[0] : t[1];
        mailer.send(to, t[0], intro + "\n\n" + url, html(t[0], intro, t[2], url));
    }

    private String[] pick(String key, String locale) {
        String lang = com.platform.shared.i18n.Languages.resolve(null, locale);
        return T.get(key).getOrDefault(lang, T.get(key).get("en"));
    }

    private String link(String path) { return props.publicUrl() + path; }

    private static String html(String title, String body, String cta, String url) {
        return """
                <!doctype html><html><body style="font-family:system-ui,sans-serif;background:#f6f7f9;padding:24px">
                <table role="presentation" style="max-width:560px;margin:auto;background:#fff;border-radius:12px;padding:32px">
                <tr><td><h1 style="font-size:20px;margin:0 0 16px">%s</h1><p style="line-height:1.5;color:#333">%s</p>
                <p><a href="%s" style="display:inline-block;background:#4f46e5;color:#fff;padding:12px 20px;border-radius:8px;text-decoration:none">%s</a></p>
                <p style="font-size:12px;color:#777">Vendocas</p></td></tr></table></body></html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(body), HtmlUtils.htmlEscape(url), HtmlUtils.htmlEscape(cta));
    }
}
