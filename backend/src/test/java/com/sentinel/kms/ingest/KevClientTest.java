package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KevClientTest {

    private static final String FEED = """
            {
              "title": "CISA Catalog of Known Exploited Vulnerabilities",
              "count": 2,
              "vulnerabilities": [
                {
                  "cveID": "CVE-2024-21413",
                  "vendorProject": "Microsoft",
                  "product": "Outlook",
                  "vulnerabilityName": "Microsoft Outlook RCE",
                  "dateAdded": "2024-02-13",
                  "dueDate": "2024-03-05",
                  "knownRansomwareCampaignUse": "Known",
                  "requiredAction": "Apply updates",
                  "notes": "Moniker link"
                },
                {
                  "cveID": "CVE-2023-20273",
                  "vendorProject": "Cisco",
                  "product": "IOS XE",
                  "vulnerabilityName": "Cisco IOS XE command injection",
                  "dateAdded": "2023-10-20",
                  "dueDate": "2023-11-09",
                  "knownRansomwareCampaignUse": "Unknown",
                  "requiredAction": "Apply updates",
                  "notes": "Web UI"
                }
              ]
            }
            """;

    @Test
    void parsesFeedEntries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                .andExpect(method(GET))
                .andRespond(withSuccess(FEED, APPLICATION_JSON));

        List<KevClient.KevEntry> entries = new KevClient(builder).fetch(10);

        assertThat(entries).hasSize(2);
        KevClient.KevEntry first = entries.get(0);
        assertThat(first.cveId()).isEqualTo("CVE-2024-21413");
        assertThat(first.vendorProject()).isEqualTo("Microsoft");
        assertThat(first.product()).isEqualTo("Outlook");
        assertThat(first.ransomwareUse()).isTrue();
        assertThat(first.dueDate()).isEqualTo("2024-03-05");
        assertThat(entries.get(1).ransomwareUse()).isFalse();
    }

    @Test
    void serverErrorsDegradeToEmptyList() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                .andRespond(withServerError());

        assertThat(new KevClient(builder).fetch(10)).isEmpty();
        server.verify();
    }

    @Test
    void maxCapsEntries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(
                        "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                .andRespond(withSuccess(FEED, APPLICATION_JSON));

        assertThat(new KevClient(builder).fetch(1)).hasSize(1);
    }
}
