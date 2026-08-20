package com.hack.segmentrec.service.llm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.hack.segmentrec.config.SegmentRecProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Application Default Credentials for Vertex AI, so access is controlled by IAM rather than
 * a shared API key. ADC resolves, in order, {@code GOOGLE_APPLICATION_CREDENTIALS}, the gcloud
 * user credentials file, and finally the attached service account on GCE/GKE/Cloud Run.
 *
 * <p>The caller needs {@code roles/aiplatform.user} on the target project. Credentials are
 * resolved once and cached; changing them requires a restart.
 */
@Component
public class VertexAiCredentials {

    private static final Logger log = LoggerFactory.getLogger(VertexAiCredentials.class);
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final SegmentRecProperties properties;

    private boolean resolved;
    private GoogleCredentials credentials;
    private String projectId;
    private String failure;

    public VertexAiCredentials(SegmentRecProperties properties) {
        this.properties = properties;
    }

    /** True when ADC and a project id are both usable, so callers can degrade instead of failing. */
    public boolean isAvailable() {
        try {
            resolve();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public String getProjectId() throws IOException {
        resolve();
        return projectId;
    }

    /**
     * Authorization headers for {@code uri}, refreshing the access token when it has expired.
     */
    public Map<String, List<String>> requestMetadata(URI uri) throws IOException {
        resolve();
        return credentials.getRequestMetadata(uri);
    }

    private synchronized void resolve() throws IOException {
        if (resolved) {
            if (failure != null) {
                throw new IOException(failure);
            }
            return;
        }
        resolved = true;
        try {
            GoogleCredentials adc = GoogleCredentials.getApplicationDefault();
            if (adc.createScopedRequired()) {
                adc = adc.createScoped(Collections.singletonList(CLOUD_PLATFORM_SCOPE));
            }
            String project = resolveProjectId(adc);
            if (project == null || project.isBlank()) {
                throw new IOException("could not determine the Google Cloud project; "
                        + "set segment-rec.gemini.project-id or GOOGLE_CLOUD_PROJECT");
            }
            this.credentials = adc;
            this.projectId = project;
            log.info("Vertex AI credentials resolved via ADC for project '{}'", project);
        } catch (IOException e) {
            this.failure = "Vertex AI ADC unavailable: " + e.getMessage();
            log.warn("{}. Gemini features will fall back until credentials are configured.", failure);
            throw new IOException(failure, e);
        }
    }

    private String resolveProjectId(GoogleCredentials adc) {
        String configured = properties.getGemini().getProjectId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (adc instanceof ServiceAccountCredentials) {
            String fromServiceAccount = ((ServiceAccountCredentials) adc).getProjectId();
            if (fromServiceAccount != null && !fromServiceAccount.isBlank()) {
                return fromServiceAccount;
            }
        }
        return adc.getQuotaProjectId();
    }
}
