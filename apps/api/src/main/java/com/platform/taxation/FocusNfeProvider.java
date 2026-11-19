package com.platform.taxation;

import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Focus NFe adapter (Brazilian NFS-e). API: POST /v2/nfse?ref={ref}, GET /v2/nfse/{ref}; HTTP
 * basic auth with the token as user. Field names follow the Focus NFe NFS-e (ABRASF-style) layout;
 * municipality-specific requirements must be validated in the homologation environment first.
 */
@Component
public class FocusNfeProvider implements FiscalDocumentProvider {
    private final AppProperties props;
    private final RestClient.Builder builder;

    public FocusNfeProvider(AppProperties props, RestClient.Builder builder) {
        this.props = props;
        this.builder = builder;
    }

    @Override public String name() { return "FOCUS_NFE"; }

    @Override public boolean configured() { return props.focusNfe() != null && props.focusNfe().configured(); }

    private RestClient http() {
        if (!configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "NFS-e provider not configured (FOCUS_NFE_TOKEN)");
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(30));
        String basic = Base64.getEncoder().encodeToString((props.focusNfe().token() + ":").getBytes(StandardCharsets.UTF_8));
        return builder.clone().baseUrl(props.focusNfe().baseUrl()).requestFactory(f)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build();
    }

    @Override
    public Status submit(NfseRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data_emissao", r.issuedAtIso());
        body.put("prestador", Map.of("cnpj", r.sellerTaxId(), "inscricao_municipal", r.sellerMunicipalRegistration(),
                "codigo_municipio", r.municipalityCode()));
        Map<String, Object> tomador = new LinkedHashMap<>();
        tomador.put("BR_CPF".equals(r.buyerTaxIdType()) ? "cpf" : "cnpj", r.buyerTaxId());
        tomador.put("razao_social", r.buyerLegalName());
        tomador.put("email", r.buyerEmail());
        tomador.put("endereco", Map.of("logradouro", nz(r.buyerAddressLine1()), "uf", nz(r.buyerState()), "cep", nz(r.buyerPostalCode())));
        body.put("tomador", tomador);
        body.put("servico", Map.of("valor_servicos", r.amountMinor() / 100.0, "item_lista_servico", r.serviceCode(),
                "discriminacao", r.description(), "codigo_municipio", r.municipalityCode()));
        try {
            JsonNode res = http().post().uri("/v2/nfse?ref={ref}", r.reference()).body(body).retrieve().body(JsonNode.class);
            return map(res);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 422 && e.getResponseBodyAsString().contains("ja_existe")) return query(r.reference());
            return new Status("REJECTED", null, null, null, null, e.getResponseBodyAsString());
        }
    }

    @Override
    public Status query(String reference) {
        return map(http().get().uri("/v2/nfse/{ref}", reference).retrieve().body(JsonNode.class));
    }

    private Status map(JsonNode res) {
        if (res == null) return new Status("PROCESSING", null, null, null, null, null);
        String status = switch (res.path("status").asString("")) {
            case "autorizado" -> "ISSUED";
            case "erro_autorizacao" -> "REJECTED";
            case "cancelado" -> "CANCELED";
            default -> "PROCESSING";
        };
        String base = props.focusNfe().baseUrl();
        String pdf = res.path("url").asString(null);
        String xml = res.path("caminho_xml_nota_fiscal").isMissingNode() ? null : base + res.path("caminho_xml_nota_fiscal").asString();
        return new Status(status, res.path("numero").asString(null), res.path("codigo_verificacao").asString(null), pdf, xml,
                res.path("erros").isMissingNode() ? null : res.path("erros").toString());
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
