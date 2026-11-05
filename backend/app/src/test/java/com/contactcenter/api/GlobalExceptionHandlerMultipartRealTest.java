package com.contactcenter.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-163: realna sciezka 413 - embedded Tomcat + {@code spring.servlet.multipart.max-file-size=1KB}
 * (MockMvc nie egzekwuje limitow multipart, wiec tu potrzebny jest prawdziwy kontener servletow).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.servlet.multipart.max-file-size=1KB",
                "spring.servlet.multipart.max-request-size=2KB"
        })
@DisplayName("GlobalExceptionHandler - realny upload multipart ponad limit (BE-163)")
class GlobalExceptionHandlerMultipartRealTest {

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration.class,
            org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class,
            org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
            org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration.class,
            org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
    @Import({UploadController.class, GlobalExceptionHandler.class})
    static class Config {
    }

    @RestController
    static class UploadController {
        @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String upload(@RequestPart("file") MultipartFile file) {
            return "ok";
        }
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("plik wiekszy niz limit -> 413 problem+json bez szczegolow")
    void oversizedUpload413() throws Exception {
        String boundary = "----be163";
        String payload = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + "x".repeat(8 * 1024) + "\r\n--" + boundary + "--\r\n";

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/upload"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body())
                .contains("payload-too-large")
                .doesNotContain("Exception", "org.apache", "1024", "FileSizeLimit");
    }
}
