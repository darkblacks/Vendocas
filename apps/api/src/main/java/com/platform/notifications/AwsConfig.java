package com.platform.notifications;

import com.platform.shared.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/** SES client exists only when AWS_REGION is configured. Credentials: explicit keys or the default chain (IAM role). */
@Configuration
public class AwsConfig {
    @Bean
    @ConditionalOnExpression("'${app.aws.region:}' != ''")
    SesV2Client sesV2Client(AppProperties props) {
        return SesV2Client.builder().region(Region.of(props.aws().region())).credentialsProvider(credentials(props)).build();
    }

    static AwsCredentialsProvider credentials(AppProperties props) {
        if (props.aws().accessKeyId() != null && !props.aws().accessKeyId().isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.aws().accessKeyId(), props.aws().secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
