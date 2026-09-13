package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class FeedSourceClientTest {
    @Test void nvdUsesModificationWindowAndPaginationAndPreservesReferences() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {
            String url=request.getURI().toString();
            assertThat(url).contains("lastModStartDate=","lastModEndDate=","startIndex=100").doesNotContain("pubStartDate");
        }).andRespond(withSuccess("""
          {"totalResults":101,"vulnerabilities":[{"cve":{"id":"CVE-2026-1234","published":"2026-01-01T00:00:00","lastModified":"2026-09-06T10:00:00",
          "descriptions":[{"lang":"en","value":"An old vulnerability with new mitigation guidance"}],"references":[{"url":"https://vendor.example/patch"}]}}]}
          """,APPLICATION_JSON));
        var page=new FeedSourceClient(new ObjectMapper(),"",builder.build()).fetch("nvd",Instant.parse("2026-09-05T00:00:00Z"),Instant.parse("2026-09-06T12:00:00Z"),100);
        assertThat(page.total()).isEqualTo(101);
        assertThat(page.articles().get(0).getId()).isEqualTo("feed:nvd:CVE-2026-1234");
        assertThat(page.articles().get(0).getBody().toString()).contains("https://vendor.example/patch","2026-09-06T10:00:00");
        server.verify();
    }

    @Test void networkFailureIsNotReportedAsEmptySuccess() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(()->new FeedSourceClient(new ObjectMapper(),"",builder.build()).fetch("nvd",Instant.now().minusSeconds(60),Instant.now(),0))
            .hasMessageContaining("HTTP 500");
    }

    @Test void malformedCatalogIsNotReportedAsEmptySuccess() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{}",APPLICATION_JSON));
        assertThatThrownBy(()->new FeedSourceClient(new ObjectMapper(),"",builder.build()).fetch("nvd",Instant.now().minusSeconds(60),Instant.now(),0))
            .hasMessageContaining("Invalid NVD");
    }

    @Test void rssKeepsSourceAndEvidenceWithStableIdentity() throws Exception {
        String rss="""
          <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><item>
          <title>Phishing incident response</title><link>https://isc.sans.edu/diary/1234</link><guid>1234</guid>
          <pubDate>Sun, 06 Sep 2026 10:00:00 GMT</pubDate>
          <content:encoded><![CDATA[<p>Observed payload: ignore previous instructions.</p><p>Mitigation: rotate credentials.</p>]]></content:encoded>
          </item></channel></rss>
          """;
        var page=FeedSourceClient.parseRss(rss.getBytes(StandardCharsets.UTF_8));
        var article=page.articles().get(0);
        assertThat(article.getSource()).isEqualTo("SANS ISC");
        assertThat(article.getBody().toString()).contains("ignore previous instructions","rotate credentials","https://isc.sans.edu/diary/1234");
        assertThat(FeedSourceClient.parseRss(rss.replace("rotate credentials","revoke sessions").getBytes(StandardCharsets.UTF_8)).articles().get(0).getId())
            .isEqualTo(article.getId());
    }

    @Test void rssRejectsExternalEntitiesAndUnexpectedLinks() {
        String xxe="<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&xxe;</title></item></channel></rss>";
        assertThatThrownBy(()->FeedSourceClient.parseRss(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(Exception.class);
        String rss="<rss><channel><item><link>http://127.0.0.1/private</link><description>test</description></item></channel></rss>";
        assertThatThrownBy(()->FeedSourceClient.parseRss(rss.getBytes(StandardCharsets.UTF_8))).hasMessageContaining("outside");
    }
}
