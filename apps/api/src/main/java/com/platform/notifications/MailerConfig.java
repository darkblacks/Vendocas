package com.platform.notifications;

import com.platform.shared.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.util.Arrays;

@Configuration
public class MailerConfig {
    private static final Logger log = LoggerFactory.getLogger(MailerConfig.class);

    @Bean
    PlatformMailer platformMailer(AppProperties props, Environment env, org.springframework.beans.factory.ObjectProvider<SesV2Client> ses) {
        String mode = props.mail().mode();
        if ("log".equals(mode)) {
            if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException("app.mail.mode=log is not allowed with the prod profile");
            }
            // Development only: no email is sent; the content (incl. links) goes to the application log.
            return (to, subject, text, html) -> log.info("[DEV MAIL — not sent] to={} subject={}\n{}", to, subject, text);
        }
        SesV2Client client = ses.getIfAvailable();
        if (client == null) throw new IllegalStateException("app.mail.mode=ses requires AWS configuration (AWS_REGION)");
        String from = props.mail().fromName() + " <" + props.mail().fromAddress() + ">";
        return (to, subject, text, html) -> client.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(to).build())
                .configurationSetName(props.aws().sesConfigurationSet())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder()
                                .text(Content.builder().data(text).charset("UTF-8").build())
                                .html(Content.builder().data(html).charset("UTF-8").build()).build())
                        .build()).build())
                .build());
    }
}
