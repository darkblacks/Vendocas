package com.platform.unit;

import com.platform.markets.AlertFooter;
import com.platform.markets.AlertFooter.Contact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertFooterTest {
    @Test
    void textHasStoreThenEmailAndPhone() {
        assertThat(AlertFooter.text("pt-BR", new Contact("Transmassa", "contato@transmassa.com.br", "(11) 98888-0000"))).isEqualTo("""
                Este alerta foi enviado por Transmassa. É apenas um alerta, não precisa responder.
                Para falar com a loja:
                E-mail: contato@transmassa.com.br
                Telefone: (11) 98888-0000""");
        assertThat(AlertFooter.text("en", new Contact("Shop", "a@b.co", " "))).isEqualTo("""
                This alert was sent by Shop. It is only an alert, no need to reply.
                To reach the store:
                Email: a@b.co""");
        assertThat(AlertFooter.text("es", new Contact(null, null, null))).isEqualTo("Este mensaje es solo un aviso, no hace falta responder.");
    }

    @Test
    void smsIsOneShortLinePreferringThePhone() {
        assertThat(AlertFooter.sms("pt", new Contact("Transmassa", "a@b.co", "(11) 98888-0000")))
                .isEqualTo("Alerta de Transmassa, não responda. Para falar com a loja: (11) 98888-0000");
        assertThat(AlertFooter.sms("pt", new Contact("Transmassa", "a@b.co", null))).endsWith("a@b.co");
    }

    @Test
    void whatsappFooterFitsSixtyCharacters() {
        assertThat(AlertFooter.whatsapp("pt", new Contact("Transmassa", "a@b.co", "(11) 98888-0000")))
                .isEqualTo("Alerta de Transmassa. Não responda. (11) 98888-0000");
        String longName = "Pizzaria e Restaurante Bella Napoli do Centro Histórico";
        String f = AlertFooter.whatsapp("pt", new Contact(longName, "contato@bellanapoli.com.br", "(11) 98888-0000"));
        assertThat(f.length()).isLessThanOrEqualTo(AlertFooter.WHATSAPP_MAX);
        assertThat(f).isEqualTo("Apenas um alerta. Não responda. (11) 98888-0000");
    }
}
