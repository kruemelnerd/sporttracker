package local.runningdashboard;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GitHubCompareClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;

    @Autowired
    public GitHubCompareClient(
            @Value("${running-dashboard.github.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${running-dashboard.github.read-timeout:PT3S}") Duration readTimeout) {
        this(RestClient.builder().requestFactory(GhcrRegistryClient.requestFactory(connectTimeout, readTimeout)));
    }

    GitHubCompareClient(RestClient.Builder builder) {
        client = builder.baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "PACEBASE-version-check")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28").build();
    }

    public Comparison compare(String installedCommit, String latestCommit) {
        if (!VersionStatus.validCommit(installedCommit) || !VersionStatus.validCommit(latestCommit)) return Comparison.UNAVAILABLE;
        if (installedCommit.equals(latestCommit)) return Comparison.NOT_AHEAD;
        try {
            // Summary fields prove ancestry; one bounded page is enough, never follow links or redirects.
            return client.get().uri("/repos/kruemelnerd/sporttracker/compare/" + installedCommit + "..." + latestCommit + "?per_page=1")
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) return Comparison.UNAVAILABLE;
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (bytes.length > MAX_RESPONSE_BYTES) return Comparison.UNAVAILABLE;
                        var body = JSON.readTree(bytes);
                        if (body == null || !body.isObject()) return Comparison.UNAVAILABLE;
                        String status = body.path("status").asString("");
                        if (status.equals("behind") || status.equals("diverged") || status.equals("identical")) return Comparison.NOT_AHEAD;
                        if (!status.equals("ahead")) return Comparison.UNAVAILABLE;
                        return installedCommit.equals(body.path("base_commit").path("sha").asString())
                                && installedCommit.equals(body.path("merge_base_commit").path("sha").asString())
                                && body.path("ahead_by").isIntegralNumber() && body.path("ahead_by").asLong() > 0
                                && body.path("behind_by").isIntegralNumber() && body.path("behind_by").asLong() == 0
                                ? Comparison.AHEAD : Comparison.UNAVAILABLE;
                    });
        } catch (RestClientException | JacksonException | IllegalArgumentException failure) {
            return Comparison.UNAVAILABLE;
        }
    }

    public enum Comparison { AHEAD, NOT_AHEAD, UNAVAILABLE }
}
