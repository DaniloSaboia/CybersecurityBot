package com.sentinel.kms.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IocExtractorTest {

    private final IocExtractor extractor = new IocExtractor();

    @Test
    void extractsIpDomainHashAndEmail() {
        List<IocExtractor.Ioc> iocs = extractor.extract(
                "Check 185.220.101.4 and evil-site.ru plus hash "
                        + "a".repeat(64) + " from bob@evil-site.ru");

        assertThat(iocs).extracting(IocExtractor.Ioc::type).contains("ip", "domain", "sha256", "email");
        assertThat(iocs).extracting(IocExtractor.Ioc::value)
                .contains("185.220.101.4", "evil-site.ru", "bob@evil-site.ru");
    }

    @Test
    void hexLengthDistinguishesHashTypes() {
        String sha1 = "A".repeat(40);
        String md5 = "b".repeat(32);
        List<IocExtractor.Ioc> iocs = extractor.extract("seen " + sha1 + " and " + md5);
        assertThat(iocs).extracting(IocExtractor.Ioc::type).containsExactlyInAnyOrder("sha1", "md5");
    }

    @Test
    void rejectsInvalidIpv4Octets() {
        // 999 exceeds the octet range, so nothing should be flagged as an IP.
        List<IocExtractor.Ioc> iocs = extractor.extract("version 999.12.31.99 shipped");
        assertThat(iocs).isEmpty();
    }

    @Test
    void domainInsideEmailIsNotDoubleCounted() {
        List<IocExtractor.Ioc> iocs = extractor.extract("alice@corp-io.example.com wrote");
        assertThat(iocs).hasSize(1);
        assertThat(iocs.get(0).type()).isEqualTo("email");
    }

    @Test
    void plainTextYieldsNothing() {
        assertThat(extractor.extract("What is the key rotation playbook?")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void valuesAreLowercasedAndDeduped() {
        List<IocExtractor.Ioc> iocs = extractor.extract("EVIL-Site.RU and evil-site.ru");
        assertThat(iocs).hasSize(1);
        assertThat(iocs.get(0).value()).isEqualTo("evil-site.ru");
    }
}
