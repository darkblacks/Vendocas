package com.platform.storage;

import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

/** S3-compatible object storage (Cloudflare R2, Amazon S3, MinIO). Bucket is private; access via presigned URLs. */
@Service
public class StorageService {
    private final AppProperties props;
    private volatile S3Client client;
    private volatile S3Presigner presigner;

    public StorageService(AppProperties props) { this.props = props; }

    public boolean configured() { return props.storage() != null && props.storage().configured(); }

    private void init() {
        if (!configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "File storage is not configured (STORAGE_BUCKET)");
        if (client != null) return;
        synchronized (this) {
            if (client != null) return;
            var s = props.storage();
            var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(s.accessKey(), s.secretKey()));
            var cfg = S3Configuration.builder().pathStyleAccessEnabled(true).build();
            var b = S3Client.builder().region(Region.of(s.region())).credentialsProvider(creds).serviceConfiguration(cfg);
            var p = S3Presigner.builder().region(Region.of(s.region())).credentialsProvider(creds).serviceConfiguration(cfg);
            if (s.endpoint() != null && !s.endpoint().isBlank()) {
                b.endpointOverride(URI.create(s.endpoint()));
                p.endpointOverride(URI.create(s.endpoint()));
            }
            presigner = p.build();
            client = b.build();
        }
    }

    public void put(String key, byte[] content, String contentType) {
        init();
        client.putObject(PutObjectRequest.builder().bucket(props.storage().bucket()).key(key).contentType(contentType)
                .serverSideEncryption(props.storage().endpoint() == null || props.storage().endpoint().isBlank() ? "AES256" : null).build(),
                RequestBody.fromBytes(content));
    }

    public void delete(String key) {
        init();
        client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(props.storage().bucket()).key(key).build());
    }

    public String presignedGet(String key, Duration ttl) {
        init();
        return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(props.storage().bucket()).key(key).build()).build()).url().toString();
    }
}
