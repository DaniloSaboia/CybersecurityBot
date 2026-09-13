package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleSection;
import com.sentinel.kms.kb.TrustedArticleSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fixed official sources only. Fetch errors are errors, never a successful empty feed. */
@Component
public class FeedSourceClient {
    public static final String NVD = "https://services.nvd.nist.gov/rest/json/cves/2.0";
    public static final String KEV = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";
    public static final String KEV_MIRROR = "https://raw.githubusercontent.com/cisagov/kev-data/develop/known_exploited_vulnerabilities.json";
    public static final String SANS = "https://isc.sans.edu/rssfeed_full.xml";
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String nvdKey;

    @org.springframework.beans.factory.annotation.Autowired
    public FeedSourceClient(ObjectMapper mapper, @Value("${app.nvd.api-key:}") String nvdKey) {
        this(mapper, nvdKey, defaultClient());
    }

    FeedSourceClient(ObjectMapper mapper, String nvdKey, RestClient client) {
        this.mapper = mapper;
        this.nvdKey = nvdKey == null ? "" : nvdKey;
        this.client = client;
    }

    private static RestClient defaultClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory).build();
    }

    public record Page(List<Article> articles, int total, String sourceUrl) {}

    public Page fetch(String id, Instant from, Instant to, int offset) {
        try {
            return switch (id) {
                case "nvd" -> nvd(from, to, offset);
                case "kev" -> kev();
                case "sans" -> sans();
                default -> throw new IllegalArgumentException("Unsupported daily feed");
            };
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Source response could not be parsed", ex);
        }
    }

    private byte[] get(String url, boolean withKey) {
        var request = client.get().uri(url).header("User-Agent", "SentinelKMS-Feeds/1.0");
        if (withKey && !nvdKey.isBlank()) request.header("apiKey", nvdKey);
        return request.exchange((req, res) -> {
            if (!res.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Source returned HTTP " + res.getStatusCode().value());
            }
            byte[] bytes = res.getBody().readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalStateException("Source response size invalid");
            return bytes;
        });
    }

    private Page nvd(Instant from, Instant to, int offset) throws Exception {
        var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
        String url = UriComponentsBuilder.fromUriString(NVD)
                .queryParam("lastModStartDate", fmt.format(from)).queryParam("lastModEndDate", fmt.format(to))
                .queryParam("startIndex", offset).queryParam("resultsPerPage", 100).build().toUriString();
        JsonNode root = mapper.readTree(get(url, true));
        if (!root.path("vulnerabilities").isArray() || !root.path("totalResults").canConvertToInt())
            throw new IllegalStateException("Invalid NVD response");
        var articles = new ArrayList<Article>();
        for (JsonNode wrapper : root.path("vulnerabilities")) {
            JsonNode c = wrapper.path("cve");
            String id = required(c, "id");
            String description = "";
            for (JsonNode d : c.path("descriptions")) {
                if (description.isEmpty() || "en".equals(d.path("lang").asText())) description = d.path("value").asText();
                if ("en".equals(d.path("lang").asText())) break;
            }
            if (description.isBlank()) throw new IllegalStateException("NVD record has no description");
            Double score = null;
            for (String v : List.of("cvssMetricV40", "cvssMetricV31", "cvssMetricV30", "cvssMetricV2")) {
                var metrics = c.path("metrics").path(v);
                if (metrics.isArray() && !metrics.isEmpty() && metrics.get(0).path("cvssData").path("baseScore").isNumber()) {
                    score = metrics.get(0).path("cvssData").path("baseScore").asDouble(); break;
                }
            }
            String sourceUrl = "https://nvd.nist.gov/vuln/detail/" + id;
            var article = article("nvd", id, id + " — " + shortText(description, 100), description,
                    "NVD API", sourceUrl, c.path("published").asText(), c.path("lastModified").asText(), List.of("CVE", id, "NVD"));
            article.setCvss(score == null ? null : score.toString());
            article.setSeverity(score == null ? "info" : score >= 9 ? "critical" : score >= 7 ? "high" : score >= 4 ? "medium" : "low");
            article.getBody().add(new ArticleSection("Record status", c.path("vulnStatus").asText("Not supplied")));
            var references = new TreeSet<String>();
            for (var ref : c.path("references")) if (safeUrl(ref.path("url").asText())) references.add(ref.path("url").asText());
            if (!references.isEmpty()) article.getBody().add(new ArticleSection("Published references", String.join("\n", references)));
            articles.add(article);
        }
        if (articles.isEmpty() && offset < root.path("totalResults").asInt()) throw new IllegalStateException("NVD returned an incomplete page");
        return new Page(articles, root.path("totalResults").asInt(), NVD);
    }

    private Page kev() throws Exception {
        byte[] bytes;
        String endpoint = KEV;
        try { bytes = get(KEV, false); }
        catch (RuntimeException unavailable) { endpoint = KEV_MIRROR; bytes = get(KEV_MIRROR, false); }
        JsonNode root = mapper.readTree(bytes);
        if (!root.path("vulnerabilities").isArray()) throw new IllegalStateException("Invalid KEV catalog");
        var articles = new ArrayList<Article>();
        for (var c : root.path("vulnerabilities")) {
            String id = required(c, "cveID");
            String description = required(c, "shortDescription");
            String action = required(c, "requiredAction");
            var article = article("kev", id, id + " — " + required(c, "vulnerabilityName"), description,
                    "CISA KEV", "https://www.cisa.gov/known-exploited-vulnerabilities-catalog", c.path("dateAdded").asText(),
                    c.path("dateAdded").asText(), List.of("CVE", id, "KEV", c.path("vendorProject").asText(), c.path("product").asText()));
            article.setSeverity("high");
            article.getBody().add(new ArticleSection("CISA required action", action));
            article.getBody().add(new ArticleSection("Exploitation and deadline", "Known exploited: yes\nRansomware campaign use: "
                    + c.path("knownRansomwareCampaignUse").asText("Unknown") + "\nCISA due date: " + c.path("dueDate").asText()));
            article.getBody().add(new ArticleSection("CISA references", c.path("notes").asText("")));
            articles.add(article);
        }
        articles.sort(Comparator.comparing(Article::getUpdatedOn, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Article::getId));
        return new Page(articles, articles.size(), endpoint);
    }

    private Page sans() throws Exception { return parseRss(get(SANS, false)); }

    static Page parseRss(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if (!"rss".equals(doc.getDocumentElement().getTagName()) || doc.getElementsByTagName("channel").getLength() != 1)
            throw new IllegalStateException("Invalid advisory RSS feed");
        var articles = new ArrayList<Article>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            var item = (Element) items.item(i);
            String link = text(item, "link");
            if (!safeUrl(link) || !"isc.sans.edu".equalsIgnoreCase(URI.create(link).getHost()))
                throw new IllegalStateException("Advisory link is outside the official source");
            String guid = text(item, "guid");
            String raw = text(item, "content:encoded");
            if (raw.isBlank()) raw = text(item, "description");
            if (raw.isBlank() || raw.length() >= 200_000) throw new IllegalStateException("Advisory is empty or exceeds extraction limit");
            String content = new TextExtractor().extract(raw.getBytes(StandardCharsets.UTF_8), "advisory.html");
            if (content.isBlank() || content.length() >= 200_000) throw new IllegalStateException("Advisory text extraction incomplete");
            articles.add(article("sans", digest(guid.isBlank() ? link : guid), text(item,"title"), content,
                    "SANS ISC", link, text(item, "pubDate"), text(item, "pubDate"), List.of("SANS", "incident-report")));
        }
        return new Page(articles, articles.size(), SANS);
    }

    private static Article article(String connector, String key, String title, String content, String source,
                                   String url, String published, String modified, List<String> tags) {
        var a = new Article();
        a.setId("feed:" + connector + ":" + key);
        a.setTitle(title); a.setCategoryId("advisories"); a.setSeverity("info");
        a.setSnippet(shortText(content,180)); a.setSource(source); a.setAuthor("connector:" + connector);
        a.setConfidence("Source report"); a.setUpdatedOn(date(modified)); a.setTags(tags);
        a.setRelated(key.startsWith("CVE-") ? List.of("feed:nvd:"+key, "feed:kev:"+key).stream().filter(id->!id.equals(a.getId())).toList() : List.of());
        var sections = new ArrayList<ArticleSection>();
        sections.add(new ArticleSection("Source and dates", "Source URL: " + url + "\nPublished: " + published + "\nSource modified: " + modified));
        var chunks = new Chunker().chunk(content, 1200, 150);
        for (int i=0;i<chunks.size();i++) sections.add(new ArticleSection("Source text " + (i+1), chunks.get(i)));
        a.setBody(sections);
        return a;
    }

    public static TrustedArticleSource source(String id) { return TrustedArticleSource.valueOf(id.toUpperCase(Locale.ROOT)); }
    private static String required(JsonNode node, String key) {
        String value = node.path(key).asText("");
        if (value.isBlank()) throw new IllegalStateException("Source record missing " + key);
        return value;
    }
    private static String text(Element e,String tag) { var nodes=e.getElementsByTagName(tag); return nodes.getLength()==0 ? "" : nodes.item(0).getTextContent().trim(); }
    private static String shortText(String s,int length) { return s.length()<=length?s:s.substring(0,length)+"…"; }
    private static boolean safeUrl(String url) { try { var u=URI.create(url); return "https".equals(u.getScheme()) && u.getHost()!=null && u.getUserInfo()==null; } catch(Exception e){return false;} }
    private static LocalDate date(String value) {
        try { return LocalDate.parse(value.substring(0,10)); } catch(Exception ignored) {}
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate(); } catch(Exception ignored) { return null; }
    }
    private static String digest(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
