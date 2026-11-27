package com.platform.unit;

import com.platform.shared.i18n.Languages;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguagesTest {
    @Test
    void oneLanguagePerCountry() {
        assertThat(Languages.forCountry("CA")).isEqualTo("en");
        assertThat(Languages.forCountry("US")).isEqualTo("en");
        assertThat(Languages.forCountry("MX")).isEqualTo("es");
        assertThat(Languages.forCountry("AR")).isEqualTo("es");
        assertThat(Languages.forCountry("PT")).isEqualTo("pt");
        assertThat(Languages.forCountry("br")).isEqualTo("pt");
        assertThat(Languages.forCountry("FR")).isEqualTo("fr");
        assertThat(Languages.forCountry("DE")).isEqualTo("de");
        assertThat(Languages.forCountry("ES")).isEqualTo("es");
        assertThat(Languages.forCountry("GB")).isEqualTo("en");
        assertThat(Languages.forCountry("IE")).isEqualTo("en");
        assertThat(Languages.forCountry("IT")).isEqualTo("it");
        assertThat(Languages.forCountry("NL")).isEqualTo("en");
        assertThat(Languages.forCountry(null)).isEqualTo("en");
    }

    @Test
    void noRegionalVariants() {
        assertThat(Languages.of("pt-BR")).isEqualTo("pt");
        assertThat(Languages.of("pt_PT")).isEqualTo("pt");
        assertThat(Languages.of("fr-CA")).isEqualTo("fr");
        assertThat(Languages.of("es-419")).isEqualTo("es");
        assertThat(Languages.of("ja")).isNull();
        assertThat(Languages.resolve("IT", "ja", null)).isEqualTo("it");
        assertThat(Languages.resolve("IT", "de-AT")).isEqualTo("de");
        assertThat(Languages.whatsappCode("pt")).isEqualTo("pt_BR");
        assertThat(Languages.whatsappCode("it-IT")).isEqualTo("it");
    }
}
