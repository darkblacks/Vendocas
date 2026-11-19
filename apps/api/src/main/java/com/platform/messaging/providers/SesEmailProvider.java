package com.platform.messaging.providers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.util.ArrayList;
import java.util.List;

/** Amazon SES v2. Sender identity = the organization's verified domain or the platform-managed subdomain. */
@Component
public class SesEmailProvider implements MessageProvider {
    private final ObjectProvider<SesV2Client> ses;
    private final com.platform.shared.AppProperties props;

    public SesEmailProvider(ObjectProvider<SesV2Client> ses, com.platform.shared.AppProperties props) {
        this.ses = ses;
        this.props = props;
    }

    @Override public String provider() { return "SES"; }
    @Override public String channel() { return "EMAIL"; }
    @Override public boolean configured() { return ses.getIfAvailable() != null; }

    @Override
    public Result send(Outbound m, Connection c) {
        SesV2Client client = ses.getIfAvailable();
        if (client == null) return Result.permanent("NOT_CONFIGURED", "SES not configured");
        List<MessageHeader> headers = new ArrayList<>();
        if (m.headers() != null) m.headers().forEach((k, v) -> headers.add(MessageHeader.builder().name(k).value(v).build()));
        try {
            var req = SendEmailRequest.builder()
                    .fromEmailAddress(quoteName(m.fromName()) + " <" + m.from() + ">")
                    .destination(Destination.builder().toAddresses(m.to()).build())
                    .replyToAddresses(m.replyTo() == null ? List.of() : List.of(m.replyTo()))
                    .configurationSetName(blankToNull(props.aws().sesConfigurationSet()))
                    .emailTags(MessageTag.builder().name("vd_message").value(m.messageId().toString()).build())
                    .content(EmailContent.builder().simple(Message.builder()
                            .subject(Content.builder().data(m.subject()).charset("UTF-8").build())
                            .body(Body.builder().text(Content.builder().data(m.text()).charset("UTF-8").build())
                                    .html(m.html() == null ? null : Content.builder().data(m.html()).charset("UTF-8").build()).build())
                            .headers(headers).build()).build())
                    .build();
            return Result.accepted(client.sendEmail(req).messageId());
        } catch (TooManyRequestsException | LimitExceededException e) {
            return Result.retryable("THROTTLED", e.getMessage());
        } catch (MessageRejectedException | MailFromDomainNotVerifiedException | NotFoundException | BadRequestException e) {
            return Result.permanent(e.getClass().getSimpleName(), e.getMessage());
        } catch (AccountSuspendedException | SendingPausedException e) {
            return new Result(Outcome.RETRYABLE, null, "SENDING_PAUSED", e.getMessage(), false, false);
        } catch (SdkClientException e) {
            return Result.retryable("NETWORK", e.getMessage());
        } catch (SesV2Exception e) {
            return e.statusCode() >= 500 ? Result.retryable("SES_" + e.statusCode(), e.getMessage()) : Result.permanent("SES_" + e.statusCode(), e.getMessage());
        }
    }

    private static String quoteName(String n) {
        String clean = n == null ? "" : n.replace("\"", "").replace("\r", "").replace("\n", "");
        return "\"" + clean + "\"";
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
